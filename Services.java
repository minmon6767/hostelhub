/*
 * Services.java - the business rules, one class per module.
 *
 *   StudentService    module 1, the resident registry
 *   RoomService       module 2, rooms and allocation
 *   ComplaintService  module 3, the complaint desk
 *   ReportService     module 4, reporting across all of the above
 *
 * Nothing here reads from the console or touches a file directly: services
 * depend only on Repository<T>, which is what lets TestRunner construct them
 * with throwaway repositories.
 */

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Module 1 - Student Registry.
 * Owns every rule about who counts as a valid resident record.
 */
class StudentService {

    private final Repository<Student> students;
    private final AppLogger log;

    public StudentService(Repository<Student> students, AppLogger log) {
        this.students = students;
        this.log = log;
    }

    public Student register(String name, String email, String phone, String branch, int year)
            throws ValidationException, DuplicateRecordException {

        String cleanName = Validator.requireText(name, "Name");
        String cleanEmail = Validator.requireEmail(email);
        String cleanPhone = Validator.requirePhone(phone);
        String cleanBranch = Validator.requireText(branch, "Branch").toUpperCase();
        Validator.requireRange(year, 1, 4, "Year of study");

        boolean emailTaken = students.findBy(s -> s.getEmail().equalsIgnoreCase(cleanEmail)).size() > 0;
        if (emailTaken) {
            throw new DuplicateRecordException("A student with email " + cleanEmail + " is already registered.");
        }

        String id = IdGenerator.next("STU", ids(), 3);
        Student student = new Student(id, cleanName, cleanEmail, cleanPhone, cleanBranch, year);
        students.save(student);
        log.info("Registered student " + id + " (" + cleanName + ")");
        return student;
    }

    public Student findById(String id) throws RecordNotFoundException {
        return students.findById(id.trim().toUpperCase())
                .orElseThrow(() -> new RecordNotFoundException("No student found with ID " + id));
    }

    public List<Student> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        return students.findBy(s -> s.getName().toLowerCase().contains(q)
                        || s.getId().toLowerCase().contains(q)
                        || s.getBranch().toLowerCase().contains(q))
                .stream()
                .sorted(Comparator.comparing(Student::getId))
                .collect(Collectors.toList());
    }

    public List<Student> listAll() {
        return students.findAll().stream()
                .sorted(Comparator.comparing(Student::getId))
                .collect(Collectors.toList());
    }

    public Student updateContact(String id, String newEmail, String newPhone)
            throws RecordNotFoundException, ValidationException {
        Student student = findById(id);
        if (newEmail != null && !newEmail.trim().isEmpty()) {
            student.setEmail(Validator.requireEmail(newEmail));
        }
        if (newPhone != null && !newPhone.trim().isEmpty()) {
            student.setPhone(Validator.requirePhone(newPhone));
        }
        students.save(student);
        log.info("Updated contact details for " + id);
        return student;
    }

    public void remove(String id) throws RecordNotFoundException {
        Student student = findById(id);
        students.deleteById(student.getId());
        log.info("Removed student record " + student.getId());
    }

    public int count() {
        return students.count();
    }

    private List<String> ids() {
        return students.findAll().stream().map(Student::getId).collect(Collectors.toList());
    }
}

/**
 * Module 2 - Rooms and Allocation.
 * The allocation rule itself is delegated to an AllocationStrategy, which can
 * be swapped at runtime from the menu.
 */
class RoomService {

    private final Repository<Room> rooms;
    private final Repository<Allocation> allocations;
    private final AppLogger log;
    private AllocationStrategy strategy;

    public RoomService(Repository<Room> rooms, Repository<Allocation> allocations,
                       AllocationStrategy strategy, AppLogger log) {
        this.rooms = rooms;
        this.allocations = allocations;
        this.strategy = strategy;
        this.log = log;
    }

    public AllocationStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(AllocationStrategy strategy) {
        this.strategy = strategy;
        log.info("Allocation strategy switched to: " + strategy.name());
    }

    public List<Room> listRooms() {
        return rooms.findAll().stream()
                .sorted(Comparator.comparing(Room::getRoomNo))
                .collect(Collectors.toList());
    }

    public Room findRoom(String roomNo) throws RecordNotFoundException {
        return rooms.findById(roomNo.trim().toUpperCase())
                .orElseThrow(() -> new RecordNotFoundException("No room numbered " + roomNo));
    }

    public List<Room> availableRooms(RoomType type) {
        return rooms.findAll().stream()
                .filter(Room::isAvailable)
                .filter(room -> type == null || room.getType() == type)
                .sorted(Comparator.comparing(Room::getRoomNo))
                .collect(Collectors.toList());
    }

