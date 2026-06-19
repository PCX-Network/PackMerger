package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>When two packs collide on the same predicate (e.g. both claim
 * {@code custom_model_data: 1000001} on {@code iron_sword}), the lower-priority
 * side is silently dropped by the dedup. A warning is logged via the merge
 * context so operators can diagnose "my knife turned into a pistol" bug reports
 * without digging through pack diffs.</p>
 */
public class ModelMergeStrategy implements MergeStrategy {

    private static final String OVERRIDES_KEY = "overrides";
    private static final String PREDICATE_KEY = "predicate";

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches("assets/[^/]+/models/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
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

        warnOnPredicateCollisions(highArr, lowArr, ctx);

        JsonArray merged = JsonMerger.concatArraysWithDedup(highArr, lowArr, ModelMergeStrategy::overrideIdentity);
        result.add(OVERRIDES_KEY, merged);
        return result;
    }

    @Override
    public String name() {
        return "model";
    }

    /**
     * Emits a warning for every predicate that appears in both the high-priority and
     * low-priority overrides arrays — the lower-priority entry will be silently
     * dropped by the subsequent dedup call, and operators typically discover this
     * only when the wrong model renders in-game.
     */
    private static void warnOnPredicateCollisions(JsonArray high, JsonArray low, MergeContext ctx) {
        if (ctx == null || high.size() == 0 || low.size() == 0) return;
        Set<String> lowIdentities = new HashSet<>();
        for (JsonElement el : low) {
            String id = overrideIdentity(el);
            if (id != null) lowIdentities.add(id);
        }
        if (lowIdentities.isEmpty()) return;

        for (JsonElement el : high) {
            String id = overrideIdentity(el);
            if (id == null || !lowIdentities.contains(id)) continue;
            String summary = predicateSummary(el);
            ctx.warn("CustomModelData collision in " + ctx.path()
                    + ": predicate " + summary
                    + " — higher-priority pack wins, lower-priority override dropped");
        }
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

    /**
     * Compact, human-readable form of an override's predicate for log output.
     * Falls back to the override's string form if no predicate is present.
     */
    private static String predicateSummary(JsonElement element) {
        if (!element.isJsonObject()) return element.toString();
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has(PREDICATE_KEY)) return obj.toString();
        return obj.get(PREDICATE_KEY).toString();
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
