package sh.pcx.packmerger.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import sh.pcx.packmerger.PackMerger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

/**
 * Manages loading and access to all plugin configuration values from {@code config.yml}.
 *
 * <p>All configuration is loaded eagerly into fields on {@link #load()}, so getter calls
 * are simple field accesses with no disk I/O. This class is re-entrant — calling
 * {@link #load()} again (e.g. during a reload) replaces all values atomically from
 * the main thread.</p>
 *
 * <p>If the {@code server-name} field is empty, the server name is auto-detected from
 * {@code server.properties} to support per-server pack configurations on multi-server
 * networks.</p>
 *
 * @see PackMerger#reload()
 */
public class ConfigManager {

    /** Reference to the owning plugin instance, used to access Bukkit config API. */
    private final PackMerger plugin;

    // -------------------------------------------------------------------------
    // General
    // -------------------------------------------------------------------------

    /** The server's identity, used for per-server pack configs. Auto-detected if empty. */
    private String serverName;

    // -------------------------------------------------------------------------
    // Priority
    // -------------------------------------------------------------------------

    /** Ordered list of pack filenames; first entry = highest merge priority. */
    private List<String> priority;

    // -------------------------------------------------------------------------
    // Server packs
    // -------------------------------------------------------------------------

    /** Per-server pack configurations, keyed by lowercase server name. */
    private final Map<String, ServerPackConfig> serverPacks = new HashMap<>();

    // -------------------------------------------------------------------------
    // Merge settings
    // -------------------------------------------------------------------------

    /** Whether to automatically trigger a merge when the plugin enables. */
    private boolean autoMergeOnStartup;

    /** Whether to strip junk/hidden files (e.g. .DS_Store, Thumbs.db) from the merged output. */
    private boolean stripJunkFiles;

    /** ZIP compression level (0-9). Higher = smaller file but slower compression. */
    private int compressionLevel;

    /** Threshold in MB above which a console warning is logged. 0 disables the warning. */
    private int sizeWarningMb;

    /** Whether the file watcher should monitor the packs folder for changes. */
    private boolean hotReloadEnabled;

    /** Seconds to wait after the last file change before triggering a hot-reload merge. */
    private int hotReloadDebounceSeconds;

    // -------------------------------------------------------------------------
    // Upload settings
    // -------------------------------------------------------------------------

    /** Whether to automatically upload after every merge. */
    private boolean autoUpload;

    /** The upload provider identifier: "polymath" or "self-host". */
    private String uploadProvider;

    // -------------------------------------------------------------------------
    // Self-host settings
    // -------------------------------------------------------------------------

    /** Port for the built-in HTTP server (default 8080). */
    private int selfHostPort;

    /** Public URL override for the self-hosted pack. Auto-detected if empty. */
    private String selfHostPublicUrl;

    /** Maximum concurrent downloads allowed by the self-host server. 0 = unlimited. */
    private int selfHostRateLimit;

    // -------------------------------------------------------------------------
    // Polymath settings
    // -------------------------------------------------------------------------

    /** Polymath server URL (e.g. "http://atlas.oraxen.com"). */
    private String polymathServer;

    /** Shared secret for Polymath upload authentication. */
    private String polymathSecret;

    /** Unique ID for this server's pack on the Polymath instance. Empty = use server name. */
    private String polymathId;

    // -------------------------------------------------------------------------
    // Distribution settings
    // -------------------------------------------------------------------------

    /** Whether to send the resource pack to players on join. */
    private boolean distributionEnabled;

    /** Whether the resource pack is required (players who decline are kicked). */
    private boolean required;

    /** Custom prompt message shown to players (MiniMessage format). Empty = Minecraft default. */
    private String promptMessage;

    /** If {@code true}, uses {@code addResourcePack} (1.20.3+) instead of replacing all packs. */
    private boolean useAddResourcePack;

    /** Delay in server ticks before sending the pack after a player joins. */
    private int joinDelayTicks;

    /** Whether to track which players already have the current pack to skip redundant sends. */
    private boolean cacheEnabled;

    /**
     * Action to take when a new pack is produced while players are online.
     * Valid values: "none", "notify", "resend".
     */
    private String onNewPackAction;

    /** MiniMessage-formatted notification sent when {@code onNewPackAction} is "notify". */
    private String notifyMessage;

    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------

