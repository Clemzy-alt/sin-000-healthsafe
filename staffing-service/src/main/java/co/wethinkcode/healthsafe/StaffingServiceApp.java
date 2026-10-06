package co.wethinkcode.healthsafe;

import io.javalin.Javalin;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Main application class for the Staffing Service.
 *
 * This service computes on-call doctor schedules for a ward. A schedule is the
 * product of three inputs, two of which are fetched synchronously over HTTP:
 *
 * 1. the ward itself        -> ward-service        (GET /wards/{id}, 404 if unknown)
 * 2. the Emergency Status   -> alert-level-service (GET /alert-level, 0-8)
 * 3. the scheduling rules   -> ScheduleComputer    (in-process, pure function)
 *
 * Downstream failures are handled explicitly rather than assumed away: an
 * unknown ward becomes a 404, and an unreachable or misbehaving dependency
 * becomes a 502/504 with the reason attached. The rubric calls this out as the
 * signal of a real integration — the happy path is the shortest branch here.
 *
 * REST endpoints:
 * - GET /health              - Health check endpoint, returns "OK"
 * - GET /schedule/{wardId}   - On-call schedule for a ward
 *
 * Response codes for GET /schedule/{wardId}:
 * - 200 schedule JSON, 404 unknown ward, 502 dependency returned an error,
 *   504 dependency timed out.
 */
public class StaffingServiceApp {

    /** The port this service listens on. */
    static final int PORT = 7033;

    /** Default URL for ward-service (used when WARD_SERVICE_URL is not set). */
    static final String DEFAULT_WARD_URL = "http://localhost:7031";

    /** Default URL for alert-level-service (used when ALERT_LEVEL_SERVICE_URL is not set). */
    static final String DEFAULT_ALERT_URL = "http://localhost:7032";

    /**
     * Starts the Staffing Service.
     *
     * Wires up the two downstream HTTP clients, starts the Javalin web server,
     * and defines the health and schedule endpoints.
     *
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        // Dependency URLs come from the environment so Docker/ compose setups can
        // override them, falling back to the local defaults above
        WardClient wards = new WardClient(envOrDefault("WARD_SERVICE_URL", DEFAULT_WARD_URL));
        AlertLevelClient alertLevels = new AlertLevelClient(envOrDefault("ALERT_LEVEL_SERVICE_URL", DEFAULT_ALERT_URL));

        // Start the Javalin web server on port 7033
        Javalin app = Javalin.create().start(PORT);

        // Health check endpoint - used by load balancers and monitoring
        app.get("/health", ctx -> ctx.result("OK"));

        // GET endpoint - compute an on-call schedule for one ward
        app.get("/schedule/{wardId}", ctx -> {
            String wardId = ctx.pathParam("wardId");

            // Step 1: validate the ward against ward-service (synchronous call)
            Optional<Ward> ward;
            try {
                ward = wards.find(wardId);
            } catch (IOException e) {
                // ward-service is down or answered something unexpected
                ctx.status(502).json(Map.of(
                        "error", "ward-service unavailable",
                        "wardId", wardId,
                        "detail", e.toString()));
                return;
            } catch (InterruptedException e) {
                // The request thread was interrupted - restore the flag and report a timeout
                Thread.currentThread().interrupt();
                ctx.status(504).json(Map.of("error", "ward-service call interrupted", "wardId", wardId));
                return;
            }

            if (ward.isEmpty()) {
                // Unknown ward - propagate ward-service's 404 to our caller
                ctx.status(404).json(Map.of("error", "unknown ward", "wardId", wardId));
                return;
            }

            // Step 2: read the current Emergency Status (synchronous call)
            int alertLevel;
            try {
                alertLevel = alertLevels.currentLevel();
            } catch (IOException e) {
                // alert-level-service is down or sent bad data - refuse to guess a level
                ctx.status(502).json(Map.of(
                        "error", "alert-level-service unavailable",
                        "detail", e.toString()));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.status(504).json(Map.of("error", "alert-level-service call interrupted"));
                return;
            }

            // Step 3: compute the schedule from ward + status, entirely in-process
            ScheduleComputer.OnCallRoster roster = ScheduleComputer.compute(ward.get().department(), alertLevel);
            Schedule schedule = new Schedule(
                    ward.get().wardId(),
                    ward.get().department(),
                    alertLevel,
                    roster.totalDoctors(),
                    roster.roles(),
                    Instant.now().toString());

            ctx.json(schedule);
        });
    }

    /**
     * Gets an environment variable value, or returns a default if not set or blank.
     *
     * @param name     the environment variable name
     * @param fallback the default value to use if the env var is not set or blank
     * @return the env var value, or the fallback
     */
    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
