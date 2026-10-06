package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.EquipmentAlertConsumer;
import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;

/**
 * Main application class for the Equipment Alert Service.
 *
 * This service is the guaranteed-delivery consumer of the
 * equipment-failure-queue. ward-service publishes to that queue whenever it
 * detects a failed piece of equipment on a ward; this service drains the queue
 * and keeps every alert it has handled so it can be inspected over REST.
 *
 * The queue consumer ({@link EquipmentAlertConsumer}) runs on its own daemon
 * thread with client-acknowledge semantics, so alerts published while this
 * service is down stay on the broker and are delivered on reconnect. It starts
 * before the HTTP server and never requires a broker to be present at boot.
 *
 * REST endpoints:
 * - GET /health  - Health check endpoint, returns "OK"
 * - GET /alerts  - Every equipment failure alert handled so far, as a JSON array
 */
public class EquipmentAlertServiceApp {

    /** The port this service listens on. */
    static final int PORT = 7034;

    /**
     * Starts the Equipment Alert Service.
     *
     * Starts the queue consumer, then the Javalin web server with the health
     * and alert-inspection endpoints.
     *
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        // Start consuming immediately: the consumer owns its own reconnect loop,
        // so a missing broker at boot is logged and retried, not fatal
        EquipmentAlertConsumer consumer = new EquipmentAlertConsumer(MqConfig.BROKER_URL);
        consumer.start();

        // Start the Javalin web server on port 7034
        Javalin app = Javalin.create().start(PORT);

        // Health check endpoint - used by load balancers and monitoring
        app.get("/health", ctx -> ctx.result("OK"));

        // GET endpoint - every alert handled so far (raw JSON from ward-service)
        app.get("/alerts", ctx -> ctx.json(consumer.handledAlerts()));
    }
}
