package sh.pcx.packmerger.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired asynchronously after the merged Java pack has been converted to a Bedrock
 * pack (and Geyser custom-item mappings) for cross-platform players.
 *
 * <p>Only fires when {@code bedrock.enabled} is set and the conversion produced
 * output. Use it for "deploy the .mcpack somewhere" style integrations beyond the
 * built-in Geyser auto-deploy.</p>
 */
public class PackBedrockConvertedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String mcpackPath;
    private final String geyserMappingsPath;
    private final int itemsConverted;

    public PackBedrockConvertedEvent(String mcpackPath, String geyserMappingsPath, int itemsConverted) {
        super(true);
        this.mcpackPath = mcpackPath;
        this.geyserMappingsPath = geyserMappingsPath;
        this.itemsConverted = itemsConverted;
    }

    /** @return absolute path of the generated {@code .mcpack} */
    public String getMcpackPath() { return mcpackPath; }

    /** @return absolute path of the generated Geyser custom-item mappings JSON */
    public String getGeyserMappingsPath() { return geyserMappingsPath; }

    /** @return number of custom-model-data entries converted */
    public int getItemsConverted() { return itemsConverted; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
