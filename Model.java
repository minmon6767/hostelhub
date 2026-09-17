/*
 * Model.java - the domain layer.
 *
 * Person (abstract) with Student and Staff, the Room / Allocation / Complaint
 * records, and the four enums that carry the rules which belong to a type
 * rather than to a service: room capacity, complaint SLA and the status
 * lifecycle.
 *
 * These classes are grouped into one file on purpose. The project has to be
 * uploadable to GitHub through the browser, which means a flat folder with no
 * packages, so related classes are kept together rather than scattered across
 * a dozen files in the same directory.
 */

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Common base for everyone the system knows about. Abstract because a bare
 * "Person" is never a meaningful record here - it is always a Student or a
 * maintenance Staff member.
 *
 * Fields are private with protected-free access through getters so that
 * subclasses cannot quietly bypass validation done in the constructors.
 */
abstract class Person implements Identifiable, CsvSerializable {

    private final String id;
    private String name;
    private String email;
    private String phone;

    protected Person(String id, String name, String email, String phone) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    /** Each subclass reports what it is - used in listings and reports. */
    public abstract String role();

    /** Short one line label used all over the console output. */
    public String displayLabel() {
        return id + " - " + name;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Person)) {
            return false;
        }
        return id.equals(((Person) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return role() + "[" + id + ", " + name + "]";
    }
}

/**
 * A hostel resident. Note that the room is NOT stored on the student:
 * the Allocation record is the single source of truth for who lives where,
 * which keeps history intact when a student vacates and is re-allocated.
 */
class Student extends Person {

    private String branch;
    private int year;

    public Student(String id, String name, String email, String phone, String branch, int year) {
        super(id, name, email, phone);
        this.branch = branch;
        this.year = year;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    @Override
    public String role() {
        return "STUDENT";
    }

    @Override
    public String toCsvRow() {
        return String.join(",",
                getId(),
                CsvUtil.clean(getName()),
                CsvUtil.clean(getEmail()),
                CsvUtil.clean(getPhone()),
                CsvUtil.clean(branch),
                String.valueOf(year));
    }

    public static Student fromCsv(String line) {
        String[] c = CsvUtil.split(line, 6);
        return new Student(c[0], c[1], c[2], c[3], c[4], Integer.parseInt(c[5]));
    }

    public static final String CSV_HEADER = "id,name,email,phone,branch,year";
}

/**
 * Maintenance staff. Each member handles exactly one trade, which is what
 * lets the complaint module auto-suggest the right person for a complaint.
 */
class Staff extends Person {

    private ComplaintCategory trade;

    public Staff(String id, String name, String email, String phone, ComplaintCategory trade) {
        super(id, name, email, phone);
        this.trade = trade;
    }

    public ComplaintCategory getTrade() {
        return trade;
    }

    public void setTrade(ComplaintCategory trade) {
        this.trade = trade;
    }

    @Override
    public String role() {
        return "STAFF";
    }

    @Override
    public String displayLabel() {
        return getId() + " - " + getName() + " (" + trade.label() + ")";
    }

    @Override
    public String toCsvRow() {
        return String.join(",",
                getId(),
                CsvUtil.clean(getName()),
                CsvUtil.clean(getEmail()),
                CsvUtil.clean(getPhone()),
                trade.name());
    }

    public static Staff fromCsv(String line) {
        String[] c = CsvUtil.split(line, 5);
        return new Staff(c[0], c[1], c[2], c[3], ComplaintCategory.valueOf(c[4]));
    }

    public static final String CSV_HEADER = "id,name,email,phone,trade";
}

/**
 * Room categories with the two things that vary between them: how many beds
 * they hold and what they cost per month. Putting the data on the enum means
 * capacity rules live in exactly one place.
 */
enum RoomType {

    SINGLE(1, 8500),
    DOUBLE(2, 6200),
    TRIPLE(3, 4800);

    private final int capacity;
    private final int monthlyRent;

    RoomType(int capacity, int monthlyRent) {
        this.capacity = capacity;
        this.monthlyRent = monthlyRent;
    }

    public int capacity() {
        return capacity;
    }

    public int monthlyRent() {
        return monthlyRent;
    }

    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase() + " (" + capacity + " bed)";
    }
}

/**
 * A physical room. The occupant list is kept private and only mutated through
 * addOccupant / removeOccupant so the capacity rule can never be bypassed -
 * this is the main encapsulation example in the project.
 */
class Room implements Identifiable, CsvSerializable {

    private final String roomNo;      // e.g. A-101
    private final String block;       // "A"
    private final int floor;          // 1
    private final RoomType type;
    private final List<String> occupantIds = new ArrayList<>();
    private boolean underMaintenance;

