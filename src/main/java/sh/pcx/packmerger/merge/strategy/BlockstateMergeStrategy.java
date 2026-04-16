package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

import java.util.Map;

/**
 * Merges blockstate JSON files under {@code assets/<namespace>/blockstates/}.
 *
 * <p>Blockstates come in two shapes that both benefit from intelligent merging:</p>
 *
 * <ul>
 *   <li><strong>{@code variants}</strong> — an object mapping variant selectors
 *       (e.g. {@code "facing=north,half=top"}) to model descriptors. Two packs that
 *       each add distinct variants to the same block should both survive; when both
 *       define the same selector, high-priority wins. {@link JsonMerger#deepMergeObjects}
 *       does this correctly for free.</li>
 *   <li><strong>{@code multipart}</strong> — an array of cases (each with {@code when}
 *       and {@code apply}). A naive merge would let one pack's multipart array replace
 *       the other; we concat and dedup by the {@code when} clause so additions survive.</li>
 * </ul>
 */
public class BlockstateMergeStrategy implements MergeStrategy {

    private static final String MULTIPART_KEY = "multipart";
    private static final String WHEN_KEY = "when";

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches("assets/[^/]+/blockstates/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low) {
        boolean highHasMultipart = high.has(MULTIPART_KEY) && high.get(MULTIPART_KEY).isJsonArray();
        boolean lowHasMultipart = low.has(MULTIPART_KEY) && low.get(MULTIPART_KEY).isJsonArray();

        if (!highHasMultipart && !lowHasMultipart) {
            return JsonMerger.deepMergeObjects(high, low);
        }

        JsonObject highWithout = copyWithout(high, MULTIPART_KEY);
        JsonObject lowWithout = copyWithout(low, MULTIPART_KEY);
        JsonObject result = JsonMerger.deepMergeObjects(highWithout, lowWithout);

        JsonArray highArr = highHasMultipart ? high.getAsJsonArray(MULTIPART_KEY) : new JsonArray();
        JsonArray lowArr = lowHasMultipart ? low.getAsJsonArray(MULTIPART_KEY) : new JsonArray();
        JsonArray merged = JsonMerger.concatArraysWithDedup(highArr, lowArr, BlockstateMergeStrategy::multipartIdentity);
        result.add(MULTIPART_KEY, merged);
        return result;
    }

    @Override
    public String name() {
        return "blockstate";
    }

    /**
     * Dedup key for a multipart case: the stringified {@code when} clause.
     * Cases without a {@code when} (unconditional apply) are treated as unique.
     */
    private static String multipartIdentity(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has(WHEN_KEY)) return null;
        return obj.get(WHEN_KEY).toString();
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
