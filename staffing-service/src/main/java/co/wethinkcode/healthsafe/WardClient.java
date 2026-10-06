package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Synchronous HTTP client for ward-service, used to validate a ward before a
 * schedule is computed.
 *
 * Mirrors the shape of ward-service's own WardCatalog (its cache of
 * ingestion-service data) but from the caller's side: one GET, one optional
 * ward back.
 *
 * Failure mapping is deliberate, because staffing-service must not assume the
 * happy path:
 * - HTTP 404                -> Optional.empty() (the ward genuinely doesn't exist)
 * - any other non-200       -> IOException (upstream bug or degraded service)
 * - connection refused/timeouts -> IOException from the JDK client
 *
 * Callers turn an Optional.empty() into a 404 and an IOException into a 502,
 * so a ward-service outage degrades staffing-service instead of crashing it.
 *
 * Thread safety: instances are immutable apart from the HttpClient, which is
 * thread safe, so a single instance is shared by all request threads.
 */
public final class WardClient {

    /** Base URL of ward-service, e.g. "http://localhost:7031". */
    private final String wardServiceUrl;

    /** Shared HTTP client with a short connect timeout. */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Jackson mapper for decoding the ward JSON. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates a client for one ward-service instance.
     *
     * @param wardServiceUrl the base URL of ward-service
     */
    public WardClient(String wardServiceUrl) {
        this.wardServiceUrl = wardServiceUrl;
    }

    /**
     * Looks up a single ward by ID.
     *
     * The ID is URL-encoded before it is placed in the path, so odd input
     * (spaces, slashes) cannot break the request or escape the path.
     *
     * @param wardId the ward ID to validate (e.g. "W-05"; matching is
     *               case-insensitive on the server side)
     * @return the ward if it exists, or empty if ward-service answered 404
     * @throws IOException          if ward-service is unreachable or answers with an unexpected status
     * @throws InterruptedException if the request is interrupted while waiting
     */
    public Optional<Ward> find(String wardId) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(wardId, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(wardServiceUrl + "/wards/" + encoded))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        // 404 means "unknown ward" - a normal answer, not an outage
        if (response.statusCode() == 404) {
            return Optional.empty();
        }

        // Anything else that isn't 200 is an upstream problem worth surfacing
        if (response.statusCode() != 200) {
            throw new IOException("ward-service returned HTTP " + response.statusCode());
        }

        return Optional.of(mapper.readValue(response.body(), Ward.class));
    }
}
