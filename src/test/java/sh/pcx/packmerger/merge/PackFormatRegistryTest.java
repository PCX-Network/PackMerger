package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackFormatRegistryTest {

    @Test
    void forMinecraftVersion_knownPrefixes_returnMappedFormat() {
        assertEquals(46, PackFormatRegistry.forMinecraftVersion("1.21.4"));
        assertEquals(55, PackFormatRegistry.forMinecraftVersion("1.21.5"));
        assertEquals(34, PackFormatRegistry.forMinecraftVersion("1.21"));
        assertEquals(34, PackFormatRegistry.forMinecraftVersion("1.21.1"));
    }

    @Test
    void forMinecraftVersion_longestPrefixWins() {
        // 1.21.4 must match its specific entry (46), not 1.21's (34).
        assertEquals(46, PackFormatRegistry.forMinecraftVersion("1.21.4"));
        assertEquals(42, PackFormatRegistry.forMinecraftVersion("1.21.2"));
    }

    @Test
    void forMinecraftVersion_unknown_returnsNegativeOne() {
        assertEquals(-1, PackFormatRegistry.forMinecraftVersion("1.22.0"));
        assertEquals(-1, PackFormatRegistry.forMinecraftVersion("1.18.2"));
        assertEquals(-1, PackFormatRegistry.forMinecraftVersion(null));
    }

    @Test
    void classify_matchOnExactFormat() {
        assertEquals(PackFormatRegistry.Drift.MATCH,
                PackFormatRegistry.classify(46, null, "1.21.4"));
    }

    @Test
    void classify_minorDriftOneOff() {
        assertEquals(PackFormatRegistry.Drift.MINOR,
                PackFormatRegistry.classify(45, null, "1.21.4"));
        assertEquals(PackFormatRegistry.Drift.MINOR,
                PackFormatRegistry.classify(47, null, "1.21.4"));
    }

    @Test
    void classify_majorDriftLarge() {
        assertEquals(PackFormatRegistry.Drift.MAJOR,
                PackFormatRegistry.classify(34, null, "1.21.4")); // 12-wide gap
        assertEquals(PackFormatRegistry.Drift.MAJOR,
                PackFormatRegistry.classify(63, null, "1.21.4")); // 17-wide gap
    }

    @Test
    void classify_unknownServerVersion_returnsUnknown() {
        assertEquals(PackFormatRegistry.Drift.UNKNOWN,
                PackFormatRegistry.classify(46, null, "1.22.0"));
    }

    @Test
    void classify_supportedFormatsRangeCoversExpected_returnsMatch() {
        // Pack declares pack_format: 34 (old) but supported_formats: [34, 50]
        // spans the expected 46 → treat as MATCH.
        assertEquals(PackFormatRegistry.Drift.MATCH,
                PackFormatRegistry.classify(34, new int[]{34, 50}, "1.21.4"));
    }

    @Test
    void classify_supportedFormatsRangeMissesExpected_classifiesByPackFormat() {
        // Range [10, 30] does not include 46 → fall through to pack_format comparison.
        assertEquals(PackFormatRegistry.Drift.MAJOR,
                PackFormatRegistry.classify(20, new int[]{10, 30}, "1.21.4"));
    }
}
