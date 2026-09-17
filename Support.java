/*
 * Support.java - cross-cutting pieces.
 *
 * The checked exception hierarchy that carries every business rule violation,
 * plus field validation, the file logger, sequential ID generation and the
 * first-run seed data.
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Base checked exception for every business rule this app enforces.
 * Checked on purpose - a caller that allocates a room should be forced by the
 * compiler to decide what happens when the hostel is full.
 */
class HostelAppException extends Exception {

    private static final long serialVersionUID = 1L;

    public HostelAppException(String message) {
        super(message);
    }
}

/** Raised when user supplied data fails a field level rule. */
class ValidationException extends HostelAppException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}

/** Raised when an ID that already exists is being inserted again. */
class DuplicateRecordException extends HostelAppException {

    private static final long serialVersionUID = 1L;

    public DuplicateRecordException(String message) {
        super(message);
    }
}

/** Raised when a lookup by ID finds nothing. */
class RecordNotFoundException extends HostelAppException {

    private static final long serialVersionUID = 1L;

    public RecordNotFoundException(String message) {
        super(message);
    }
}

/** Raised when a room has already hit the capacity of its room type. */
class RoomFullException extends HostelAppException {

    private static final long serialVersionUID = 1L;

    public RoomFullException(String message) {
        super(message);
    }
}

/** Raised for allocation rule violations - double allocation, no vacancy, etc. */
class AllocationException extends HostelAppException {

    private static final long serialVersionUID = 1L;

    public AllocationException(String message) {
        super(message);
    }
}

/**
 * Field level validation kept in one place so the rules cannot drift apart
 * between the console layer and the service layer.
 */
final class Validator {

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern ROOM_NO = Pattern.compile("^[A-Z]-\\d{3}$");

    private Validator() {
    }

    public static String requireText(String value, String fieldName) throws ValidationException {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(fieldName + " cannot be blank.");
        }
        return value.trim();
    }

    public static String requireEmail(String value) throws ValidationException {
        String email = requireText(value, "Email");
        if (!EMAIL.matcher(email).matches()) {
            throw new ValidationException("'" + email + "' is not a valid email address.");
        }
        return email;
    }

    public static String requirePhone(String value) throws ValidationException {
        String phone = requireText(value, "Phone");
        if (!PHONE.matcher(phone).matches()) {
            throw new ValidationException("Phone must be a 10 digit Indian mobile number starting with 6-9.");
        }
        return phone;
    }

    public static String requireRoomNumber(String value) throws ValidationException {
        String room = requireText(value, "Room number").toUpperCase();
        if (!ROOM_NO.matcher(room).matches()) {
            throw new ValidationException("Room number must look like A-101 (block letter, dash, three digits).");
        }
        return room;
    }

    public static int requireRange(int value, int min, int max, String fieldName) throws ValidationException {
        if (value < min || value > max) {
            throw new ValidationException(fieldName + " must be between " + min + " and " + max + ".");
        }
        return value;
    }
}

/**
 * Small append-only logger. I deliberately avoided java.util.logging and
 * log4j here: the assignment asks for zero external dependencies and the
 * standard logger's default console output kept mixing itself into the
 * menu screens, which made the CLI unreadable.
 *
 * Everything goes to logs/app.log. Nothing is printed to the console except
 * errors, which the user genuinely needs to see.
 */
class AppLogger {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path logFile;
    private boolean echoErrorsToConsole = true;

    public AppLogger(Path logFile) {
        this.logFile = logFile;
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException e) {
            System.err.println("Logging disabled, could not create log file: " + e.getMessage());
        }
    }

    public void setEchoErrorsToConsole(boolean echo) {
        this.echoErrorsToConsole = echo;
    }

    public void info(String message) {
        write("INFO ", message);
    }

    public void warn(String message) {
        write("WARN ", message);
    }

    public void error(String message) {
        write("ERROR", message);
        if (echoErrorsToConsole) {
            System.err.println("[error] " + message);
        }
    }

    private void write(String level, String message) {
        String line = LocalDateTime.now().format(STAMP) + " [" + level + "] " + message;
        try {
            Files.write(logFile, Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // If logging itself fails there is nowhere sensible left to complain.
            System.err.println("[logger] " + e.getMessage());
        }
    }
}