    public Optional<Allocation> activeAllocationOf(String studentId) {
        return allocations.findBy(a -> a.isActive() && a.getStudentId().equals(studentId))
                .stream()
                .findFirst();
    }

    /**
     * Allocates a bed using the current strategy.
     * Rules enforced here: one active allocation per student, and the room must
     * actually have space (Room itself throws if it does not).
     */
    public Allocation allocate(Student student, RoomType preferredType)
            throws AllocationException, RoomFullException {

        if (activeAllocationOf(student.getId()).isPresent()) {
            throw new AllocationException(student.getName() + " already has an active room allocation. "
                    + "Vacate the existing room first.");
        }

        List<Room> candidates = availableRooms(preferredType);
        if (candidates.isEmpty()) {
            throw new AllocationException("No vacant "
                    + (preferredType == null ? "rooms" : preferredType.label() + " rooms")
                    + " are available right now.");
        }

        Room chosen = strategy.pick(candidates, student)
                .orElseThrow(() -> new AllocationException(
                        "Strategy '" + strategy.name() + "' could not settle on a room."));

        chosen.addOccupant(student.getId());
        rooms.save(chosen);

        String id = IdGenerator.next("ALC", allocationIds(), 4);
        Allocation allocation = new Allocation(id, student.getId(), chosen.getRoomNo(), LocalDate.now());
        allocations.save(allocation);

        log.info("Allocated " + chosen.getRoomNo() + " to " + student.getId()
                + " using strategy " + strategy.name());
        return allocation;
    }

    /** Manual override - the warden picks the exact room. */
    public Allocation allocateSpecific(Student student, String roomNo)
            throws AllocationException, RoomFullException, RecordNotFoundException {

        if (activeAllocationOf(student.getId()).isPresent()) {
            throw new AllocationException(student.getName() + " already occupies a room.");
        }
        Room room = findRoom(roomNo);
        if (room.isUnderMaintenance()) {
            throw new AllocationException("Room " + room.getRoomNo() + " is flagged for maintenance.");
        }
        room.addOccupant(student.getId());
        rooms.save(room);

        Allocation allocation = new Allocation(IdGenerator.next("ALC", allocationIds(), 4),
                student.getId(), room.getRoomNo(), LocalDate.now());
        allocations.save(allocation);
        log.info("Manually allocated " + room.getRoomNo() + " to " + student.getId());
        return allocation;
    }

    public Allocation vacate(String studentId) throws AllocationException, RecordNotFoundException {
        Allocation active = activeAllocationOf(studentId)
                .orElseThrow(() -> new AllocationException("Student " + studentId + " has no active allocation."));

        Room room = findRoom(active.getRoomNo());
        room.removeOccupant(studentId);
        rooms.save(room);

        active.close(LocalDate.now());
        allocations.save(active);
        log.info("Student " + studentId + " vacated room " + room.getRoomNo());
        return active;
    }

    public void setMaintenanceFlag(String roomNo, boolean flag)
            throws RecordNotFoundException, AllocationException {
        Room room = findRoom(roomNo);
        if (flag && room.occupancy() > 0) {
            throw new AllocationException("Room " + room.getRoomNo() + " still has "
                    + room.occupancy() + " resident(s). Move them out before blocking it.");
        }
        room.setUnderMaintenance(flag);
        rooms.save(room);
        log.info("Room " + room.getRoomNo() + " maintenance flag set to " + flag);
    }

    public List<Allocation> historyOf(String studentId) {
        return allocations.findBy(a -> a.getStudentId().equals(studentId))
                .stream()
                .sorted(Comparator.comparing(Allocation::getAllocatedOn).reversed())
                .collect(Collectors.toList());
    }

    private List<String> allocationIds() {
        return allocations.findAll().stream().map(Allocation::getId).collect(Collectors.toList());
    }
}

/**
 * Module 3 - Complaint Desk.
 * Handles raising, assigning and closing maintenance complaints, including the
 * SLA check that drives the "overdue" list.
 */
class ComplaintService {

    private final Repository<Complaint> complaints;
    private final Repository<Staff> staff;
    private final AppLogger log;

    public ComplaintService(Repository<Complaint> complaints, Repository<Staff> staff, AppLogger log) {
        this.complaints = complaints;
        this.staff = staff;
        this.log = log;
    }

    public Complaint raise(String studentId, String roomNo, ComplaintCategory category,
                           Priority priority, String description) throws ValidationException {

        String text = Validator.requireText(description, "Description");
        if (text.length() < 10) {
            throw new ValidationException("Please describe the problem in at least 10 characters.");
        }

        String id = IdGenerator.next("CMP", ids(), 4);
        Complaint complaint = new Complaint(id, studentId, roomNo, category, priority,
                CsvUtil.clean(text), LocalDate.now());
        complaints.save(complaint);
        log.info("Complaint " + id + " raised by " + studentId + " for room " + roomNo);
        return complaint;
    }

