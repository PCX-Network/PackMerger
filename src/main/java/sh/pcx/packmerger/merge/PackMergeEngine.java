package sh.pcx.packmerger.merge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.config.ConfigManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.logging.Level;

/**
 * Core merge engine that combines multiple Minecraft resource packs into a single output zip.
 *
 * <p>The merge strategy is priority-based: packs are processed from lowest to highest priority
 * so that higher-priority files overwrite lower-priority ones. This applies to non-JSON files
 * (textures, sounds, etc.) which use a simple last-write-wins approach.</p>
 *
 * <p>Certain JSON files receive special treatment instead of being overwritten:</p>
 * <ul>
 *   <li><strong>Model and blockstate JSON</strong> ({@code assets/<ns>/models/} and
 *       {@code assets/<ns>/blockstates/}) — deep merged via {@link JsonMerger#deepMerge},
 *       preserving non-conflicting keys from both packs</li>
 *   <li><strong>sounds.json</strong> ({@code assets/<ns>/sounds.json}) — sound event arrays
 *       are concatenated via {@link JsonMerger#mergeSoundsJson} so sounds from multiple
 *       packs coexist</li>
 * </ul>
 *
 * <p>The engine also supports custom overrides: placing a {@code pack.mcmeta} or
 * {@code pack.png} directly in the packs folder (not inside a pack) overrides the
 * merged pack's metadata and icon regardless of priority.</p>
 *
 * <p>Called from {@link PackMerger#mergeAndUpload} on an async thread. This class
 * does not interact with the Bukkit API and is safe to run off the main thread.</p>
 *
 * @see JsonMerger
 * @see PackValidator
 * @see PackMerger#mergeAndUpload(org.bukkit.command.CommandSender)
 */
public class PackMergeEngine {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMerger plugin;

    /**
     * Filenames (lowercase) considered junk/OS metadata that should be stripped
     * from the merged pack when the strip-junk-files option is enabled.
     */
    private static final Set<String> JUNK_FILES = Set.of(
            ".ds_store", "thumbs.db", "desktop.ini", ".gitignore", ".gitattributes"
    );

    /**
     * Directory names (lowercase) considered junk that should be excluded
     * entirely from the merged pack (e.g. macOS resource forks, git metadata).
     */
    private static final Set<String> JUNK_DIRS = Set.of(
            "__macosx", ".git"
    );

