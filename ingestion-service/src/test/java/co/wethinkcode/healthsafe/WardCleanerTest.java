package co.wethinkcode.healthsafe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the WardCleaner class.
 * 
 * These tests verify that the WardCleaner correctly handles various data quality
 * issues found in the raw CSV export. Each test focuses on a specific cleaning
 * scenario:
 * 
 * - Trimming and whitespace normalization
 * - Inconsistent casing fixes
 * - Acronym preservation (e.g. ICU stays uppercase)
 * - Regional spelling variant mapping
 * - Placeholder normalization to null
 * - Non-numeric bed count detection
 * - Negative bed count detection
 * - Unrealistic bed count detection
 * - Duplicate ward merging
 * - Missing value filling from duplicates
 * - Handling of rows without IDs
 * - Handling of short rows
 * - Full dataset cleaning
 * - Date normalization
 * - Boolean normalization
 * 
 * The tests use a helper method that creates a standard header and processes
 * the data rows through the cleaner.
 */
class WardCleanerTest {

    /**
     * Helper method to clean test data rows with a standard header.
     * 
     * Creates a header with columns: ward_id, Wing, department, beds_available
     * and processes the given data rows through WardCleaner.clean().
     * 
     * @param dataRows variable number of CSV data rows to clean
     * @return list of cleaned Ward records
     */
    private static List<Ward> clean(String... dataRows) {
        // Standard header for all tests
        String[] header = new String[]{"ward_id", "Wing", "department", "beds_available"};
        
        // Split each row by comma and trim whitespace from each cell
        List<String[]> rows = java.util.stream.Stream.of(dataRows)
                .map(r -> r.split(",", -1))
                .map(cells -> java.util.Arrays.stream(cells).map(c -> c.trim()).toArray(String[]::new))
                .toList();
        
        return WardCleaner.clean(header, rows);
    }

    /**
     * Tests that padding and double spaces are properly cleaned.
     * Verifies that leading/trailing whitespace is trimmed and internal
     * runs of spaces are collapsed to single spaces.
     */
    @Test
    void trimsPaddingAndCollapsesDoubleSpaces() {
        Ward ward = clean(" W-01 , East  Wing ,Cardiology, 3 ").get(0);
        assertEquals("W-01", ward.wardId());
        assertEquals("East Wing", ward.wing());
        assertEquals("Cardiology", ward.department());
        assertEquals(3, ward.bedsAvailable());
        assertNull(ward.notes());
    }

    /**
     * Tests that inconsistent casing is fixed throughout the record.
     * Ward IDs should be uppercase, wings should be title-cased,
     * and departments should be normalized.
     */
    @Test
    void fixesInconsistentCasing() {
        Ward ward = clean("w-02,West Wing,paediatrics,N/A").get(0);
        assertEquals("W-02", ward.wardId());
        assertEquals("West Wing", ward.wing());
        assertEquals("Paediatrics", ward.department());
    }

    /**
     * Tests that all-caps acronyms like ICU are preserved during title-casing.
     * Acronyms should not be converted to "Icu" or similar.
     */
    @Test
    void keepsAllCapsAcronymsLikeIcu() {
        Ward ward = clean("W-09,North Wing,ICU,2").get(0);
        assertEquals("ICU", ward.department());
    }

    /**
     * Tests that regional spelling variants are mapped to standardized forms.
     * For example, "Pediatrics" (US spelling) should become "Paediatrics" (British).
     */
    @Test
    void mapsRegionalSpellingVariants() {
        Ward ward = clean("W-11,East Wing,Pediatrics,3").get(0);
        assertEquals("Paediatrics", ward.department());
    }

    /**
     * Tests that placeholder values like "N/A" are normalized to null.
     * Placeholders should not be flagged as errors in the notes field.
     */
    @Test
    void normalizesPlaceholdersToNull() {
        Ward ward = clean("W-02,West Wing,paediatrics,N/A").get(0);
        assertNull(ward.bedsAvailable());
        assertNull(ward.notes(), "placeholder should not be flagged as an error");
    }

    /**
     * Tests that non-numeric bed counts are detected and flagged.
     * The bed count should be null and the notes should mention "non-numeric".
     */
    @Test
    void flagsNonNumericBedCount() {
        Ward ward = clean("w-05,east wing,PAEDIATRICS,five").get(0);
        assertNull(ward.bedsAvailable());
        assertNotNull(ward.notes());
        assertTrue(ward.notes().contains("non-numeric"));
    }

    /**
     * Tests that negative bed counts are detected and flagged.
     * The bed count should be null and the notes should mention "negative".
     */
    @Test
    void flagsNegativeBedCount() {
        Ward ward = clean("W-04,North Wing,Oncology,-1").get(0);
        assertNull(ward.bedsAvailable());
        assertTrue(ward.notes().contains("negative"));
    }

    /**
     * Tests that unrealistically large bed counts are detected and flagged.
     * The bed count should be null and the notes should mention "unrealistic".
     */
    @Test
    void flagsUnrealisticBedCount() {
        Ward ward = clean("W-13,North Wing,Oncology,2023").get(0);
        assertNull(ward.bedsAvailable());
        assertTrue(ward.notes().contains("unrealistic"));
    }

    /**
     * Tests that duplicate wards are merged, keeping the most complete values.
     * When the same ward_id appears multiple times, the records should be
     * merged into one, with the first valid values preserved.
     */
    @Test
    void mergesDuplicatesKeepingMoreCompleteValues() {
        List<Ward> wards = clean(
                "W-05,East Wing,Paediatrics,5",
                "w-05,east wing,PAEDIATRICS,five");
        assertEquals(1, wards.size());
        Ward merged = wards.get(0);
        assertEquals("W-05", merged.wardId());
        assertEquals(5, merged.bedsAvailable(), "kept the valid bed count from the first row");
        assertTrue(merged.notes().contains("merged duplicate"));
    }

