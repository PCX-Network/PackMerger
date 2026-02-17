package sh.pcx.packmerger.distribution;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.config.ConfigManager;

import java.net.URI;
import java.util.UUID;

/**
 * Handles sending the merged resource pack to players and managing new-pack behavior.
 *
 * <p>The distributor uses Paper's Adventure API to send resource pack requests to players.
 * It integrates with the {@link PlayerCacheManager} to skip sending the pack to players
 * who already have the current version, avoiding unnecessary re-downloads on rejoin.</p>
 *
 * <p>When a new pack is produced (different SHA-1 hash), the distributor handles online
 * players according to the configured action: resend the pack immediately, send a
 * notification message, or do nothing.</p>
 *
 * <p>All methods in this class must be called from the main server thread, as they
 * interact with the Bukkit player API which is not thread-safe.</p>
 *
 * @see PlayerCacheManager
 * @see sh.pcx.packmerger.listeners.PlayerJoinListener
 * @see PackMerger#mergeAndUpload(org.bukkit.command.CommandSender)
 */
public class PackDistributor {

    /** Reference to the owning plugin for state access and logging. */
    private final PackMerger plugin;

    /** Shared MiniMessage instance for parsing MiniMessage-formatted text. */
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /**
     * Creates a new pack distributor.
     *
     * @param plugin the owning PackMerger plugin
     */
    public PackDistributor(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends the current merged resource pack to a single player.
     *
     * <p>This method performs several checks before sending:</p>
     * <ul>
     *   <li>Distribution must be enabled in config</li>
     *   <li>A merged pack must be available (URL and hash must be non-null)</li>
     *   <li>Unless {@code bypassCache} is true, the player's cache is checked to
     *       skip sending if they already have the current pack version</li>
     * </ul>
     *
     * <p>The pack UUID is deterministically derived from the SHA-1 hash using
     * {@link UUID#nameUUIDFromBytes}, ensuring the same pack always gets the same UUID.
     * This allows Minecraft's client-side caching to work correctly.</p>
     *
     * <p>The {@code use-add-resource-pack} config option controls whether the pack
     * is added alongside existing packs or replaces them. When false (default),
     * existing packs are cleared before sending.</p>
     *
     * @param player      the player to send the pack to; must be online
     * @param bypassCache if {@code true}, sends the pack even if the player's cache indicates
     *                    they already have it (used by the apply command and resend-on-new-pack)
     */
    public void sendPack(Player player, boolean bypassCache) {
        ConfigManager config = plugin.getConfigManager();

        if (!config.isDistributionEnabled()) return;

        String url = plugin.getCurrentPackUrl();
        byte[] hash = plugin.getCurrentPackHash();
        String hashHex = plugin.getCurrentPackHashHex();

        if (url == null || hash == null) {
            if (config.isDebug()) {
                plugin.getLogger().info("Skipping pack send for " + player.getName() + " — no merged pack available yet");
            }
            return;
        }

        // Check if the player already has the current pack (skip redundant downloads)
        if (!bypassCache && plugin.getCacheManager().hasCurrentPack(player.getUniqueId())) {
            if (config.isDebug()) {
                plugin.getLogger().info("Skipping pack send for " + player.getName() + " — already has current pack");
            }
            return;
        }

        try {
            // Generate a deterministic UUID from the SHA-1 hash so the same pack always
            // produces the same UUID, enabling Minecraft's client-side pack caching
            UUID packUuid = UUID.nameUUIDFromBytes(hash);

            // Parse the optional custom prompt message (MiniMessage format)
            net.kyori.adventure.text.Component prompt = null;
            String promptMsg = config.getPromptMessage();
            if (promptMsg != null && !promptMsg.isEmpty()) {
                prompt = miniMessage.deserialize(promptMsg);
            }

            // Build the resource pack info with URL, hash, and UUID
            ResourcePackInfo packInfo = ResourcePackInfo.resourcePackInfo()
                    .uri(URI.create(url))
                    .hash(hashHex)
                    .id(packUuid)
                    .build();

            ResourcePackRequest.Builder requestBuilder = ResourcePackRequest.resourcePackRequest()
                    .packs(packInfo)
                    .required(config.isRequired());

            if (prompt != null) {
                requestBuilder.prompt(prompt);
            }

            if (config.isUseAddResourcePack()) {
                // Add this pack alongside any existing packs the player has loaded
                player.sendResourcePacks(requestBuilder.build());
            } else {
                // Replace all existing packs with this one (default behavior)
                player.clearResourcePacks();
                player.sendResourcePacks(requestBuilder.build());
            }

            if (config.isDebug()) {
                plugin.getLogger().info("Sent resource pack to " + player.getName() + " (URL: " + url + ")");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send resource pack to " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Sends the current merged resource pack to all online players.
     *
     * @param bypassCache if {@code true}, sends to all players regardless of cache status
     */
    public void sendToAll(boolean bypassCache) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendPack(player, bypassCache);
        }
    }

    /**
     * Called when a new pack has been produced (different SHA-1 hash than before).
     *
     * <p>Performs the configured action for handling online players:</p>
     * <ul>
     *   <li><strong>"resend"</strong> — immediately sends the new pack to all online players,
     *       bypassing cache</li>
     *   <li><strong>"notify"</strong> — sends a chat message to all players with the
     *       {@code packmerger.notify} permission, informing them a new pack is available</li>
     *   <li><strong>"none"</strong> — does nothing; players will get the new pack on next join</li>
     * </ul>
     *
     * <p>Must be called from the main server thread.</p>
     */
    public void onNewPack() {
        ConfigManager config = plugin.getConfigManager();
        String action = config.getOnNewPackAction();

        switch (action.toLowerCase()) {
            case "resend" -> {
                plugin.getLogger().info("New pack detected, resending to all online players");
                sendToAll(true);
            }
            case "notify" -> {
                String message = config.getNotifyMessage();
                if (message != null && !message.isEmpty()) {
                    var component = miniMessage.deserialize(message);
                    // Only notify players with the packmerger.notify permission
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.hasPermission("packmerger.notify")) {
                            player.sendMessage(component);
                        }
                    }
                    plugin.getLogger().info("Notified online players about new resource pack");
                }
            }
            case "none" -> {
                if (config.isDebug()) {
                    plugin.getLogger().info("New pack detected, but on-new-pack action is 'none'");
                }
            }
            default -> plugin.getLogger().warning("Unknown on-new-pack action: " + action);
        }
    }
}
