package co.wethinkcode.healthsafe;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import io.javalin.Javalin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main application class for the Ingestion Service.
 * 
 * This service is responsible for:
 * 1. Loading the raw CSV file (wards-outdated.csv) from the classpath
 * 2. Cleaning and normalizing the data using WardCleaner
 * 3. Serving the cleaned ward records over REST API
 * 
 * The ward-service calls this service to get clean ward data. This separation
 * allows the data cleaning logic to be isolated and tested independently.
 * 
 * REST endpoints:
 * - GET /health - Health check endpoint, returns "OK"
 * - GET /wards - Returns all cleaned ward records as JSON array
 * - GET /wards/{id} - Returns a specific ward by its ID
 * 
 * The service loads all wards into memory on startup and keeps them in a
 * thread-safe map for fast concurrent access.
 */
public class IngestionServiceApp {

    /** The classpath path to the CSV file containing raw ward data. */
    static final String CSV_RESOURCE = "/wards-outdated.csv";

    /**
     * Starts the Ingestion Service.
     * 
     * Creates a Javalin HTTP server, loads and cleans the CSV data, and
     * defines REST endpoints for accessing the ward records.
     * 
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        // Start the Javalin web server on port 7030
        Javalin app = Javalin.create().start(7030);

        // Health check endpoint - used by load balancers and monitoring
        app.get("/health", ctx -> ctx.result("OK"));

        // Thread-safe map to store cleaned ward records by their ID
        Map<String, Ward> wards = new ConcurrentHashMap<>();
        
        // Load and clean the CSV data on startup
        try {
            List<Ward> cleaned = loadCleanedWards();
            // Add each cleaned ward to the map
            for (Ward ward : cleaned) {
                wards.put(ward.wardId(), ward);
            }
        } catch (IOException | com.opencsv.exceptions.CsvException e) {
            // Log the error but don't crash - the service will return 500 errors
            // if anyone tries to access ward data before it's loaded
            System.err.println("Could not load " + CSV_RESOURCE + ": " + e.getMessage());
        }

        // GET endpoint - returns all cleaned ward records
        app.get("/wards", ctx -> {
            if (wards.isEmpty()) {
                // If no wards are loaded, return a 500 error with details
                ctx.status(500).json(Map.of(
                        "error", "wards data not loaded",
                        "detail", "could not read " + CSV_RESOURCE + " from classpath"));
                return;
            }
            // Return a snapshot of all wards (copy for thread safety)
            ctx.json(List.copyOf(wards.values()));
        });

        // GET endpoint - returns a specific ward by ID
        app.get("/wards/{id}", ctx -> {
            // Normalize the ID and look up the ward
            Optional<Ward> ward = Optional.ofNullable(wards.get(normalizeId(ctx.pathParam("id"))));
            if (ward.isPresent()) {
                ctx.json(ward.get());
            } else {
                // Ward not found - return 404 with details
                ctx.status(404).json(Map.of("error", "unknown ward", "wardId", ctx.pathParam("id")));
            }
        });
    }

    /**
     * Reads and cleans the CSV resource from the classpath.
     * 
     * This method:
     * 1. Opens the CSV file as an InputStream
     * 2. Reads all rows using OpenCSV library
     * 3. Passes the header and data rows to WardCleaner for cleaning
     * 4. Returns the list of cleaned Ward records
     * 
     * Never throws on bad rows - see WardCleaner for details on error handling.
     * 
     * @return list of cleaned Ward records
     * @throws IOException if the CSV file cannot be found or read
     * @throws com.opencsv.exceptions.CsvException if the CSV format is invalid
     */
    static List<Ward> loadCleanedWards() throws IOException, com.opencsv.exceptions.CsvException {
        // Open the CSV file from the classpath
        try (InputStream in = IngestionServiceApp.class.getResourceAsStream(CSV_RESOURCE)) {
            if (in == null) {
                throw new IOException("resource " + CSV_RESOURCE + " not found on classpath");
            }
            
            // Read the CSV file using OpenCSV library
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            CSVReader csv = new CSVReaderBuilder(reader)
                    .withCSVParser(new CSVParserBuilder().build())
                    .build();
            List<String[]> allRows = csv.readAll();
            
            // Handle empty file
            if (allRows.isEmpty()) {
                return List.of();
            }
            
            // Split into header and data rows
            String[] header = allRows.get(0);
            List<String[]> rows = allRows.subList(1, allRows.size());
            
            // Clean the data and return the results
            return WardCleaner.clean(header, rows);
        }
    }

    /**
     * Normalizes a ward ID by trimming whitespace and converting to uppercase.
     * 
     * This ensures consistent ID comparison regardless of how the ID was
     * provided in the request.
     * 
     * @param id the raw ward ID from the request
     * @return normalized uppercase ID, or null if input was null
     */
    private static String normalizeId(String id) {
        return id == null ? null : id.trim().toUpperCase();
    }
}
