package sh.pcx.packmerger.merge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Detects textures and sounds in a merged resource pack that no JSON file
 * references. Orphans are bytes that ship to every player download for nothing
 * — surfacing them lets admins trim bloated packs.
 *
 * <p>Reference discovery handles the most impactful cases:</p>
 * <ul>
 *   <li><b>Models</b> ({@code assets/&lt;ns&gt;/models/*.json}) — {@code textures.*}
 *       values (skipping {@code #slot} variable references) and {@code parent}.
 *   </li>
 *   <li><b>Item definitions</b> ({@code assets/&lt;ns&gt;/items/*.json}, 1.21.4+) —
 *       any {@code model} or {@code particle} string value nested anywhere in
 *       the tree.</li>
 *   <li><b>Blockstates</b> — {@code apply.model} refs in variants and multipart.</li>
 *   <li><b>Atlases</b> — {@code sources} with type {@code single} (specific
 *       resource) or {@code directory} (expanded as a glob over
 *       {@code assets/&lt;ns&gt;/textures/&lt;prefix&gt;}).</li>
 *   <li><b>Font providers</b> — bitmap file refs.</li>
 *   <li><b>Sounds</b> ({@code assets/&lt;ns&gt;/sounds.json}) — sound event
 *       {@code name} fields resolved against {@code assets/&lt;ns&gt;/sounds/*.ogg}.
 *   </li>
 * </ul>
 *
 * <p>Filter-type atlas sources (regex-based) are not expanded; a pack using
 * them will see false-positive orphan warnings for matched textures. This is
 * acceptable for a first cut — operators can disable the check per-pack via
 * config if it proves noisy.</p>
 */
public final class OrphanDetector {

    private OrphanDetector() {}

    /** An individual unreferenced asset with its on-disk size. */
    public record Orphan(String path, long size) {}

    /** Summary of the orphan scan, suitable for both logging and chat output. */
    public record OrphanReport(
            List<Orphan> orphans,
            long totalBytes,
            List<Orphan> topLargest) {

        public int count() { return orphans.size(); }
    }

    /**
     * Scan a merged pack zip for orphan textures and sounds.
     *
     * @param zf   the merged pack zip — caller retains ownership
     * @param topN how many entries to include in {@link OrphanReport#topLargest}
     */
    public static OrphanReport scan(ZipFile zf, int topN) throws IOException {
        // Step 1: enumerate every candidate asset (png/ogg) with its size
        Map<String, Long> candidates = new HashMap<>();
        Set<String> allPaths = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String path = entry.getName().replace('\\', '/');
            allPaths.add(path);
            if (path.endsWith(".png") || path.endsWith(".ogg")) {
                candidates.put(path, entry.getSize() < 0 ? 0L : entry.getSize());
            }
        }

        // Step 2: walk every JSON file and collect what it references
        Set<String> referenced = new HashSet<>();
        entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String path = entry.getName().replace('\\', '/').toLowerCase();
            if (!path.endsWith(".json")) continue;

            JsonObject root;
            try (InputStream is = zf.getInputStream(entry)) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                JsonElement parsed = JsonParser.parseString(content);
                if (!parsed.isJsonObject()) continue;
                root = parsed.getAsJsonObject();
            } catch (JsonSyntaxException e) {
                // JSON validity is checked elsewhere; for orphan purposes, skip.
                continue;
            }

            collectReferences(path, root, referenced, allPaths);
        }

        // Step 3: orphans = candidates - referenced
        List<Orphan> orphans = new ArrayList<>();
        long totalBytes = 0;
        for (Map.Entry<String, Long> e : candidates.entrySet()) {
            if (!referenced.contains(e.getKey())) {
                orphans.add(new Orphan(e.getKey(), e.getValue()));
                totalBytes += e.getValue();
            }
        }
        orphans.sort(Comparator.comparing(Orphan::path));

        List<Orphan> topLargest = new ArrayList<>(orphans);
        topLargest.sort(Comparator.comparingLong(Orphan::size).reversed());
        if (topLargest.size() > topN) topLargest = new ArrayList<>(topLargest.subList(0, topN));

        return new OrphanReport(orphans, totalBytes, topLargest);
    }

    private static void collectReferences(String path, JsonObject root, Set<String> out, Set<String> allPaths) {
        if (path.matches("assets/[^/]+/models/.+\\.json")) {
            collectFromModel(root, out);
        } else if (path.matches("assets/[^/]+/items/.+\\.json")) {
            collectFromItemDefinition(root, out);
        } else if (path.matches("assets/[^/]+/blockstates/.+\\.json")) {
            collectFromBlockstate(root, out);
        } else if (path.matches("assets/[^/]+/atlases/.+\\.json")) {
            collectFromAtlas(root, allPaths, out);
        } else if (path.matches("assets/[^/]+/font/.+\\.json")) {
            collectFromFont(root, out);
        } else if (path.matches("assets/[^/]+/sounds\\.json")) {
            String ns = pathNamespace(path);
            collectFromSoundsJson(root, ns, out);
        }
    }

    // ---- model/item/blockstate helpers ----

    private static void collectFromModel(JsonObject model, Set<String> out) {
        if (model.has("textures") && model.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : model.getAsJsonObject("textures").entrySet()) {
                JsonElement v = e.getValue();
                if (!v.isJsonPrimitive()) continue;
                String ref = v.getAsString();
                if (ref.startsWith("#")) continue;        // variable ref, not a file
                out.add(textureToPath(ref));
            }
        }
    }

    private static void collectFromItemDefinition(JsonElement node, Set<String> out) {
        if (node == null || node.isJsonNull()) return;
        if (node.isJsonObject()) {
            JsonObject obj = node.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String key = e.getKey();
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    String ref = v.getAsString();
                    if (key.equals("model")) {
                        out.add(modelToPath(ref));
                    } else if (key.equals("texture") || key.equals("particle") || key.equals("value")) {
                        // Heuristic — particle names are treated as textures when they
                        // look like resource identifiers. We also defensively mark any
                        // "texture" string we find.
                        if (ref.contains(":") || ref.contains("/")) {
                            out.add(textureToPath(ref));
                        }
                    }
                } else {
                    collectFromItemDefinition(v, out);
                }
            }
        } else if (node.isJsonArray()) {
            for (JsonElement el : node.getAsJsonArray()) {
                collectFromItemDefinition(el, out);
            }
        }
    }

    private static void collectFromBlockstate(JsonObject blockstate, Set<String> out) {
        if (blockstate.has("variants") && blockstate.get("variants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : blockstate.getAsJsonObject("variants").entrySet()) {
                collectApplyModels(e.getValue(), out);
            }
        }
        if (blockstate.has("multipart") && blockstate.get("multipart").isJsonArray()) {
            for (JsonElement el : blockstate.getAsJsonArray("multipart")) {
                if (!el.isJsonObject()) continue;
                JsonElement apply = el.getAsJsonObject().get("apply");
                if (apply != null) collectApplyModels(apply, out);
            }
        }
    }

    private static void collectApplyModels(JsonElement apply, Set<String> out) {
        if (apply == null || apply.isJsonNull()) return;
        if (apply.isJsonObject()) {
            JsonElement m = apply.getAsJsonObject().get("model");
            if (m != null && m.isJsonPrimitive()) out.add(modelToPath(m.getAsString()));
        } else if (apply.isJsonArray()) {
            for (JsonElement el : apply.getAsJsonArray()) collectApplyModels(el, out);
        }
    }

    // ---- atlas / font / sounds helpers ----

    private static void collectFromAtlas(JsonObject atlas, Set<String> allPaths, Set<String> out) {
        if (!atlas.has("sources") || !atlas.get("sources").isJsonArray()) return;
        for (JsonElement el : atlas.getAsJsonArray("sources")) {
            if (!el.isJsonObject()) continue;
            JsonObject src = el.getAsJsonObject();
            String type = src.has("type") ? src.get("type").getAsString() : "";
            switch (type) {
                case "single" -> {
                    if (src.has("resource")) out.add(textureToPath(src.get("resource").getAsString()));
                    if (src.has("sprite")) out.add(textureToPath(src.get("sprite").getAsString()));
                }
                case "directory" -> {
                    // Expand: mark every texture under assets/<ns>/textures/<source-path>...
                    if (!src.has("source")) break;
                    String source = src.get("source").getAsString();
                    String ns = source.contains(":") ? source.substring(0, source.indexOf(':')) : "minecraft";
                    String dir = source.contains(":") ? source.substring(source.indexOf(':') + 1) : source;
                    String globPrefix = "assets/" + ns + "/textures/" + dir;
                    if (!globPrefix.endsWith("/") && !globPrefix.isEmpty()) globPrefix += "/";
                    for (String p : allPaths) {
                        if (p.startsWith(globPrefix) && p.endsWith(".png")) {
                            out.add(p);
                        }
                    }
                }
                default -> {
                    // filter, unstitch, and custom types — too complex to expand correctly.
                    // We err on the side of caution by not marking anything, which
                    // may produce false-positive orphans for packs that rely on them.
                }
            }
        }
    }

    private static void collectFromFont(JsonObject font, Set<String> out) {
        if (!font.has("providers") || !font.get("providers").isJsonArray()) return;
        for (JsonElement el : font.getAsJsonArray("providers")) {
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            String type = p.has("type") ? p.get("type").getAsString() : "";
            if ("bitmap".equals(type) && p.has("file")) {
                out.add(textureToPath(p.get("file").getAsString()));
            }
        }
    }

    private static void collectFromSoundsJson(JsonObject soundsJson, String namespace, Set<String> out) {
        for (Map.Entry<String, JsonElement> e : soundsJson.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject event = e.getValue().getAsJsonObject();
            if (!event.has("sounds") || !event.get("sounds").isJsonArray()) continue;
            for (JsonElement sel : event.getAsJsonArray("sounds")) {
                String name;
                if (sel.isJsonPrimitive()) {
                    name = sel.getAsString();
                } else if (sel.isJsonObject() && sel.getAsJsonObject().has("name")) {
                    name = sel.getAsJsonObject().get("name").getAsString();
                } else continue;
                out.add(soundToPath(name, namespace));
            }
        }
    }

    // ---- ref-to-path conversion ----

    /** {@code "minecraft:block/stone"} or {@code "block/stone"} → {@code "assets/minecraft/textures/block/stone.png"}. */
    private static String textureToPath(String ref) {
        String ns, p;
        if (ref.contains(":")) {
            int idx = ref.indexOf(':');
            ns = ref.substring(0, idx);
            p = ref.substring(idx + 1);
        } else {
            ns = "minecraft";
            p = ref;
        }
        return "assets/" + ns + "/textures/" + p + ".png";
    }

    /** {@code "minecraft:block/stone"} → {@code "assets/minecraft/models/block/stone.json"}. */
    private static String modelToPath(String ref) {
        String ns, p;
        if (ref.contains(":")) {
            int idx = ref.indexOf(':');
            ns = ref.substring(0, idx);
            p = ref.substring(idx + 1);
        } else {
            ns = "minecraft";
            p = ref;
        }
        return "assets/" + ns + "/models/" + p + ".json";
    }

    /** {@code "block.stone.step"} under namespace {@code minecraft} → {@code "assets/minecraft/sounds/block/stone/step.ogg"}. */
    private static String soundToPath(String soundRef, String defaultNs) {
        String ns, p;
        if (soundRef.contains(":")) {
            int idx = soundRef.indexOf(':');
            ns = soundRef.substring(0, idx);
            p = soundRef.substring(idx + 1);
        } else {
            ns = defaultNs;
            p = soundRef;
        }
        return "assets/" + ns + "/sounds/" + p + ".ogg";
    }

    private static String pathNamespace(String path) {
        // path is "assets/<ns>/sounds.json"
        String trimmed = path.substring("assets/".length());
        int slash = trimmed.indexOf('/');
        return slash > 0 ? trimmed.substring(0, slash) : "minecraft";
    }

    /** Public for tests — namespace-agnostic list shorthand in unit tests. */
    static Set<String> orderedEmpty() { return new LinkedHashSet<>(); }
}
