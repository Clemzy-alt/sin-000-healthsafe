package co.wethinkcode.healthsafe;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cleans the messy {@code wards-outdated.csv} legacy export into tidy {@link Ward} records.
 * 
 * This class handles all the data quality issues found in the source CSV file:
 * - Inconsistent casing (IDs, wings, departments)
 * - Extra whitespace and collapsed double spaces
 * - Duplicate records for the same real-world ward
 * - Placeholder/missing values (N/A, TBD, -, blank, etc.)
 * - Invalid or non-numeric values in numeric columns (including negatives and unrealistic values)
 * - Spelling variants (e.g. "Pediatrics" vs "Paediatrics")
 * - Date and boolean columns, normalized generically by column name
 * 
 * The cleaner never throws exceptions on malformed rows. Instead, bad cells are
 * flagged via the record's "notes" field, allowing the rest of the data to be
 * processed successfully. This makes the system resilient to data quality issues.
 * 
 * Usage:
 *   List<Ward> cleaned = WardCleaner.clean(header, rows);
 */
public final class WardCleaner {

    /** List of strings that are treated as "placeholder" or missing values. */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "", "n/a", "na", "tbd", "unknown", "-", "nan", "null", "nul",
            "none", "undefined", "?");

    /** Bed counts above this threshold are treated as data-entry errors. */
    private static final int MAX_SENSIBLE_BEDS = 500;

    /** Maps common department spelling variants to their standardized forms. */
    private static final Map<String, String> DEPARTMENT_SYNONYMS = Map.of(
            "pediatrics", "Paediatrics",
            "intensive care", "ICU",
            "emergency room", "Emergency");

    /** Date formatters tried in order when parsing dates. Uses strict resolution to reject invalid dates like Feb 31. */
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd"),
            DateTimeFormatter.ofPattern("uuuu/MM/dd"),
            DateTimeFormatter.ofPattern("MM/dd/uuuu"),
            DateTimeFormatter.ofPattern("M/d/uuuu"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("dd.MM.uuuu"),
            DateTimeFormatter.ofPattern("dd/MM/uuuu")).stream()
            .map(f -> f.withResolverStyle(java.time.format.ResolverStyle.STRICT))
            .toList();

    /** Private constructor - this is a utility class with only static methods. */
    private WardCleaner() {
    }

    /**
     * Cleans the given CSV rows (headers + data) into de-duplicated ward records.
     * 
     * This is the main entry point for the cleaner. It:
     * 1. Normalizes the header names to lowercase with underscores
     * 2. Processes each row, extracting and cleaning the field values
     * 3. Merges duplicate wards (same ward_id) keeping the most complete values
     * 4. Returns a list of unique Ward records in first-seen order
     * 
     * @param header first row of the file, used to identify columns
     * @param rows   remaining raw data rows from the CSV
     * @return cleaned wards, one per real-world ward, in first-seen order
     */
    public static List<Ward> clean(String[] header, List<String[]> rows) {
        // Normalize all column names to lowercase with underscores
        List<String> normalizedHeader = new ArrayList<>(header.length);
        for (String column : header) {
            normalizedHeader.add(normalizeHeader(column));
        }

        // Map to store wards by their ID, preserving insertion order
        Map<String, WardBuilder> byId = new LinkedHashMap<>();
        int csvLine = 1; // CSV line numbers are 1-based; header is line 1

        // Process each row of data
        for (String[] rawRow : rows) {
            csvLine++;
            WardBuilder builder = new WardBuilder(csvLine);
            
            // Pad the row to match the header length (handles short rows)
            String[] row = padRow(rawRow, normalizedHeader.size());

            // Process each column based on its normalized name
            for (int i = 0; i < normalizedHeader.size(); i++) {
                String column = normalizedHeader.get(i);
                String value = row[i];
                
                // Route to the appropriate cleaning logic based on column name
                switch (column) {
                    case "ward_id" -> builder.wardId = normalizeWardId(value);
                    case "wing" -> builder.wing = titleCase(value);
                    case "department" -> builder.department = normalizeDepartment(value);
                    case "beds_available" -> builder.beds = parseBeds(value, builder.issues);
                    default -> normalizeByColumnType(column, value, builder);
                }
            }

            // Merge this row into the map or add as new ward
            mergeOrAdd(byId, builder);
        }

        // Convert all WardBuilder objects to final Ward records
        return byId.values().stream().map(WardBuilder::build).toList();
    }

    /**
     * Merges one cleaned row into the map, keeping the most complete/valid values.
     * 
     * When a duplicate ward_id is found, this method:
     * 1. Logs that a duplicate was merged
     * 2. Fills in any missing values from the new row
     * 3. Preserves any issues from the new row
     * 4. Merges any additional fields
     * 
     * @param byId   map of ward_id to WardBuilder being built
     * @param row    the new row to merge
     */
    private static void mergeOrAdd(Map<String, WardBuilder> byId, WardBuilder row) {
        // Skip rows without a usable identifier - nothing to merge on
        if (row.wardId == null || row.wardId.isBlank()) {
            return;
        }
        
        WardBuilder existing = byId.get(row.wardId);
        if (existing == null) {
            // First time seeing this ward_id - add it to the map
            byId.put(row.wardId, row);
            return;
        }
        
        // Ward already exists - merge the new data into the existing record
        existing.issues.add("merged duplicate from csv line " + row.csvLine);
        
        // Fill in missing values from the duplicate row
        if (existing.wing == null && row.wing != null) {
            existing.wing = row.wing;
        }
        if (existing.department == null && row.department != null) {
            existing.department = row.department;
        }
        if (existing.beds == null && row.beds != null) {
            existing.beds = row.beds;
        }
        
        // Preserve any issues from the duplicate row
        if (!row.issues.isEmpty()) {
            existing.issues.add("duplicate had issues: " + String.join("; ", row.issues));
        }
        
        // Merge any additional fields from the duplicate
        existing.other.putAll(row.other);
    }

    /**
     * Pads a row to match the expected header length.
     * 
     * If a row has fewer columns than the header, empty strings are added
     * to fill the missing positions. This prevents ArrayIndexOutOfBoundsException
     * when processing short rows.
     * 
     * @param row  the original row from the CSV
     * @param size the expected number of columns (from the header)
     * @return a new array of the correct length, padded with empty strings
     */
    private static String[] padRow(String[] row, int size) {
        String[] padded = new String[size];
        for (int i = 0; i < size; i++) {
            padded[i] = i < row.length ? row[i] : "";
        }
        return padded;
    }

    /**
     * Normalizes a CSV header column name.
     * 
     * Converts to lowercase, trims whitespace, and replaces spaces with underscores.
     * For example, "Beds Available" becomes "beds_available".
     * 
     * @param raw the original column name from the CSV
     * @return normalized column name (lowercase with underscores)
     */
    private static String normalizeHeader(String raw) {
        String trimmed = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.replaceAll("\\s+", "_");
    }

    /**
     * Normalizes a raw string value by trimming whitespace and collapsing internal spaces.
     * 
     * Returns null if the result is blank or matches a known placeholder value.
     * This is the core normalization method used by most other normalizers.
     * 
     * @param raw the original string value from the CSV
     * @return normalized string, or null if blank/placeholder
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        // Trim leading/trailing whitespace and collapse internal runs of whitespace
        String collapsed = raw.trim().replaceAll("\\s+", " ").trim();
        // Return null for empty strings and known placeholders
        if (collapsed.isEmpty() || isPlaceholder(collapsed)) {
            return null;
        }
        return collapsed;
    }

    /**
     * Checks if a value is a known placeholder for missing data.
     * 
     * Placeholders include empty strings, "N/A", "TBD", "unknown", "-", etc.
     * These are treated as missing values rather than actual data.
     * 
     * @param value the string to check
     * @return true if the value is a placeholder, false otherwise
     */
    private static boolean isPlaceholder(String value) {
        return PLACEHOLDERS.contains(value.toLowerCase(Locale.ROOT));
    }

    /**
     * Normalizes a ward ID by trimming, collapsing spaces, and converting to uppercase.
     * 
     * Ward IDs are always stored in uppercase to ensure consistent comparison.
     * For example, "w-05" becomes "W-05".
     * 
     * @param raw the original ward ID from the CSV
     * @return normalized uppercase ward ID, or null if blank/placeholder
     */
    public static String normalizeWardId(String raw) {
        String normalized = normalize(raw);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    /**
     * Title-cases a display name, keeping all-caps acronyms intact.
     * 
     * For example:
     * - "east wing" becomes "East Wing"
     * - "ICU" stays "ICU" (all-caps acronym)
     * - "intensive care" becomes "Intensive Care"
     * 
     * @param raw the original string from the CSV
     * @return title-cased string, or null if blank/placeholder
     */
    public static String titleCase(String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return null;
        }
        
        StringBuilder result = new StringBuilder(normalized.length());
        // Split on spaces and process each word
        for (String token : normalized.split(" ")) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            // Keep all-caps acronyms like "ICU" as-is
            if (isAcronym(token)) {
                result.append(token);
            } else {
                // Title-case: first letter uppercase, rest lowercase
                result.append(Character.toUpperCase(token.charAt(0)))
                        .append(token.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return result.toString();
    }

    /**
     * Checks if a token is an all-caps acronym (like "ICU" or "MRI").
     * 
     * Acronyms are kept in all-caps when title-casing, since they represent
     * well-known abbreviations that should not be converted to title case.
     * 
     * @param token the word to check
     * @return true if the token is all-caps and has more than one character
     */
    private static boolean isAcronym(String token) {
        return token.length() > 1 && token.equals(token.toUpperCase(Locale.ROOT));
    }

    /**
     * Normalizes a department name, mapping regional spelling/synonym variants.
     * 
     * For example:
     * - "pediatrics" becomes "Paediatrics" (British spelling)
     * - "intensive care" becomes "ICU" (common abbreviation)
     * - "emergency room" becomes "Emergency" (standardized name)
     * 
     * @param raw the original department name from the CSV
     * @return normalized department name, or null if blank/placeholder
     */
    public static String normalizeDepartment(String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return null;
        }
        // First title-case the name, then check for known synonyms
        String titled = titleCase(normalized);
        String synonym = DEPARTMENT_SYNONYMS.get(titled.toLowerCase(Locale.ROOT));
        return synonym != null ? synonym : titled;
    }

    /**
     * Parses a bed count from a string value.
     * 
     * Returns null for missing/placeholder values and for anything that is:
     * - Non-numeric (e.g. "five")
     * - Negative (e.g. "-1")
     * - Unrealistically large (e.g. "2023")
     * 
     * Problems are reported via the "issues" list rather than throwing exceptions.
     * 
     * @param raw    the raw string value from the CSV
     * @param issues list to collect any problems found during parsing
     * @return parsed bed count, or null if invalid/missing
     */
    public static Integer parseBeds(String raw, List<String> issues) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return null; // missing or placeholder — not an error
        }
        
        try {
            int beds = Integer.parseInt(normalized);
            
            // Flag negative bed counts as errors
            if (beds < 0) {
                issues.add("bedsAvailable was negative (" + beds + ") — flagged for follow-up");
                return null;
            }
            
            // Flag unrealistically large bed counts as errors
            if (beds > MAX_SENSIBLE_BEDS) {
                issues.add("bedsAvailable was unrealistic (" + beds + ") — flagged for follow-up");
                return null;
            }
            
            return beds;
        } catch (NumberFormatException e) {
            // Non-numeric value - flag it as an issue
            issues.add("bedsAvailable was non-numeric ('" + normalized + "') — flagged for follow-up");
            return null;
        }
    }

    /**
     * Normalizes a date string to ISO format (yyyy-MM-dd).
     * 
     * Tries multiple common date formats in order:
     * - yyyy-MM-dd (ISO format)
     * - yyyy/MM/dd
     * - MM/dd/yyyy (US format)
     * - M/d/yyyy (US format without leading zeros)
     * - dd-MM-yyyy (European format)
     * - d-M-yyyy (European format without leading zeros)
     * - dd.MM.yyyy (German format)
     * - dd/MM/yyyy (European format with slashes)
     * 
     * Uses strict resolution to reject invalid dates like Feb 31.
     * 
     * @param raw the raw date string from the CSV
     * @return normalized date in yyyy-MM-dd format, or null if invalid
     */
    public static String normalizeDate(String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return null;
        }
        
        // Try each date format until one works
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalized, formatter).format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        return null; // no format matched
    }

    /**
     * Normalizes common boolean representations.
     * 
     * Accepts various forms of true/false:
     * - True: "y", "yes", "1", "true", "t"
     * - False: "n", "no", "0", "false", "f"
     * 
     * Returns null if the value is not a recognized boolean representation.
     * 
     * @param raw the raw boolean string from the CSV
     * @return Boolean.TRUE, Boolean.FALSE, or null if unrecognised
     */
    public static Boolean normalizeBoolean(String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return null;
        }
        
        // Map common boolean representations to Boolean values
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "y", "yes", "1", "true", "t" -> Boolean.TRUE;
            case "n", "no", "0", "false", "f" -> Boolean.FALSE;
            default -> null; // unrecognised value
        };
    }

    /**
     * Applies type-specific normalization to a column based on its name.
     * 
     * This method handles date and boolean columns generically by looking for
     * keywords in the column name:
     * - Columns containing "date" are parsed as dates
     * - Columns containing "flag", "active", "enabled", "boolean", or starting
     *   with "is_" are parsed as booleans
     * - All other columns are stored as plain strings
     * 
     * This makes the cleaner flexible enough to handle new columns in future
     * CSV exports without code changes.
     * 
     * @param column  the normalized column name
     * @param value   the raw value from the CSV
     * @param builder the WardBuilder to populate
     */
    private static void normalizeByColumnType(String column, String value, WardBuilder builder) {
        String normalized = normalize(value);
        if (normalized == null) {
            return;
        }
        
        // Check if this is a date column
        if (column.contains("date")) {
            String iso = normalizeDate(normalized);
            if (iso == null) {
                builder.issues.add("column '" + column + "' had invalid date '" + value.trim() + "' — set to null");
            } else {
                builder.other.put(column, iso);
            }
        } 
        // Check if this is a boolean column
        else if (column.contains("flag") || column.contains("active") || column.contains("enabled")
                || column.contains("boolean") || column.startsWith("is_")) {
            Boolean bool = normalizeBoolean(normalized);
            if (bool == null) {
                builder.issues.add("column '" + column + "' had unrecognised boolean '" + value.trim() + "' — set to null");
            } else {
                builder.other.put(column, bool);
            }
        } 
        // Otherwise, store as a plain string
        else {
            builder.other.put(column, normalized);
        }
    }

    /**
     * Mutable accumulator for one cleaned ward during processing.
     * 
     * This helper class holds the intermediate state while a ward is being
     * cleaned. It collects issues and additional fields that don't fit into
     * the standard Ward record fields.
     * 
     * Using a mutable builder avoids creating many intermediate Ward objects
     * during the cleaning process.
     */
    private static final class WardBuilder {
        /** The CSV line number where this ward was found (for error reporting). */
        final int csvLine;
        
        /** The normalized ward ID (e.g. "W-05"). */
        String wardId;
        
        /** The title-cased wing name (e.g. "East Wing"). */
        String wing;
        
        /** The normalized department name (e.g. "Cardiology"). */
        String department;
        
        /** The parsed bed count, or null if invalid/missing. */
        Integer beds;
        
        /** List of issues found during cleaning (e.g. "bedsAvailable was negative"). */
        final List<String> issues = new ArrayList<>();
        
        /** Map of additional fields not in the standard Ward record. */
        final Map<String, Object> other = new LinkedHashMap<>();

        /**
         * Creates a new WardBuilder for a row at the given CSV line number.
         * 
         * @param csvLine the line number in the CSV file (for error reporting)
         */
        WardBuilder(int csvLine) {
            this.csvLine = csvLine;
        }

        /**
         * Builds the final Ward record from the accumulated data.
         * 
         * @return a new Ward record with all the cleaned data
         */
        Ward build() {
            // Join all issues into a single notes string (or null if no issues)
            String notesText = issues.isEmpty() ? null : String.join("; ", issues);
            return new Ward(wardId, wing, department, beds, notesText);
        }
    }
}
