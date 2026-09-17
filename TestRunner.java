/*
 * TestRunner.java - the test suite and the runner that executes it.
 *
 * Run with:  ./test.sh      (or javac *.java && java TestRunner)
 *
 * Assert holds the assertion helpers, TestSupport builds throwaway
 * repositories under build/test-data, and the five test classes cover rooms,
 * validation, allocation, complaints and CSV persistence. Reflection is used
 * to find and invoke every test method, which avoids a JUnit dependency.
 */

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal assertion helpers.
 *
 * JUnit would have been the obvious choice, but it means shipping jars or a
 * build tool that downloads them, and the brief asks for something that runs
 * from a plain terminal. Twenty lines of assertions do the same job here.
 */
final class Assert {

    private Assert() {
    }

    public static void isTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Expected true: " + what);
        }
    }

    public static void isFalse(boolean condition, String what) {
        isTrue(!condition, what);
    }

    public static void equals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + " -> expected <" + expected + "> but was <" + actual + ">");
        }
    }

    /** Asserts that running the block throws the given exception type. */
    public static void throwz(Class<? extends Throwable> expected, ThrowingBlock block, String what) {
        try {
            block.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(what + " -> expected " + expected.getSimpleName()
                    + " but got " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage());
        }
        throw new AssertionError(what + " -> expected " + expected.getSimpleName() + " but nothing was thrown");
    }

    public interface ThrowingBlock {
        void run() throws Exception;
    }
}

/**
 * Builds throwaway repositories under build/test-data so the tests never touch
 * the real data folder.
 */
final class TestSupport {

    public static final Path SANDBOX = Paths.get("build", "test-data");

    private TestSupport() {
    }

    public static AppLogger logger() {
        AppLogger log = new AppLogger(Paths.get("build", "test-data", "test.log"));
        log.setEchoErrorsToConsole(false);
        return log;
    }

    public static Repository<Room> rooms(String name) {
        return new CsvRepository<>(SANDBOX.resolve(name + "-rooms.csv"),
                Room.CSV_HEADER, Room::fromCsv, logger());
    }

    public static Repository<Student> students(String name) {
        return new CsvRepository<>(SANDBOX.resolve(name + "-students.csv"),
                Student.CSV_HEADER, Student::fromCsv, logger());
    }

    public static Repository<Allocation> allocations(String name) {
        return new CsvRepository<>(SANDBOX.resolve(name + "-allocations.csv"),
                Allocation.CSV_HEADER, Allocation::fromCsv, logger());
    }

    public static Repository<Complaint> complaints(String name) {
        return new CsvRepository<>(SANDBOX.resolve(name + "-complaints.csv"),
                Complaint.CSV_HEADER, Complaint::fromCsv, logger());
    }

    public static Repository<Staff> staff(String name) {
        Repository<Staff> repo = new CsvRepository<>(SANDBOX.resolve(name + "-staff.csv"),
                Staff.CSV_HEADER, Staff::fromCsv, logger());
        repo.save(new Staff("STF001", "Test Electrician", "e@hostel.in", "9800000001", ComplaintCategory.ELECTRICAL));
        repo.save(new Staff("STF002", "Test Plumber", "p@hostel.in", "9800000002", ComplaintCategory.PLUMBING));
        return repo;
    }

    public static Student student(String id, String branch, int year) {
        return new Student(id, "Test " + id, id.toLowerCase() + "@example.com", "9800000000", branch, year);
    }

    public static Room room(String roomNo, RoomType type) {
        String block = roomNo.substring(0, 1);
        int floor = Character.getNumericValue(roomNo.charAt(2));
        return new Room(roomNo, block, floor, type);
    }
}

/** Capacity and encapsulation rules on the Room model. */
class RoomTest {

    public void capacityComesFromRoomType() {
        Room triple = TestSupport.room("A-101", RoomType.TRIPLE);
        Assert.equals(3, triple.getType().capacity(), "triple room capacity");
        Assert.equals(3, triple.bedsFree(), "beds free when empty");
        Assert.isFalse(triple.isFull(), "empty room should not be full");
    }

    public void addingOccupantsReducesFreeBeds() throws Exception {
        Room room = TestSupport.room("A-102", RoomType.DOUBLE);
        room.addOccupant("STU001");
        Assert.equals(1, room.occupancy(), "occupancy after one student");
        Assert.equals(1, room.bedsFree(), "free beds after one student");
        Assert.equals(50.0, room.occupancyRate(), "occupancy percentage");
    }

