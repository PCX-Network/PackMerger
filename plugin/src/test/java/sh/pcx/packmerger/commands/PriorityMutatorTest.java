package sh.pcx.packmerger.commands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PriorityMutatorTest {

    private static final List<String> SAMPLE = List.of("a.zip", "b.zip", "c.zip", "d.zip");

    @Test
    void up_movesTowardTop() {
        assertEquals(List.of("a.zip", "c.zip", "b.zip", "d.zip"),
                PriorityMutator.up(SAMPLE, "c.zip"));
    }

    @Test
    void up_alreadyTop_throws() {
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.up(SAMPLE, "a.zip"));
    }

    @Test
    void down_movesTowardBottom() {
        assertEquals(List.of("a.zip", "c.zip", "b.zip", "d.zip"),
                PriorityMutator.down(SAMPLE, "b.zip"));
    }

    @Test
    void down_alreadyBottom_throws() {
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.down(SAMPLE, "d.zip"));
    }

    @Test
    void top_movesToIndexZero() {
        assertEquals(List.of("c.zip", "a.zip", "b.zip", "d.zip"),
                PriorityMutator.top(SAMPLE, "c.zip"));
    }

    @Test
    void top_alreadyTop_noOp() {
        assertEquals(SAMPLE, PriorityMutator.top(SAMPLE, "a.zip"));
    }

    @Test
    void bottom_movesToLast() {
        assertEquals(List.of("a.zip", "c.zip", "d.zip", "b.zip"),
                PriorityMutator.bottom(SAMPLE, "b.zip"));
    }

    @Test
    void set_absolutePosition_oneBased() {
        assertEquals(List.of("a.zip", "c.zip", "b.zip", "d.zip"),
                PriorityMutator.set(SAMPLE, "c.zip", 2));
    }

    @Test
    void set_positionOutOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.set(SAMPLE, "c.zip", 0));
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.set(SAMPLE, "c.zip", 5));
    }

    @Test
    void unknownPack_throws() {
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.up(SAMPLE, "nope.zip"));
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.down(SAMPLE, "nope.zip"));
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.top(SAMPLE, "nope.zip"));
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.bottom(SAMPLE, "nope.zip"));
        assertThrows(IllegalArgumentException.class, () -> PriorityMutator.set(SAMPLE, "nope.zip", 1));
    }

    @Test
    void addIfMissing_appendsOrNoOps() {
        assertEquals(List.of("a.zip", "b.zip", "c.zip", "d.zip", "e.zip"),
                PriorityMutator.addIfMissing(SAMPLE, "e.zip"));
        assertEquals(SAMPLE, PriorityMutator.addIfMissing(SAMPLE, "b.zip"));
    }

    @Test
    void remove_deletesOrNoOps() {
        assertEquals(List.of("a.zip", "c.zip", "d.zip"),
                PriorityMutator.remove(SAMPLE, "b.zip"));
        assertEquals(SAMPLE, PriorityMutator.remove(SAMPLE, "nope.zip"));
    }

    @Test
    void inputNotMutated() {
        PriorityMutator.up(SAMPLE, "c.zip");
        PriorityMutator.remove(SAMPLE, "b.zip");
        assertEquals(List.of("a.zip", "b.zip", "c.zip", "d.zip"), SAMPLE);
    }
}
