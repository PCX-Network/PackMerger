package sh.pcx.packmerger.commands;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.MergeProvenance;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InspectRendererTest {

    private MergeProvenance twoPackFixture() {
        MergeProvenance.Builder b = new MergeProvenance.Builder();
        b.record("a.png", "low.zip", null, false);
        b.record("a.png", "high.zip", null, false);
        b.record("b.png", "low.zip", null, false);
        b.record("c.png", "high.zip", null, false);
        b.record("m.json", "low.zip", "model", false);
        b.record("m.json", "high.zip", "model", true);
        return b.build(List.of("high.zip", "low.zip"), Instant.parse("2026-04-16T20:00:00Z"));
    }

    @Test
    void summary_nullProvenance_returnsPlaceholder() {
        List<String> out = InspectRenderer.summary(null);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("No merge"));
    }

    @Test
    void summary_includesPackOrderAndCounts() {
        List<String> out = InspectRenderer.summary(twoPackFixture());
        String joined = String.join("\n", out);
        assertTrue(joined.contains("high.zip"), "must list high.zip");
        assertTrue(joined.contains("low.zip"), "must list low.zip");
        assertTrue(joined.contains("Total files:"), "must show total");
        assertTrue(joined.contains("collisions:"), "must show collision count");
    }

    @Test
    void packDetail_unknownPack_lists_knownPacks() {
        List<String> out = InspectRenderer.packDetail(twoPackFixture(), "nope.zip");
        String joined = String.join("\n", out);
        assertTrue(joined.contains("was not in the last merge"));
        assertTrue(joined.contains("high.zip"), "should list known packs");
        assertTrue(joined.contains("low.zip"));
    }

    @Test
    void packDetail_shows_won_and_overridden_counts() {
        // high.zip won a.png, c.png, m.json = 3
        // high.zip did not contribute to b.png, so nothing overridden
        List<String> out = InspectRenderer.packDetail(twoPackFixture(), "high.zip");
        String joined = String.join("\n", out);
        assertTrue(joined.contains("Won:"));
        assertTrue(joined.contains("a.png"));
        assertTrue(joined.contains("c.png"));
        assertTrue(joined.contains("m.json"));

        // low.zip won b.png = 1
        // low.zip was overridden on a.png and m.json = 2
        List<String> out2 = InspectRenderer.packDetail(twoPackFixture(), "low.zip");
        String joined2 = String.join("\n", out2);
        assertTrue(joined2.contains("b.png"));
        assertTrue(joined2.contains("overridden"));
        assertTrue(joined2.contains("a.png → high.zip") || joined2.contains("a.png"));
    }

    @Test
    void collisions_listsOnlyMultiContributorPaths() {
        List<String> out = InspectRenderer.collisions(twoPackFixture());
        String joined = String.join("\n", out);
        // a.png and m.json are collisions, b.png and c.png are not
        assertTrue(joined.contains("a.png"));
        assertTrue(joined.contains("m.json"));
        assertFalse(joined.contains("b.png"), "b.png is not a collision");
        assertFalse(joined.contains("c.png"), "c.png is not a collision");
    }

    @Test
    void collisions_noneFound_noticed() {
        MergeProvenance.Builder b = new MergeProvenance.Builder();
        b.record("solo.png", "only.zip", null, false);
        MergeProvenance prov = b.build(List.of("only.zip"), Instant.now());

        List<String> out = InspectRenderer.collisions(prov);
        String joined = String.join("\n", out);
        assertTrue(joined.contains("0 file(s)"));
    }

    @Test
    void fullReport_containsAllSections() {
        List<String> out = InspectRenderer.fullReport(twoPackFixture());
        String joined = String.join("\n", out);
        assertTrue(joined.contains("PackMerger merge report"));
        assertTrue(joined.contains("Pack order"));
        assertTrue(joined.contains("Files won per pack"));
        assertTrue(joined.contains("Files touched per pack"));
        assertTrue(joined.contains("Totals:"));
        assertTrue(joined.contains("All files"));
        // Full report doesn't use color tags — spot check
        assertFalse(joined.contains("<aqua>"), "full report must not contain color tags");
        assertFalse(joined.contains("<gray>"));
    }

    @Test
    void fullReport_listsEveryFile() {
        List<String> out = InspectRenderer.fullReport(twoPackFixture());
        String joined = String.join("\n", out);
        assertTrue(joined.contains("a.png"));
        assertTrue(joined.contains("b.png"));
        assertTrue(joined.contains("c.png"));
        assertTrue(joined.contains("m.json"));
    }
}