    /** Enables verbose debug logging throughout the plugin. */
    private boolean debug;

    /**
     * Creates a new configuration manager bound to the given plugin instance.
     *
     * @param plugin the owning PackMerger plugin
     */
    public ConfigManager(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads (or reloads) all configuration values from {@code config.yml}.
     *
     * <p>Saves the default config first if the file doesn't exist, then reads every
     * field into memory. This method should only be called from the main thread.</p>
     */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        // Server name — auto-detect from server.properties if not explicitly set
        serverName = config.getString("server-name", "");
        if (serverName.isEmpty()) {
            serverName = readServerNameFromProperties();
        }

        // Priority list — determines merge order (first = highest priority)
        priority = config.getStringList("priority");

        // Per-server pack configurations
        serverPacks.clear();
        ConfigurationSection serverPacksSection = config.getConfigurationSection("server-packs");
        if (serverPacksSection != null) {
            for (String server : serverPacksSection.getKeys(false)) {
                ConfigurationSection section = serverPacksSection.getConfigurationSection(server);
                if (section != null) {
                    List<String> additional = section.getStringList("additional");
                    List<String> exclude = section.getStringList("exclude");
                    // Store with lowercase key for case-insensitive server name matching
                    serverPacks.put(server.toLowerCase(), new ServerPackConfig(additional, exclude));
                }
            }
        }

        // Merge settings
        autoMergeOnStartup = config.getBoolean("merge.auto-merge-on-startup", true);
        stripJunkFiles = config.getBoolean("merge.optimization.strip-junk-files", true);
        compressionLevel = config.getInt("merge.optimization.compression-level", 6);
        sizeWarningMb = config.getInt("merge.optimization.size-warning-mb", 100);
        hotReloadEnabled = config.getBoolean("merge.hot-reload.enabled", true);
        hotReloadDebounceSeconds = config.getInt("merge.hot-reload.debounce-seconds", 5);

        // Upload settings
        autoUpload = config.getBoolean("upload.auto-upload", true);
        uploadProvider = config.getString("upload.provider", "polymath");

        // Self-host settings
        selfHostPort = config.getInt("upload.self-host.port", 8080);
        selfHostPublicUrl = config.getString("upload.self-host.public-url", "");
        selfHostRateLimit = config.getInt("upload.self-host.rate-limit", 50);

        // Polymath settings
        polymathServer = config.getString("upload.polymath.server", "http://atlas.oraxen.com");
        polymathSecret = config.getString("upload.polymath.secret", "oraxen");
        polymathId = config.getString("upload.polymath.id", "");

        // Distribution settings
        distributionEnabled = config.getBoolean("distribution.enabled", true);
        required = config.getBoolean("distribution.required", false);
        promptMessage = config.getString("distribution.prompt-message", "");
        useAddResourcePack = config.getBoolean("distribution.use-add-resource-pack", false);
        joinDelayTicks = config.getInt("distribution.join-delay-ticks", 20);
        cacheEnabled = config.getBoolean("distribution.cache.enabled", true);
        onNewPackAction = config.getString("distribution.on-new-pack.action", "notify");
        notifyMessage = config.getString("distribution.on-new-pack.notify-message",
                "<yellow>[PackMerger]</yellow> <gray>A new resource pack is available. Rejoin or use F3+T to reload.</gray>");

        // Debug
        debug = config.getBoolean("debug", false);
    }

