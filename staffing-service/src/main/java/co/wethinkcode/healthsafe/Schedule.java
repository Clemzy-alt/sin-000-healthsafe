package co.wethinkcode.healthsafe;

import java.util.List;

/**
 * A computed on-call schedule, returned by the staffing-service.
 * 
 * This record represents the result of computing an on-call schedule for a
 * specific ward. It contains all the information needed to understand the
 * staffing requirements:
 * - Which ward the schedule is for
 * - What department it belongs to
 * - What the current emergency level is
 * - How many doctors are needed total
 * - The specific roles and their counts
 * - When the schedule was generated
 * 
 * The schedule is immutable and can be safely shared between threads.
 * 
 * @param wardId        the ID of the ward this schedule is for
 * @param department    the department name (e.g. "Cardiology", "ICU")
 * @param alertLevel    the current emergency level (0-8)
 * @param totalDoctors  total number of doctors needed
 * @param onCall        list of roles and their counts (e.g. 1 Consultant, 1 Registrar)
 * @param generatedAt   ISO timestamp when this schedule was created
 */
public record Schedule(String wardId, String department, int alertLevel, int totalDoctors,
                       List<ScheduleComputer.Role> onCall, String generatedAt) {
}