/**
 * Generates the next sequential ID for a prefix, e.g. STU007 or CMP014.
 *
 * It scans the IDs already in the repository rather than keeping a counter in
 * a file, so deleting the data folder and starting fresh can never produce a
 * duplicate key.
 */
final class IdGenerator {

    private IdGenerator() {
    }

    public static String next(String prefix, List<String> existingIds, int width) {
        int highest = 0;
        for (String id : existingIds) {
            if (id == null || !id.startsWith(prefix)) {
                continue;
            }
            try {
                highest = Math.max(highest, Integer.parseInt(id.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // Hand-edited ID in the CSV - ignore it for numbering purposes.
            }
        }
        return prefix + String.format("%0" + width + "d", highest + 1);
    }
}

/**
 * First-run data. Only applied when the repositories come up empty, so the
 * seed can never overwrite real data that already exists on disk.
 *
 * Two blocks, three floors each, mixed room types - enough variety for the
 * occupancy report and the allocation strategies to show a difference.
 */
final class SeedData {

    private SeedData() {
    }

    public static void applyIfEmpty(Repository<Room> rooms, Repository<Staff> staff,
                                    Repository<Student> students, AppLogger log) {
        if (rooms.count() == 0) {
            seedRooms(rooms);
            log.info("Seeded " + rooms.count() + " rooms on first run.");
        }
        if (staff.count() == 0) {
            seedStaff(staff);
            log.info("Seeded " + staff.count() + " maintenance staff on first run.");
        }
        if (students.count() == 0) {
            seedStudents(students);
            log.info("Seeded " + students.count() + " sample students on first run.");
        }
    }

    private static void seedRooms(Repository<Room> rooms) {
        String[] blocks = {"A", "B"};
        RoomType[] pattern = {RoomType.TRIPLE, RoomType.DOUBLE, RoomType.TRIPLE, RoomType.SINGLE};
        for (String block : blocks) {
            for (int floor = 1; floor <= 3; floor++) {
                for (int index = 0; index < pattern.length; index++) {
                    String roomNo = String.format("%s-%d%02d", block, floor, index + 1);
                    rooms.save(new Room(roomNo, block, floor, pattern[index]));
                }
            }
        }
    }

    private static void seedStaff(Repository<Staff> staff) {
        staff.save(new Staff("STF001", "Ramesh Patil", "ramesh.patil@hostel.in", "9812345601", ComplaintCategory.ELECTRICAL));
        staff.save(new Staff("STF002", "Imran Sheikh", "imran.sheikh@hostel.in", "9812345602", ComplaintCategory.PLUMBING));
        staff.save(new Staff("STF003", "Dinesh Kumar", "dinesh.kumar@hostel.in", "9812345603", ComplaintCategory.CARPENTRY));
        staff.save(new Staff("STF004", "Sunita Bai", "sunita.bai@hostel.in", "9812345604", ComplaintCategory.HOUSEKEEPING));
        staff.save(new Staff("STF005", "Arjun Nair", "arjun.nair@hostel.in", "9812345605", ComplaintCategory.INTERNET));
        staff.save(new Staff("STF006", "Farid Ansari", "farid.ansari@hostel.in", "9812345606", ComplaintCategory.ELECTRICAL));
    }

    private static void seedStudents(Repository<Student> students) {
        students.save(new Student("STU001", "Aditya Rane", "aditya.rane@example.com", "9876543210", "CSE", 2));
        students.save(new Student("STU002", "Priya Menon", "priya.menon@example.com", "9876543211", "CSE", 2));
        students.save(new Student("STU003", "Karan Bhatia", "karan.bhatia@example.com", "9876543212", "ECE", 3));
        students.save(new Student("STU004", "Neha Joshi", "neha.joshi@example.com", "9876543213", "MECH", 1));
        students.save(new Student("STU005", "Sahil Qureshi", "sahil.qureshi@example.com", "9876543214", "CSE", 1));
    }
}
