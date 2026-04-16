package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.merge.JsonMerger;

import static org.junit.jupiter.api.Assertions.*;

class ModelMergeStrategyTest {

    private final ModelMergeStrategy strategy = new ModelMergeStrategy();

    @Test
    void matches_itemModelPath() {
        assertTrue(strategy.matches("assets/minecraft/models/item/iron_sword.json"));
        assertTrue(strategy.matches("assets/qualityarmory/models/item/sword/ak47.json"));
    }

    @Test
    void doesNotMatch_nonModelPaths() {
        assertFalse(strategy.matches("assets/minecraft/textures/item/iron_sword.png"));
        assertFalse(strategy.matches("assets/minecraft/blockstates/stone.json"));
        assertFalse(strategy.matches("assets/minecraft/sounds.json"));
    }

    /**
     * The QualityArmory regression: two packs each defining their own CustomModelData
     * overrides on the same vanilla item must end up with BOTH packs' overrides in
     * the merged output, not just the high-priority one's.
     */
    @Test
    void overridesFromBothPacks_survive() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "parent": "item/handheld",
                  "textures": { "layer0": "item/iron_sword" },
                  "overrides": [
                    { "predicate": { "custom_model_data": 500 }, "model": "custom:item/enchanted_look" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "parent": "item/handheld",
                  "textures": { "layer0": "item/iron_sword" },
                  "overrides": [
                    { "predicate": { "custom_model_data": 1001 }, "model": "qualityarmory:item/sword/quality_sword" },
                    { "predicate": { "custom_model_data": 1002 }, "model": "qualityarmory:item/sword/epic_sword" }
                  ]
                }""");

        JsonObject merged = strategy.merge(high, low);
        JsonArray overrides = merged.getAsJsonArray("overrides");

        assertEquals(3, overrides.size(), "all three overrides must survive");
        // High-priority entries come first
        assertEquals(500, overrides.get(0).getAsJsonObject()
                .getAsJsonObject("predicate").get("custom_model_data").getAsInt());
        assertEquals(1001, overrides.get(1).getAsJsonObject()
                .getAsJsonObject("predicate").get("custom_model_data").getAsInt());
        assertEquals(1002, overrides.get(2).getAsJsonObject()
                .getAsJsonObject("predicate").get("custom_model_data").getAsInt());
    }

    @Test
    void conflictingPredicate_highPriorityWins() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1001 }, "model": "high:model" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1001 }, "model": "low:model" },
                    { "predicate": { "custom_model_data": 1002 }, "model": "low:other" }
                  ]
                }""");

        JsonObject merged = strategy.merge(high, low);
        JsonArray overrides = merged.getAsJsonArray("overrides");

        assertEquals(2, overrides.size());
        assertEquals("high:model", overrides.get(0).getAsJsonObject().get("model").getAsString());
        assertEquals("low:other", overrides.get(1).getAsJsonObject().get("model").getAsString());
    }

    @Test
    void onlyOneSideHasOverrides_stillWorks() {
        JsonObject high = JsonMerger.parseJson("""
                { "parent": "item/handheld" }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1 }, "model": "foo:bar" }
                  ]
                }""");

        JsonObject merged = strategy.merge(high, low);
        assertEquals("item/handheld", merged.get("parent").getAsString());
        assertEquals(1, merged.getAsJsonArray("overrides").size());
    }

    @Test
    void predicateKeyOrder_doesNotAffectDedup() {
        JsonObject high = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "custom_model_data": 1, "damaged": 0 }, "model": "high" }
                  ]
                }""");
        JsonObject low = JsonMerger.parseJson("""
                {
                  "overrides": [
                    { "predicate": { "damaged": 0, "custom_model_data": 1 }, "model": "low" }
                  ]
                }""");

        JsonArray overrides = strategy.merge(high, low).getAsJsonArray("overrides");
        assertEquals(1, overrides.size(), "predicates with reordered keys must dedup");
        assertEquals("high", overrides.get(0).getAsJsonObject().get("model").getAsString());
    }
}
