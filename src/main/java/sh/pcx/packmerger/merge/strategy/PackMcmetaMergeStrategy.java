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
 * handled by a standard high-priority-wins deep merge. Two nested arrays get
 * special handling because a naive object-only deep merge silently drops one
 * side's entries:</p>
 *
 * <ul>
 *   <li>{@code overlays.entries} — packs like QualityArmory use Minecraft's
 *       native overlay system to ship version-specific assets and declare the
 *       overlay directories here. Concatenated and deduped by {@code directory}.</li>
 *   <li>{@code filter.block} — declares namespace/path pairs that should be
 *       hidden from the client. When two packs both filter vanilla assets,
 *       one side's filters would otherwise be lost. Concatenated and deduped
 *       by the {@code namespace + "|" + path} tuple.</li>
 * </ul>
 */
public class PackMcmetaMergeStrategy implements MergeStrategy {

    private static final String OVERLAYS_KEY = "overlays";
    private static final String ENTRIES_KEY = "entries";
    private static final String DIRECTORY_KEY = "directory";

    private static final String FILTER_KEY = "filter";
    private static final String BLOCK_KEY = "block";
    private static final String NAMESPACE_KEY = "namespace";
    private static final String PATH_KEY = "path";

    @Override
    public boolean matches(String path) {
        return path.equalsIgnoreCase("pack.mcmeta");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        JsonObject highWithout = copyWithout(high, OVERLAYS_KEY, FILTER_KEY);
        JsonObject lowWithout = copyWithout(low, OVERLAYS_KEY, FILTER_KEY);
        JsonObject result = JsonMerger.deepMergeObjects(highWithout, lowWithout);

        JsonObject mergedOverlays = mergeOverlays(high, low);
        if (mergedOverlays != null) {
            result.add(OVERLAYS_KEY, mergedOverlays);
        }

        JsonObject mergedFilter = mergeFilter(high, low);
        if (mergedFilter != null) {
            result.add(FILTER_KEY, mergedFilter);
        }

        return result;
    }

    @Override
    public String name() {
        return "pack_mcmeta";
    }

    private static JsonObject mergeOverlays(JsonObject high, JsonObject low) {
        JsonObject highOverlays = getObjectOrNull(high, OVERLAYS_KEY);
        JsonObject lowOverlays = getObjectOrNull(low, OVERLAYS_KEY);
        if (highOverlays == null && lowOverlays == null) return null;

        JsonObject highNoEntries = highOverlays == null ? new JsonObject() : copyWithout(highOverlays, ENTRIES_KEY);
        JsonObject lowNoEntries = lowOverlays == null ? new JsonObject() : copyWithout(lowOverlays, ENTRIES_KEY);
        JsonObject merged = JsonMerger.deepMergeObjects(highNoEntries, lowNoEntries);

        JsonArray highEntries = getArrayOrEmpty(highOverlays, ENTRIES_KEY);
        JsonArray lowEntries = getArrayOrEmpty(lowOverlays, ENTRIES_KEY);
        if (highEntries.size() > 0 || lowEntries.size() > 0) {
            JsonArray mergedEntries = JsonMerger.concatArraysWithDedup(highEntries, lowEntries, PackMcmetaMergeStrategy::entryIdentity);
            merged.add(ENTRIES_KEY, mergedEntries);
        }
        return merged;
    }

    private static JsonObject mergeFilter(JsonObject high, JsonObject low) {
        JsonObject highFilter = getObjectOrNull(high, FILTER_KEY);
        JsonObject lowFilter = getObjectOrNull(low, FILTER_KEY);
        if (highFilter == null && lowFilter == null) return null;

        JsonObject highNoBlock = highFilter == null ? new JsonObject() : copyWithout(highFilter, BLOCK_KEY);
        JsonObject lowNoBlock = lowFilter == null ? new JsonObject() : copyWithout(lowFilter, BLOCK_KEY);
        JsonObject merged = JsonMerger.deepMergeObjects(highNoBlock, lowNoBlock);

        JsonArray highBlock = getArrayOrEmpty(highFilter, BLOCK_KEY);
        JsonArray lowBlock = getArrayOrEmpty(lowFilter, BLOCK_KEY);
        if (highBlock.size() > 0 || lowBlock.size() > 0) {
            JsonArray mergedBlock = JsonMerger.concatArraysWithDedup(highBlock, lowBlock, PackMcmetaMergeStrategy::filterBlockIdentity);
            merged.add(BLOCK_KEY, mergedBlock);
        }
        return merged;
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

    /**
     * Dedup key for a filter.block entry, based on its {@code namespace + "|" + path}
     * tuple. Either side is optional in the pack.mcmeta schema (an entry with only
     * {@code path} filters all namespaces); entries with neither field fall back to
     * their raw string form to avoid silently merging unrelated malformed entries.
     */
    private static String filterBlockIdentity(JsonElement element) {
        if (!element.isJsonObject()) return element.toString();
        JsonObject obj = element.getAsJsonObject();
        String ns = stringOrNull(obj, NAMESPACE_KEY);
        String path = stringOrNull(obj, PATH_KEY);
        if (ns == null && path == null) return element.toString();
        return (ns == null ? "" : ns) + "|" + (path == null ? "" : path);
    }

    private static String stringOrNull(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
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

    private static JsonObject copyWithout(JsonObject source, String... excludedKeys) {
        JsonObject copy = new JsonObject();
        outer:
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            for (String excluded : excludedKeys) {
                if (entry.getKey().equals(excluded)) continue outer;
            }
            copy.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return copy;
    }
}