    public Complaint findById(String id) throws RecordNotFoundException {
        return complaints.findById(id.trim().toUpperCase())
                .orElseThrow(() -> new RecordNotFoundException("No complaint with ID " + id));
    }

    /** Suggests the least loaded staff member whose trade matches the complaint. */
    public Optional<Staff> suggestStaff(ComplaintCategory category) {
        return staff.findBy(s -> s.getTrade() == category).stream()
                .min(Comparator.comparingLong(s -> openWorkload(s.getId())));
    }

    public long openWorkload(String staffId) {
        return complaints.findBy(c -> staffId.equals(c.getAssignedStaffId()) && !c.getStatus().isClosed()).size();
    }

    public Complaint assign(String complaintId, String staffId)
            throws RecordNotFoundException, ValidationException {

        Complaint complaint = findById(complaintId);
        Staff member = staff.findById(staffId.trim().toUpperCase())
                .orElseThrow(() -> new RecordNotFoundException("No staff member with ID " + staffId));

        if (complaint.getStatus().isClosed()) {
            throw new ValidationException("Complaint " + complaintId + " is already resolved.");
        }
        if (member.getTrade() != complaint.getCategory()) {
            throw new ValidationException(member.getName() + " handles " + member.getTrade().label()
                    + ", but this complaint is " + complaint.getCategory().label() + ".");
        }

        complaint.assignTo(member.getId());
        complaints.save(complaint);
        log.info("Complaint " + complaintId + " assigned to " + member.getId());
        return complaint;
    }

    public Complaint moveStatus(String complaintId, ComplaintStatus next)
            throws RecordNotFoundException, ValidationException {

        Complaint complaint = findById(complaintId);
        if (!complaint.getStatus().canMoveTo(next)) {
            throw new ValidationException("Cannot move complaint from " + complaint.getStatus()
                    + " to " + next + ". Allowed flow is OPEN -> ASSIGNED -> IN_PROGRESS -> RESOLVED.");
        }
        complaint.moveTo(next, LocalDate.now());
        complaints.save(complaint);
        log.info("Complaint " + complaintId + " moved to " + next);
        return complaint;
    }

    public List<Complaint> listAll() {
        return sorted(complaints.findAll());
    }

    public List<Complaint> byStatus(ComplaintStatus status) {
        return sorted(complaints.findBy(c -> c.getStatus() == status));
    }

    public List<Complaint> byStudent(String studentId) {
        return sorted(complaints.findBy(c -> c.getStudentId().equals(studentId)));
    }

    public List<Complaint> overdue() {
        LocalDate today = LocalDate.now();
        return sorted(complaints.findBy(c -> c.isOverdue(today)));
    }

    /** Open complaints first, then the most urgent, then the oldest. */
    private List<Complaint> sorted(List<Complaint> input) {
        return input.stream()
                .sorted(Comparator.comparing((Complaint c) -> c.getStatus().isClosed())
                        .thenComparing(c -> -c.getPriority().ordinal())
                        .thenComparing(Complaint::getRaisedOn))
                .collect(Collectors.toList());
    }

    public List<Staff> allStaff() {
        return staff.findAll().stream()
                .sorted(Comparator.comparing(Staff::getId))
                .collect(Collectors.toList());
    }

    private List<String> ids() {
        return complaints.findAll().stream().map(Complaint::getId).collect(Collectors.toList());
    }
}

/**
 * Module 4 - Reporting and analytics.
 * Returns formatted strings rather than printing directly, so the same report
 * can be shown on screen today and written to a file later without a rewrite.
 */
class ReportService {

    private final Repository<Student> students;
    private final Repository<Room> rooms;
    private final Repository<Allocation> allocations;
    private final Repository<Complaint> complaints;

    public ReportService(Repository<Student> students, Repository<Room> rooms,
                         Repository<Allocation> allocations, Repository<Complaint> complaints) {
        this.students = students;
        this.rooms = rooms;
        this.allocations = allocations;
        this.complaints = complaints;
    }

    public String occupancyReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(title("OCCUPANCY BY BLOCK"));

        Map<String, List<Room>> byBlock = new TreeMap<>(rooms.findAll().stream()
                .collect(Collectors.groupingBy(Room::getBlock)));

        sb.append(String.format("%-8s %8s %8s %8s %10s%n", "Block", "Rooms", "Beds", "Filled", "Occupancy"));
        sb.append(divider());

