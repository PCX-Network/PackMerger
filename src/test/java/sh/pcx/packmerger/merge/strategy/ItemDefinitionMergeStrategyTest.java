package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class ItemDefinitionMergeStrategyTest {

    private final ItemDefinitionMergeStrategy strategy = new ItemDefinitionMergeStrategy();

    @Test
    void matches_itemsPath() {
        assertTrue(strategy.matches("assets/minecraft/items/iron_sword.json"));
        assertFalse(strategy.matches("assets/minecraft/models/item/iron_sword.json"));
    }

    @Test
    void deepMerge_nonConflictingKeys() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "model": {
                    "type": "minecraft:model",
                    "model": "high:item/sword"
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "model": {
                    "type": "minecraft:model",
                    "tints": [{ "type": "minecraft:constant", "value": 16711680 }]
                  }
                }""");

        JsonObject merged = strategy.merge(high, low);
        JsonObject model = merged.getAsJsonObject("model");
        assertEquals("high:item/sword", model.get("model").getAsString());
        assertTrue(model.has("tints"));
    }
}
