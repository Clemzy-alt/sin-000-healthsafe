package co.wethinkcode.healthsafe.mq;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Manages this service's two MQ roles:
 * 
 * 1. CONSUMER of the broadcast staffing-events-topic:
 *    - Receives staffing updates asynchronously (schedule changes)
 *    - Stores events in a thread-safe list for querying via REST
 *    - No acknowledgment needed - just receives and stores
 * 
 * 2. PRODUCER on the equipment-failure-queue:
 *    - Publishes equipment failure alerts with guaranteed delivery
 *    - Uses persistent delivery mode so messages survive broker restarts
 *    - Queues alerts locally while disconnected, flushes when reconnected
 * 
 * The manager connects to the broker in a background loop, so the service
 * still starts even if the broker is down. It automatically reconnects
 * when the broker becomes available.
 * 
 * Key features:
 * - Background connection loop with automatic reconnection
 * - Local outbox for equipment failure alerts (survives disconnections)
 * - Thread-safe storage for received staffing events
 * - Persistent delivery for equipment failures (guaranteed delivery)
 */
public final class MqManager {

    /** Logger for connection and message events. */
    private static final Logger log = LoggerFactory.getLogger(MqManager.class);

    /** The URL of the ActiveMQ broker to connect to. */
    private final String brokerUrl;

    /** Thread-safe list to store received staffing events. */
    private final List<String> staffingEvents = new CopyOnWriteArrayList<>();

    /** Queue of equipment failure alerts waiting to be published (the "outbox"). */
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();

    /** Flag to stop the background thread gracefully. */
    private volatile boolean running = true;

    /**
     * Creates a new MqManager.
     * 
     * @param brokerUrl the URL of the ActiveMQ broker
     */
    public MqManager(String brokerUrl) {
        this.brokerUrl = brokerUrl;
    }

    /**
     * Starts the background MQ manager thread.
     * 
     * The thread is marked as a daemon thread so it won't prevent the JVM from
     * shutting down. It runs the run() method which handles both consuming and
     * producing messages.
     */
    public void start() {
        Thread thread = new Thread(this::run, "mq-manager");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Background loop that connects to the broker and handles both consuming
     * and producing messages.
     * 
     * This method:
     * 1. Connects to the ActiveMQ broker
     * 2. Creates a consumer for the staffing-events-topic
     * 3. Creates a producer for the equipment-failure-queue
     * 4. Polls the outbox for equipment failure alerts
     * 5. Publishes any alerts found
     * 6. If connection fails, waits 3 seconds and retries
     * 
     * The connection is recreated on each iteration to handle broker restarts.
     */
    private void run() {
        while (running) {
            try (Connection connection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
                 Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                
                // Start the connection (required before consuming/producing)
                connection.start();

                // Set up the topic consumer for staffing events
                MessageConsumer topicConsumer = session.createConsumer(session.createTopic(MqConfig.TOPIC));
                topicConsumer.setMessageListener(message -> {
                    if (message instanceof TextMessage text) {
                        String body = textBody(text);
                        // Store the received event in the thread-safe list
                        staffingEvents.add(body);
                        log.info("Received staffing event: {}", body);
                    }
                });

                // Set up the queue producer for equipment failure alerts
                MessageProducer queueProducer = session.createProducer(session.createQueue(MqConfig.QUEUE));
                queueProducer.setDeliveryMode(DeliveryMode.PERSISTENT);

                log.info("Connected to ActiveMQ at {} (topic '{}', queue '{}')",
                        brokerUrl, MqConfig.TOPIC, MqConfig.QUEUE);

                // Poll the outbox and publish equipment failure alerts
                while (running) {
                    // Wait up to 500ms for an alert
                    String json = outbox.poll(500, TimeUnit.MILLISECONDS);
                    if (json != null) {
                        // Create a text message and send it to the queue
                        queueProducer.send(session.createTextMessage(json));
                        log.info("Published equipment failure alert: {}", json);
                    }
                }
            } catch (InterruptedException e) {
                // Thread was interrupted - stop gracefully
                running = false;
            } catch (JMSException e) {
                // Connection failed - log and retry after 3 seconds
                log.warn("MQ connection failed ({}); retrying in 3s", e.getMessage());
                sleepQuietly(3000);
            }
        }
    }

    /**
     * Queues an equipment-failure alert for publishing.
     * 
     * This method is called from HTTP request threads. It's non-blocking:
     * the alert is added to the outbox and will be published by the background thread.
     * 
     * If the broker is unavailable, the alert waits in the outbox until
     * the connection is re-established.
     * 
     * @param json the JSON alert to publish
     */
    public void publishEquipmentFailure(String json) {
        outbox.offer(json);
    }

    /**
     * Returns a copy of all received staffing events.
     * 
     * This is used by the /staffing-events REST endpoint to view received events.
     * Returns a copy to ensure thread safety.
     * 
     * @return immutable list of staffing event JSON strings
     */
    public List<String> getStaffingEvents() {
        return List.copyOf(staffingEvents);
    }

    /**
     * Extracts the text body from a JMS TextMessage.
     * 
     * Handles JMSException gracefully by returning a placeholder string.
     * 
     * @param text the TextMessage to extract from
     * @return the message body, or "<unreadable message>" if extraction fails
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
            // Restore the interrupt flag
            Thread.currentThread().interrupt();
        }
    }
}
