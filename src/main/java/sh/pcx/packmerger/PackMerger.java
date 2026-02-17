package sh.pcx.packmerger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import sh.pcx.packmerger.commands.PackMergerCommand;
import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.distribution.PackDistributor;
import sh.pcx.packmerger.distribution.PlayerCacheManager;
import sh.pcx.packmerger.listeners.PlayerJoinListener;
import sh.pcx.packmerger.merge.FileWatcher;
import sh.pcx.packmerger.merge.PackMergeEngine;
import sh.pcx.packmerger.merge.PackValidator;
import sh.pcx.packmerger.upload.S3UploadProvider;
import sh.pcx.packmerger.upload.SFTPUploadProvider;
import sh.pcx.packmerger.upload.SelfHostProvider;
import sh.pcx.packmerger.upload.UploadProvider;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Main plugin class for PackMerger — a Paper plugin that merges multiple Minecraft resource
 * packs into a single pack and distributes it to players.
 *
 * <p>This class acts as the central coordinator for the plugin's lifecycle. On enable, it
 * initializes all subsystems in order: configuration, directory structure, merge engine,
 * validator, player cache, distributor, upload provider, commands, listeners, and the
 * file watcher. On disable, it tears them down in reverse.</p>
 *
 * <p>The core workflow is: merge packs &rarr; validate &rarr; compute SHA-1 hash &rarr;
 * upload via the configured provider &rarr; distribute to online players. This entire
 * pipeline is orchestrated by {@link #mergeAndUpload(org.bukkit.command.CommandSender)}.</p>
 *
 * @see PackMergeEngine
 * @see PackDistributor
 * @see UploadProvider
 * @see ConfigManager
 */
public class PackMerger extends JavaPlugin {

    /** Manages loading and access to all plugin configuration values. */
    private ConfigManager configManager;

    /** Performs the actual merge of resource packs from the packs folder. */
    private PackMergeEngine mergeEngine;

    /** Validates merged pack output for structural correctness. */
    private PackValidator validator;

    /** Watches the packs folder for file changes to trigger hot-reload merges. */
    private FileWatcher fileWatcher;

    /** The active upload provider (S3, SFTP, or self-host) used to serve the merged pack. */
    private UploadProvider uploadProvider;

    /** Handles sending the resource pack to individual players or all online players. */
    private PackDistributor distributor;

    /** Tracks which pack version each player has downloaded to avoid redundant re-sends. */
    private PlayerCacheManager cacheManager;

    /** The public download URL of the most recently uploaded merged pack, or {@code null} if no merge has completed. */
    private String currentPackUrl;

    /** The raw SHA-1 hash bytes of the current merged pack, used by Minecraft's resource pack protocol. */
    private byte[] currentPackHash;

    /** Hex-encoded SHA-1 hash of the current merged pack, used for cache comparisons and logging. */
    private String currentPackHashHex;

    /** Timestamp of the last successful merge, displayed in the status command. */
    private LocalDateTime lastMergeTime;

    /**
     * Guard flag that prevents concurrent merges. Uses {@link AtomicBoolean} so that
     * the file watcher thread and command thread cannot trigger overlapping merges.
     */
    private final AtomicBoolean merging = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        // Initialize config first — all other components depend on it
        configManager = new ConfigManager(this);
        configManager.load();

        // Ensure the plugin's working directories exist before any component tries to use them
        getPacksFolder().mkdirs();
        getOutputFolder().mkdirs();
        getCacheFolder().mkdirs();

        // Initialize components in dependency order
        mergeEngine = new PackMergeEngine(this);
        validator = new PackValidator(this);
        cacheManager = new PlayerCacheManager(this);
        cacheManager.load();
        distributor = new PackDistributor(this);

        // Initialize upload provider (may start an HTTP server if using self-host)
        initUploadProvider();

        // Register Brigadier commands via Paper's lifecycle API
        new PackMergerCommand(this);

        // Register event listeners for player join and resource pack status tracking
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        // Periodically save the player cache to disk every 5 minutes (6000 ticks)
        // to prevent data loss on unclean shutdowns
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> cacheManager.save(), 6000L, 6000L);

        getLogger().info("PackMerger enabled!");

        // Kick off the initial merge if configured to do so
        if (configManager.isAutoMergeOnStartup()) {
            mergeAndUpload(null);
        }

        // Start watching for file changes in the packs folder (hot-reload)
        startFileWatcher();
    }

    @Override
    public void onDisable() {
        // Stop the file watcher thread first to prevent new merges from starting
        stopFileWatcher();

        // Shut down the built-in HTTP server if self-host is the active provider
        if (uploadProvider instanceof SelfHostProvider selfHost) {
            selfHost.stop();
        }

        // Persist the player cache so it survives restarts
        if (cacheManager != null) {
            cacheManager.save();
        }

        // Cancel any remaining scheduled tasks (cache save timer, pending join delays, etc.)
        Bukkit.getScheduler().cancelTasks(this);

        getLogger().info("PackMerger disabled!");
    }

    /**
     * Initializes (or re-initializes) the upload provider based on the current configuration.
     *
     * <p>If a {@link SelfHostProvider} was previously active, its HTTP server is stopped before
     * switching. If the configured provider name is unrecognized, falls back to self-host with
     * a console warning.</p>
     *
     * @see UploadProvider
     * @see S3UploadProvider
     * @see SFTPUploadProvider
     * @see SelfHostProvider
     */
    public void initUploadProvider() {
        // Stop existing self-host HTTP server if we're switching providers
        if (uploadProvider instanceof SelfHostProvider selfHost) {
            selfHost.stop();
        }

        String provider = configManager.getUploadProvider();
        uploadProvider = switch (provider.toLowerCase()) {
            case "s3" -> new S3UploadProvider(this);
            case "sftp" -> new SFTPUploadProvider(this);
            case "self-host" -> {
                SelfHostProvider selfHost = new SelfHostProvider(this);
                selfHost.start();
                yield selfHost;
            }
            default -> {
                getLogger().warning("Unknown upload provider: " + provider + ", defaulting to self-host");
                SelfHostProvider selfHost = new SelfHostProvider(this);
                selfHost.start();
                yield selfHost;
            }
        };
    }

    /**
     * Executes the full merge-validate-upload pipeline asynchronously.
     *
     * <p>Only one merge can run at a time — if a merge is already in progress, the caller
     * is notified and the request is ignored. The merge work runs off the main thread via
     * {@link CompletableFuture#runAsync}, but player-facing messages and distribution
     * callbacks are dispatched back to the main thread using {@code Bukkit.getScheduler().runTask}.</p>
     *
     * <p>Pipeline steps:</p>
     * <ol>
     *   <li>Merge all packs via {@link PackMergeEngine#merge()}</li>
     *   <li>Validate the output via {@link PackValidator#validate(File)}</li>
     *   <li>Compute SHA-1 hash for Minecraft's resource pack protocol</li>
     *   <li>Upload via the configured {@link UploadProvider} (if auto-upload is enabled)</li>
     *   <li>If the pack changed, notify online players via {@link PackDistributor#onNewPack()}</li>
     * </ol>
     *
     * @param sender the command sender to receive status messages, or {@code null} for automated merges
     *               (e.g. startup, hot-reload) where no feedback is needed
     * @return a future that completes when the entire pipeline finishes (including upload)
     */
    public CompletableFuture<Void> mergeAndUpload(org.bukkit.command.CommandSender sender) {
        // Atomically claim the merge lock — prevents concurrent merges from file watcher + commands
        if (!merging.compareAndSet(false, true)) {
            if (sender != null) {
                sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<red>A merge is already in progress!</red>"));
            }
            return CompletableFuture.completedFuture(null);
        }

        if (sender != null) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<yellow>Starting merge process...</yellow>"));
        }

        // Run the entire pipeline off the main thread to avoid blocking the server tick loop
        return CompletableFuture.runAsync(() -> {
            try {
                // Step 1: Merge all packs into a single output zip
                File outputFile = mergeEngine.merge();
                if (outputFile == null) {
                    if (sender != null) {
                        // Return to main thread for Bukkit API calls (sendMessage)
                        Bukkit.getScheduler().runTask(this, () ->
                                sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                        .deserialize("<red>Merge failed — no packs found or merge error.</red>")));
                    }
                    return;
                }

                lastMergeTime = LocalDateTime.now();

                // Step 2: Validate the merged pack for structural issues
                validator.validate(outputFile);

                // Step 3: Compute SHA-1 hash (required by Minecraft's resource pack protocol)
                byte[] hash = computeSha1(outputFile);
                String hashHex = bytesToHex(hash);

                // Determine if this merge produced a new pack (different hash than before)
                boolean isNewPack = currentPackHashHex == null || !currentPackHashHex.equals(hashHex);

                currentPackHash = hash;
                currentPackHashHex = hashHex;

                getLogger().info("Merged pack SHA1: " + hashHex);

                // Step 4: Upload the merged pack if auto-upload is enabled
                if (configManager.isAutoUpload()) {
                    try {
                        String url = uploadProvider.upload(outputFile, hashHex);
                        currentPackUrl = url;
                        getLogger().info("Pack uploaded successfully: " + url);

                        if (sender != null) {
                            // Return to main thread for player messaging
                            Bukkit.getScheduler().runTask(this, () ->
                                    sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                            .deserialize("<green>Merge and upload complete!</green> <gray>URL: " + url + "</gray>")));
                        }

                        // Step 5: If the pack actually changed, handle online players
                        if (isNewPack) {
                            // Must run on main thread — player API calls are not thread-safe
                            Bukkit.getScheduler().runTask(this, () -> distributor.onNewPack());
                        }
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Upload failed", e);
                        if (sender != null) {
                            Bukkit.getScheduler().runTask(this, () ->
                                    sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                            .deserialize("<red>Upload failed: " + e.getMessage() + "</red>")));
                        }
                    }
                } else {
                    if (sender != null) {
                        Bukkit.getScheduler().runTask(this, () ->
                                sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                        .deserialize("<green>Merge complete!</green> <gray>Auto-upload is disabled.</gray>")));
                    }
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Merge failed", e);
                if (sender != null) {
                    Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                    .deserialize("<red>Merge failed: " + e.getMessage() + "</red>")));
                }
            } finally {
                // Always release the merge lock, even on failure
                merging.set(false);
            }
        });
    }

    /**
     * Starts the file watcher for hot-reload functionality.
     *
     * <p>If hot-reload is disabled in config, this method is a no-op. If a watcher is
     * already running, it is stopped before a new one is started.</p>
     *
     * @see FileWatcher
     */
    public void startFileWatcher() {
        if (!configManager.isHotReloadEnabled()) return;
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        fileWatcher = new FileWatcher(this);
        fileWatcher.start();
    }

    /**
     * Stops the file watcher if one is currently active.
     *
     * @see FileWatcher
     */
    public void stopFileWatcher() {
        if (fileWatcher != null) {
            fileWatcher.stop();
            fileWatcher = null;
        }
    }

    /**
     * Performs a full reload: re-reads the config, re-initializes the upload provider,
     * and restarts the file watcher. Called by the {@code /packmerger reload} command.
     *
     * <p>Does not automatically trigger a merge — the calling command handles that separately.</p>
     */
    public void reload() {
        configManager.load();
        initUploadProvider();
        stopFileWatcher();
        startFileWatcher();
    }

    /**
     * Computes the SHA-1 hash of a file using buffered reads.
     *
     * <p>Minecraft's resource pack protocol requires the SHA-1 hash to verify pack integrity.
     * The client compares this hash against its cached version to decide whether to re-download.</p>
     *
     * @param file the file to hash
     * @return the raw SHA-1 hash bytes (20 bytes)
     * @throws Exception if the file cannot be read or SHA-1 is unavailable (should never happen
     *                   as SHA-1 is a mandatory JCA algorithm)
     */
    private byte[] computeSha1(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192]; // 8 KB buffer for efficient disk I/O
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    /**
     * Converts a byte array to its lowercase hexadecimal string representation.
     *
     * @param bytes the bytes to convert
     * @return the hex string (e.g. {@code "a1b2c3..."})
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Directory helpers — all paths are relative to the plugin's data folder
    // -------------------------------------------------------------------------

    /**
     * Returns the directory where source resource packs are placed.
     *
     * @return {@code plugins/PackMerger/packs/}
     */
    public File getPacksFolder() {
        return new File(getDataFolder(), "packs");
    }

    /**
     * Returns the directory where merged pack output is written.
     *
     * @return {@code plugins/PackMerger/output/}
     */
    public File getOutputFolder() {
        return new File(getDataFolder(), "output");
    }

    /**
     * Returns the directory where persistent cache files are stored.
     *
     * @return {@code plugins/PackMerger/cache/}
     */
    public File getCacheFolder() {
        return new File(getDataFolder(), "cache");
    }

    /**
     * Returns the output file path for the merged pack.
     *
     * <p>If the server has a per-server pack configuration and the server name is not
     * "default", the filename is prefixed with the server name (e.g.
     * {@code lobby-merged-pack.zip}). Otherwise, it is {@code merged-pack.zip}.</p>
     *
     * @return the {@link File} where the merged pack zip should be written
     */
    public File getOutputFile() {
        String serverName = configManager.getServerName();
        ConfigManager.ServerPackConfig serverConfig = configManager.getServerPackConfig();
        // Use a server-specific filename for multi-server setups
        if (serverConfig != null && !serverName.equalsIgnoreCase("default")) {
            return new File(getOutputFolder(), serverName + "-merged-pack.zip");
        }
        return new File(getOutputFolder(), "merged-pack.zip");
    }

    // -------------------------------------------------------------------------
    // Component accessors
    // -------------------------------------------------------------------------

    /** @return the plugin's configuration manager */
    public ConfigManager getConfigManager() { return configManager; }

    /** @return the merge engine responsible for combining resource packs */
    public PackMergeEngine getMergeEngine() { return mergeEngine; }

    /** @return the validator used to check merged pack integrity */
    public PackValidator getValidator() { return validator; }

    /** @return the distributor that sends packs to players */
    public PackDistributor getDistributor() { return distributor; }

    /** @return the cache manager tracking which players have the current pack */
    public PlayerCacheManager getCacheManager() { return cacheManager; }

    /** @return the active upload provider */
    public UploadProvider getUploadProvider() { return uploadProvider; }

    // -------------------------------------------------------------------------
    // Pack state accessors
    // -------------------------------------------------------------------------

    /** @return the public download URL of the current merged pack, or {@code null} if not yet merged/uploaded */
    public String getCurrentPackUrl() { return currentPackUrl; }

    /** @return the raw SHA-1 hash bytes of the current merged pack, or {@code null} if not yet merged */
    public byte[] getCurrentPackHash() { return currentPackHash; }

    /** @return the hex-encoded SHA-1 hash of the current merged pack, or {@code null} if not yet merged */
    public String getCurrentPackHashHex() { return currentPackHashHex; }

    /** @return the timestamp of the last successful merge, or {@code null} if no merge has occurred */
    public LocalDateTime getLastMergeTime() { return lastMergeTime; }

    /** @return {@code true} if a merge is currently in progress */
    public boolean isMerging() { return merging.get(); }

    /**
     * Returns the last merge time as a human-readable string for display in the status command.
     *
     * @return the formatted timestamp (e.g. {@code "2025-01-15 14:30:00"}), or {@code "Never"}
     *         if no merge has been performed yet
     */
    public String getFormattedLastMergeTime() {
        if (lastMergeTime == null) return "Never";
        return lastMergeTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
