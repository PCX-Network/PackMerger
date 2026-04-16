package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.Map;

/**
 * Merges item/block model JSON files under {@code assets/<namespace>/models/}.
 *
 * <p>The critical case this strategy exists for is the {@code "overrides"} array on
 * vanilla item models. Plugins like QualityArmory add CustomModelData entries to
 * vanilla items (e.g. {@code iron_sword.json}) — if any other pack in the merge also
 * touches that file, a naive object-only deep merge silently drops one side's
 * overrides entirely. Here we concat the arrays and dedup by predicate so that both
 * packs' CustomModelData entries survive, with higher-priority predicates winning
 * on collision.</p>
 */
public class ModelMergeStrategy implements MergeStrategy {

    private static final String OVERRIDES_KEY = "overrides";
    private static final String PREDICATE_KEY = "predicate";

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches("assets/[^/]+/models/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low) {
        boolean highHasOverrides = high.has(OVERRIDES_KEY) && high.get(OVERRIDES_KEY).isJsonArray();
        boolean lowHasOverrides = low.has(OVERRIDES_KEY) && low.get(OVERRIDES_KEY).isJsonArray();

        if (!highHasOverrides && !lowHasOverrides) {
            return JsonMerger.deepMergeObjects(high, low);
        }

        JsonObject highWithoutOverrides = copyWithout(high, OVERRIDES_KEY);
        JsonObject lowWithoutOverrides = copyWithout(low, OVERRIDES_KEY);
        JsonObject result = JsonMerger.deepMergeObjects(highWithoutOverrides, lowWithoutOverrides);

        JsonArray highArr = highHasOverrides ? high.getAsJsonArray(OVERRIDES_KEY) : new JsonArray();
        JsonArray lowArr = lowHasOverrides ? low.getAsJsonArray(OVERRIDES_KEY) : new JsonArray();
        JsonArray merged = JsonMerger.concatArraysWithDedup(highArr, lowArr, ModelMergeStrategy::overrideIdentity);
        result.add(OVERRIDES_KEY, merged);
        return result;
    }

    @Override
    public String name() {
        return "model";
    }

    /**
     * Dedup key for an override entry, based on the canonical string form of its
     * {@code predicate} object. Two overrides with identical predicates are
     * considered duplicates (high priority wins). Overrides without a predicate
     * are treated as unique so they always survive.
     */
    private static String overrideIdentity(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has(PREDICATE_KEY)) return null;
        JsonElement predicate = obj.get(PREDICATE_KEY);
        if (!predicate.isJsonObject()) return predicate.toString();
        return canonicalPredicate(predicate.getAsJsonObject());
    }

    /**
     * Produces a canonical string form of a predicate object that is independent of
     * key order, so {@code {"custom_model_data": 1}} and {@code {"custom_model_data": 1}}
     * written with different whitespace/key orderings still compare equal.
     */
    private static String canonicalPredicate(JsonObject predicate) {
        StringBuilder sb = new StringBuilder("{");
        predicate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue().toString()).append(';'));
        sb.append('}');
        return sb.toString();
    }

    private static JsonObject copyWithout(JsonObject source, String excludedKey) {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!entry.getKey().equals(excludedKey)) {
                copy.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return copy;
    }
}