    /**
     * Creates a new merge engine instance.
     *
     * @param plugin the owning PackMerger plugin
     */
    public PackMergeEngine(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Performs the full merge of all discovered resource packs and writes the output zip.
     *
     * <p>The merge process:</p>
     * <ol>
     *   <li>Discover available packs in the packs folder (zips and directories)</li>
     *   <li>Build a priority-ordered pack list, factoring in per-server configs</li>
     *   <li>Iterate packs from lowest to highest priority, merging files into memory</li>
     *   <li>Apply JSON deep merge results back into the file map</li>
     *   <li>Apply custom pack.mcmeta/pack.png overrides if present</li>
     *   <li>Generate a default pack.mcmeta if none exists</li>
     *   <li>Write the final zip to the output folder</li>
     * </ol>
     *
     * @return the output zip {@link File}, or {@code null} if no packs were found or the merge
     *         produced no files
     * @throws IOException if reading source packs or writing the output zip fails
     */
    public File merge() throws IOException {
        File packsFolder = plugin.getPacksFolder();
        ConfigManager config = plugin.getConfigManager();

        // Step 1: Discover available packs (zips and directories with assets/ or pack.mcmeta)
        List<String> availablePacks = discoverPacks(packsFolder);
        if (availablePacks.isEmpty()) {
            plugin.getLogger().warning("No resource packs found in " + packsFolder.getAbsolutePath());
            return null;
        }

        plugin.getLogger().info("Discovered " + availablePacks.size() + " pack(s): " + availablePacks);

        // Step 2: Build ordered pack list based on priority config and per-server settings
        List<String> orderedPacks = buildPackOrder(availablePacks, config);

        plugin.getLogger().info("Merge order (highest priority first): " + orderedPacks);

        // Check for custom pack.mcmeta and pack.png overrides placed directly in the packs folder
        File customMcmeta = new File(packsFolder, "pack.mcmeta");
        File customIcon = new File(packsFolder, "pack.png");

        // Step 3: Merge all packs
        // mergedFiles holds non-JSON (or non-mergeable) file contents keyed by normalized path
        Map<String, byte[]> mergedFiles = new LinkedHashMap<>();
        // mergedJson holds parsed JSON objects for files that get deep-merged instead of overwritten
        Map<String, JsonObject> mergedJson = new LinkedHashMap<>();

        // Process packs in REVERSE order (lowest priority first) so that higher-priority
        // packs overwrite lower-priority ones via map.put() for non-JSON files, or take
        // precedence in JSON deep merge operations
        for (int i = orderedPacks.size() - 1; i >= 0; i--) {
            String packName = orderedPacks.get(i);
            File packFile = new File(packsFolder, packName);

            if (!packFile.exists()) {
                plugin.getLogger().warning("Pack not found: " + packName + " (skipping)");
                continue;
            }

            try {
                if (packFile.isDirectory()) {
                    mergeDirectory(packFile, packFile.toPath(), mergedFiles, mergedJson, config.isStripJunkFiles());
                } else if (packName.endsWith(".zip")) {
                    mergeZip(packFile, mergedFiles, mergedJson, config.isStripJunkFiles());
                }
                if (config.isDebug()) {
                    plugin.getLogger().info("Merged pack: " + packName);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to read pack: " + packName + " (skipping)", e);
            }
        }

        // Step 4: Serialize merged JSON objects back into byte arrays and add them to mergedFiles
        for (Map.Entry<String, JsonObject> entry : mergedJson.entrySet()) {
            mergedFiles.put(entry.getKey(), JsonMerger.toJson(entry.getValue()).getBytes(StandardCharsets.UTF_8));
        }

        // Step 5: Apply custom overrides — these take precedence over everything
        if (customMcmeta.exists() && customMcmeta.isFile()) {
            plugin.getLogger().info("Using custom pack.mcmeta from packs folder");
            mergedFiles.put("pack.mcmeta", Files.readAllBytes(customMcmeta.toPath()));
        }

        if (customIcon.exists() && customIcon.isFile()) {
            plugin.getLogger().info("Using custom pack.png from packs folder");
            mergedFiles.put("pack.png", Files.readAllBytes(customIcon.toPath()));
        }

        // Step 6: Ensure a pack.mcmeta exists — Minecraft requires it for valid resource packs
        if (!mergedFiles.containsKey("pack.mcmeta")) {
            plugin.getLogger().info("No pack.mcmeta found, generating default");
            // pack_format 46 corresponds to Minecraft 1.21.4+
            String defaultMcmeta = """
                    {
                      "pack": {
                        "pack_format": 46,
                        "description": "Merged resource pack by PackMerger"
                      }
                    }""";
            mergedFiles.put("pack.mcmeta", defaultMcmeta.getBytes(StandardCharsets.UTF_8));
        }

        if (mergedFiles.isEmpty()) {
            plugin.getLogger().warning("No files to merge after processing all packs");
            return null;
        }

        // Step 7: Write the output zip
        File outputFile = plugin.getOutputFile();
        outputFile.getParentFile().mkdirs();

        writeZip(outputFile, mergedFiles, config.getCompressionLevel());

        // Log the final file size for operator awareness
        long sizeBytes = outputFile.length();
        String sizeStr = formatSize(sizeBytes);
        plugin.getLogger().info("Merged pack written: " + outputFile.getName() + " (" + sizeStr + ")");

        // Warn if the pack exceeds the configured size threshold
        int warningMb = config.getSizeWarningMb();
        if (warningMb > 0) {
            long sizeMb = sizeBytes / (1024 * 1024);
            if (sizeMb > warningMb) {
                plugin.getLogger().warning("WARNING: Merged pack is " + sizeStr +
                        ", which exceeds the configured threshold of " + warningMb +
                        " MB. Large packs may cause download failures for players on slow connections.");
            }
        }

        return outputFile;
    }

    /**
     * Discovers valid resource packs in the given folder.
     *
     * <p>A file is considered a pack if it is either a {@code .zip} file or a directory
     * containing {@code pack.mcmeta} or an {@code assets/} subdirectory. Custom override
     * files ({@code pack.mcmeta}, {@code pack.png}) and hidden files are excluded.</p>
     *
     * @param packsFolder the directory to scan
     * @return a list of pack filenames/directory names found
     */
    private List<String> discoverPacks(File packsFolder) {
        List<String> packs = new ArrayList<>();
        File[] files = packsFolder.listFiles();
        if (files == null) return packs;

        for (File file : files) {
            String name = file.getName();
            // Skip custom override files — these are applied after the merge
            if (name.equals("pack.mcmeta") || name.equals("pack.png")) continue;
            // Skip hidden/dot files
            if (name.startsWith(".")) continue;

            if (file.isDirectory()) {
                // Validate the directory looks like a resource pack
                if (new File(file, "pack.mcmeta").exists() || new File(file, "assets").exists()) {
                    packs.add(name);
                }
            } else if (name.endsWith(".zip")) {
                packs.add(name);
            }
        }
        return packs;
    }

    /**
     * Builds the final ordered pack list based on the global priority configuration
     * and per-server include/exclude rules.
     *
     * <p>The ordering logic:</p>
     * <ol>
     *   <li>Packs listed in the priority config are added first, in config order,
     *       excluding any packs in the server's exclude list</li>
     *   <li>Packs found on disk but not in any config are appended at lowest priority
     *       with a console warning</li>
     *   <li>Server-specific additional packs are appended last (below global packs)</li>
     * </ol>
     *
     * @param available the list of pack names discovered on disk
     * @param config    the configuration manager for priority and server-pack settings
     * @return the final ordered list (first = highest priority)
     */
    private List<String> buildPackOrder(List<String> available, ConfigManager config) {
        List<String> priority = new ArrayList<>(config.getPriority());
        List<String> ordered = new ArrayList<>();

        // Resolve per-server include/exclude rules
        ConfigManager.ServerPackConfig serverConfig = config.getServerPackConfig();
        Set<String> excluded = new HashSet<>();
        List<String> additional = new ArrayList<>();

        if (serverConfig != null) {
            excluded.addAll(serverConfig.exclude());
            additional.addAll(serverConfig.additional());
        }

        // First pass: add packs from the priority list in order, respecting server exclusions
        for (String pack : priority) {
            if (available.contains(pack) && !excluded.contains(pack)) {
                ordered.add(pack);
            }
        }

        // Second pass: add packs that exist on disk but aren't in any config list
        // These get lowest priority and a console warning so the operator knows
        for (String pack : available) {
            if (!priority.contains(pack) && !additional.contains(pack)) {
                plugin.getLogger().warning("Pack '" + pack + "' found but not listed in priority config — merging at lowest priority");
                ordered.add(pack);
            }
        }

        // Third pass: add server-specific additional packs at lowest priority (below global)
        for (String pack : additional) {
            if (available.contains(pack) && !ordered.contains(pack)) {
                ordered.add(pack);
            }
        }

        return ordered;
    }

    /**
     * Merges all files from a zip archive into the merge maps.
     *
     * @param zipFile    the zip file to read
     * @param mergedFiles map of normalized path &rarr; file bytes for non-mergeable files
     * @param mergedJson  map of normalized path &rarr; parsed JSON for deep-mergeable files
     * @param stripJunk   whether to skip junk/hidden files
     * @throws IOException if reading the zip fails
     */
    private void mergeZip(File zipFile, Map<String, byte[]> mergedFiles, Map<String, JsonObject> mergedJson, boolean stripJunk) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String path = normalizePath(entry.getName());
                if (path.isEmpty()) continue;

                // Optionally strip OS metadata and hidden files
                if (stripJunk && isJunkFile(path)) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("Stripping junk: " + path);
                    }
                    continue;
                }