        int totalBeds = 0;
        int totalFilled = 0;
        for (Map.Entry<String, List<Room>> entry : byBlock.entrySet()) {
            int beds = entry.getValue().stream().mapToInt(r -> r.getType().capacity()).sum();
            int filled = entry.getValue().stream().mapToInt(Room::occupancy).sum();
            totalBeds += beds;
            totalFilled += filled;
            sb.append(String.format("%-8s %8d %8d %8d %9.1f%%%n",
                    entry.getKey(), entry.getValue().size(), beds, filled, percent(filled, beds)));
        }
        sb.append(divider());
        sb.append(String.format("%-8s %8d %8d %8d %9.1f%%%n",
                "TOTAL", rooms.count(), totalBeds, totalFilled, percent(totalFilled, totalBeds)));

        long blocked = rooms.findBy(Room::isUnderMaintenance).size();
        sb.append("\nRooms flagged for maintenance : ").append(blocked).append('\n');
        sb.append("Beds still free               : ").append(totalBeds - totalFilled).append('\n');
        return sb.toString();
    }

    public String vacancyReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(title("ROOMS WITH FREE BEDS"));
        List<Room> vacant = rooms.findBy(Room::isAvailable);
        if (vacant.isEmpty()) {
            return sb.append("The hostel is completely full.\n").toString();
        }
        sb.append(String.format("%-10s %-8s %-14s %10s %10s%n", "Room", "Floor", "Type", "Occupied", "Free"));
        sb.append(divider());
        vacant.stream()
                .sorted((a, b) -> b.bedsFree() - a.bedsFree())
                .forEach(room -> sb.append(String.format("%-10s %-8d %-14s %10d %10d%n",
                        room.getRoomNo(), room.getFloor(), room.getType().name(),
                        room.occupancy(), room.bedsFree())));
        return sb.toString();
    }

    public String complaintReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(title("COMPLAINT SUMMARY"));

        List<Complaint> all = complaints.findAll();
        if (all.isEmpty()) {
            return sb.append("No complaints have been raised yet.\n").toString();
        }

        sb.append("By status\n");
        for (ComplaintStatus status : ComplaintStatus.values()) {
            long n = all.stream().filter(c -> c.getStatus() == status).count();
            sb.append(String.format("  %-13s %3d  %s%n", status, n, bar(n)));
        }

        sb.append("\nBy category\n");
        Map<ComplaintCategory, Long> byCategory = new LinkedHashMap<>();
        for (ComplaintCategory category : ComplaintCategory.values()) {
            byCategory.put(category, all.stream().filter(c -> c.getCategory() == category).count());
        }
        byCategory.forEach((category, n) ->
                sb.append(String.format("  %-18s %3d  %s%n", category.label(), n, bar(n))));

        LocalDate today = LocalDate.now();
        double avgResolution = all.stream()
                .filter(c -> c.getStatus().isClosed())
                .mapToLong(c -> c.ageInDays(today))
                .average()
                .orElse(0.0);
        long overdue = all.stream().filter(c -> c.isOverdue(today)).count();

        sb.append("\nAverage resolution time : ").append(String.format("%.1f day(s)", avgResolution)).append('\n');
        sb.append("Currently past SLA      : ").append(overdue).append('\n');
        return sb.toString();
    }

    public String studentDigest() {
        StringBuilder sb = new StringBuilder();
        sb.append(title("RESIDENT DIGEST"));
        sb.append("Registered students : ").append(students.count()).append('\n');

        long housed = allocations.findBy(Allocation::isActive).size();
        sb.append("Currently housed    : ").append(housed).append('\n');
        sb.append("Awaiting a room     : ").append(Math.max(0, students.count() - housed)).append('\n');

        sb.append("\nBy branch\n");
        Map<String, Long> byBranch = new TreeMap<>(students.findAll().stream()
                .collect(Collectors.groupingBy(Student::getBranch, Collectors.counting())));
        byBranch.forEach((branch, n) -> sb.append(String.format("  %-10s %3d%n", branch, n)));
        return sb.toString();
    }

    /** Everything at once - this is what the --report command line flag prints. */
    public String fullReport() {
        return occupancyReport() + "\n" + vacancyReport() + "\n" + complaintReport() + "\n" + studentDigest();
    }

    private double percent(int part, int whole) {
        return whole == 0 ? 0.0 : (part * 100.0) / whole;
    }

    private String bar(long n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n && i < 40; i++) {
            b.append('#');
        }
        return b.toString();
    }

    private String title(String text) {
        return "\n" + text + "\n" + divider();
    }

    private String divider() {
        return "------------------------------------------------------------\n";
    }
}
