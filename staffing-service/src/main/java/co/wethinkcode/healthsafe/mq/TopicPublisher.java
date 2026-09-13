package co.wethinkcode.healthsafe.mq;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Broadcasts staffing events to the staffing-events-topic.
 * 
 * This publisher allows subscribers (like ward-service) to receive schedule
 * change notifications without polling. It uses a background thread to handle
 * MQ connections and message publishing.
 * 
 * Key features:
 * - Background connection loop: the service starts even if the broker is down
 * - Outbox pattern: events are queued locally and sent when the broker is reachable
 * - Persistent delivery: messages survive broker restarts
 * - Automatic reconnection: if the connection drops, it retries every 3 seconds
 * 
 * The publisher is decoupled from HTTP request threads via an outbox:
 * 1. HTTP handler calls publish() to add event to the outbox
 * 2. Background thread picks up events and sends them to the broker
 * 3. If the broker is down, events wait in the outbox until it's back
 */
public final class TopicPublisher {

    /** Logger for connection and publishing events. */
    private static final Logger log = LoggerFactory.getLogger(TopicPublisher.class);

    /** The URL of the ActiveMQ broker to connect to. */
    private final String brokerUrl;

    /** The topic name to publish messages to. */
    private final String topicName;

    /** Queue of events waiting to be published (the "outbox"). */
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();

    /** Flag to stop the background thread gracefully. */
    private volatile boolean running = true;

    /**
     * Creates a new TopicPublisher.
     * 
     * @param brokerUrl the URL of the ActiveMQ broker
     * @param topicName the topic name to publish to
     */
    public TopicPublisher(String brokerUrl, String topicName) {
        this.brokerUrl = brokerUrl;
        this.topicName = topicName;
    }

    /**
     * Starts the background publisher thread.
     * 
     * The thread is marked as a daemon thread so it won't prevent the JVM from
     * shutting down. It runs the run() method which handles connection and publishing.
     */
    public void start() {
        Thread thread = new Thread(this::run, "mq-topic-publisher");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Adds an event to the outbox for publishing.
     * 
     * This method is called from HTTP request threads. It's non-blocking:
     * the event is added to the queue and will be published by the background thread.
     * 
     * @param json the JSON event to publish
     */
    public void publish(String json) {
        outbox.offer(json);
    }

    /**
     * Background loop that connects to the broker and publishes events.
     * 
     * This method runs continuously until the service shuts down. It:
     * 1. Connects to the ActiveMQ broker
     * 2. Creates a session and producer
     * 3. Polls the outbox for events every 500ms
     * 4. Publishes any events found
     * 5. If connection fails, waits 3 seconds and retries
     * 
     * The connection is recreated on each iteration to handle broker restarts.
     */
    private void run() {
        while (running) {
            try (Connection connection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
                 Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                
                // Start the connection (required before consuming/producing)
                connection.start();
                
                // Create a topic destination and a producer
                Destination destination = session.createTopic(topicName);
                MessageProducer producer = session.createProducer(destination);
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                
                log.info("Connected to ActiveMQ topic '{}' at {}", topicName, brokerUrl);
                
                // Poll the outbox and publish events
                while (running) {
                    // Wait up to 500ms for an event
                    String json = outbox.poll(500, TimeUnit.MILLISECONDS);
                    if (json != null) {
                        // Create a text message and send it
                        producer.send(session.createTextMessage(json));
                        log.info("Broadcast staffing event: {}", json);
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
