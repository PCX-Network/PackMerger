package sh.pcx.packmerger.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.scheduler.BukkitTask;
import sh.pcx.packmerger.PackMerger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for player join, quit, and resource pack status events to manage
 * resource pack distribution and cache tracking.
 *
 * <p>On join, schedules a delayed task to send the resource pack (configurable delay
 * to allow the player's client to finish connecting). On quit, cancels any pending
 * send task. On resource pack status changes, updates the player cache and logs
 * the outcome.</p>
 *
 * <p>The pending task map uses a {@link ConcurrentHashMap} because tasks can be
 * scheduled and cancelled from the main thread, but the map itself is accessed
 * from both event handlers and scheduled task callbacks.</p>
 *
 * @see sh.pcx.packmerger.distribution.PackDistributor
 * @see sh.pcx.packmerger.distribution.PlayerCacheManager
 */
public class PlayerJoinListener implements Listener {

    /** Reference to the owning plugin for component access and config. */
    private final PackMerger plugin;

    /**
     * Tracks pending pack-send tasks for players who have joined but haven't been
     * sent the pack yet (waiting for the join delay). Mapped by player UUID so
     * the task can be cancelled if the player disconnects before the delay expires.
     */
    private final Map<UUID, BukkitTask> pendingTasks = new ConcurrentHashMap<>();

    /**
     * Creates a new player join listener.
     *
     * @param plugin the owning PackMerger plugin
     */
    public PlayerJoinListener(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles player join events by scheduling a delayed resource pack send.
     *
     * <p>Uses {@link EventPriority#MONITOR} to run after all other plugins have
     * processed the join event, ensuring the player is fully initialized before
     * we interact with them. The delay (configurable via {@code join-delay-ticks})
     * gives the client time to finish loading the world before receiving the
     * resource pack prompt.</p>
     *
     * @param event the player join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isDistributionEnabled()) return;

        Player player = event.getPlayer();
        int delay = plugin.getConfigManager().getJoinDelayTicks();

        // Schedule the pack send after the configured delay
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingTasks.remove(player.getUniqueId());
            // Verify the player is still online (they may have disconnected during the delay)
            if (player.isOnline()) {
                plugin.getDistributor().sendPack(player, false);
            }
        }, delay);

        // Track the task so it can be cancelled if the player disconnects
        pendingTasks.put(player.getUniqueId(), task);
    }

    /**
     * Handles resource pack status updates from the client.
     *
     * <p>When a player successfully loads the pack, their cache entry is updated so
     * they won't be re-sent the same pack on next join. Other statuses are logged
     * for operator visibility.</p>
     *
     * @param event the resource pack status event
     */
    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        var status = event.getStatus();

        switch (status) {
            case SUCCESSFULLY_LOADED -> {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info(player.getName() + " successfully loaded the resource pack");
                }
                // Update the player cache with the current pack hash so they skip
                // the download on next join
                String currentHash = plugin.getCurrentPackHashHex();
                if (currentHash != null) {
                    plugin.getCacheManager().updateCache(player.getUniqueId(), currentHash);
                }
            }
            case DECLINED -> {
                plugin.getLogger().info(player.getName() + " declined the resource pack");
            }
            case FAILED_DOWNLOAD -> {
                plugin.getLogger().warning(player.getName() + " failed to download the resource pack");
            }
            case FAILED_RELOAD -> {
                plugin.getLogger().warning(player.getName() + " failed to reload the resource pack");
            }
            case ACCEPTED -> {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info(player.getName() + " accepted the resource pack");
                }
            }
            case DOWNLOADED -> {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info(player.getName() + " downloaded the resource pack");
                }
            }
            default -> {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info(player.getName() + " resource pack status: " + status);
                }
            }
        }
    }

    /**
     * Handles player quit events by cancelling any pending pack-send task.
     *
     * <p>If a player disconnects before the join delay expires, the scheduled pack
     * send task is cancelled to avoid sending to a disconnected player.</p>
     *
     * @param event the player quit event
     */
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        BukkitTask task = pendingTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }
}
