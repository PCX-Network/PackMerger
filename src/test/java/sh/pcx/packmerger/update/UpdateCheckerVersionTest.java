package sh.pcx.packmerger.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerVersionTest {

    @Test
    void equalVersionsReturnZero() {
        assertEquals(0, UpdateChecker.compareVersions("1.1.0", "1.1.0"));
    }

    @Test
    void higherMajorSortsHigher() {
        assertTrue(UpdateChecker.compareVersions("1.0.0", "2.0.0") < 0);
        assertTrue(UpdateChecker.compareVersions("2.0.0", "1.9.9") > 0);
    }

    @Test
    void higherMinorSortsHigher() {
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.2.0") < 0);
        assertTrue(UpdateChecker.compareVersions("1.0.5", "1.1.0") < 0);
    }

    @Test
    void higherPatchSortsHigher() {
        assertTrue(UpdateChecker.compareVersions("1.0.4", "1.0.5") < 0);
    }

    @Test
    void missingTrailingComponentTreatedAsZero() {
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"));
        assertTrue(UpdateChecker.compareVersions("1.0", "1.0.1") < 0);
    }

    @Test
    void preReleaseTagsSortLexically() {
        // 1.1.0-alpha < 1.1.0-beta — lexical comparison of non-numeric components
        assertTrue(UpdateChecker.compareVersions("1.1.0-alpha", "1.1.0-beta") < 0);
    }

    @Test
    void nullHandling() {
        assertTrue(UpdateChecker.compareVersions(null, "1.0.0") < 0);
        assertTrue(UpdateChecker.compareVersions("1.0.0", null) > 0);
        assertEquals(0, UpdateChecker.compareVersions(null, null));
    }
}
