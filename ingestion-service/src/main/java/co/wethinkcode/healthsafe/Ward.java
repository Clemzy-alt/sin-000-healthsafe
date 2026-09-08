package co.wethinkcode.healthsafe;

/**
 * A cleaned ward record, as produced by {@link WardCleaner} and served over REST.
 * 
 * This record represents a single hospital ward after the raw CSV data has been
 * cleaned and validated. It contains the essential information about a ward that
 * other services need to work with.
 * 
 * The record is immutable - once created, its values cannot be changed.
 * This makes it safe to share between threads and services.
 * 
 * @param wardId        normalized ward identifier, e.g. "W-05" (always uppercase)
 * @param wing          human-readable wing name, e.g. "East Wing" (null if unknown/blank)
 * @param department    normalized department name, e.g. "Cardiology" (null if unknown/blank)
 * @param bedsAvailable valid non-negative bed count, null when the source value was
 *                      missing, non-numeric, negative, or unrealistically large
 * @param notes         human-readable flags for anything that needed fixing during
 *                      cleaning (null if no issues found)
 */
public record Ward(String wardId, String wing, String department, Integer bedsAvailable, String notes) {
}
