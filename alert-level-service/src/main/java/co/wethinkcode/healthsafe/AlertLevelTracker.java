package co.wethinkcode.healthsafe;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe holder for the hospital Emergency Status (0-8, 8 = full Code Blue).
 *
 * The tracker keeps the current status in a single atomic integer so concurrent
 * HTTP readers (staffing-service polling the level) always see a consistent
 * value while another caller is updating it.
 *
 * Validation lives here rather than in the HTTP layer so the range rule
 * (0-8 inclusive) is enforced no matter how the tracker is used, and so it can
 * be unit tested without starting a server.
 *
 * Usage:
 *   AlertLevelTracker tracker = new AlertLevelTracker();
 *   tracker.current();        // -> 0
 *   tracker.update(5);        // -> 5
 *   tracker.update(9);        // -> throws IllegalArgumentException
 */
public final class AlertLevelTracker {

    /** Lowest valid Emergency Status — normal operations. */
    public static final int MIN_LEVEL = 0;

    /** Highest valid Emergency Status — full Code Blue. */
    public static final int MAX_LEVEL = 8;

    /** The current status. Atomic so reads and writes never tear. */
    private final AtomicInteger level = new AtomicInteger(MIN_LEVEL);

    /**
     * Returns the current Emergency Status.
     *
     * @return the current level, between {@link #MIN_LEVEL} and {@link #MAX_LEVEL}
     */
    public int current() {
        return level.get();
    }

    /**
     * Validates and stores a new Emergency Status.
     *
     * @param newLevel the level to store, must be between 0 and 8 inclusive
     * @return the newly stored level
     * @throws IllegalArgumentException if {@code newLevel} is outside 0-8
     */
    public int update(int newLevel) throws IllegalArgumentException {
        validate(newLevel);
        level.set(newLevel);
        return level.get();
    }

    /**
     * Checks that a level falls inside the valid 0-8 range.
     *
     * @param candidate the level to check
     * @throws IllegalArgumentException if the level is outside 0-8
     */
    public static void validate(int candidate) throws IllegalArgumentException {
        if (candidate < MIN_LEVEL || candidate > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "alert level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + " (got " + candidate + ")");
        }
    }
}
