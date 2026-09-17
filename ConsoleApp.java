/*
 * ConsoleApp.java - the presentation layer.
 *
 * ConsoleIO owns every read and write to the terminal; ConsoleApp holds the
 * menu screens. No business rule lives here - the menus collect input, call a
 * service, and print whatever comes back (or the message on the exception).
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * All console reading and printing goes through this class.
 *
 * I used a BufferedReader instead of Scanner because Scanner.nextInt() leaves
 * the newline in the buffer and the very next nextLine() returns empty - that
 * bug cost me an evening early on, so every input here is read as a full line
 * and converted afterwards.
 */
class ConsoleIO {

    private final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    public void print(String text) {
        System.out.print(text);
    }

    public void println(String text) {
        System.out.println(text);
    }

    public void blank() {
        System.out.println();
    }

    public void heading(String text) {
        System.out.println();
        System.out.println("=== " + text + " ===");
    }

    public void success(String text) {
        System.out.println("  [ok] " + text);
    }

    public void failure(String text) {
        System.out.println("  [!] " + text);
    }

    public void info(String text) {
        System.out.println("  " + text);
    }

    /** Reads a line; returns null when the stream ends (Ctrl+D or piped input). */
    public String readLine(String prompt) {
        System.out.print(prompt);
        try {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** Keeps asking until the user types something that is not blank. */
    public String readRequired(String prompt) {
        while (true) {
            String value = readLine(prompt);
            if (value == null) {
                return null;
            }
            if (!value.isEmpty()) {
                return value;
            }
            failure("This field cannot be left blank.");
        }
    }

    public int readInt(String prompt, int min, int max) {
        while (true) {
            String value = readLine(prompt);
            if (value == null) {
                return min - 1;
            }
            try {
                int number = Integer.parseInt(value);
                if (number < min || number > max) {
                    failure("Please enter a number between " + min + " and " + max + ".");
                    continue;
                }
                return number;
            } catch (NumberFormatException e) {
                failure("'" + value + "' is not a number.");
            }
        }
    }

    public boolean confirm(String prompt) {
        String value = readLine(prompt + " (y/n): ");
        return value != null && (value.equalsIgnoreCase("y") || value.equalsIgnoreCase("yes"));
    }

    /**
     * Generic enum chooser - prints the constants of any enum and returns the
     * chosen one. Written once and reused for room type, category and priority.
     */
    public <E extends Enum<E>> E readEnum(String prompt, Class<E> type) {
        E[] values = type.getEnumConstants();
        println(prompt);
        for (int i = 0; i < values.length; i++) {
            println("   " + (i + 1) + ") " + values[i].name());
        }
        int choice = readInt("  Choose 1-" + values.length + ": ", 1, values.length);
        return choice < 1 ? values[0] : values[choice - 1];
    }
}

/**
 * The menu driven front end. This class only deals with screens and prompts;
 * every rule lives in the service layer, which is what makes the services
 * testable without a console attached.
 */
class ConsoleApp {

    private final AppContext ctx;
    private final ConsoleIO io = new ConsoleIO();
    private boolean running = true;

    public ConsoleApp(AppContext ctx) {
        this.ctx = ctx;
    }

    public void start() {
        banner();
        while (running) {
            showMainMenu();
            int choice = io.readInt("Select an option: ", 0, 5);
            switch (choice) {
                case 1: studentMenu(); break;
                case 2: roomMenu(); break;
                case 3: complaintMenu(); break;
                case 4: reportMenu(); break;
                case 5: saveEverything(); break;
                case 0: shutdown(); break;
                default: running = false; break;  // stream ended
            }
        }
    }

    private void banner() {
        io.blank();
        io.println("+----------------------------------------------------------+");
        io.println("|   HostelHub - Room Allocation & Complaint Desk           |");
        io.println("|   Console edition                                        |");
        io.println("+----------------------------------------------------------+");
        io.info("Data folder : " + ctx.dataDir().toAbsolutePath());
        io.info("Students registered: " + ctx.studentService().count()
                + " | Rooms on file: " + ctx.roomService().listRooms().size());
    }

    private void showMainMenu() {
        io.heading("MAIN MENU");
        io.println("  1) Student registry");
        io.println("  2) Rooms & allocation");
        io.println("  3) Complaint desk");
        io.println("  4) Reports");
        io.println("  5) Save data now");
        io.println("  0) Save & exit");
    }

    // ------------------------------------------------------------------
    // Module 1 : students
    // ------------------------------------------------------------------
    private void studentMenu() {
        boolean back = false;
        while (!back) {
            io.heading("STUDENT REGISTRY");
            io.println("  1) Register a new student");
            io.println("  2) List all students");
            io.println("  3) Search students");
            io.println("  4) Update contact details");
            io.println("  5) Remove a student");
            io.println("  0) Back");
            int choice = io.readInt("Select an option: ", 0, 5);
            try {
                switch (choice) {
                    case 1: registerStudent(); break;
                    case 2: printStudents(ctx.studentService().listAll()); break;
                    case 3: searchStudents(); break;
                    case 4: updateStudent(); break;
                    case 5: removeStudent(); break;
                    default: back = true; break;
                }
            } catch (HostelAppException e) {
                io.failure(e.getMessage());
            }
        }
    }

    private void registerStudent() throws HostelAppException {
        io.heading("NEW STUDENT");
        String name = io.readRequired("  Full name        : ");
        if (name == null) {
            return;
        }
        String email = io.readRequired("  Email            : ");
        String phone = io.readRequired("  Phone (10 digits): ");
        String branch = io.readRequired("  Branch (e.g. CSE): ");
        int year = io.readInt("  Year of study 1-4: ", 1, 4);

        Student student = ctx.studentService().register(name, email, phone, branch, year);
        io.success("Registered " + student.displayLabel());

        if (io.confirm("  Allocate a room now?")) {
            allocateFor(student);
        }
    }

    private void searchStudents() {
        String query = io.readLine("  Search by name / ID / branch: ");
        if (query == null) {
            return;
        }
        List<Student> found = ctx.studentService().search(query);
        if (found.isEmpty()) {
            io.failure("Nothing matched '" + query + "'.");
            return;
        }
        printStudents(found);
    }

    private void updateStudent() throws HostelAppException {
        String id = io.readRequired("  Student ID: ");
        if (id == null) {
            return;
        }
        Student student = ctx.studentService().findById(id);
        io.info("Editing " + student.displayLabel() + " - press Enter to keep the current value.");
        String email = io.readLine("  New email [" + student.getEmail() + "]: ");
        String phone = io.readLine("  New phone [" + student.getPhone() + "]: ");
        ctx.studentService().updateContact(student.getId(), email, phone);
        io.success("Contact details updated.");
    }

    private void removeStudent() throws HostelAppException {
        String id = io.readRequired("  Student ID to remove: ");
        if (id == null) {
            return;
        }
        Student student = ctx.studentService().findById(id);
        Optional<Allocation> active = ctx.roomService().activeAllocationOf(student.getId());
        if (active.isPresent()) {
            io.failure(student.getName() + " still occupies room " + active.get().getRoomNo() + ".");
            if (!io.confirm("  Vacate the room and then remove?")) {
                return;
            }
            ctx.roomService().vacate(student.getId());
        }
        ctx.studentService().remove(student.getId());
        io.success("Record removed.");
    }

    private void printStudents(List<Student> list) {
        if (list.isEmpty()) {
            io.failure("No students on record yet.");
            return;
        }
        io.blank();
        io.println(String.format("  %-8s %-22s %-8s %-6s %-14s %s",
                "ID", "NAME", "BRANCH", "YEAR", "PHONE", "ROOM"));
        io.println("  " + line(80));
        for (Student s : list) {
            String room = ctx.roomService().activeAllocationOf(s.getId())
                    .map(Allocation::getRoomNo)
                    .orElse("-");
            io.println(String.format("  %-8s %-22s %-8s %-6d %-14s %s",
                    s.getId(), trim(s.getName(), 22), s.getBranch(), s.getYear(), s.getPhone(), room));
        }
        io.info(list.size() + " record(s).");
    }

    // ------------------------------------------------------------------
    // Module 2 : rooms and allocation
    // ------------------------------------------------------------------
    private void roomMenu() {
        boolean back = false;
        while (!back) {
            io.heading("ROOMS & ALLOCATION");
            io.info("Current strategy: " + ctx.roomService().getStrategy().name());
            io.println("  1) View all rooms");
            io.println("  2) Allocate a room to a student");
            io.println("  3) Vacate a student's room");
            io.println("  4) Room details");
            io.println("  5) Change allocation strategy");
            io.println("  6) Flag / unflag a room for maintenance");
            io.println("  0) Back");
            int choice = io.readInt("Select an option: ", 0, 6);
            try {
                switch (choice) {
                    case 1: printRooms(); break;
                    case 2: allocateFlow(); break;
                    case 3: vacateFlow(); break;
                    case 4: roomDetails(); break;
                    case 5: switchStrategy(); break;
                    case 6: maintenanceFlow(); break;
                    default: back = true; break;
                }
            } catch (HostelAppException e) {
                io.failure(e.getMessage());
            }
        }
    }

    private void printRooms() {
        io.blank();
        io.println(String.format("  %-10s %-7s %-7s %-10s %-10s %s",
                "ROOM", "BLOCK", "FLOOR", "TYPE", "OCCUPIED", "STATUS"));
        io.println("  " + line(70));
        for (Room room : ctx.roomService().listRooms()) {
            String status = room.isUnderMaintenance() ? "MAINTENANCE"
                    : room.isFull() ? "FULL" : room.bedsFree() + " bed(s) free";
            io.println(String.format("  %-10s %-7s %-7d %-10s %-10s %s",
                    room.getRoomNo(), room.getBlock(), room.getFloor(), room.getType().name(),
                    room.occupancy() + "/" + room.getType().capacity(), status));
        }
    }

    private void allocateFlow() throws HostelAppException {
        String id = io.readRequired("  Student ID: ");
        if (id == null) {
            return;
        }
        allocateFor(ctx.studentService().findById(id));
    }

    private void allocateFor(Student student) throws HostelAppException {
        io.info("Allocating for " + student.displayLabel()
                + " using '" + ctx.roomService().getStrategy().name() + "'.");
        io.println("  1) Let the system choose");
        io.println("  2) I will pick the room myself");
        int mode = io.readInt("  Choose 1-2: ", 1, 2);

        Allocation allocation;
        if (mode == 2) {
            printRooms();
            String roomNo = io.readRequired("  Room number (e.g. A-101): ");
            if (roomNo == null) {
                return;
            }
            allocation = ctx.roomService().allocateSpecific(student, roomNo);
        } else {
            RoomType type = io.readEnum("  Preferred room type:", RoomType.class);
            allocation = ctx.roomService().allocate(student, type);
        }
        io.success(student.getName() + " has been given room " + allocation.getRoomNo()
                + " (allocation " + allocation.getId() + ").");
    }

    private void vacateFlow() throws HostelAppException {
        String id = io.readRequired("  Student ID: ");
        if (id == null) {
            return;
        }
        Student student = ctx.studentService().findById(id);
        Allocation closed = ctx.roomService().vacate(student.getId());
        io.success(student.getName() + " vacated " + closed.getRoomNo()
                + " after " + java.time.temporal.ChronoUnit.DAYS.between(
                        closed.getAllocatedOn(), LocalDate.now()) + " day(s).");
    }

    private void roomDetails() throws HostelAppException {
        String roomNo = io.readRequired("  Room number: ");
        if (roomNo == null) {
            return;
        }
        Room room = ctx.roomService().findRoom(roomNo);
        io.heading("ROOM " + room.getRoomNo());
        io.info("Block / floor : " + room.getBlock() + " / " + room.getFloor());
        io.info("Type          : " + room.getType().label());
        io.info("Rent          : Rs." + room.getType().monthlyRent() + " per bed per month");
        io.info("Occupancy     : " + room.occupancy() + "/" + room.getType().capacity()
                + String.format(" (%.0f%%)", room.occupancyRate()));
        io.info("Maintenance   : " + (room.isUnderMaintenance() ? "yes" : "no"));
        if (room.getOccupantIds().isEmpty()) {
            io.info("Residents     : none");
        } else {
            io.info("Residents     :");
            for (String occupantId : room.getOccupantIds()) {
                try {
                    io.println("     - " + ctx.studentService().findById(occupantId).displayLabel());
                } catch (HostelAppException missing) {
                    io.println("     - " + occupantId + " (record missing)");
                }
            }
        }
    }

    private void switchStrategy() {
        io.heading("ALLOCATION STRATEGY");
        List<AllocationStrategy> options = java.util.Arrays.asList(
                new FirstAvailableStrategy(),
                new ConsolidateOccupancyStrategy(),
                new SameBranchGroupingStrategy(ctx.studentRepository()));
        for (int i = 0; i < options.size(); i++) {
            io.println("  " + (i + 1) + ") " + options.get(i).name() + " - " + options.get(i).explanation());
        }
        int choice = io.readInt("  Choose 1-" + options.size() + ": ", 1, options.size());
        if (choice >= 1) {
            ctx.roomService().setStrategy(options.get(choice - 1));
            io.success("Strategy is now '" + options.get(choice - 1).name() + "'.");
        }
    }

    private void maintenanceFlow() throws HostelAppException {
        String roomNo = io.readRequired("  Room number: ");
        if (roomNo == null) {
            return;
        }
        Room room = ctx.roomService().findRoom(roomNo);
        boolean newFlag = !room.isUnderMaintenance();
        ctx.roomService().setMaintenanceFlag(room.getRoomNo(), newFlag);
        io.success("Room " + room.getRoomNo() + (newFlag ? " blocked for maintenance." : " is back in service."));
    }

    // ------------------------------------------------------------------
    // Module 3 : complaints
    // ------------------------------------------------------------------
    private void complaintMenu() {
        boolean back = false;
        while (!back) {
            io.heading("COMPLAINT DESK");
            io.println("  1) Raise a complaint");
            io.println("  2) View all complaints");
            io.println("  3) Assign a complaint to staff");
            io.println("  4) Update complaint status");
            io.println("  5) Overdue complaints (past SLA)");
            io.println("  6) Staff list & workload");
            io.println("  0) Back");
            int choice = io.readInt("Select an option: ", 0, 6);
            try {
                switch (choice) {
                    case 1: raiseComplaint(); break;
                    case 2: printComplaints(ctx.complaintService().listAll()); break;
                    case 3: assignComplaint(); break;
                    case 4: updateComplaintStatus(); break;
                    case 5: printComplaints(ctx.complaintService().overdue()); break;
                    case 6: printStaff(); break;
                    default: back = true; break;
                }
            } catch (HostelAppException e) {
                io.failure(e.getMessage());
            }
        }
    }

    private void raiseComplaint() throws HostelAppException {
        String id = io.readRequired("  Student ID raising the complaint: ");
        if (id == null) {
            return;
        }
        Student student = ctx.studentService().findById(id);
        Allocation active = ctx.roomService().activeAllocationOf(student.getId())
                .orElseThrow(() -> new AllocationException(
                        student.getName() + " does not occupy a room, so there is nothing to complain about yet."));

        ComplaintCategory category = io.readEnum("  Category:", ComplaintCategory.class);
        Priority priority = io.readEnum("  Priority:", Priority.class);
        String description = io.readRequired("  What is the problem? ");
        if (description == null) {
            return;
        }

        Complaint complaint = ctx.complaintService().raise(student.getId(), active.getRoomNo(),
                category, priority, description);
        io.success("Complaint " + complaint.getId() + " logged for room " + active.getRoomNo()
                + ". Expected resolution within " + priority.slaDays() + " day(s).");

        ctx.complaintService().suggestStaff(category).ifPresent(member ->
                io.info("Suggested handler: " + member.displayLabel()
                        + " (" + ctx.complaintService().openWorkload(member.getId()) + " open job(s))"));
    }

    private void assignComplaint() throws HostelAppException {
        String complaintId = io.readRequired("  Complaint ID: ");
        if (complaintId == null) {
            return;
        }
        Complaint complaint = ctx.complaintService().findById(complaintId);
        io.info("Category is " + complaint.getCategory().label() + ".");
        printStaff();
        String staffId = io.readRequired("  Staff ID: ");
        if (staffId == null) {
            return;
        }
        ctx.complaintService().assign(complaint.getId(), staffId);
        io.success("Complaint " + complaint.getId() + " assigned.");
    }

    private void updateComplaintStatus() throws HostelAppException {
        String complaintId = io.readRequired("  Complaint ID: ");
        if (complaintId == null) {
            return;
        }
        Complaint complaint = ctx.complaintService().findById(complaintId);
        io.info("Current status: " + complaint.getStatus());
        ComplaintStatus next = io.readEnum("  Move to:", ComplaintStatus.class);
        ctx.complaintService().moveStatus(complaint.getId(), next);
        io.success("Complaint " + complaint.getId() + " is now " + next + ".");
    }

    private void printComplaints(List<Complaint> list) {
        if (list.isEmpty()) {
            io.failure("Nothing to show here.");
            return;
        }
        LocalDate today = LocalDate.now();
        io.blank();
        io.println(String.format("  %-9s %-8s %-8s %-13s %-9s %-12s %-6s %s",
                "ID", "STUDENT", "ROOM", "CATEGORY", "PRIORITY", "STATUS", "AGE", "DETAILS"));
        io.println("  " + line(100));
        for (Complaint c : list) {
            io.println(String.format("  %-9s %-8s %-8s %-13s %-9s %-12s %-6s %s",
                    c.getId(), c.getStudentId(), c.getRoomNo(), c.getCategory().name(),
                    c.getPriority().name(), c.getStatus().name(),
                    c.ageInDays(today) + "d", trim(c.getDescription(), 34)
                            + (c.isOverdue(today) ? "  << OVERDUE" : "")));
        }
        io.info(list.size() + " complaint(s).");
    }

    private void printStaff() {
        io.blank();
        io.println(String.format("  %-8s %-20s %-16s %s", "ID", "NAME", "TRADE", "OPEN JOBS"));
        io.println("  " + line(60));
        for (Staff member : ctx.complaintService().allStaff()) {
            io.println(String.format("  %-8s %-20s %-16s %d",
                    member.getId(), trim(member.getName(), 20), member.getTrade().label(),
                    ctx.complaintService().openWorkload(member.getId())));
        }
    }

    // ------------------------------------------------------------------
    // Module 4 : reports
    // ------------------------------------------------------------------
    private void reportMenu() {
        boolean back = false;
        while (!back) {
            io.heading("REPORTS");
            io.println("  1) Occupancy by block");
            io.println("  2) Rooms with free beds");
            io.println("  3) Complaint summary");
            io.println("  4) Resident digest");
            io.println("  5) Everything");
            io.println("  0) Back");
            int choice = io.readInt("Select an option: ", 0, 5);
            switch (choice) {
                case 1: io.print(ctx.reportService().occupancyReport()); break;
                case 2: io.print(ctx.reportService().vacancyReport()); break;
                case 3: io.print(ctx.reportService().complaintReport()); break;
                case 4: io.print(ctx.reportService().studentDigest()); break;
                case 5: io.print(ctx.reportService().fullReport()); break;
                default: back = true; break;
            }
        }
    }

    // ------------------------------------------------------------------
    private void saveEverything() {
        ctx.flushAll();
        io.success("All data written to " + ctx.dataDir().toAbsolutePath());
    }

    private void shutdown() {
        saveEverything();
        io.println("\nBye. Log file: " + ctx.logFile().toAbsolutePath() + "\n");
        running = false;
    }

    private String trim(String text, int width) {
        if (text == null) {
            return "";
        }
        return text.length() <= width ? text : text.substring(0, width - 1) + "~";
    }

    private String line(int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) {
            sb.append('-');
        }
        return sb.toString();
    }
}
