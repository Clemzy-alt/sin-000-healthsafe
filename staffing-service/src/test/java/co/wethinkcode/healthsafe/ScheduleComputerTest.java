package co.wethinkcode.healthsafe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the ScheduleComputer scheduling rules.
 *
 * ScheduleComputer is the one piece of business logic staffing-service owns,
 * so these tests pin down the rules documented in its class javadoc:
 *
 * - baseline staffing: critical departments (ICU, Oncology) start at 2 doctors,
 *   everything else at 1
 * - surge staffing: no extra doctors at levels 0-2, +1 at 3-5, +2 at 6-8
 * - role composition: always 1 Consultant, a Registrar once there are 2+
 *   doctors, then Junior Doctors for the remaining slots
 * - out-of-range levels are clamped rather than trusted
 *
 * Each test asserts the whole roster, not just the total, so an accidental
 * change to role composition fails loudly.
 */
class ScheduleComputerTest {

    /** A normal ward at level 0 needs a single Consultant. */
    @Test
    void normalWardAtLevelZeroNeedsOneConsultant() {
        ScheduleComputer.OnCallRoster roster = ScheduleComputer.compute("Cardiology", 0);

        assertEquals(1, roster.totalDoctors());
        assertEquals(List.of(new ScheduleComputer.Role("Consultant", 1)), roster.roles());
    }

    /** Levels 3-5 add one surge doctor: Consultant + Registrar. */
    @Test
    void moderateSurgeAddsRegistrar() {
        ScheduleComputer.OnCallRoster roster = ScheduleComputer.compute("Cardiology", 4);

        assertEquals(2, roster.totalDoctors());
        assertEquals(List.of(
                new ScheduleComputer.Role("Consultant", 1),
                new ScheduleComputer.Role("Registrar", 1)), roster.roles());
    }

    /** Levels 6-8 add two surge doctors: Consultant + Registrar + 1 Junior Doctor. */
    @Test
    void highSurgeAddsJuniorDoctor() {
        ScheduleComputer.OnCallRoster roster = ScheduleComputer.compute("Cardiology", 8);

        assertEquals(3, roster.totalDoctors());
        assertEquals(List.of(
                new ScheduleComputer.Role("Consultant", 1),
                new ScheduleComputer.Role("Registrar", 1),
                new ScheduleComputer.Role("Junior Doctor", 1)), roster.roles());
    }

    /** Critical departments start with 2 doctors, so level 0 already has a Registrar. */
    @Test
    void criticalDepartmentStartsWithTwoDoctors() {
        ScheduleComputer.OnCallRoster roster = ScheduleComputer.compute("ICU", 0);

        assertEquals(2, roster.totalDoctors());
        assertEquals(List.of(
                new ScheduleComputer.Role("Consultant", 1),
                new ScheduleComputer.Role("Registrar", 1)), roster.roles());
    }

    /** Critical department matching is case-insensitive ("oncology" == "Oncology"). */
    @Test
    void criticalDepartmentMatchIsCaseInsensitive() {
        ScheduleComputer.OnCallRoster lower = ScheduleComputer.compute("oncology", 0);
        ScheduleComputer.OnCallRoster exact = ScheduleComputer.compute("Oncology", 0);
        ScheduleComputer.OnCallRoster upper = ScheduleComputer.compute("ONCOLOGY", 0);

        assertEquals(2, lower.totalDoctors());
        assertEquals(2, exact.totalDoctors());
        assertEquals(2, upper.totalDoctors());
    }

    /** An unknown or null department is treated as a normal ward, not an error. */
    @Test
    void unknownDepartmentFallsBackToBaseline() {
        assertEquals(1, ScheduleComputer.compute(null, 0).totalDoctors());
        assertEquals(1, ScheduleComputer.compute("Unicorn Medicine", 0).totalDoctors());
    }

    /** Levels outside 0-8 are clamped: negative behaves like 0, 99 like 8. */
    @Test
    void outOfRangeLevelsAreClamped() {
        ScheduleComputer.OnCallRoster belowZero = ScheduleComputer.compute("Cardiology", -5);
        ScheduleComputer.OnCallRoster wayAbove = ScheduleComputer.compute("Cardiology", 99);

        assertEquals(ScheduleComputer.compute("Cardiology", 0).totalDoctors(), belowZero.totalDoctors());
        assertEquals(ScheduleComputer.compute("Cardiology", 8).totalDoctors(), wayAbove.totalDoctors());
    }

    /** Role counts always add up to the reported total - no doctor is unaccounted for. */
    @Test
    void roleCountsSumToTotal() {
        for (int level = 0; level <= 8; level++) {
            ScheduleComputer.OnCallRoster roster = ScheduleComputer.compute("ICU", level);
            int sum = roster.roles().stream().mapToInt(ScheduleComputer.Role::count).sum();
            assertEquals(roster.totalDoctors(), sum, "roles must sum to total at level " + level);
            assertEquals("Consultant", roster.roles().get(0).role(), "roster always starts with a Consultant");
        }
    }
}
