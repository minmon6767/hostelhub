/*
 * Repository.java - the storage layer.
 *
 * Identifiable and CsvSerializable are the two tiny contracts an entity has to
 * satisfy to be stored; Repository<T> is what the services talk to; and
 * CsvRepository<T> is the only implementation - an in-memory cache flushed to
 * a CSV file. CsvUtil holds the helpers for this very small CSV dialect.
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Anything that can live inside a Repository needs a stable, unique key.
 * Deliberately tiny - it is the only thing the generic repository actually
 * needs to know about the objects it stores.
 */
interface Identifiable {
    String getId();
}

/**
 * Objects that know how to flatten themselves into one CSV line.
 * Parsing back is done by a static fromCsv(...) on each model, which is
 * handed to the repository as a function - interfaces cannot usefully
 * declare static factory methods for this.
 */
interface CsvSerializable {
    String toCsvRow();
}

/**
 * Storage contract used by every service class. Services never touch files
 * directly - they only talk to this interface, so replacing CSV files with a
 * database later would not require touching the service layer at all.
 */
interface Repository<T extends Identifiable> {

    T save(T entity);

    Optional<T> findById(String id);

    List<T> findAll();

    List<T> findBy(Predicate<T> filter);

    boolean deleteById(String id);

    boolean existsById(String id);

    int count();

    void flush();
}

/**
 * A file-backed repository. Rows are held in memory while the app runs (so
 * menu operations stay instant) and written back to the CSV file on flush().
 *
 * The bounded type parameter does real work here: T must be both identifiable
 * (so we can key the map) and CSV serialisable (so we can persist it), and the
 * compiler enforces both.
 */
class CsvRepository<T extends Identifiable & CsvSerializable> implements Repository<T> {

    private final Path file;
    private final String header;
    private final Function<String, T> parser;
    private final Map<String, T> cache = new LinkedHashMap<>();
    private final AppLogger log;

    public CsvRepository(Path file, String header, Function<String, T> parser, AppLogger log) {
        this.file = file;
        this.header = header;
        this.parser = parser;
        this.log = log;
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            log.info("No file at " + file.getFileName() + " yet - starting empty.");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int lineNo = 0;
            for (String line : lines) {
                lineNo++;
                if (lineNo == 1 || line.trim().isEmpty()) {
                    continue; // header row or blank padding
                }
                try {
                    T entity = parser.apply(line);
                    cache.put(entity.getId(), entity);
                } catch (RuntimeException badRow) {
                    // One corrupt row should never stop the whole app from booting.
                    log.warn("Skipped bad row " + lineNo + " in " + file.getFileName() + ": " + badRow.getMessage());
                }
            }
            log.info("Loaded " + cache.size() + " record(s) from " + file.getFileName());
        } catch (IOException e) {
            log.error("Could not read " + file + " - " + e.getMessage());
        }
    }

    @Override
    public T save(T entity) {
        cache.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<T> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    @Override
    public List<T> findAll() {
        return Collections.unmodifiableList(new ArrayList<>(cache.values()));
    }

    @Override
    public List<T> findBy(Predicate<T> filter) {
        return cache.values().stream().filter(filter).collect(Collectors.toList());
    }

    @Override
    public boolean deleteById(String id) {
        return cache.remove(id) != null;
    }

    @Override
    public boolean existsById(String id) {
        return cache.containsKey(id);
    }

    @Override
    public int count() {
        return cache.size();
    }

    @Override
    public void flush() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            List<String> out = new ArrayList<>();
            out.add(header);
            for (T entity : cache.values()) {
                out.add(entity.toCsvRow());
            }
            Files.write(file, out, StandardCharsets.UTF_8);
            log.info("Saved " + cache.size() + " record(s) to " + file.getFileName());
        } catch (IOException e) {
            log.error("Failed to write " + file + " - " + e.getMessage());
        }
    }
}

/**
 * Helpers for the very small CSV dialect this project uses.
 *
 * Design decision: instead of writing a full RFC-4180 parser with quote and
 * escape handling, free text fields are sanitised at the point of entry -
 * commas become semicolons and newlines become spaces. For a hostel complaint
 * description that loses nothing meaningful, and it keeps the storage layer
 * short enough to actually read.
 */
final class CsvUtil {

    private CsvUtil() {
        // utility class, never instantiated
    }

    /** Strip characters that would break a naive split(","). */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(",", ";").replace("\n", " ").replace("\r", " ").trim();
    }

    /**
     * split() with a negative limit so that trailing empty fields survive.
     * Without this, a complaint that has not been assigned yet loses its
     * columns and the row length check fails on reload.
     */
    public static String[] split(String line, int expectedColumns) {
        String[] parts = line.split(",", -1);
        if (parts.length != expectedColumns) {
            throw new IllegalArgumentException("expected " + expectedColumns + " columns, found " + parts.length);
        }
        return parts;
    }

    /** Empty string in the file means "no value" - convert it back to null. */
    public static String orNull(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value.trim();
    }

    /** null back to empty string when writing. */
    public static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
