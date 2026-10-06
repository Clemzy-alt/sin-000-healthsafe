package co.wethinkcode.healthsafe.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Guaranteed-delivery consumer for the equipment-failure-queue.
 *
 * ward-service publishes an alert to this queue whenever it detects a failed
 * piece of medical equipment; this class is the receiving half. A queue (not a
 * topic) is used deliberately: exactly one consumer should process each alert,
 * and the alert must not vanish if this service happens to be down when it is
 * published.
 *
 * How the guarantee is implemented (at-least-once delivery):
 *
 * 1. The session uses {@link Session#CLIENT_ACKNOWLEDGE}, so receiving a
 *    message does <em>not</em> remove it from the queue.
 * 2. The message is acknowledged only <em>after</em> {@link #handle(String)}
 *    has stored it successfully.
 * 3. If anything fails before the ack (JMS error, processing error), the
 *    session is closed without acknowledging, the broker returns the message
 *    to the queue, and the reconnect loop picks it up again.
 * 4. A message that fails repeatedly is a poison message: after
 *    {@link #MAX_DELIVERIES} attempts it is recorded as failed and acked
 *    anyway, so one bad payload cannot block every alert behind it. (A larger
 *    system would route it to a dead-letter queue instead.)
 *
 * The consumer runs on a daemon thread with its own reconnect loop, so the
 * service starts without a broker and recovers automatically when one appears.
 */
public final class EquipmentAlertConsumer {

    /** Logger for connection, delivery, and failure events. */
    private static final Logger log = LoggerFactory.getLogger(EquipmentAlertConsumer.class);

    /** Give up on a message after this many deliveries to avoid blocking the queue. */
    static final int MAX_DELIVERIES = 5;

    /** The URL of the ActiveMQ broker to connect to. */
    private final String brokerUrl;

    /** Thread-safe list of alerts that have been handled and acknowledged. */
    private final List<String> handled = new CopyOnWriteArrayList<>();

    /** Flag to stop the background thread gracefully. */
    private volatile boolean running = true;

    /**
     * Creates a new consumer for one broker.
     *
     * @param brokerUrl the URL of the ActiveMQ broker, e.g. "tcp://localhost:61616"
     */
    public EquipmentAlertConsumer(String brokerUrl) {
        this.brokerUrl = brokerUrl;
    }

    /**
     * Starts the background consumer thread.
     *
     * The thread is a daemon thread, so it never blocks JVM shutdown. Actual
     * broker connection happens inside {@link #run()}, which retries on its own.
     */
    public void start() {
        Thread thread = new Thread(this::run, "mq-queue-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Returns a copy of every alert handled so far.
     *
     * Backing store for the GET /alerts endpoint. A copy is returned so callers
     * cannot modify the live list.
     *
     * @return immutable list of handled alert JSON strings, in arrival order
     */
    public List<String> handledAlerts() {
        return List.copyOf(handled);
    }

    /**
     * Background loop: connect, drain the queue, reconnect on failure.
     *
     * Each iteration opens a fresh connection and session (so broker restarts
     * are picked up), consumes until the service stops or an error forces a
     * reconnect, then either exits or waits 3 seconds before retrying.
     */
    private void run() {
        while (running) {
            // True when we deliberately dropped the session to force redelivery
            boolean retryAfterFailure = false;

            try (Connection connection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
                 Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)) {

                // Start the connection (required before consuming)
                connection.start();

                MessageConsumer consumer = session.createConsumer(session.createQueue(MqConfig.QUEUE));
                log.info("Connected to ActiveMQ at {} (queue '{}')", brokerUrl, MqConfig.QUEUE);

                while (running) {
                    // Blocking receive with a timeout so the loop can notice `running`
                    Message message = consumer.receive(500);
                    if (message == null) {
                        continue; // no alert waiting right now
                    }
                    if (!(message instanceof TextMessage text)) {
                        // Not our payload - ack it so it doesn't cycle forever
                        log.warn("Ignoring non-text message of type {}", message.getClass().getSimpleName());
                        message.acknowledge();
                        continue;
                    }

                    String body = textBody(text);

                    // A message that keeps failing must not block the queue forever
                    if (deliveryCount(message) > MAX_DELIVERIES) {
                        log.error("Giving up on alert after {} deliveries: {}", deliveryCount(message), body);
                        handled.add(failed(body));
                        text.acknowledge();
                        continue;
                    }

                    try {
                        // Process first, acknowledge second - this ordering is the guarantee
                        handle(body);
                        text.acknowledge();
                    } catch (RuntimeException e) {
                        log.error("Failed to handle alert ({}); closing session so it is redelivered", e.getMessage());
                        retryAfterFailure = true;
                        break; // leave unacked -> broker redelivers after the session closes
                    }
                }
            } catch (JMSException e) {
                log.warn("MQ connection failed ({}); retrying in 3s", e.getMessage());
                retryAfterFailure = true;
            }

            if (running && retryAfterFailure) {
                // Back off before reconnecting so a poison message cannot spin
                sleepQuietly(3000);
            }
        }
    }

    /**
     * Handles one alert body: log it and keep it for the REST endpoint.
     *
     * The raw JSON is stored verbatim rather than parsed into a class - the
     * alert schema belongs to ward-service, and a consumer that rejects
     * unknown fields would silently drop alerts from a newer producer.
     *
     * @param body the JSON alert published by ward-service
     * @throws RuntimeException if the alert cannot be recorded
     */
    private void handle(String body) {
        handled.add(body);
        log.info("Received equipment failure alert: {}", body);
    }

    /**
     * Marks an alert that was abandoned after too many failed deliveries.
     *
     * @param body the original alert JSON
     * @return a placeholder string stored in place of the unreadable alert
     */
    private static String failed(String body) {
        return "{\"error\":\"alert abandoned after repeated failures\",\"payload\":\""
                + body.replace("\"", "'") + "\"}";
    }

    /**
     * Reads the broker's redelivery counter for a message.
     *
     * @param message the message to inspect
     * @return the delivery attempt number (1 for a first delivery), or 1 if absent
     */
    private static int deliveryCount(Message message) {
        try {
            return message.getIntProperty("JMSXDeliveryCount");
        } catch (Exception e) {
            // Property missing or unreadable - treat it as a first delivery
            return 1;
        }
    }

    /**
     * Extracts the text body of a JMS TextMessage without leaking JMSException.
     *
     * @param text the message to read
     * @return the message body, or a placeholder if it cannot be read
     */
    private static String textBody(TextMessage text) {
        try {
            return text.getText();
        } catch (JMSException e) {
            return "<unreadable message>";
        }
    }

    /**
     * Sleeps for the specified time, handling InterruptedException.
     *
     * If interrupted, the interrupt flag is restored so the caller can handle it.
     *
     * @param millis the time to sleep in milliseconds
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