    /**
     * Reads the {@code server-name} property from {@code server.properties} in the server root.
     *
     * <p>Used as a fallback when the config's {@code server-name} field is empty, enabling
     * automatic per-server pack detection on multi-server networks without manual configuration.</p>
     *
     * @return the server name from {@code server.properties}, or {@code "default"} if unreadable
     */
    private String readServerNameFromProperties() {
        File serverProperties = new File("server.properties");
        if (!serverProperties.exists()) return "default";

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(serverProperties)) {
            props.load(fis);
            String name = props.getProperty("server-name", "default");
            return name.isEmpty() ? "default" : name;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read server.properties: " + e.getMessage());
            return "default";
        }
    }

    // -------------------------------------------------------------------------
    // Getters — General
    // -------------------------------------------------------------------------

    /** @return the server name used for per-server pack resolution */
    public String getServerName() { return serverName; }

    /** @return the ordered priority list of pack filenames (first = highest priority) */
    public List<String> getPriority() { return priority; }

    /** @return all per-server pack configurations, keyed by lowercase server name */
    public Map<String, ServerPackConfig> getServerPacks() { return serverPacks; }

    /**
     * Returns the per-server pack configuration for this server, or {@code null} if none is defined.
     *
     * @return the matching {@link ServerPackConfig}, or {@code null}
     */
    public ServerPackConfig getServerPackConfig() { return serverPacks.get(serverName.toLowerCase()); }

    // -------------------------------------------------------------------------
    // Getters — Merge
    // -------------------------------------------------------------------------

    /** @return {@code true} if packs should be merged automatically on plugin enable */
    public boolean isAutoMergeOnStartup() { return autoMergeOnStartup; }

    /** @return {@code true} if junk files should be stripped from the merged output */
    public boolean isStripJunkFiles() { return stripJunkFiles; }

    /** @return ZIP compression level (0-9) */
    public int getCompressionLevel() { return compressionLevel; }

    /** @return size threshold in MB for the console warning, or 0 if disabled */
    public int getSizeWarningMb() { return sizeWarningMb; }

    /** @return {@code true} if the file watcher for hot-reload is enabled */
    public boolean isHotReloadEnabled() { return hotReloadEnabled; }

    /** @return debounce delay in seconds before a hot-reload merge triggers */
    public int getHotReloadDebounceSeconds() { return hotReloadDebounceSeconds; }

    // -------------------------------------------------------------------------
    // Getters — Upload
    // -------------------------------------------------------------------------

    /** @return {@code true} if the pack should be uploaded automatically after merge */
    public boolean isAutoUpload() { return autoUpload; }

    /** @return the configured upload provider name: "polymath" or "self-host" */
    public String getUploadProvider() { return uploadProvider; }

    /** @return the port for the built-in HTTP server */
    public int getSelfHostPort() { return selfHostPort; }

    /** @return the public URL override for self-hosted downloads, or empty for auto-detection */
    public String getSelfHostPublicUrl() { return selfHostPublicUrl; }

    /** @return the maximum concurrent downloads for the self-host server (0 = unlimited) */
    public int getSelfHostRateLimit() { return selfHostRateLimit; }

    /** @return the Polymath server URL (e.g. "http://atlas.oraxen.com") */
    public String getPolymathServer() { return polymathServer; }

    /** @return the shared secret for Polymath upload authentication */
    public String getPolymathSecret() { return polymathSecret; }

    /** @return the unique ID for this server's pack on the Polymath instance, or empty to use server name */
    public String getPolymathId() { return polymathId; }

    // -------------------------------------------------------------------------
    // Getters — Distribution
    // -------------------------------------------------------------------------

    /** @return {@code true} if the pack should be sent to players on join */
    public boolean isDistributionEnabled() { return distributionEnabled; }

    /** @return {@code true} if the pack is required (declining kicks the player) */
    public boolean isRequired() { return required; }

    /** @return the custom MiniMessage prompt text, or empty for Minecraft's default */
    public String getPromptMessage() { return promptMessage; }

    /** @return {@code true} to use addResourcePack instead of replacing existing packs */
    public boolean isUseAddResourcePack() { return useAddResourcePack; }

    /** @return delay in ticks before sending the pack after join */
    public int getJoinDelayTicks() { return joinDelayTicks; }

    /** @return {@code true} if the player pack cache is enabled */
    public boolean isCacheEnabled() { return cacheEnabled; }

    /** @return the action to take on new pack: "none", "notify", or "resend" */
    public String getOnNewPackAction() { return onNewPackAction; }

    /** @return the MiniMessage notification text for the "notify" action */
    public String getNotifyMessage() { return notifyMessage; }

    // -------------------------------------------------------------------------
    // Getters — Debug
    // -------------------------------------------------------------------------

    /** @return {@code true} if verbose debug logging is enabled */
    public boolean isDebug() { return debug; }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Per-server pack configuration that can add or exclude packs from the global merge.
     *
     * <p>Used in multi-server network setups where each backend server needs a slightly
     * different resource pack composition.</p>
     *
     * @param additional list of pack filenames to include in addition to the global priority list
     * @param exclude    list of pack filenames to exclude from merging on this server
     */
    public record ServerPackConfig(List<String> additional, List<String> exclude) {}
}