                byte[] data;
                try (InputStream is = zf.getInputStream(entry)) {
                    data = is.readAllBytes();
                }

                processFile(path, data, mergedFiles, mergedJson);
            }
        }
    }

    /**
     * Merges all files from an unzipped pack directory into the merge maps.
     *
     * @param baseDir     the root directory of the pack
     * @param basePath    the root path used to compute relative paths
     * @param mergedFiles map of normalized path &rarr; file bytes for non-mergeable files
     * @param mergedJson  map of normalized path &rarr; parsed JSON for deep-mergeable files
     * @param stripJunk   whether to skip junk/hidden files
     * @throws IOException if walking the directory tree fails
     */
    private void mergeDirectory(File baseDir, Path basePath, Map<String, byte[]> mergedFiles, Map<String, JsonObject> mergedJson, boolean stripJunk) throws IOException {
        try (var stream = Files.walk(basePath)) {
            stream.filter(Files::isRegularFile).forEach(filePath -> {
                try {
                    String relativePath = normalizePath(basePath.relativize(filePath).toString());
                    if (relativePath.isEmpty()) return;

                    if (stripJunk && isJunkFile(relativePath)) {
                        if (plugin.getConfigManager().isDebug()) {
                            plugin.getLogger().info("Stripping junk: " + relativePath);
                        }
                        return;
                    }

                    byte[] data = Files.readAllBytes(filePath);
                    processFile(relativePath, data, mergedFiles, mergedJson);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to read file: " + filePath, e);
                }
            });
        }
    }

    /**
     * Routes a single file into the appropriate merge map based on its path.
     *
     * <p>Files in {@code assets/<ns>/models/}, {@code assets/<ns>/blockstates/}, and
     * {@code assets/<ns>/sounds.json} are treated as mergeable JSON — their contents are
     * parsed and deep-merged with any existing entry rather than being overwritten.
     * sounds.json gets special handling where sound arrays are concatenated instead of
     * replaced.</p>
     *
     * <p>All other files (textures, audio, shaders, etc.) use simple overwrite semantics —
     * the higher-priority pack's version wins.</p>
     *
     * @param path        the normalized file path within the resource pack
     * @param data        the raw file content bytes
     * @param mergedFiles map for non-mergeable files (overwrite semantics)
     * @param mergedJson  map for deep-mergeable JSON files
     */
    private void processFile(String path, byte[] data, Map<String, byte[]> mergedFiles, Map<String, JsonObject> mergedJson) {
        if (isMergeableJson(path)) {
            // Parse the JSON content for deep merging
            String content = new String(data, StandardCharsets.UTF_8);
            JsonObject newJson = JsonMerger.parseJson(content);
            if (newJson != null) {
                if (mergedJson.containsKey(path)) {
                    // This file already exists from a lower-priority pack — merge them
                    JsonObject existing = mergedJson.get(path);
                    if (isSoundsJson(path)) {
                        // sounds.json: concatenate sound arrays for the same event
                        mergedJson.put(path, JsonMerger.mergeSoundsJson(newJson, existing));
                    } else {
                        // Model/blockstate JSON: deep merge preserving non-conflicting keys
                        mergedJson.put(path, JsonMerger.deepMerge(newJson, existing));
                    }
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("JSON merged: " + path);
                    }
                } else {
                    // First occurrence of this file — store it as-is
                    mergedJson.put(path, newJson);
                }
            } else {
                // Invalid JSON in a mergeable path — fall back to raw overwrite with a warning
                plugin.getLogger().warning("Invalid JSON in mergeable file (using raw): " + path);
                mergedFiles.put(path, data);
            }
        } else {
            // Non-JSON or non-mergeable file: higher priority replaces lower priority (last write wins)
            mergedFiles.put(path, data);
        }
    }

    /**
     * Determines whether a file at the given path should be deep-merged as JSON
     * rather than using simple overwrite semantics.
     *
     * <p>Mergeable paths include:</p>
     * <ul>
     *   <li>{@code assets/<namespace>/models/**&#47;*.json} — item/block model definitions</li>
     *   <li>{@code assets/<namespace>/blockstates/**&#47;*.json} — blockstate variant mappings</li>
     *   <li>{@code assets/<namespace>/sounds.json} — sound event definitions</li>
     * </ul>
     *
     * @param path the normalized file path to check
     * @return {@code true} if this file should be deep-merged
     */
    private boolean isMergeableJson(String path) {
        String lower = path.toLowerCase();
        // Match: assets/<any-namespace>/models/<any-depth>.json
        if (lower.matches("assets/[^/]+/models/.+\\.json")) return true;
        // Match: assets/<any-namespace>/blockstates/<any-depth>.json
        if (lower.matches("assets/[^/]+/blockstates/.+\\.json")) return true;
        // Match: assets/<any-namespace>/sounds.json
        if (lower.matches("assets/[^/]+/sounds\\.json")) return true;
        return false;
    }

    /**
     * Checks if a path points to a sounds.json file.
     *
     * @param path the normalized file path to check
     * @return {@code true} if this is a namespace sounds.json
     */
    private boolean isSoundsJson(String path) {
        // Match: assets/<any-namespace>/sounds.json
        return path.toLowerCase().matches("assets/[^/]+/sounds\\.json");
    }

    /**
     * Determines whether a file should be treated as junk and stripped from the output.
     *
     * <p>Junk includes hidden/dot files, OS metadata files (.DS_Store, Thumbs.db,
     * desktop.ini), version control files (.gitignore, .gitattributes), and files
     * inside junk directories (__MACOSX, .git).</p>
     *
     * @param path the normalized file path to check
     * @return {@code true} if the file is junk
     */
    private boolean isJunkFile(String path) {
        String lower = path.toLowerCase().replace('\\', '/');
        String fileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;

        // Hidden/dot files (e.g. .gitkeep, .hidden)
        if (fileName.startsWith(".")) return true;

        // Well-known OS metadata and VCS files
        if (JUNK_FILES.contains(fileName)) return true;

        // Check if any directory component in the path is a known junk directory
        String[] parts = lower.split("/");
        for (String part : parts) {
            if (JUNK_DIRS.contains(part)) return true;
        }

        return false;
    }

    /**
     * Normalizes a file path by converting backslashes to forward slashes and
     * stripping leading slashes. This ensures consistent path keys across
     * zip entries and directory walks regardless of OS.
     *
     * @param path the raw path string
     * @return the normalized path
     */
    private String normalizePath(String path) {
        return path.replace('\\', '/').replaceAll("^/+", "");
    }

    /**
     * Writes a map of file paths and their contents to a zip archive.
     *
     * @param outputFile       the zip file to create
     * @param files            map of path &rarr; content bytes
     * @param compressionLevel the ZIP compression level (0-9)
     * @throws IOException if writing fails
     */
    private void writeZip(File outputFile, Map<String, byte[]> files, int compressionLevel) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)))) {
            zos.setLevel(compressionLevel);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                ZipEntry ze = new ZipEntry(entry.getKey());
                zos.putNextEntry(ze);
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
    }

    /**
     * Formats a byte count as a human-readable size string (B, KB, MB, or GB).
     *
     * @param bytes the size in bytes
     * @return a formatted string like "1.5 MB"
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