    /**
     * Tests that missing values are filled from duplicate rows.
     * If one row has a wing and another doesn't, the merged result should
     * have the wing from the row that provided it.
     */
    @Test
    void fillsMissingValuesFromDuplicate() {
        List<Ward> wards = clean(
                "W-08,,Oncology,4",
                "W-08,South Wing,Oncology,");
        assertEquals(1, wards.size());
        Ward merged = wards.get(0);
        assertEquals("South Wing", merged.wing(), "wing filled in from duplicate");
        assertEquals(4, merged.bedsAvailable());
    }

    /**
     * Tests that rows without a usable ward ID are dropped without crashing.
     * Rows with empty or blank ward IDs should be silently ignored.
     */
    @Test
    void dropsRowsWithoutUsableIdWithoutCrashing() {
        List<Ward> wards = clean(
                ",East Wing,Cardiology,3",
                "W-01,East Wing,Cardiology,3");
        assertEquals(1, wards.size());
        assertEquals("W-01", wards.get(0).wardId());
    }

    /**
     * Tests that short rows (with fewer columns than the header) are handled.
     * Missing columns should be treated as empty/blank values.
     */
    @Test
    void handlesShorterRowsWithoutCrashing() {
        List<Ward> wards = clean("W-06,South Wing"); // missing department + beds columns
        assertEquals(1, wards.size());
        assertEquals("W-06", wards.get(0).wardId());
        assertNull(wards.get(0).bedsAvailable());
    }

    /**
     * Integration test that cleans the full provided dataset.
     * Verifies that all cleaning rules work together correctly on a
     * realistic dataset with multiple issues.
     */
    @Test
    void fullCleanOfProvidedDataset() {
        // Create a realistic dataset with various issues
        List<String[]> rows = java.util.stream.Stream.of(
                        "W-01, East Wing ,Cardiology,3",
                        "w-02,West Wing,paediatrics,N/A",
                        "W-03 ,east wing,Cardiology,0",
                        "W-04,North Wing,Oncology,-1",
                        "W-05,East Wing,Paediatrics,5",
                        "w-05,east wing ,PAEDIATRICS,five",
                        "W-06,South Wing,Radiology,2",
                        "W-07,West Wing,cardiology,TBD",
                        "W-08,,Oncology,4",
                        "W-09,North Wing,ICU,N/A",
                        "W-10,South  Wing,Maternity,1",
                        "W-11,East Wing,Pediatrics,3",
                        "w-12,west wing,Cardiology,full",
                        "W-13,North Wing,Oncology,2023",
                        "W-14,South Wing,radiology,-2",
                        "W-15,East Wing,ICU,unknown",
                        "W-16,West Wing ,Maternity,0",
                        "w-17,North wing,oncology,6")
                .map(r -> r.split(",", -1))
                .map(cells -> java.util.Arrays.stream(cells).map(c -> c.trim()).toArray(String[]::new))
                .toList();
        
        // Clean the data
        List<Ward> wards = WardCleaner.clean(new String[]{"ward_id", "Wing", "department", "beds_available"}, rows);

        // Verify the results
        assertEquals(17, wards.size(), "18 data rows, one duplicate merged");
        
        // Check that W-05 was merged correctly
        assertEquals("W-05", wards.get(4).wardId());
        assertEquals(5, wards.get(4).bedsAvailable());
        assertTrue(wards.get(4).notes().contains("merged duplicate"));
        
        // Check that double spaces were collapsed
        assertEquals("South Wing", wards.get(9).wing(), "double space collapsed");
        assertFalse(wards.get(9).wing().contains("  "));
        
        // Check that invalid bed counts were flagged
        assertTrue(wards.get(11).notes().contains("non-numeric"), "W-12 'full' flagged");
        assertTrue(wards.get(12).notes().contains("unrealistic"), "W-13 2023 flagged");
    }

    /**
     * Tests that various date formats are normalized to ISO format (yyyy-MM-dd).
     * Tests common formats like yyyy-MM-dd, MM/dd/yyyy, dd-MM-yyyy, etc.
     */
    @Test
    void normalizesDateVariants() {
        assertEquals("2024-03-15", WardCleaner.normalizeDate("2024-03-15"));
        assertEquals("2024-03-15", WardCleaner.normalizeDate("03/15/2024"));
        assertEquals("2024-03-15", WardCleaner.normalizeDate("15-03-2024"));
        assertEquals("2024-03-05", WardCleaner.normalizeDate("3/5/2024"));
        assertNull(WardCleaner.normalizeDate("not-a-date"));
        assertNull(WardCleaner.normalizeDate("2024-02-31"), "invalid date rejected");
    }

    /**
     * Tests that various boolean representations are normalized correctly.
     * Tests common true/false variants like y/n, yes/no, 1/0, true/false, t/f.
     */
    @Test
    void normalizesBooleanVariants() {
        // Test all true variants
        for (String yes : List.of("Y", "yes", "1", "TRUE", "t")) {
            assertEquals(Boolean.TRUE, WardCleaner.normalizeBoolean(yes));
        }
        // Test all false variants
        for (String no : List.of("N", "no", "0", "FALSE", "f")) {
            assertEquals(Boolean.FALSE, WardCleaner.normalizeBoolean(no));
        }
        // Test unrecognised value
        assertNull(WardCleaner.normalizeBoolean("maybe"));
    }
}
