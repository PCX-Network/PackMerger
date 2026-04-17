package sh.pcx.packmerger.listeners;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;

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
    private final PackMergerBootstrap plugin;

    /** Colored console logger. */
    private final PluginLogger logger;

    /**
     * Tracks pending pack-send tasks for players who have joined but haven't been
     * sent the pack yet (waiting for the join delay). Mapped by player UUID so
     * the task can be cancelled if the player disconnects before the delay expires.
     */
    private final Map<UUID, ScheduledTask> pendingTasks = new ConcurrentHashMap<>();

    /**
     * Creates a new player join listener.
     *
     * @param plugin the owning PackMergerBootstrap plugin
     */
    public PlayerJoinListener(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
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
        Player player = event.getPlayer();

        // Notify admins about available PackMerger updates. Runs before the
        // distribution short-circuit so this fires even if pack distribution
        // is disabled on the server.
        if (plugin.getUpdateChecker() != null && player.hasPermission("packmerger.admin")) {
            String msg = plugin.getUpdateChecker().updateMessageOrNull();
            if (msg != null) {
                player.sendMessage(net.kyori.adventure.text.Component.text(msg));
            }
        }

        if (!plugin.getConfigManager().isDistributionEnabled()) return;

        long delay = plugin.getConfigManager().getJoinDelayTicks();

        // Schedule the pack send on the PLAYER's region thread — Folia-safe and
        // Paper-compatible. The entity scheduler automatically retires the task
        // if the player disconnects before the delay fires, but we still clean
        // up pendingTasks on quit for belt-and-braces.
        ScheduledTask task = player.getScheduler().runDelayed(plugin.getLoader(),
                scheduled -> {
                    pendingTasks.remove(player.getUniqueId());
                    if (player.isOnline()) {
                        plugin.getDistributor().sendPack(player, false);
                    }
                },
                () -> pendingTasks.remove(player.getUniqueId()),
                Math.max(1, delay));

        if (task != null) pendingTasks.put(player.getUniqueId(), task);
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
                logger.debug(player.getName() + " successfully loaded the resource pack");
                // Update the player cache with the current pack hash so they skip
                // the download on next join
                String currentHash = plugin.getCurrentPackHashHex();
                if (currentHash != null) {
                    plugin.getCacheManager().updateCache(player.getUniqueId(), currentHash);
                }
            }
            case DECLINED -> logger.info(player.getName() + " declined the resource pack");
            case FAILED_DOWNLOAD -> logger.warning(player.getName() + " failed to download the resource pack");
            case FAILED_RELOAD -> logger.warning(player.getName() + " failed to reload the resource pack");
            case ACCEPTED -> logger.debug(player.getName() + " accepted the resource pack");
            case DOWNLOADED -> logger.debug(player.getName() + " downloaded the resource pack");
            default -> logger.debug(player.getName() + " resource pack status: " + status);
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
        ScheduledTask task = pendingTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }
}
