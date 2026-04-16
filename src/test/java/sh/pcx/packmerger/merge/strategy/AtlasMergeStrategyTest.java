package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class AtlasMergeStrategyTest {

    private final AtlasMergeStrategy strategy = new AtlasMergeStrategy();

    @Test
    void matches_atlasPath() {
        assertTrue(strategy.matches("assets/minecraft/atlases/blocks.json"));
    }

    @Test
    void sourcesFromBothPacks_concat() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "sources": [
                    { "type": "directory", "source": "minecraft:block", "prefix": "" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "sources": [
                    { "type": "directory", "source": "qualityarmory:block", "prefix": "" }
                  ]
                }""");

        JsonArray sources = strategy.merge(high, low).getAsJsonArray("sources");
        assertEquals(2, sources.size());
    }

    @Test
    void identicalSources_dedup() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "sources": [
                    { "type": "directory", "source": "minecraft:block", "prefix": "" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "sources": [
                    { "type": "directory", "source": "minecraft:block", "prefix": "" }
                  ]
                }""");

        JsonArray sources = strategy.merge(high, low).getAsJsonArray("sources");
        assertEquals(1, sources.size(), "structurally-identical sources must dedup");
    }
}