    public Room(String roomNo, String block, int floor, RoomType type) {
        this.roomNo = roomNo;
        this.block = block;
        this.floor = floor;
        this.type = type;
    }

    @Override
    public String getId() {
        return roomNo;
    }

    public String getRoomNo() {
        return roomNo;
    }

    public String getBlock() {
        return block;
    }

    public int getFloor() {
        return floor;
    }

    public RoomType getType() {
        return type;
    }

    public boolean isUnderMaintenance() {
        return underMaintenance;
    }

    public void setUnderMaintenance(boolean underMaintenance) {
        this.underMaintenance = underMaintenance;
    }

    /** Defensive copy - callers can read the list but not edit it behind our back. */
    public List<String> getOccupantIds() {
        return Collections.unmodifiableList(occupantIds);
    }

    public int occupancy() {
        return occupantIds.size();
    }

    public int bedsFree() {
        return type.capacity() - occupantIds.size();
    }

    public boolean isFull() {
        return bedsFree() <= 0;
    }

    public boolean isAvailable() {
        return !underMaintenance && !isFull();
    }

    public double occupancyRate() {
        return (occupantIds.size() * 100.0) / type.capacity();
    }

    public void addOccupant(String studentId) throws RoomFullException {
        if (isFull()) {
            throw new RoomFullException("Room " + roomNo + " is already full (" + type.label() + ").");
        }
        if (!occupantIds.contains(studentId)) {
            occupantIds.add(studentId);
        }
    }

    public boolean removeOccupant(String studentId) {
        return occupantIds.remove(studentId);
    }

    @Override
    public String toCsvRow() {
        return String.join(",",
                roomNo,
                block,
                String.valueOf(floor),
                type.name(),
                String.valueOf(underMaintenance),
                String.join("|", occupantIds));
    }

    public static Room fromCsv(String line) {
        String[] c = CsvUtil.split(line, 6);
        Room room = new Room(c[0], c[1], Integer.parseInt(c[2]), RoomType.valueOf(c[3]));
        room.setUnderMaintenance(Boolean.parseBoolean(c[4]));
        if (!c[5].trim().isEmpty()) {
            for (String occupant : c[5].split("\\|")) {
                if (!occupant.trim().isEmpty()) {
                    room.occupantIds.add(occupant.trim());
                }
            }
        }
        return room;
    }

    public static final String CSV_HEADER = "roomNo,block,floor,type,underMaintenance,occupants";

    @Override
    public String toString() {
        return roomNo + " [" + type.name() + "] " + occupancy() + "/" + type.capacity();
    }
}

/** The kinds of problems a resident can raise, and who normally fixes them. */
enum ComplaintCategory {

    ELECTRICAL("Electrical"),
    PLUMBING("Plumbing"),
    CARPENTRY("Carpentry"),
    HOUSEKEEPING("Housekeeping"),
    INTERNET("Internet / Wi-Fi");

    private final String label;

    ComplaintCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

/**
 * Priority carries the SLA with it - the number of days within which the
 * complaint is expected to be resolved. The overdue report simply asks each
 * complaint's priority for this number instead of hard-coding a table.
 */
enum Priority {

    LOW(7),
    MEDIUM(4),
    HIGH(2),
    URGENT(1);

    private final int slaDays;

    Priority(int slaDays) {
        this.slaDays = slaDays;
    }

    public int slaDays() {
        return slaDays;
    }
}

/**
 * Complaint lifecycle. canMoveTo() encodes the allowed transitions so that,
 * for example, an OPEN complaint can never jump straight to RESOLVED without
 * somebody being assigned to it first.
 */
enum ComplaintStatus {

    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    RESOLVED;

    public boolean canMoveTo(ComplaintStatus next) {
        switch (this) {
            case OPEN:
                return next == ASSIGNED;
            case ASSIGNED:
                return next == IN_PROGRESS || next == RESOLVED;
            case IN_PROGRESS:
                return next == RESOLVED;
            case RESOLVED:
            default:
                return false;
        }
    }

    public boolean isClosed() {
        return this == RESOLVED;
    }
}

/**
 * Links a student to a room for a period of time. Vacating does not delete
 * the record, it closes it - so the hostel keeps a full occupancy history and
 * the reports can show how many allocations happened over a term.
 */
class Allocation implements Identifiable, CsvSerializable {

    private final String allocationId;
    private final String studentId;
    private final String roomNo;
    private final LocalDate allocatedOn;
    private LocalDate vacatedOn;   // null while the allocation is active

    public Allocation(String allocationId, String studentId, String roomNo, LocalDate allocatedOn) {
        this.allocationId = allocationId;
        this.studentId = studentId;
        this.roomNo = roomNo;
        this.allocatedOn = allocatedOn;
    }

