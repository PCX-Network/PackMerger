package sh.pcx.packmerger.merge;

import com.google.gson.*;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared JSON merge primitives used by {@link sh.pcx.packmerger.merge.strategy.MergeStrategy}
 * implementations.
 *
 * <p>Higher-level format-specific behaviour (item-model {@code overrides} dedup, atlas
 * {@code sources} concat, sounds.json event merging) lives in the strategy classes
 * under {@code sh.pcx.packmerger.merge.strategy}. This class exposes only the generic
 * building blocks:</p>
 *
 * <ul>
 *   <li>{@link #parseJson} / {@link #toJson} — raw JSON I/O</li>
 *   <li>{@link #deepMergeObjects} — recursive object merge; arrays fall through to
 *       high-priority wins unless the caller handles them first</li>
 *   <li>{@link #concatArraysWithDedup} — generic array concat with caller-supplied
 *       identity function for dedup semantics</li>
 * </ul>
 */
public class JsonMerger {

    /** Private constructor to prevent instantiation of this utility class. */
    private JsonMerger() {}

    /**
     * Recursively deep-merges two JSON objects at the key level.
     *
     * <p>When both values for a key are JSON objects, the merge recurses. Otherwise the
     * high-priority value wins outright (including array-vs-array — callers that want
     * array concatenation must handle those keys before delegating here).</p>
     *
     * @param high the higher-priority object
     * @param low  the lower-priority object
     * @return a new merged object (inputs are not modified)
     */
    public static JsonObject deepMergeObjects(JsonObject high, JsonObject low) {
        JsonObject result = low.deepCopy();
        for (Map.Entry<String, JsonElement> entry : high.entrySet()) {
            String key = entry.getKey();
            JsonElement highValue = entry.getValue();
            if (result.has(key)) {
                JsonElement lowValue = result.get(key);
                if (highValue.isJsonObject() && lowValue.isJsonObject()) {
                    result.add(key, deepMergeObjects(highValue.getAsJsonObject(), lowValue.getAsJsonObject()));
                } else {
                    result.add(key, highValue.deepCopy());
                }
            } else {
                result.add(key, highValue.deepCopy());
            }
        }
        return result;
    }

    /**
     * Concatenates two JSON arrays with caller-defined deduplication.
     *
     * <p>High-priority entries come first so that when a duplicate (per {@code identity})
     * is encountered in the low-priority array it is dropped — effectively "high wins on
     * collision." If the identity function returns {@code null} for an element, that
     * element is treated as unique and always included.</p>
     *
     * @param high     higher-priority array (entries inserted first)
     * @param low      lower-priority array (entries appended after)
     * @param identity function producing a dedup key per element, or {@code null} to
     *                 skip dedup and concatenate blindly
     * @return a new {@link JsonArray} with both inputs combined
     */
    public static JsonArray concatArraysWithDedup(JsonArray high, JsonArray low, Function<JsonElement, String> identity) {
        JsonArray result = new JsonArray();
        Set<String> seen = identity == null ? null : new LinkedHashSet<>();

        for (JsonElement e : high) {
            result.add(e.deepCopy());
            if (seen != null) {
                String key = identity.apply(e);
                if (key != null) seen.add(key);
            }
        }
        for (JsonElement e : low) {
            if (seen != null) {
                String key = identity.apply(e);
                if (key != null && seen.contains(key)) continue;
            }
            result.add(e.deepCopy());
        }
        return result;
    }

    /**
     * Parses a JSON string into a {@link JsonObject}.
     *
     * @param json the JSON string to parse
     * @return the parsed object, or {@code null} if the string is invalid JSON or not an object
     */
    public static JsonObject parseJson(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            return null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * Serializes a {@link JsonObject} to a pretty-printed JSON string with HTML escaping
     * disabled so characters like {@code <} and {@code >} survive round-trip.
     *
     * @param obj the JSON object to serialize
     * @return the pretty-printed JSON string
     */
    public static String toJson(JsonObject obj) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(obj);
    }
}
