package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of the cleaned wards, populated by calling ingestion-service.
 * 
 * This class acts as a local cache for ward data. It:
 * 1. Fetches cleaned ward records from ingestion-service via HTTP
 * 2. Stores them in a thread-safe ConcurrentHashMap
 * 3. Provides fast lookups by ward ID
 * 4. Can be refreshed at any time to get updated data
 * 
 * The cache is used by ward-service to avoid calling ingestion-service on every
 * request. Instead, it's populated on startup and can be refreshed manually via
 * the /wards/refresh endpoint.
 * 
 * Thread safety is ensured by using ConcurrentHashMap, which allows concurrent
 * reads and writes without external synchronization.
 */
public final class WardCatalog {

    /** The base URL of the ingestion-service to fetch ward data from. */
    private final String ingestionUrl;

    /** Jackson mapper for JSON deserialization. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** HTTP client with 3-second connection timeout. */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Thread-safe map storing ward records by their normalized ID. */
    private final Map<String, Ward> wards = new ConcurrentHashMap<>();

    /**
     * Creates a new WardCatalog.
     * 
     * @param ingestionUrl the base URL of the ingestion-service
     */
    public WardCatalog(String ingestionUrl) {
        this.ingestionUrl = ingestionUrl;
    }

    /**
     * Fetches the cleaned records from ingestion-service and replaces the cache.
     * 
     * This method:
     * 1. Makes an HTTP GET request to ingestion-service /wards endpoint
     * 2. Deserializes the JSON response into a list of Ward records
     * 3. Clears the current cache and populates it with the new data
     * 4. Returns the number of wards loaded
     * 
     * @return the number of wards loaded
     * @throws IOException if the HTTP request fails
     * @throws InterruptedException if the request is interrupted
     */
    public int refresh() throws IOException, InterruptedException {
        // Build the HTTP request with 5-second timeout
        HttpRequest request = HttpRequest.newBuilder(URI.create(ingestionUrl + "/wards"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        
        // Send the request and get the response
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        
        // Check for errors
        if (response.statusCode() != 200) {
            throw new IOException("ingestion-service returned HTTP " + response.statusCode());
        }
        
        // Deserialize the JSON response into a list of Ward records
        List<Ward> loaded = mapper.readValue(response.body(), new TypeReference<>() {
        });
        
        // Clear the current cache and populate with new data
        wards.clear();
        for (Ward ward : loaded) {
            // Normalize the ID to ensure consistent lookups
            wards.put(normalizeId(ward.wardId()), ward);
        }
        
        return wards.size();
    }

    /**
     * Returns all cached wards as a list.
     * 
     * @return list of all Ward records in the cache
     */
    public List<Ward> all() {
        return wards.values().stream().toList();
    }

    /**
     * Looks up a ward by its ID.
     * 
     * @param id the ward ID to look up (will be normalized)
     * @return an Optional containing the Ward if found, or empty if not
     */
    public Optional<Ward> byId(String id) {
        return Optional.ofNullable(wards.get(normalizeId(id)));
    }

    /**
     * Returns a sorted list of unique department names from all cached wards.
     * 
     * This is used by ward-service to provide a /departments endpoint that
     * lists all available departments.
     * 
     * @return sorted list of unique department names
     */
    public List<String> departments() {
        return wards.values().stream()
                .map(Ward::department)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /**
     * Normalizes a ward ID by trimming whitespace and converting to uppercase.
     * 
     * This ensures consistent ID comparison regardless of how the ID was
     * provided in the request.
     * 
     * @param id the raw ward ID
     * @return normalized uppercase ID, or null if input was null
     */
    private static String normalizeId(String id) {
        return id == null ? null : id.trim().toUpperCase();
    }
}
