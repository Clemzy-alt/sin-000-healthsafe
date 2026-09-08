package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import co.wethinkcode.healthsafe.mq.MqManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main application class for the Ward Service.
 * 
 * This service provides ward and department information to other services, sourced
 * from ingestion-service. It also handles equipment failure reports and broadcasts
 * them to the equipment-alert-service via MQ.
 * 
 * Responsibilities:
 * 1. Cache ward data from ingestion-service
 * 2. Provide REST API for querying wards and departments
 * 3. Handle equipment failure reports from staff
 * 4. Broadcast equipment failures to equipment-alert-service via MQ
 * 5. Subscribe to staffing events from staffing-service via MQ
 * 
 * REST endpoints:
 * - GET /health - Health check endpoint
 * - GET /wards - List all wards (503 if not loaded yet)
 * - GET /wards/{id} - Get a specific ward by ID
 * - GET /departments - List all unique departments
 * - POST /wards/refresh - Force refresh ward data from ingestion-service
 * - POST /wards/{id}/equipment-failures - Report an equipment failure
 * - GET /staffing-events - View received staffing events
 * 
 * MQ roles:
 * - Consumer of staffing-events-topic (receives schedule changes)
 * - Producer on equipment-failure-queue (publishes equipment failures)
 */
public class WardServiceApp {

    /** The port this service listens on. */
    static final int PORT = 7031;

    /** Default URL for ingestion-service (used when INGESTION_SERVICE_URL env var is not set). */
    static final String DEFAULT_INGESTION_URL = "http://localhost:7030";

    /**
     * Request body for reporting a detected equipment failure on a ward.
     * 
     * @param equipment the name or ID of the failed equipment
     * @param failure   description of the failure
     */
    public record EquipmentFailure(String equipment, String failure) {
    }

    /**
     * Starts the Ward Service.
     * 
     * Sets up the ward catalog, MQ manager, HTTP endpoints, and starts
     * background threads for loading ward data and handling MQ connections.
     * 
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        // Get ingestion-service URL from environment variable, or use default
        String ingestionUrl = envOrDefault("INGESTION_SERVICE_URL", DEFAULT_INGESTION_URL);

        // Create the ward catalog (local cache of ward data)
        WardCatalog catalog = new WardCatalog(ingestionUrl);
        
        // Create and start the MQ manager (handles both publishing and subscribing)
        MqManager mq = new MqManager(MqConfig.BROKER_URL);
        mq.start();

        // Bootstrap the ward catalog in a background thread
        // This retries until ingestion-service is available, so service startup
        // order doesn't matter (ingestion-service can start after ward-service)
        AtomicInteger loaded = new AtomicInteger(0);
        Thread bootstrap = new Thread(() -> {
            while (true) {
                try {
                    // Try to fetch and cache ward data
                    loaded.set(catalog.refresh());
                    System.out.println("Loaded " + loaded.get() + " wards from " + ingestionUrl);
                    return; // Success - exit the retry loop
                } catch (Exception e) {
                    // ingestion-service not available yet - wait and retry
                    System.err.println("Could not reach " + ingestionUrl + " (" + e.getMessage() + "); retrying in 3s");
                    sleepQuietly(3000);
                }
            }
        }, "ingestion-bootstrap");
        bootstrap.setDaemon(true);
        bootstrap.start();

        // Jackson mapper for JSON serialization
        ObjectMapper mapper = new ObjectMapper();
        
        // Start the Javalin web server
        Javalin app = Javalin.create().start(PORT);

        // Health check endpoint
        app.get("/health", ctx -> ctx.result("OK"));

        // GET endpoint - list all wards
        app.get("/wards", ctx -> {
            // Return 503 if wards haven't been loaded yet
            if (loaded.get() == 0) {
                ctx.status(503).json(Map.of(
                        "error", "wards not loaded yet",
                        "detail", "ingestion-service has not been reached"));
                return;
            }
            // Return all cached wards
            ctx.json(catalog.all());
        });

        // GET endpoint - get a specific ward by ID
        app.get("/wards/{id}", ctx -> {
            Optional<Ward> ward = catalog.byId(ctx.pathParam("id"));
            if (ward.isPresent()) {
                ctx.json(ward.get());
            } else {
                // Ward not found - return 404
                ctx.status(404).json(Map.of("error", "unknown ward", "wardId", ctx.pathParam("id")));
            }
        });

        // GET endpoint - list all unique departments
        app.get("/departments", ctx -> ctx.json(catalog.departments()));

        // POST endpoint - force refresh ward data from ingestion-service
        app.post("/wards/refresh", ctx -> {
            try {
                int count = catalog.refresh();
                loaded.set(count);
                ctx.json(Map.of("wardsLoaded", count));
            } catch (Exception e) {
                // ingestion-service is unavailable
                ctx.status(502).json(Map.of("error", "could not refresh from ingestion-service", "detail", e.getMessage()));
            }
        });

        // POST endpoint - report an equipment failure on a ward
        app.post("/wards/{id}/equipment-failures", ctx -> {
            // First, verify the ward exists
            Optional<Ward> ward = catalog.byId(ctx.pathParam("id"));
            if (ward.isEmpty()) {
                ctx.status(404).json(Map.of("error", "unknown ward", "wardId", ctx.pathParam("id")));
                return;
            }
            
            // Parse the equipment failure from the request body
            EquipmentFailure failure;
            try {
                failure = ctx.bodyAsClass(EquipmentFailure.class);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "invalid body", "expected", "{\"equipment\":\"...\",\"failure\":\"...\"}"));
                return;
            }
            
            // Build the alert event with ward details
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("wardId", ward.get().wardId());
            alert.put("wing", ward.get().wing());
            alert.put("department", ward.get().department());
            alert.put("equipment", failure.equipment());
            alert.put("failure", failure.failure());
            alert.put("timestamp", Instant.now().toString());
            
            // Publish the alert to the equipment-failure-queue
            mq.publishEquipmentFailure(mapper.writeValueAsString(alert));
            
            // Return 202 Accepted with the alert details
            ctx.status(202).json(alert);
        });

        // GET endpoint - view received staffing events (for debugging/testing)
        app.get("/staffing-events", ctx -> ctx.json(mq.getStaffingEvents()));
    }

    /**
     * Gets an environment variable value, or returns a default if not set or blank.
     * 
     * @param name     the environment variable name
     * @param fallback the default value to use if the env var is not set
     * @return the env var value, or the fallback
     */
    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
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