    public void roomRefusesExtraOccupant() throws Exception {
        Room single = TestSupport.room("A-104", RoomType.SINGLE);
        single.addOccupant("STU001");
        Assert.throwz(RoomFullException.class,
                () -> single.addOccupant("STU002"),
                "second student in a single room");
    }

    public void sameStudentIsNotCountedTwice() throws Exception {
        Room room = TestSupport.room("A-103", RoomType.TRIPLE);
        room.addOccupant("STU001");
        room.addOccupant("STU001");
        Assert.equals(1, room.occupancy(), "duplicate add should be ignored");
    }

    public void occupantListCannotBeEditedFromOutside() throws Exception {
        Room room = TestSupport.room("B-101", RoomType.TRIPLE);
        room.addOccupant("STU001");
        Assert.throwz(UnsupportedOperationException.class,
                () -> room.getOccupantIds().add("STU999"),
                "occupant list should be unmodifiable");
    }

    public void maintenanceRoomIsNotAvailable() {
        Room room = TestSupport.room("B-102", RoomType.DOUBLE);
        room.setUnderMaintenance(true);
        Assert.isFalse(room.isAvailable(), "blocked room must not be available");
    }
}

/** Field level validation rules. */
class ValidatorTest {

    public void acceptsSensibleEmail() throws Exception {
        Assert.equals("a.b@example.com", Validator.requireEmail(" a.b@example.com "), "trimmed email");
    }

    public void rejectsBadEmail() {
        Assert.throwz(ValidationException.class, () -> Validator.requireEmail("not-an-email"), "bad email");
    }

    public void rejectsShortPhone() {
        Assert.throwz(ValidationException.class, () -> Validator.requirePhone("98765"), "short phone");
    }

    public void rejectsPhoneStartingWithFive() {
        Assert.throwz(ValidationException.class, () -> Validator.requirePhone("5876543210"), "invalid prefix");
    }

    public void normalisesRoomNumberToUppercase() throws Exception {
        Assert.equals("A-101", Validator.requireRoomNumber("a-101"), "lowercase room number");
    }

    public void rejectsMalformedRoomNumber() {
        Assert.throwz(ValidationException.class, () -> Validator.requireRoomNumber("A101"), "missing dash");
    }

    public void rejectsBlankText() {
        Assert.throwz(ValidationException.class, () -> Validator.requireText("   ", "Name"), "blank name");
    }
}

/** End to end behaviour of the allocation module, including the strategies. */
class AllocationTest {

    private RoomService serviceWith(String tag, Repository<Room> rooms, Repository<Allocation> allocations) {
        return new RoomService(rooms, allocations, new FirstAvailableStrategy(), TestSupport.logger());
    }

    public void firstAvailablePicksLowestRoomNumber() throws Exception {
        Repository<Room> rooms = TestSupport.rooms("alloc1");
        rooms.save(TestSupport.room("A-201", RoomType.TRIPLE));
        rooms.save(TestSupport.room("A-101", RoomType.TRIPLE));
        RoomService service = serviceWith("alloc1", rooms, TestSupport.allocations("alloc1"));

        Allocation allocation = service.allocate(TestSupport.student("STU001", "CSE", 1), null);
        Assert.equals("A-101", allocation.getRoomNo(), "lowest room number chosen");
    }

    public void consolidateStrategyFillsPartlyOccupiedRoomFirst() throws Exception {
        Repository<Room> rooms = TestSupport.rooms("alloc2");
        Room busy = TestSupport.room("A-201", RoomType.TRIPLE);
        busy.addOccupant("STU999");
        rooms.save(busy);
        rooms.save(TestSupport.room("A-101", RoomType.TRIPLE));

        RoomService service = serviceWith("alloc2", rooms, TestSupport.allocations("alloc2"));
        service.setStrategy(new ConsolidateOccupancyStrategy());

        Allocation allocation = service.allocate(TestSupport.student("STU001", "CSE", 1), null);
        Assert.equals("A-201", allocation.getRoomNo(), "partly filled room preferred");
    }

