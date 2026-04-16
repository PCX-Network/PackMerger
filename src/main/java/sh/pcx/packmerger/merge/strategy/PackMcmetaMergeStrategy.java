package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.Map;

/**
 * Merges the root {@code pack.mcmeta} file.
 *
 * <p>Most of the file is a plain metadata object (the {@code pack} block with
 * {@code pack_format}, {@code description}, {@code supported_formats}) and is
 * handled by a standard high-priority-wins deep merge. The critical case this
 * strategy exists for is the {@code overlays.entries} array — packs like
 * QualityArmory use Minecraft's native overlay system to ship version-specific
 * assets (e.g. {@code 1_21_6/assets/...}) and declare the overlay directories
 * here. A naive last-write-wins on {@code pack.mcmeta} silently drops those
 * declarations when another pack's mcmeta has higher priority, leaving the
 * overlay directories inert in the output zip.</p>
 *
 * <p>This strategy concatenates {@code overlays.entries} from all packs and
 * dedups by {@code directory} so each overlay declaration survives the merge,
 * with higher-priority entries winning on directory collision.</p>
 */
public class PackMcmetaMergeStrategy implements MergeStrategy {

    private static final String OVERLAYS_KEY = "overlays";
    private static final String ENTRIES_KEY = "entries";
    private static final String DIRECTORY_KEY = "directory";

    @Override
    public boolean matches(String path) {
        return path.equalsIgnoreCase("pack.mcmeta");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low) {
        JsonObject highWithout = copyWithout(high, OVERLAYS_KEY);
        JsonObject lowWithout = copyWithout(low, OVERLAYS_KEY);
        JsonObject result = JsonMerger.deepMergeObjects(highWithout, lowWithout);

        JsonObject highOverlays = getObjectOrNull(high, OVERLAYS_KEY);
        JsonObject lowOverlays = getObjectOrNull(low, OVERLAYS_KEY);

        if (highOverlays == null && lowOverlays == null) {
            return result;
        }

        JsonObject highOverlaysNoEntries = highOverlays == null ? new JsonObject() : copyWithout(highOverlays, ENTRIES_KEY);
        JsonObject lowOverlaysNoEntries = lowOverlays == null ? new JsonObject() : copyWithout(lowOverlays, ENTRIES_KEY);
        JsonObject mergedOverlays = JsonMerger.deepMergeObjects(highOverlaysNoEntries, lowOverlaysNoEntries);

        JsonArray highEntries = getArrayOrEmpty(highOverlays, ENTRIES_KEY);
        JsonArray lowEntries = getArrayOrEmpty(lowOverlays, ENTRIES_KEY);
        if (highEntries.size() > 0 || lowEntries.size() > 0) {
            JsonArray mergedEntries = JsonMerger.concatArraysWithDedup(highEntries, lowEntries, PackMcmetaMergeStrategy::entryIdentity);
            mergedOverlays.add(ENTRIES_KEY, mergedEntries);
        }

        result.add(OVERLAYS_KEY, mergedOverlays);
        return result;
    }

    @Override
    public String name() {
        return "pack_mcmeta";
    }

    /**
     * Dedup key for an overlay entry, based on its {@code directory}. Entries
     * without a usable directory fall back to their full string form so malformed
     * or unrecognized entries are never silently merged together.
     */
    private static String entryIdentity(JsonElement element) {
        if (!element.isJsonObject()) return element.toString();
        JsonObject obj = element.getAsJsonObject();
        if (obj.has(DIRECTORY_KEY) && obj.get(DIRECTORY_KEY).isJsonPrimitive()) {
            return obj.get(DIRECTORY_KEY).getAsString();
        }
        return element.toString();
    }

    private static JsonObject getObjectOrNull(JsonObject source, String key) {
        if (source == null || !source.has(key)) return null;
        JsonElement element = source.get(key);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray getArrayOrEmpty(JsonObject source, String key) {
        if (source == null || !source.has(key)) return new JsonArray();
        JsonElement element = source.get(key);
        return element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
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
