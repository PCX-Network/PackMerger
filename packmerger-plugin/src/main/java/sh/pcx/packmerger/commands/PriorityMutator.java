package sh.pcx.packmerger.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure list-manipulation helpers backing {@code /pm priority} commands.
 *
 * <p>Every method returns a new list — inputs are never mutated — and throws
 * {@link IllegalArgumentException} with a human-friendly message when the
 * requested operation doesn't make sense (pack not in list, already at top,
 * etc.). That keeps the command handler thin: run the op, catch the
 * exception, forward the message to the sender.</p>
 *
 * <p>Kept dependency-free (no Bukkit, no config) so each op can be unit
 * tested in isolation.</p>
 */
public final class PriorityMutator {

    private PriorityMutator() {}

    /**
     * Move {@code pack} one position higher in priority (toward index 0, the
     * highest-priority slot).
     */
    public static List<String> up(List<String> priority, String pack) {
        int idx = requireIndex(priority, pack);
        if (idx == 0) throw new IllegalArgumentException(pack + " is already at the top");
        List<String> copy = new ArrayList<>(priority);
        swap(copy, idx, idx - 1);
        return copy;
    }

    /**
     * Move {@code pack} one position lower in priority (toward the end of the list).
     */
    public static List<String> down(List<String> priority, String pack) {
        int idx = requireIndex(priority, pack);
        if (idx == priority.size() - 1) throw new IllegalArgumentException(pack + " is already at the bottom");
        List<String> copy = new ArrayList<>(priority);
        swap(copy, idx, idx + 1);
        return copy;
    }

    /** Move {@code pack} to index 0 (highest priority). */
    public static List<String> top(List<String> priority, String pack) {
        int idx = requireIndex(priority, pack);
        if (idx == 0) return new ArrayList<>(priority);
        List<String> copy = new ArrayList<>(priority);
        copy.remove(idx);
        copy.add(0, pack);
        return copy;
    }

    /** Move {@code pack} to the last index (lowest priority). */
    public static List<String> bottom(List<String> priority, String pack) {
        int idx = requireIndex(priority, pack);
        if (idx == priority.size() - 1) return new ArrayList<>(priority);
        List<String> copy = new ArrayList<>(priority);
        copy.remove(idx);
        copy.add(pack);
        return copy;
    }

    /**
     * Place {@code pack} at the given 1-based position (so {@code set(list, p, 1)}
     * is equivalent to {@code top}).
     *
     * @param oneBasedPosition 1 = top, {@code list.size()} = bottom
     */
    public static List<String> set(List<String> priority, String pack, int oneBasedPosition) {
        int idx = requireIndex(priority, pack);
        int target = oneBasedPosition - 1;
        if (target < 0 || target >= priority.size()) {
            throw new IllegalArgumentException("position must be between 1 and " + priority.size());
        }
        if (target == idx) return new ArrayList<>(priority);
        List<String> copy = new ArrayList<>(priority);
        copy.remove(idx);
        copy.add(target, pack);
        return copy;
    }

    /** Add {@code pack} to the end of the list, if not already present. No-op otherwise. */
    public static List<String> addIfMissing(List<String> priority, String pack) {
        if (priority.contains(pack)) return new ArrayList<>(priority);
        List<String> copy = new ArrayList<>(priority);
        copy.add(pack);
        return copy;
    }

    /** Remove {@code pack} from the priority list, if present. No-op otherwise. */
    public static List<String> remove(List<String> priority, String pack) {
        List<String> copy = new ArrayList<>(priority);
        copy.remove(pack);
        return copy;
    }

    private static int requireIndex(List<String> priority, String pack) {
        int idx = priority.indexOf(pack);
        if (idx < 0) throw new IllegalArgumentException(pack + " is not in the priority list");
        return idx;
    }

    private static <T> void swap(List<T> list, int i, int j) {
        T tmp = list.get(i);
        list.set(i, list.get(j));
        list.set(j, tmp);
    }
}
