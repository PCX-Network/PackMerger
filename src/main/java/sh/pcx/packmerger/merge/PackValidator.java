package sh.pcx.packmerger.merge;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.PluginLogger;

/**
 * Validates a merged resource pack zip for structural correctness and missing references.
 *
 * <p>Validation is run after every merge to provide early warning about issues that could
 * cause problems in-game. The validator checks:</p>
 *
 * <ol>
 *   <li><strong>pack.mcmeta</strong> — must exist and contain a valid {@code pack.pack_format} field</li>
 *   <li><strong>JSON syntax</strong> — all {@code .json} files are parsed to detect syntax errors</li>
 *   <li><strong>Texture references</strong> — model files referencing textures are checked to ensure
 *       the referenced {@code .png} files exist in the pack</li>
 *   <li><strong>Model references</strong> — blockstate files referencing models are checked to ensure
 *       the referenced model JSON files exist in the pack</li>
 * </ol>
 *
 * <p>Issues are categorized as errors (critical, pack may not work) or warnings (non-critical,
 * pack may have visual glitches). Results are logged to console and returned as a
 * {@link ValidationResult} for display in the validate command.</p>
 *
 * <p>Called from {@link PackMerger#mergeAndUpload} after the merge completes. Runs on an
 * async thread and does not interact with the Bukkit API.</p>
 *
 * @see PackMergeEngine
 * @see PackMerger#mergeAndUpload(org.bukkit.command.CommandSender)
 */
public class PackValidator {

    /** Reference to the owning plugin for config access and logging. */
    private final PackMerger plugin;

    /** Colored console logger. */
    private final PluginLogger logger;

    /**
     * Creates a new pack validator.
     *
     * @param plugin the owning PackMerger plugin
     */
    public PackValidator(PackMerger plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
    }

    /**
     * Immutable result of a validation run, containing warning/error counts and messages.
     *
     * @param warnings number of non-critical issues found
     * @param errors   number of critical issues found
     * @param messages list of human-readable issue descriptions, prefixed with "WARNING:" or "ERROR:"
     */
    public record ValidationResult(int warnings, int errors, List<String> messages) {}

    /**
     * Validates the given merged pack zip file.
     *
     * <p>Opens the zip and performs all validation checks in a single pass where possible.
     * Multiple passes over the zip entries are needed because later checks (texture/model
     * references) require the complete set of paths to be known first.</p>
     *
     * @param packFile the merged pack zip file to validate
     * @return a {@link ValidationResult} with the counts and messages
     */
    public ValidationResult validate(File packFile) {
        int warnings = 0;
        int errors = 0;
        List<String> messages = new ArrayList<>();

        if (!packFile.exists()) {
            logger.error("Validation: Merged pack file does not exist!");
            return new ValidationResult(0, 1, List.of("Merged pack file does not exist"));
        }

        try (ZipFile zf = new ZipFile(packFile)) {
            // First pass: collect all file paths in the pack for reference checking later
            Set<String> allPaths = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    allPaths.add(entry.getName().replace('\\', '/'));
                }
            }

