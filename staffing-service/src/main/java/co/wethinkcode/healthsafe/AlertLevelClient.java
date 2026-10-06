package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Synchronous HTTP client for alert-level-service, used to read the current
 * hospital Emergency Status (0-8) when sizing an on-call schedule.
 *
 * One GET returns { "level": 0-8 }; this client decodes it and checks it.
 *
 * Failure mapping is deliberate:
 * - HTTP 200 with a valid level -> the level is returned
 * - any other status           -> IOException (alert-level-service degraded)
 * - a level outside 0-8        -> IOException (bad upstream data, not trusted)
 * - malformed JSON / missing field -> JsonProcessingException (an IOException)
 *
 * Callers turn an IOException into a 502 with the message attached, so a
 * broken or outdated alert-level-service degrades staffing-service with a
 * clear reason instead of producing a nonsensical schedule.
 *
 * Thread safety: instances are immutable apart from the HttpClient, which is
 * thread safe, so a single instance is shared by all request threads.
 */
public final class AlertLevelClient {

    /** The valid range of the Emergency Status, duplicated here because each service is its own Maven project. */
    static final int MIN_LEVEL = 0;
    static final int MAX_LEVEL = 8;

    /** Base URL of alert-level-service, e.g. "http://localhost:7032". */
    private final String alertLevelUrl;

    /** Shared HTTP client with a short connect timeout. */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Jackson mapper for decoding the alert-level JSON. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates a client for one alert-level-service instance.
     *
     * @param alertLevelUrl the base URL of alert-level-service
     */
    public AlertLevelClient(String alertLevelUrl) {
        this.alertLevelUrl = alertLevelUrl;
    }

    /**
     * Reads the current Emergency Status from alert-level-service.
     *
     * @return the current level, between 0 and 8 inclusive
     * @throws IOException          if alert-level-service is unreachable, answers with a non-200
     *                              status, sends malformed JSON, or sends an out-of-range level
     * @throws InterruptedException if the request is interrupted while waiting
     */
    public int currentLevel() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(alertLevelUrl + "/alert-level"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("alert-level-service returned HTTP " + response.statusCode());
        }

        int level = mapper.readValue(response.body(), LevelBody.class).level();

        // Never schedule against a status the contract doesn't define
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IOException("alert-level-service returned out-of-range level " + level);
        }

        return level;
    }

    /**
     * The response body of GET /alert-level.
     *
     * @param level the current Emergency Status, 0-8 inclusive
     */
    record LevelBody(int level) {
    }
}
