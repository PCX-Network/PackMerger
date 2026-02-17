package sh.pcx.packmerger.merge;

import com.google.gson.*;

import java.util.Map;

/**
 * Utility class providing JSON deep merge operations for Minecraft resource pack files.
 *
 * <p>Standard resource pack files like textures use simple overwrite semantics (higher
 * priority wins), but certain JSON files benefit from intelligent merging:</p>
 *
 * <ul>
 *   <li><strong>Model/blockstate JSON</strong> — deep merged so that non-conflicting
 *       keys from multiple packs are preserved (e.g. two packs defining different
 *       item model overrides)</li>
 *   <li><strong>sounds.json</strong> — sound event arrays are concatenated so that
 *       sounds from multiple packs coexist under the same event</li>
 * </ul>
 *
 * <p>This class is stateless and uses only static methods. It is called by
 * {@link PackMergeEngine#merge()} during the merge process.</p>
 *
 * @see PackMergeEngine
 */
public class JsonMerger {

    /** Private constructor to prevent instantiation of this utility class. */
    private JsonMerger() {}

    /**
     * Deep merges two JSON objects, with the "high" priority object winning on conflicts.
     *
     * <p>The merge strategy is recursive:</p>
     * <ul>
     *   <li>If a key exists only in one object, it is included in the result</li>
     *   <li>If a key exists in both and both values are JSON objects, the values are
     *       recursively deep-merged</li>
     *   <li>If a key exists in both but values are not both objects (e.g. one is a
     *       primitive or array), the high-priority value wins</li>
     * </ul>
     *
     * <p>This is used for model and blockstate JSON where multiple packs may define
     * different properties on the same model, and we want to preserve all of them
     * unless they directly conflict.</p>
     *
     * @param high the higher-priority JSON object whose values take precedence on conflict
     * @param low  the lower-priority JSON object whose non-conflicting values are preserved
     * @return a new merged JSON object (neither input is modified)
     */
    public static JsonObject deepMerge(JsonObject high, JsonObject low) {
        // Start with a copy of the lower-priority object as the base
        JsonObject result = low.deepCopy();
        for (Map.Entry<String, JsonElement> entry : high.entrySet()) {
            String key = entry.getKey();
            JsonElement highValue = entry.getValue();
            if (result.has(key)) {
                JsonElement lowValue = result.get(key);
                // Both values are objects — recurse to merge nested structures
                if (highValue.isJsonObject() && lowValue.isJsonObject()) {
                    result.add(key, deepMerge(highValue.getAsJsonObject(), lowValue.getAsJsonObject()));
                } else {
                    // Conflicting types or primitives/arrays — high priority wins
                    result.add(key, highValue.deepCopy());
                }
            } else {
                // Key only exists in high — add it to the result
                result.add(key, highValue.deepCopy());
            }
        }
        return result;
    }

    /**
     * Merges two sounds.json objects, concatenating sound arrays for the same event.
     *
     * <p>Minecraft's sounds.json maps event names to objects containing a "sounds" array.
     * When two packs define sounds for the same event, we want to keep all sounds from
     * both packs rather than having one overwrite the other.</p>
     *
     * <p>For each sound event:</p>
     * <ul>
     *   <li>If both objects define a "sounds" array, the arrays are concatenated
     *       (high-priority sounds first, then low-priority)</li>
     *   <li>If only one defines "sounds", that array is used as-is</li>
     *   <li>Non-"sounds" properties (e.g. "replace", "subtitle") use the high-priority
     *       value</li>
     *   <li>Sound events that only exist in one object are included unchanged</li>
     * </ul>
     *
     * @param high the higher-priority sounds.json object
     * @param low  the lower-priority sounds.json object
     * @return a new merged sounds.json object
     */
    public static JsonObject mergeSoundsJson(JsonObject high, JsonObject low) {
        // Start with a copy of the lower-priority object as the base
        JsonObject result = low.deepCopy();
        for (Map.Entry<String, JsonElement> entry : high.entrySet()) {
            String soundEvent = entry.getKey();
            JsonElement highValue = entry.getValue();

            if (result.has(soundEvent) && highValue.isJsonObject() && result.get(soundEvent).isJsonObject()) {
                // Both packs define this sound event — merge their properties
                JsonObject highObj = highValue.getAsJsonObject();
                JsonObject lowObj = result.getAsJsonObject(soundEvent);
                JsonObject merged = lowObj.deepCopy();

                // Concatenate the "sounds" arrays from both packs
                if (highObj.has("sounds") && lowObj.has("sounds")) {
                    JsonArray mergedSounds = new JsonArray();
                    // High-priority sounds come first in the array
                    for (JsonElement e : highObj.getAsJsonArray("sounds")) {
                        mergedSounds.add(e.deepCopy());
                    }
                    // Then low-priority sounds
                    for (JsonElement e : lowObj.getAsJsonArray("sounds")) {
                        mergedSounds.add(e.deepCopy());
                    }
                    merged.add("sounds", mergedSounds);
                } else if (highObj.has("sounds")) {
                    // Only high has sounds — use them directly
                    merged.add("sounds", highObj.getAsJsonArray("sounds").deepCopy());
                }
                // If only low has sounds, they're already in the merged copy

                // Copy non-"sounds" properties from high (e.g. "replace", "subtitle")
                // High-priority values override low-priority for metadata properties
                for (Map.Entry<String, JsonElement> prop : highObj.entrySet()) {
                    if (!prop.getKey().equals("sounds")) {
                        merged.add(prop.getKey(), prop.getValue().deepCopy());
                    }
                }

                result.add(soundEvent, merged);
            } else {
                // Sound event only exists in high, or types don't match — use high's version
                result.add(soundEvent, highValue.deepCopy());
            }
        }
        return result;
    }

    /**
     * Parses a JSON string into a {@link JsonObject}.
     *
     * @param json the JSON string to parse
     * @return the parsed object, or {@code null} if the string is invalid JSON or not an object
     *         (e.g. a JSON array or primitive)
     */
    public static JsonObject parseJson(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            // Valid JSON but not an object (e.g. array or primitive) — not mergeable
            return null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * Serializes a {@link JsonObject} to a pretty-printed JSON string.
     *
     * <p>HTML escaping is disabled to preserve characters like {@code <} and {@code >}
     * that may appear in Minecraft text components.</p>
     *
     * @param obj the JSON object to serialize
     * @return the pretty-printed JSON string
     */
    public static String toJson(JsonObject obj) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(obj);
    }
}
