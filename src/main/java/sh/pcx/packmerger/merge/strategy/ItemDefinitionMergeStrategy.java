package sh.pcx.packmerger.merge.strategy;

import com.google.gson.JsonObject;
import sh.pcx.packmerger.merge.JsonMerger;

/**
 * Merges 1.21.4+ item definition JSON files under {@code assets/<namespace>/items/}.
 *
 * <p>The new item-definition format (a {@code model} block with nested selectors and
 * conditions) is predominantly object-shaped, so a straight key-level deep merge is
 * the right default: two packs defining different components or fallback models on
 * the same item will be combined, with high-priority values winning on direct
 * conflicts. Ships as its own strategy so future Minecraft versions can add
 * format-specific array handling here without disturbing the generic merge path.</p>
 */
public class ItemDefinitionMergeStrategy implements MergeStrategy {

    @Override
    public boolean matches(String path) {
        return path.toLowerCase().matches("assets/[^/]+/items/.+\\.json");
    }

    @Override
    public JsonObject merge(JsonObject high, JsonObject low, MergeContext ctx) {
        return JsonMerger.deepMergeObjects(high, low);
    }

    @Override
    public String name() {
        return "item";
    }
}
