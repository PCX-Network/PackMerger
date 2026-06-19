package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class FontMergeStrategyTest {

    private final FontMergeStrategy strategy = new FontMergeStrategy();
    private static final MergeContext CTX = new MergeContext("assets/minecraft/font/default.json", null);

    @Test
    void matches_fontPath() {
        assertTrue(strategy.matches("assets/minecraft/font/default.json"));
    }

    @Test
    void providers_concat_highPriorityFirst() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "providers": [
                    { "type": "bitmap", "file": "high:icon.png", "chars": ["\uE000"] }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "providers": [
                    { "type": "bitmap", "file": "low:icon.png", "chars": ["\uE001"] }
                  ]
                }""");

        JsonArray providers = strategy.merge(high, low, CTX).getAsJsonArray("providers");
        assertEquals(2, providers.size());
        assertEquals("high:icon.png", providers.get(0).getAsJsonObject().get("file").getAsString());
    }
}
