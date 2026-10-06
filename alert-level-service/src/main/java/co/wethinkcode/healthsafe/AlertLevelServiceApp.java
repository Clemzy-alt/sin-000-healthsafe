package co.wethinkcode.healthsafe;

import io.javalin.Javalin;
import io.javalin.http.Handler;

import java.util.Map;

/**
 * Main application class for the Alert Level Service.
 *
 * This service tracks the hospital Emergency Status on a single scale of 0-8,
 * where 8 is a full Code Blue. The status is held in an {@link AlertLevelTracker}
 * and is the shared source of truth that staffing-service reads when it sizes an
 * on-call schedule.
 *
 * REST endpoints:
 * - GET  /health          - Health check endpoint, returns "OK"
 * - GET  /alert-level     - Returns the current status as { "level": 0-8 }
 * - PUT  /alert-level     - Sets the status from a { "level": 0-8 } body
 * - POST /alert-level     - Alias for PUT, so plain curl -d calls work too
 *
 * Validation rules (all reported as HTTP 400 with a JSON error body):
 * - the body must be JSON with an integer "level" field
 * - the level must be between 0 and 8 inclusive
 *
 * The service holds state in memory only: the status resets to 0 on restart.
 * That is deliberate — it is a live operational signal, not a historical record.
 */
public class AlertLevelServiceApp {

    /** The port this service listens on. */
    static final int PORT = 7032;

    /**
     * Starts the Alert Level Service.
     *
     * Creates the in-memory tracker, starts the Javalin web server, and wires up
     * the health and alert-level endpoints.
     *
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        // Single shared tracker — every request reads and writes this one instance
        AlertLevelTracker tracker = new AlertLevelTracker();

        // Start the Javalin web server on port 7032
        Javalin app = Javalin.create().start(PORT);

        // Health check endpoint - used by load balancers and monitoring
        app.get("/health", ctx -> ctx.result("OK"));

        // GET endpoint - read the current Emergency Status
        app.get("/alert-level", ctx -> ctx.json(Map.of("level", tracker.current())));

        // Shared handler for PUT and POST - validates and stores a new status
        // (POST is an alias so callers can use a plain `curl -d '{"level":5}'`)
        Handler update = ctx -> {
            // Parse the body; a missing/malformed "level" field is a client error
            int requested;
            try {
                requested = ctx.bodyAsClass(LevelBody.class).level();
            } catch (Exception e) {
                ctx.status(400).json(Map.of(
                        "error", "invalid body",
                        "expected", "{\"level\":0-8}",
                        "detail", String.valueOf(e.getMessage())));
                return;
            }

            // Range-check the level and report violations as HTTP 400
            try {
                AlertLevelTracker.validate(requested);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of(
                        "error", e.getMessage(),
                        "min", AlertLevelTracker.MIN_LEVEL,
                        "max", AlertLevelTracker.MAX_LEVEL));
                return;
            }

            // Store the new status and echo it back
            int stored = tracker.update(requested);
            ctx.json(Map.of("level", stored));
        };

        app.put("/alert-level", update);
        app.post("/alert-level", update);
    }

    /**
     * Request body for setting the Emergency Status.
     *
     * @param level the requested level, 0-8 inclusive
     */
    record LevelBody(int level) {
    }
}
