package co.wethinkcode.healthsafe;

/**
 * A ward record as served by ingestion-service and cached locally.
 * 
 * This is a local copy of the Ward record used by the ward-service.
 * It's kept in this service's own source tree because each service is an
 * independent Maven project with no shared parent pom.
 * 
 * The fields match the Ward record from ingestion-service exactly:
 * - wardId: normalized identifier (e.g. "W-05")
 * - wing: human-readable wing name (e.g. "East Wing")
 * - department: normalized department name (e.g. "Cardiology")
 * - bedsAvailable: valid bed count (null if unknown/invalid)
 * - notes: issues found during data cleaning (null if clean)
 * 
 * @param wardId        the normalized ward identifier
 * @param wing          the wing name
 * @param department    the department name
 * @param bedsAvailable the number of available beds
 * @param notes         any issues or notes about the ward data
 */
public record Ward(String wardId, String wing, String department, Integer bedsAvailable, String notes) {
}
