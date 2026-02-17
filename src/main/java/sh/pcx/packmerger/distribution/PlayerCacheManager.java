package sh.pcx.packmerger.distribution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import sh.pcx.packmerger.PackMerger;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Tracks which resource pack version each player has successfully downloaded.
 *
 * <p>The cache maps player UUIDs to the SHA-1 hash of the pack they last loaded. When a
 * player joins, the distributor can check this cache to skip sending the pack if the
 * player already has the current version, avoiding unnecessary re-downloads.</p>
 *
 * <p>The cache is stored on disk as a JSON file ({@code cache/player-cache.json}) and
 * is persisted periodically (every 5 minutes) and on plugin disable. It uses a
 * {@link ConcurrentHashMap} for thread safety, since the cache is updated from the
 * main thread (on resource pack status events) and saved from an async timer task.</p>
 *
 * <p>Cache entries are updated when a player's resource pack status changes to
 * {@code SUCCESSFULLY_LOADED}, as reported by
 * {@link sh.pcx.packmerger.listeners.PlayerJoinListener}.</p>
 *
 * @see PackDistributor
 * @see sh.pcx.packmerger.listeners.PlayerJoinListener
 */
public class PlayerCacheManager {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMerger plugin;

    /** The JSON file where the cache is persisted to disk. */
    private final File cacheFile;

    /**
     * In-memory cache mapping player UUIDs to the SHA-1 hash of their last loaded pack.
     * Uses ConcurrentHashMap for thread-safe access between the main thread (updates)
     * and async timer (saves).
     */
    private final Map<UUID, String> cache = new ConcurrentHashMap<>();

    /** Gson instance for reading/writing the cache file with pretty printing. */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Creates a new player cache manager.
     *
     * @param plugin the owning PackMerger plugin
     */
    public PlayerCacheManager(PackMerger plugin) {
        this.plugin = plugin;
        this.cacheFile = new File(plugin.getCacheFolder(), "player-cache.json");
    }

    /**
     * Loads the player cache from disk.
     *
     * <p>If the cache file doesn't exist, starts with an empty cache. Invalid UUID entries
     * in the file are skipped with a warning. Called once during plugin enable.</p>
     */
    public void load() {
        if (!cacheFile.exists()) {
            plugin.getLogger().info("No player cache file found, starting fresh");
            return;
        }

        try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
            // The JSON file stores UUID strings as keys (not UUID objects)
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> rawCache = gson.fromJson(reader, type);
            if (rawCache != null) {
                cache.clear();
                for (Map.Entry<String, String> entry : rawCache.entrySet()) {
                    try {
                        cache.put(UUID.fromString(entry.getKey()), entry.getValue());
                    } catch (IllegalArgumentException e) {
                        // Skip entries with malformed UUIDs (e.g. from manual file edits)
                        plugin.getLogger().warning("Invalid UUID in cache: " + entry.getKey());
                    }
                }
                plugin.getLogger().info("Loaded " + cache.size() + " player cache entries");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player cache", e);
        }
    }

    /**
     * Saves the player cache to disk.
     *
     * <p>Converts UUID keys to strings for JSON serialization. Called periodically
     * by the async save timer and once during plugin disable.</p>
     */
    public void save() {
        cacheFile.getParentFile().mkdirs();

        // Convert UUID keys to strings for JSON serialization
        Map<String, String> rawCache = new java.util.LinkedHashMap<>();
        for (Map.Entry<UUID, String> entry : cache.entrySet()) {
            rawCache.put(entry.getKey().toString(), entry.getValue());
        }

        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(cacheFile), StandardCharsets.UTF_8))) {
            gson.toJson(rawCache, writer);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save player cache", e);
        }
    }

    /**
     * Returns the SHA-1 hash of the pack that the given player last successfully loaded.
     *
     * @param playerId the player's UUID
     * @return the cached SHA-1 hash hex string, or {@code null} if the player has no cache entry
     */
    public String getCachedHash(UUID playerId) {
        return cache.get(playerId);
    }

    /**
     * Updates the cache entry for a player after they successfully load a resource pack.
     *
     * @param playerId the player's UUID
     * @param sha1Hex  the SHA-1 hash of the pack they loaded
     */
    public void updateCache(UUID playerId, String sha1Hex) {
        cache.put(playerId, sha1Hex);
    }

    /**
     * Checks whether a player already has the current merged pack version.
     *
     * <p>Returns {@code false} if caching is disabled in the config, if the player has
     * no cache entry, or if the cached hash doesn't match the current pack hash.</p>
     *
     * @param playerId the player's UUID
     * @return {@code true} if the player's cached hash matches the current pack hash
     */
    public boolean hasCurrentPack(UUID playerId) {
        if (!plugin.getConfigManager().isCacheEnabled()) return false;
        String cached = cache.get(playerId);
        String current = plugin.getCurrentPackHashHex();
        return cached != null && current != null && cached.equals(current);
    }
}