    public void sameBranchStrategyKeepsBranchmatesTogether() throws Exception {
        Repository<Room> rooms = TestSupport.rooms("alloc3");
        Repository<Student> students = TestSupport.students("alloc3");

        Student senior = TestSupport.student("STU900", "ECE", 2);
        students.save(senior);

        Room eceRoom = TestSupport.room("B-301", RoomType.TRIPLE);
        eceRoom.addOccupant(senior.getId());
        rooms.save(eceRoom);
        rooms.save(TestSupport.room("A-101", RoomType.TRIPLE));

        RoomService service = serviceWith("alloc3", rooms, TestSupport.allocations("alloc3"));
        service.setStrategy(new SameBranchGroupingStrategy(students));

        Allocation allocation = service.allocate(TestSupport.student("STU001", "ECE", 2), null);
        Assert.equals("B-301", allocation.getRoomNo(), "same branch room preferred");
    }

    public void studentCannotHoldTwoRoomsAtOnce() throws Exception {
        Repository<Room> rooms = TestSupport.rooms("alloc4");
        rooms.save(TestSupport.room("A-101", RoomType.TRIPLE));
        rooms.save(TestSupport.room("A-102", RoomType.DOUBLE));
        RoomService service = serviceWith("alloc4", rooms, TestSupport.allocations("alloc4"));

        Student student = TestSupport.student("STU001", "CSE", 1);
        service.allocate(student, null);

        Assert.throwz(AllocationException.class,
                () -> service.allocate(student, null),
                "second allocation for the same student");
    }

    public void vacatingFreesTheBedAndClosesTheRecord() throws Exception {
        Repository<Room> rooms = TestSupport.rooms("alloc5");
        rooms.save(TestSupport.room("A-104", RoomType.SINGLE));
        RoomService service = serviceWith("alloc5", rooms, TestSupport.allocations("alloc5"));

        Student student = TestSupport.student("STU001", "CSE", 1);
        service.allocate(student, null);
        Assert.equals(1, rooms.findById("A-104").get().occupancy(), "room occupied after allocation");

        Allocation closed = service.vacate(student.getId());
        Assert.isFalse(closed.isActive(), "allocation should be closed");
        Assert.equals(0, rooms.findById("A-104").get().occupancy(), "bed freed after vacating");
        Assert.isFalse(service.activeAllocationOf(student.getId()).isPresent(), "no active allocation left");
    }

    public void allocationFailsWhenNothingIsVacant() {
        Repository<Room> rooms = TestSupport.rooms("alloc6");
        Room blocked = TestSupport.room("A-104", RoomType.SINGLE);
        blocked.setUnderMaintenance(true);
        rooms.save(blocked);
        RoomService service = serviceWith("alloc6", rooms, TestSupport.allocations("alloc6"));

        Assert.throwz(AllocationException.class,
                () -> service.allocate(TestSupport.student("STU001", "CSE", 1), null),
                "allocation with no vacancy");
    }

    public void historyKeepsClosedAllocations() throws Exception {
        Repository<Room> rooms = TestSupport.rooms("alloc7");
        rooms.save(TestSupport.room("A-101", RoomType.TRIPLE));
        rooms.save(TestSupport.room("A-102", RoomType.DOUBLE));
        RoomService service = serviceWith("alloc7", rooms, TestSupport.allocations("alloc7"));

        Student student = TestSupport.student("STU001", "CSE", 1);
        service.allocate(student, null);
        service.vacate(student.getId());
        service.allocate(student, null);

        Assert.equals(2, service.historyOf(student.getId()).size(), "both allocations retained");
    }
}

/** Complaint lifecycle, assignment rules and SLA maths. */
class ComplaintTest {

    private ComplaintService service(String tag) {
        return new ComplaintService(TestSupport.complaints(tag), TestSupport.staff(tag), TestSupport.logger());
    }

    public void raisingAComplaintStartsItAsOpen() throws Exception {
        Complaint complaint = service("cmp1").raise("STU001", "A-101",
                ComplaintCategory.ELECTRICAL, Priority.HIGH, "Fan makes a loud grinding noise");
        Assert.equals(ComplaintStatus.OPEN, complaint.getStatus(), "initial status");
        Assert.isTrue(complaint.getId().startsWith("CMP"), "generated ID prefix");
    }

    public void shortDescriptionIsRejected() {
        ComplaintService service = service("cmp2");
        Assert.throwz(ValidationException.class,
                () -> service.raise("STU001", "A-101", ComplaintCategory.PLUMBING, Priority.LOW, "tap"),
                "too short description");
    }

