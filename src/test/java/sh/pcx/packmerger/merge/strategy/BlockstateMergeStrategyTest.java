package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class BlockstateMergeStrategyTest {

    private final BlockstateMergeStrategy strategy = new BlockstateMergeStrategy();

    @Test
    void matches_blockstatePath() {
        assertTrue(strategy.matches("assets/minecraft/blockstates/stone.json"));
        assertFalse(strategy.matches("assets/minecraft/models/block/stone.json"));
    }

    @Test
    void variantsFromBothPacks_merge() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "variants": {
                    "facing=north": { "model": "high:north" }
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "variants": {
                    "facing=south": { "model": "low:south" }
                  }
                }""");

        JsonObject merged = strategy.merge(high, low);
        JsonObject variants = merged.getAsJsonObject("variants");
        assertTrue(variants.has("facing=north"));
        assertTrue(variants.has("facing=south"));
    }

    @Test
    void multipartCases_concatAndDedup() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "multipart": [
                    { "when": { "north": "true" }, "apply": { "model": "high:north" } }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "multipart": [
                    { "when": { "north": "true" }, "apply": { "model": "low:north" } },
                    { "when": { "south": "true" }, "apply": { "model": "low:south" } }
                  ]
                }""");

        JsonArray cases = strategy.merge(high, low).getAsJsonArray("multipart");
        assertEquals(2, cases.size());
        // High wins on duplicate "when"
        assertEquals("high:north", cases.get(0).getAsJsonObject()
                .getAsJsonObject("apply").get("model").getAsString());
        assertEquals("low:south", cases.get(1).getAsJsonObject()
                .getAsJsonObject("apply").get("model").getAsString());
    }
}
