package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class SoundsMergeStrategyTest {

    private final SoundsMergeStrategy strategy = new SoundsMergeStrategy();
    private static final MergeContext CTX = new MergeContext("assets/minecraft/sounds.json", null);

    @Test
    void matches_soundsJson() {
        assertTrue(strategy.matches("assets/minecraft/sounds.json"));
        assertFalse(strategy.matches("assets/minecraft/sounds/foo.ogg"));
    }

    @Test
    void sameEvent_soundArraysConcat() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "entity.player.hurt": {
                    "sounds": ["high:hurt1"]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "entity.player.hurt": {
                    "sounds": ["low:hurt1", "low:hurt2"]
                  }
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        JsonArray sounds = merged.getAsJsonObject("entity.player.hurt").getAsJsonArray("sounds");
        assertEquals(3, sounds.size());
        assertEquals("high:hurt1", sounds.get(0).getAsString(), "high-priority sounds first");
    }

    @Test
    void disjointEvents_bothPreserved() {
        JsonObject high = JsonMerger.parseJson("""
                { "a": { "sounds": ["a1"] } }""");
        JsonObject low = JsonMerger.parseJson("""
                { "b": { "sounds": ["b1"] } }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertTrue(merged.has("a"));
        assertTrue(merged.has("b"));
    }

    @Test
    void subtitle_highWins() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "entity.player.hurt": {
                    "subtitle": "high.subtitle",
                    "sounds": ["high:hurt"]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "entity.player.hurt": {
                    "subtitle": "low.subtitle",
                    "sounds": ["low:hurt"]
                  }
                }""");

        JsonObject event = strategy.merge(high, low, CTX).getAsJsonObject("entity.player.hurt");
        assertEquals("high.subtitle", event.get("subtitle").getAsString());
        assertEquals(2, event.getAsJsonArray("sounds").size());
    }
}
