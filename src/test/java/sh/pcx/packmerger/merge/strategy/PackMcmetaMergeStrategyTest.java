package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class PackMcmetaMergeStrategyTest {

    private final PackMcmetaMergeStrategy strategy = new PackMcmetaMergeStrategy();

    @Test
    void matches_rootMcmetaOnly() {
        assertTrue(strategy.matches("pack.mcmeta"));
        assertTrue(strategy.matches("Pack.McMeta"));
        assertFalse(strategy.matches("assets/minecraft/models/iron_sword.json"));
        assertFalse(strategy.matches("1_21_6/pack.mcmeta"));
        assertFalse(strategy.matches("foo/pack.mcmeta"));
    }

    @Test
    void overlays_entries_concat_fromBothPacks() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46, "description": "main" },
                  "overlays": {
                    "entries": [
                      { "directory": "legacy", "formats": { "min_inclusive": 15, "max_inclusive": 45 } }
                    ]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46, "description": "qa" },
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_2", "formats": { "min_inclusive": 36, "max_inclusive": 45 } },
                      { "directory": "1_21_5", "formats": 55 },
                      { "directory": "1_21_6", "formats": [63, 63] }
                    ]
                  }
                }""");

        JsonArray entries = strategy.merge(high, low)
                .getAsJsonObject("overlays")
                .getAsJsonArray("entries");

        assertEquals(4, entries.size());
        assertEquals("legacy", entries.get(0).getAsJsonObject().get("directory").getAsString());
        assertEquals("1_21_2", entries.get(1).getAsJsonObject().get("directory").getAsString());
        assertEquals("1_21_5", entries.get(2).getAsJsonObject().get("directory").getAsString());
        assertEquals("1_21_6", entries.get(3).getAsJsonObject().get("directory").getAsString());
    }

    @Test
    void overlays_entries_dedup_highWinsOnDirectoryCollision() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_6", "formats": 99 }
                    ]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_6", "formats": 63 },
                      { "directory": "1_21_5", "formats": 55 }
                    ]
                  }
                }""");

        JsonArray entries = strategy.merge(high, low)
                .getAsJsonObject("overlays")
                .getAsJsonArray("entries");

        assertEquals(2, entries.size());
        JsonObject first = entries.get(0).getAsJsonObject();
        assertEquals("1_21_6", first.get("directory").getAsString());
        assertEquals(99, first.get("formats").getAsInt());
        assertEquals("1_21_5", entries.get(1).getAsJsonObject().get("directory").getAsString());
    }

    @Test
    void packBlock_highWins_onConflictingFormat() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46, "description": "main" }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 22, "description": "qa", "supported_formats": [22, 46] }
                }""");

        JsonObject merged = strategy.merge(high, low).getAsJsonObject("pack");
        assertEquals(46, merged.get("pack_format").getAsInt());
        assertEquals("main", merged.get("description").getAsString());
        assertTrue(merged.has("supported_formats"));
    }

    @Test
    void overlays_onlyOnOneSide_survives() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46 }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "pack": { "pack_format": 46 },
                  "overlays": {
                    "entries": [
                      { "directory": "1_21_6", "formats": 63 }
                    ]
                  }
                }""");

        JsonObject merged = strategy.merge(high, low);
        assertTrue(merged.has("overlays"));
        JsonArray entries = merged.getAsJsonObject("overlays").getAsJsonArray("entries");
        assertEquals(1, entries.size());
        assertEquals("1_21_6", entries.get(0).getAsJsonObject().get("directory").getAsString());
    }

    @Test
    void noOverlays_onEitherSide_noOverlaysInResult() {
        JsonObject high = JsonMerger.parseJson("""
                { "pack": { "pack_format": 46, "description": "main" } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "pack": { "pack_format": 46, "description": "other" } }""");

        JsonObject merged = strategy.merge(high, low);
        assertFalse(merged.has("overlays"));
        assertEquals("main", merged.getAsJsonObject("pack").get("description").getAsString());
    }
}
