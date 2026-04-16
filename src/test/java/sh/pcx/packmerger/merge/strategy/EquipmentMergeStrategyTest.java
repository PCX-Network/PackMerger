package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentMergeStrategyTest {

    private final EquipmentMergeStrategy strategy = new EquipmentMergeStrategy();

    @Test
    void matches_equipmentPath() {
        assertTrue(strategy.matches("assets/minecraft/equipment/iron.json"));
    }

    @Test
    void disjointSlots_merge() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "layers": {
                    "humanoid": [{ "texture": "high:helm" }]
                  }
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "layers": {
                    "humanoid_leggings": [{ "texture": "low:leg" }]
                  }
                }""");

        JsonObject layers = strategy.merge(high, low).getAsJsonObject("layers");
        assertTrue(layers.has("humanoid"));
        assertTrue(layers.has("humanoid_leggings"));
    }
}
