package co.wethinkcode.healthsafe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the AlertLevelTracker class.
 *
 * These tests verify the two responsibilities of the tracker:
 *
 * - State handling: the level starts at 0 and stores what it is given
 * - Range validation: only 0-8 inclusive is accepted, everything else is
 *   rejected with IllegalArgumentException
 *
 * Validation is the contract staffing-service and operators rely on, so the
 * boundary values (just inside and just outside the range) are covered
 * explicitly.
 */
class AlertLevelTrackerTest {

    /** A fresh tracker starts at normal operations (level 0). */
    @Test
    void startsAtNormalOperations() {
        assertEquals(0, new AlertLevelTracker().current());
    }

    /** Every valid level in the 0-8 range is accepted and stored. */
    @Test
    void acceptsEveryLevelInRange() {
        AlertLevelTracker tracker = new AlertLevelTracker();
        for (int level = AlertLevelTracker.MIN_LEVEL; level <= AlertLevelTracker.MAX_LEVEL; level++) {
            assertEquals(level, tracker.update(level));
            assertEquals(level, tracker.current());
        }
    }

    /** Levels above 8 (e.g. "full code blue plus" mistakes) are rejected. */
    @Test
    void rejectsLevelAboveMaximum() {
        AlertLevelTracker tracker = new AlertLevelTracker();
        assertThrows(IllegalArgumentException.class, () -> tracker.update(9));
        // The rejected value must not have been stored
        assertEquals(0, tracker.current());
    }

    /** Negative levels are rejected — there is no status below normal. */
    @Test
    void rejectsNegativeLevel() {
        AlertLevelTracker tracker = new AlertLevelTracker();
        assertThrows(IllegalArgumentException.class, () -> tracker.update(-1));
    }

    /** validate() alone enforces the same range, for use without a tracker instance. */
    @Test
    void validateEnforcesBoundaries() {
        AlertLevelTracker.validate(0);
        AlertLevelTracker.validate(8);
        assertThrows(IllegalArgumentException.class, () -> AlertLevelTracker.validate(-1));
        assertThrows(IllegalArgumentException.class, () -> AlertLevelTracker.validate(9));
    }
}
