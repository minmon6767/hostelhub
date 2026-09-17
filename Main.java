/*
 * Main.java - entry point and application wiring.
 *
 * AppContext builds the repositories, hands them to the services and exposes
 * the finished object graph. Main parses the command line flags and starts
 * either the menu or a one-shot report.
 */

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires the whole application together in one place: repositories first, then
 * the services that depend on them. Doing this by hand (poor man's dependency
 * injection) keeps every other class free of "new CsvRepository(...)" calls,
 * so the services can be constructed with test doubles in the unit tests.
 */
class AppContext {

    private final Path dataDir;
    private final Path logFile;
    private final AppLogger logger;

    private final Repository<Student> studentRepo;
    private final Repository<Staff> staffRepo;
    private final Repository<Room> roomRepo;
    private final Repository<Allocation> allocationRepo;
    private final Repository<Complaint> complaintRepo;

    private final StudentService studentService;
    private final RoomService roomService;
    private final ComplaintService complaintService;
    private final ReportService reportService;

    public AppContext(String dataDirName) {
        this.dataDir = Paths.get(dataDirName);
        this.logFile = Paths.get("logs", "app.log");
        this.logger = new AppLogger(logFile);

        this.studentRepo = new CsvRepository<>(dataDir.resolve("students.csv"),
                Student.CSV_HEADER, Student::fromCsv, logger);
        this.staffRepo = new CsvRepository<>(dataDir.resolve("staff.csv"),
                Staff.CSV_HEADER, Staff::fromCsv, logger);
        this.roomRepo = new CsvRepository<>(dataDir.resolve("rooms.csv"),
                Room.CSV_HEADER, Room::fromCsv, logger);
        this.allocationRepo = new CsvRepository<>(dataDir.resolve("allocations.csv"),
                Allocation.CSV_HEADER, Allocation::fromCsv, logger);
        this.complaintRepo = new CsvRepository<>(dataDir.resolve("complaints.csv"),
                Complaint.CSV_HEADER, Complaint::fromCsv, logger);

        // A brand new checkout has no CSV files at all, so lay down a small
        // starter hostel instead of dropping the evaluator into an empty app.
        SeedData.applyIfEmpty(roomRepo, staffRepo, studentRepo, logger);

        this.studentService = new StudentService(studentRepo, logger);
        this.roomService = new RoomService(roomRepo, allocationRepo, new ConsolidateOccupancyStrategy(), logger);
        this.complaintService = new ComplaintService(complaintRepo, staffRepo, logger);
        this.reportService = new ReportService(studentRepo, roomRepo, allocationRepo, complaintRepo);
    }

    public StudentService studentService() {
        return studentService;
    }

    public RoomService roomService() {
        return roomService;
    }

    public ComplaintService complaintService() {
        return complaintService;
    }

    public ReportService reportService() {
        return reportService;
    }

    public Repository<Student> studentRepository() {
        return studentRepo;
    }

    public AppLogger logger() {
        return logger;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path logFile() {
        return logFile;
    }

    /** Writes every repository back to disk. Called on exit and on "Save now". */
    public void flushAll() {
        studentRepo.flush();
        staffRepo.flush();
        roomRepo.flush();
        allocationRepo.flush();
        complaintRepo.flush();
    }
}

/**
 * Entry point.
 *
 * Supported flags:
 *   (none)          start the interactive menu
 *   --report        print every report and exit (handy for a quick check
 *                   or for piping the output into a file)
 *   --data <dir>    use a different data folder, e.g. for a throwaway run
 *   --help          usage
 */
public class Main {

    public static void main(String[] args) {
        String dataDir = "data";
        boolean reportOnly = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                return;
            } else if ("--report".equals(arg)) {
                reportOnly = true;
            } else if ("--data".equals(arg) && i + 1 < args.length) {
                dataDir = args[++i];
            } else {
                System.out.println("Unrecognised option: " + arg);
                printUsage();
                return;
            }
        }

        AppContext context = new AppContext(dataDir);

        if (reportOnly) {
            System.out.print(context.reportService().fullReport());
            context.flushAll();
            return;
        }

        try {
            new ConsoleApp(context).start();
        } catch (RuntimeException unexpected) {
            // Last line of defence: log it, tell the user, and still save their work.
            context.logger().error("Unexpected failure: " + unexpected);
            System.out.println("Something went wrong, but your data has been saved. "
                    + "Details are in logs/app.log");
            context.flushAll();
        }
    }

    private static void printUsage() {
        System.out.println("HostelHub - Hostel Room Allocation & Complaint Desk");
        System.out.println();
        System.out.println("  java -cp out Main               interactive menu");
        System.out.println("  java -cp out Main --report      print all reports and exit");
        System.out.println("  java -cp out Main --data demo   use ./demo as the data folder");
        System.out.println("  java -cp out Main --help        this message");
    }
}