    @Override
    public String getId() {
        return allocationId;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getRoomNo() {
        return roomNo;
    }

    public LocalDate getAllocatedOn() {
        return allocatedOn;
    }

    public LocalDate getVacatedOn() {
        return vacatedOn;
    }

    public boolean isActive() {
        return vacatedOn == null;
    }

    public void close(LocalDate on) {
        this.vacatedOn = on;
    }

    @Override
    public String toCsvRow() {
        return String.join(",",
                allocationId,
                studentId,
                roomNo,
                allocatedOn.toString(),
                vacatedOn == null ? "" : vacatedOn.toString());
    }

    public static Allocation fromCsv(String line) {
        String[] c = CsvUtil.split(line, 5);
        Allocation allocation = new Allocation(c[0], c[1], c[2], LocalDate.parse(c[3]));
        String vacated = CsvUtil.orNull(c[4]);
        if (vacated != null) {
            allocation.close(LocalDate.parse(vacated));
        }
        return allocation;
    }

    public static final String CSV_HEADER = "allocationId,studentId,roomNo,allocatedOn,vacatedOn";
}

/**
 * A maintenance request raised by a resident. Status changes go through
 * ComplaintStatus.canMoveTo(), so the object protects its own lifecycle.
 */
class Complaint implements Identifiable, CsvSerializable {

    private final String complaintId;
    private final String studentId;
    private final String roomNo;
    private final ComplaintCategory category;
    private final Priority priority;
    private final String description;
    private final LocalDate raisedOn;

    private ComplaintStatus status = ComplaintStatus.OPEN;
    private String assignedStaffId;   // null until somebody picks it up
    private LocalDate resolvedOn;     // null until resolved

    public Complaint(String complaintId, String studentId, String roomNo, ComplaintCategory category,
                     Priority priority, String description, LocalDate raisedOn) {
        this.complaintId = complaintId;
        this.studentId = studentId;
        this.roomNo = roomNo;
        this.category = category;
        this.priority = priority;
        this.description = description;
        this.raisedOn = raisedOn;
    }

    @Override
    public String getId() {
        return complaintId;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getRoomNo() {
        return roomNo;
    }

    public ComplaintCategory getCategory() {
        return category;
    }

    public Priority getPriority() {
        return priority;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getRaisedOn() {
        return raisedOn;
    }

    public ComplaintStatus getStatus() {
        return status;
    }

    public String getAssignedStaffId() {
        return assignedStaffId;
    }

    public LocalDate getResolvedOn() {
        return resolvedOn;
    }

    public void assignTo(String staffId) {
        this.assignedStaffId = staffId;
        this.status = ComplaintStatus.ASSIGNED;
    }

    public void moveTo(ComplaintStatus next, LocalDate today) {
        this.status = next;
        if (next.isClosed()) {
            this.resolvedOn = today;
        }
    }

    /** Days the complaint has been open, or how long it took if already resolved. */
    public long ageInDays(LocalDate today) {
        LocalDate end = resolvedOn != null ? resolvedOn : today;
        return ChronoUnit.DAYS.between(raisedOn, end);
    }

    /** True when an unresolved complaint has crossed the SLA of its priority. */
    public boolean isOverdue(LocalDate today) {
        return !status.isClosed() && ageInDays(today) > priority.slaDays();
    }

    @Override
    public String toCsvRow() {
        return String.join(",",
                complaintId,
                studentId,
                roomNo,
                category.name(),
                priority.name(),
                CsvUtil.clean(description),
                raisedOn.toString(),
                status.name(),
                CsvUtil.orEmpty(assignedStaffId),
                resolvedOn == null ? "" : resolvedOn.toString());
    }

    public static Complaint fromCsv(String line) {
        String[] c = CsvUtil.split(line, 10);
        Complaint complaint = new Complaint(c[0], c[1], c[2], ComplaintCategory.valueOf(c[3]),
                Priority.valueOf(c[4]), c[5], LocalDate.parse(c[6]));
        complaint.status = ComplaintStatus.valueOf(c[7]);
        complaint.assignedStaffId = CsvUtil.orNull(c[8]);
        String resolved = CsvUtil.orNull(c[9]);
        if (resolved != null) {
            complaint.resolvedOn = LocalDate.parse(resolved);
        }
        return complaint;
    }

    public static final String CSV_HEADER =
            "complaintId,studentId,roomNo,category,priority,description,raisedOn,status,assignedStaffId,resolvedOn";

    @Override
    public String toString() {
        return complaintId + " " + category.label() + " (" + priority + ") - " + status;
    }
}