    public void cannotSkipStraightFromOpenToResolved() throws Exception {
        ComplaintService service = service("cmp3");
        Complaint complaint = service.raise("STU001", "A-101",
                ComplaintCategory.PLUMBING, Priority.MEDIUM, "Wash basin is draining very slowly");
        Assert.throwz(ValidationException.class,
                () -> service.moveStatus(complaint.getId(), ComplaintStatus.RESOLVED),
                "illegal status jump");
    }

    public void normalLifecycleWorksEndToEnd() throws Exception {
        ComplaintService service = service("cmp4");
        Complaint complaint = service.raise("STU001", "A-101",
                ComplaintCategory.ELECTRICAL, Priority.URGENT, "Power socket sparked while charging a laptop");

        service.assign(complaint.getId(), "STF001");
        Assert.equals(ComplaintStatus.ASSIGNED, complaint.getStatus(), "status after assignment");

        service.moveStatus(complaint.getId(), ComplaintStatus.IN_PROGRESS);
        service.moveStatus(complaint.getId(), ComplaintStatus.RESOLVED);

        Assert.isTrue(complaint.getStatus().isClosed(), "complaint closed");
        Assert.equals(LocalDate.now(), complaint.getResolvedOn(), "resolution date stamped");
    }

    public void staffOfTheWrongTradeCannotBeAssigned() throws Exception {
        ComplaintService service = service("cmp5");
        Complaint complaint = service.raise("STU001", "A-101",
                ComplaintCategory.ELECTRICAL, Priority.LOW, "Corridor tube light flickers at night");
        Assert.throwz(ValidationException.class,
                () -> service.assign(complaint.getId(), "STF002"),
                "plumber assigned to an electrical job");
    }

    public void slaBreachIsDetected() {
        Complaint old = new Complaint("CMP0001", "STU001", "A-101", ComplaintCategory.INTERNET,
                Priority.URGENT, "No Wi-Fi on the second floor", LocalDate.now().minusDays(5));
        Assert.isTrue(old.isOverdue(LocalDate.now()), "urgent complaint open for 5 days is overdue");
        Assert.equals(5L, old.ageInDays(LocalDate.now()), "age in days");
    }

    public void resolvedComplaintIsNeverOverdue() {
        Complaint complaint = new Complaint("CMP0002", "STU001", "A-101", ComplaintCategory.CARPENTRY,
                Priority.URGENT, "Cupboard door hinge has come off", LocalDate.now().minusDays(9));
        complaint.assignTo("STF003");
        complaint.moveTo(ComplaintStatus.RESOLVED, LocalDate.now().minusDays(1));
        Assert.isFalse(complaint.isOverdue(LocalDate.now()), "closed complaint cannot be overdue");
    }

    public void workloadCountsOnlyOpenJobs() throws Exception {
        ComplaintService service = service("cmp6");
        Complaint first = service.raise("STU001", "A-101",
                ComplaintCategory.ELECTRICAL, Priority.LOW, "Switchboard cover is cracked");
        Complaint second = service.raise("STU002", "A-102",
                ComplaintCategory.ELECTRICAL, Priority.LOW, "Bathroom light does not switch on");
        service.assign(first.getId(), "STF001");
        service.assign(second.getId(), "STF001");
        service.moveStatus(second.getId(), ComplaintStatus.RESOLVED);

        Assert.equals(1L, service.openWorkload("STF001"), "only unresolved jobs counted");
    }
}

/**
 * CSV round trips. These caught two real bugs while I was building: trailing
 * empty columns being dropped by split(), and a comma typed inside a complaint
 * description shifting every field one place to the left.
 */
class PersistenceTest {

    public void studentSurvivesARoundTrip() {
        Student original = new Student("STU007", "Aarti Deshmukh", "aarti@example.com", "9876543210", "CSE", 3);
        Student restored = Student.fromCsv(original.toCsvRow());
        Assert.equals(original.getId(), restored.getId(), "id");
        Assert.equals(original.getName(), restored.getName(), "name");
        Assert.equals(original.getYear(), restored.getYear(), "year");
    }