            // Check 1: Validate pack.mcmeta presence and structure
            if (!allPaths.contains("pack.mcmeta")) {
                errors++;
                String msg = "ERROR: pack.mcmeta is missing from merged pack";
                messages.add(msg);
                logger.error("Validation: " + msg);
            } else {
                ZipEntry mcmetaEntry = zf.getEntry("pack.mcmeta");
                try (InputStream is = zf.getInputStream(mcmetaEntry)) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject mcmeta = JsonParser.parseString(content).getAsJsonObject();
                    // Minecraft requires the "pack" object with a "pack_format" integer
                    if (!mcmeta.has("pack") || !mcmeta.getAsJsonObject("pack").has("pack_format")) {
                        errors++;
                        String msg = "ERROR: pack.mcmeta is missing 'pack.pack_format' field";
                        messages.add(msg);
                        logger.error("Validation: " + msg);
                    }
                } catch (JsonSyntaxException e) {
                    errors++;
                    String msg = "ERROR: pack.mcmeta contains invalid JSON";
                    messages.add(msg);
                    logger.error("Validation: " + msg);
                }
            }

            // Check 2: Validate all JSON files for syntax errors
            entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');

                if (path.endsWith(".json")) {
                    try (InputStream is = zf.getInputStream(entry)) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        // Attempt to parse — throws JsonSyntaxException on invalid JSON
                        JsonParser.parseString(content);
                    } catch (JsonSyntaxException e) {
                        warnings++;
                        String msg = "WARNING: Invalid JSON: " + path + " — " + e.getMessage();
                        messages.add(msg);
                        logger.warning("Validation: " + msg);
                    }
                }
            }

            // Check 3: Validate model texture references
            entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/').toLowerCase();

                // Match: assets/<namespace>/models/<any>.json
                if (path.matches("assets/[^/]+/models/.+\\.json")) {
                    try (InputStream is = zf.getInputStream(entry)) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject model = JsonParser.parseString(content).getAsJsonObject();

                        // Check each texture reference in the model's "textures" block
                        if (model.has("textures") && model.get("textures").isJsonObject()) {
                            JsonObject textures = model.getAsJsonObject("textures");
                            for (Map.Entry<String, JsonElement> tex : textures.entrySet()) {
                                if (!tex.getValue().isJsonPrimitive()) continue;
                                String ref = tex.getValue().getAsString();
                                // Skip variable references like #layer0 — these refer to
                                // other texture slots within the same model, not actual files
                                if (ref.startsWith("#")) continue;
                                // Verify the referenced .png texture exists in the pack
                                if (!textureExists(allPaths, ref)) {
                                    warnings++;
                                    String msg = "WARNING: Model " + entry.getName() + " references missing texture: " + ref;
                                    messages.add(msg);
                                    logger.debug("Validation: " + msg);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // JSON syntax errors were already reported in Check 2
                    }
                }

                // Check 4: Validate blockstate model references
                // Match: assets/<namespace>/blockstates/<any>.json
                if (path.matches("assets/[^/]+/blockstates/.+\\.json")) {
                    try (InputStream is = zf.getInputStream(entry)) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject blockstate = JsonParser.parseString(content).getAsJsonObject();

                        checkBlockstateModels(blockstate, entry.getName(), allPaths, messages);
                    } catch (Exception e) {
                        // JSON syntax errors were already reported in Check 2
                    }
                }
            }

        } catch (IOException e) {
            errors++;
            String msg = "ERROR: Could not open merged pack for validation: " + e.getMessage();
            messages.add(msg);
            logger.error("Validation: " + msg);
        }

        // Recount warnings from messages since checkBlockstateModels adds directly to the list
        warnings = (int) messages.stream().filter(m -> m.startsWith("WARNING:")).count();

        logger.info("Validation complete: " + warnings + " warnings, " + errors + " errors");
        return new ValidationResult(warnings, errors, messages);
    }

    /**
     * Checks whether a texture referenced by a model exists in the pack.
     *
     * <p>Texture references in Minecraft models use the format {@code "namespace:path"}
     * (e.g. {@code "minecraft:block/stone"}) or just {@code "path"} which defaults to
     * the {@code minecraft} namespace. The actual file is located at
     * {@code assets/<namespace>/textures/<path>.png}.</p>
     *
     * @param allPaths   set of all file paths in the pack
     * @param textureRef the texture reference string from the model JSON
     * @return {@code true} if the referenced texture file exists
     */
    private boolean textureExists(Set<String> allPaths, String textureRef) {
        // Parse the "namespace:path" format
        String namespace;
        String path;
        if (textureRef.contains(":")) {
            String[] parts = textureRef.split(":", 2);
            namespace = parts[0];
            path = parts[1];
        } else {
            // No namespace specified — defaults to "minecraft"
            namespace = "minecraft";
            path = textureRef;
        }

        // Construct the expected file path and check both exact and lowercase matches
        String texturePath = "assets/" + namespace + "/textures/" + path + ".png";
        return allPaths.contains(texturePath) || allPaths.contains(texturePath.toLowerCase());
    }

    /**
     * Checks whether a model referenced by a blockstate exists in the pack.
     *
     * <p>Model references use the same {@code "namespace:path"} format as texture
     * references. The actual file is at {@code assets/<namespace>/models/<path>.json}.</p>
     *
     * @param allPaths set of all file paths in the pack
     * @param modelRef the model reference string from the blockstate JSON
     * @return {@code true} if the referenced model file exists
     */
    private boolean modelExists(Set<String> allPaths, String modelRef) {
        String namespace;
        String path;
        if (modelRef.contains(":")) {
            String[] parts = modelRef.split(":", 2);
            namespace = parts[0];
            path = parts[1];
        } else {
            namespace = "minecraft";
            path = modelRef;
        }

        String modelPath = "assets/" + namespace + "/models/" + path + ".json";
        return allPaths.contains(modelPath) || allPaths.contains(modelPath.toLowerCase());
    }

    /**
     * Validates all model references within a blockstate JSON object.
     *
     * <p>Blockstates can use two formats:</p>
     * <ul>
     *   <li><strong>"variants" format</strong> — a map of state strings to model references</li>
     *   <li><strong>"multipart" format</strong> — an array of condition/apply pairs</li>
     * </ul>
     *
     * <p>Each referenced model is checked for existence via {@link #modelExists}.</p>
     *
     * @param blockstate the parsed blockstate JSON object
     * @param fileName   the blockstate file name (for error messages)
     * @param allPaths   set of all file paths in the pack
     * @param messages   list to append warning messages to
     */
    private void checkBlockstateModels(JsonObject blockstate, String fileName, Set<String> allPaths, List<String> messages) {
        // Handle "variants" format: { "variants": { "facing=north": { "model": "..." } } }
        if (blockstate.has("variants") && blockstate.get("variants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> variant : blockstate.getAsJsonObject("variants").entrySet()) {
                checkModelRef(variant.getValue(), fileName, allPaths, messages);
            }
        }

        // Handle "multipart" format: { "multipart": [ { "apply": { "model": "..." } } ] }
        if (blockstate.has("multipart") && blockstate.get("multipart").isJsonArray()) {
            for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                    checkModelRef(part.getAsJsonObject().get("apply"), fileName, allPaths, messages);
                }
            }
        }
    }

    /**
     * Checks a single model reference element from a blockstate definition.
     *
     * <p>The element can be either a JSON object with a "model" field, or an array of
     * such objects (for weighted random variants). Handles both cases recursively.</p>
     *
     * @param element  the JSON element to check (object or array)
     * @param fileName the blockstate file name (for error messages)
     * @param allPaths set of all file paths in the pack
     * @param messages list to append warning messages to
     */
    private void checkModelRef(JsonElement element, String fileName, Set<String> allPaths, List<String> messages) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("model") && obj.get("model").isJsonPrimitive()) {
                String modelRef = obj.get("model").getAsString();
                if (!modelExists(allPaths, modelRef)) {
                    String msg = "WARNING: Blockstate " + fileName + " references missing model: " + modelRef;
                    messages.add(msg);
                    logger.debug("Validation: " + msg);
                }
            }
        } else if (element.isJsonArray()) {
            // Array of weighted model variants — check each one
            for (JsonElement item : element.getAsJsonArray()) {
                checkModelRef(item, fileName, allPaths, messages);
            }
        }
    }
}
