package co.wethinkcode.healthsafe;

/**
 * Computes on-call doctor schedules based on the ward's department and the current
 * hospital Emergency Status (0-8).
 * 
 * This is a pure function class with no side effects, making it easy to unit test.
 * The scheduling rules are:
 * 
 * 1. Base staffing:
 *    - Critical departments (ICU, Oncology) start with 2 doctors
 *    - All other departments start with 1 doctor
 * 
 * 2. Surge staffing based on emergency level:
 *    - Levels 0-2 (normal to elevated): no extra doctors
 *    - Levels 3-5 (high to severe): +1 doctor
 *    - Levels 6-8 (critical to Code Blue): +2 doctors
 * 
 * 3. Role composition:
 *    - Always starts with 1 Consultant (senior doctor)
 *    - If total >= 2, adds 1 Registrar (mid-level doctor)
 *    - Remaining slots are filled with Junior Doctors
 * 
 * Example: A normal ward at alert level 0 gets 1 Consultant.
 *          The same ward at level 8 gets 1 Consultant + 1 Registrar + 1 Junior Doctor.
 *          ICU at level 8 gets 1 Consultant + 1 Registrar + 2 Junior Doctors.
 */
public final class ScheduleComputer {

    /** Departments that always have a heavier on-call baseline (2 doctors instead of 1). */
    private static final java.util.Set<String> CRITICAL_DEPARTMENTS = java.util.Set.of("ICU", "Oncology");

    /**
     * Represents a specific role in the on-call roster.
     * 
     * @param role  the role name (e.g. "Consultant", "Registrar", "Junior Doctor")
     * @param count how many doctors are needed for this role
     */
    public record Role(String role, int count) {
    }

    /**
     * The complete on-call roster for a ward.
     * 
     * @param totalDoctors total number of doctors needed
     * @param roles        list of roles and their counts
     */
    public record OnCallRoster(int totalDoctors, java.util.List<Role> roles) {
    }

    /** Private constructor - this is a utility class with only static methods. */
    private ScheduleComputer() {
    }

    /**
     * Computes the on-call roster for a given department and emergency level.
     * 
     * This is the main entry point for schedule computation. It determines:
     * 1. The base staffing level (1 or 2 doctors depending on department)
     * 2. The surge staffing based on emergency level (0, 1, or 2 extra doctors)
     * 3. The composition of roles (Consultant, Registrar, Junior Doctors)
     * 
     * The alert level is clamped to the valid range (0-8) to handle invalid inputs.
     * 
     * @param department   normalized department name (may be null for general wards)
     * @param alertLevel   Emergency Status level (0-8), clamped if out of range
     * @return the computed on-call roster with role breakdown
     */
    public static OnCallRoster compute(String department, int alertLevel) {
        // Clamp the alert level to valid range (0-8)
        int level = Math.max(0, Math.min(8, alertLevel));
        
        // Determine base staffing: 2 for critical departments, 1 for others
        int base = isCritical(department) ? 2 : 1;
        
        // Determine surge staffing based on emergency level:
        // 0-2: no surge (0 extra doctors)
        // 3-5: moderate surge (1 extra doctor)
        // 6-8: high surge (2 extra doctors)
        int surge = level <= 2 ? 0 : (level <= 5 ? 1 : 2);
        
        // Total doctors needed
        int total = base + surge;

        // Build the list of roles
        java.util.List<Role> roles = new java.util.ArrayList<>();
        
        // Always start with a Consultant (senior doctor)
        roles.add(new Role("Consultant", 1));
        
        // Add a Registrar if we need 2 or more doctors
        if (total >= 2) {
            roles.add(new Role("Registrar", 1));
        }
        
        // Fill remaining slots with Junior Doctors
        int junior = total - roles.stream().mapToInt(Role::count).sum();
        if (junior > 0) {
            roles.add(new Role("Junior Doctor", junior));
        }
        
        // Return the complete roster as an immutable list
        return new OnCallRoster(total, java.util.List.copyOf(roles));
    }

    /**
     * Checks if a department is considered critical (requires higher baseline staffing).
     * 
     * Critical departments are ICU and Oncology, which always need at least 2 doctors
     * on call, even at normal emergency levels.
     * 
     * @param department the department name to check (may be null)
     * @return true if the department is critical, false otherwise
     */
    private static boolean isCritical(String department) {
        return department != null && CRITICAL_DEPARTMENTS.contains(department.toUpperCase());
    }
}
