package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class LanguageMergeStrategyTest {

    private final LanguageMergeStrategy strategy = new LanguageMergeStrategy();
    private static final MergeContext CTX = new MergeContext("assets/minecraft/lang/en_us.json", null);

    @Test
    void matches_langJsonPaths() {
        assertTrue(strategy.matches("assets/minecraft/lang/en_us.json"));
        assertTrue(strategy.matches("assets/customplugin/lang/de_de.json"));
    }

    @Test
    void doesNotMatch_nonLangPaths() {
        assertFalse(strategy.matches("assets/minecraft/lang/en_us.lang"), "pre-1.13 .lang files are out of scope");
        assertFalse(strategy.matches("assets/minecraft/sounds.json"));
        assertFalse(strategy.matches("assets/minecraft/models/item/iron_sword.json"));
    }

    @Test
    void keyUnion_highWinsOnCollision() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "menu.singleplayer": "Solo Mode",
                  "menu.quit": "Exit"
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "menu.quit": "Quit Game",
                  "menu.options": "Options"
                }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertEquals("Solo Mode", merged.get("menu.singleplayer").getAsString());
        assertEquals("Exit", merged.get("menu.quit").getAsString(), "high wins on collision");
        assertEquals("Options", merged.get("menu.options").getAsString());
        assertEquals(3, merged.size());
    }

    @Test
    void emptyHigh_roundTripsLow() {
        JsonObject high = JsonMerger.parseJson("{}");
        JsonObject low = JsonMerger.parseJson("""
                { "only.low": "value" }""");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertEquals("value", merged.get("only.low").getAsString());
        assertEquals(1, merged.size());
    }

    @Test
    void emptyLow_roundTripsHigh() {
        JsonObject high = JsonMerger.parseJson("""
                { "only.high": "value" }""");
        JsonObject low = JsonMerger.parseJson("{}");

        JsonObject merged = strategy.merge(high, low, CTX);
        assertEquals("value", merged.get("only.high").getAsString());
        assertEquals(1, merged.size());
    }
}