    public void roomKeepsItsOccupantsAcrossASave() throws Exception {
        Room room = TestSupport.room("A-101", RoomType.TRIPLE);
        room.addOccupant("STU001");
        room.addOccupant("STU002");
        Room restored = Room.fromCsv(room.toCsvRow());
        Assert.equals(2, restored.occupancy(), "occupants restored");
        Assert.equals(RoomType.TRIPLE, restored.getType(), "room type restored");
    }

    public void unassignedComplaintKeepsItsEmptyColumns() {
        Complaint complaint = new Complaint("CMP0009", "STU001", "A-101", ComplaintCategory.HOUSEKEEPING,
                Priority.LOW, "Dustbin has not been emptied for three days", LocalDate.now());
        Complaint restored = Complaint.fromCsv(complaint.toCsvRow());
        Assert.equals(null, restored.getAssignedStaffId(), "no staff assigned yet");
        Assert.equals(null, restored.getResolvedOn(), "not resolved yet");
        Assert.equals(ComplaintStatus.OPEN, restored.getStatus(), "status restored");
    }

    public void commaInDescriptionDoesNotBreakTheRow() {
        Complaint complaint = new Complaint("CMP0010", "STU001", "A-101", ComplaintCategory.PLUMBING,
                Priority.HIGH, "Leak under the sink, water spreading, floor is wet", LocalDate.now());
        Complaint restored = Complaint.fromCsv(complaint.toCsvRow());
        Assert.isTrue(restored.getDescription().contains("water spreading"), "description preserved");
    }

    public void activeAllocationWritesAnEmptyVacatedDate() {
        Allocation allocation = new Allocation("ALC0001", "STU001", "A-101", LocalDate.now().minusDays(30));
        Allocation restored = Allocation.fromCsv(allocation.toCsvRow());
        Assert.isTrue(restored.isActive(), "still active after reload");

        allocation.close(LocalDate.now());
        Allocation closed = Allocation.fromCsv(allocation.toCsvRow());
        Assert.isFalse(closed.isActive(), "closed after reload");
    }
}

/**
 * Runs every public no-argument method of the test classes listed below and
 * prints a pass/fail summary. Exits with status 1 if anything fails, so the
 * whole thing can be wired into a CI step later if needed.
 */
public class TestRunner {

    private static final Class<?>[] SUITES = {
            RoomTest.class,
            ValidatorTest.class,
            AllocationTest.class,
            ComplaintTest.class,
            PersistenceTest.class
    };

    public static void main(String[] args) throws Exception {
        cleanSandbox();

        int passed = 0;
        List<String> failures = new ArrayList<>();

        System.out.println("Running HostelHub test suite");
        System.out.println("============================================================");

        for (Class<?> suite : SUITES) {
            System.out.println("\n" + suite.getSimpleName());
            Object instance = suite.getDeclaredConstructor().newInstance();

            List<Method> methods = Arrays.stream(suite.getDeclaredMethods())
                    .filter(m -> m.getParameterCount() == 0)
                    .filter(m -> !m.getName().contains("$"))
                    .sorted(Comparator.comparing(Method::getName))
                    .collect(java.util.stream.Collectors.toList());

            for (Method test : methods) {
                try {
                    test.invoke(instance);
                    System.out.println("  PASS  " + readable(test.getName()));
                    passed++;
                } catch (Exception invocationProblem) {
                    Throwable cause = invocationProblem.getCause() == null
                            ? invocationProblem : invocationProblem.getCause();
                    System.out.println("  FAIL  " + readable(test.getName()));
                    System.out.println("        " + cause.getMessage());
                    failures.add(suite.getSimpleName() + "." + test.getName() + " :: " + cause.getMessage());
                }
            }
        }

        System.out.println("\n============================================================");
        System.out.println("Passed: " + passed + "   Failed: " + failures.size());
        if (!failures.isEmpty()) {
            System.out.println("\nFailures:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
        System.out.println("All good.");
    }

    /** camelCaseTestName -> "camel case test name", easier to read in the output. */
    private static String readable(String methodName) {
        return methodName.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }

    /** Each run starts from an empty sandbox so leftover CSVs cannot skew results. */
    private static void cleanSandbox() {
        java.io.File dir = TestSupport.SANDBOX.toFile();
        if (dir.exists()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (!file.delete()) {
                        System.out.println("  (could not delete " + file.getName() + ")");
                    }
                }
            }
        } else if (!dir.mkdirs()) {
            System.out.println("  (could not create the test sandbox folder)");
        }
    }
}
