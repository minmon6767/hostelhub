/*
 * Strategy.java - the room allocation policies.
 *
 * AllocationStrategy plus three implementations that produce genuinely
 * different hostels from the same input. RoomService holds one of these and
 * never knows which; the warden swaps it from the menu at runtime.
 */

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Strategy pattern. The warden can switch the rule the hostel uses to pick a
 * room without any change to RoomService - the service just calls pick().
 */
interface AllocationStrategy {

    /** @return the chosen room, or empty if none of the candidates suit. */
    Optional<Room> pick(List<Room> candidates, Student student);

    String name();

    String explanation();
}

/**
 * Simplest rule: whatever room comes first in room-number order. Useful as a
 * baseline to compare the other two strategies against.
 */
class FirstAvailableStrategy implements AllocationStrategy {

    @Override
    public Optional<Room> pick(List<Room> candidates, Student student) {
        return candidates.stream()
                .filter(Room::isAvailable)
                .min(Comparator.comparing(Room::getRoomNo));
    }

    @Override
    public String name() {
        return "First Available";
    }

    @Override
    public String explanation() {
        return "Picks the lowest room number that still has a free bed.";
    }
}

/**
 * Fills partly occupied rooms before opening new ones. In a real hostel this
 * is what the warden actually wants, because empty wings can then be locked up
 * and left unlit - fewer rooms in use means lower running cost.
 */
class ConsolidateOccupancyStrategy implements AllocationStrategy {

    @Override
    public Optional<Room> pick(List<Room> candidates, Student student) {
        return candidates.stream()
                .filter(Room::isAvailable)
                .filter(room -> room.occupancy() > 0)          // prefer rooms already in use
                .max(Comparator.comparingInt(Room::occupancy))
                .map(Optional::of)
                .orElseGet(() -> candidates.stream()           // nothing part-filled, open a fresh room
                        .filter(Room::isAvailable)
                        .min(Comparator.comparing(Room::getRoomNo)));
    }

    @Override
    public String name() {
        return "Consolidate Occupancy";
    }

    @Override
    public String explanation() {
        return "Fills rooms that already have residents before opening an empty room.";
    }
}

/**
 * Tries to put a student in a room where somebody from the same branch and
 * year already lives, which helps first years settle in. Falls back to the
 * lowest floor available so that seniors are not pushed to the top floors.
 *
 * This strategy needs to look up the other occupants, so unlike the other two
 * it is constructed with the student repository.
 */
class SameBranchGroupingStrategy implements AllocationStrategy {

    private final Repository<Student> students;

    public SameBranchGroupingStrategy(Repository<Student> students) {
        this.students = students;
    }

    @Override
    public Optional<Room> pick(List<Room> candidates, Student student) {
        Optional<Room> branchMatch = candidates.stream()
                .filter(Room::isAvailable)
                .filter(room -> sharesBranch(room, student))
                .min(Comparator.comparingInt(Room::bedsFree));

        if (branchMatch.isPresent()) {
            return branchMatch;
        }
        return candidates.stream()
                .filter(Room::isAvailable)
                .min(Comparator.comparingInt(Room::getFloor).thenComparing(Room::getRoomNo));
    }

    private boolean sharesBranch(Room room, Student student) {
        return room.getOccupantIds().stream()
                .map(students::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .anyMatch(mate -> mate.getBranch().equalsIgnoreCase(student.getBranch())
                        && mate.getYear() == student.getYear());
    }

    @Override
    public String name() {
        return "Same Branch Grouping";
    }

    @Override
    public String explanation() {
        return "Prefers a room where a same-branch, same-year student already stays.";
    }
}
