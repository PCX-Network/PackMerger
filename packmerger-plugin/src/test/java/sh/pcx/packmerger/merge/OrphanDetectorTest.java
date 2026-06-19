package sh.pcx.packmerger.merge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OrphanDetectorTest {

    private File writeZip(Path dir, String name, Map<String, byte[]> files) throws Exception {
        File f = dir.resolve(name).toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f))) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return f;
    }

    private byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private Set<String> orphanPaths(OrphanDetector.OrphanReport report) {
        return report.orphans().stream().map(OrphanDetector.Orphan::path).collect(Collectors.toSet());
    }

    @Test
    void referencedByModel_isNotOrphan(@TempDir Path dir) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/textures/item/sword.png", new byte[]{1, 2, 3});
        files.put("assets/minecraft/textures/item/orphan.png", new byte[]{4, 5, 6});
        files.put("assets/minecraft/models/item/iron_sword.json", utf8("""
                { "textures": { "layer0": "item/sword" } }"""));

        File zip = writeZip(dir, "pack.zip", files);
        try (ZipFile zf = new ZipFile(zip)) {
            OrphanDetector.OrphanReport report = OrphanDetector.scan(zf, 10);
            Set<String> orphans = orphanPaths(report);
            assertTrue(orphans.contains("assets/minecraft/textures/item/orphan.png"));
            assertFalse(orphans.contains("assets/minecraft/textures/item/sword.png"));
            assertEquals(1, report.count());
        }
    }

    @Test
    void referencedByAtlasDirectory_expandsGlob(@TempDir Path dir) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/textures/block/stone.png", new byte[]{1});
        files.put("assets/minecraft/textures/block/dirt.png", new byte[]{2});
        files.put("assets/minecraft/textures/item/unrelated.png", new byte[]{3});
        files.put("assets/minecraft/atlases/blocks.json", utf8("""
                {
                  "sources": [
                    { "type": "directory", "source": "minecraft:block", "prefix": "" }
                  ]
                }"""));

        File zip = writeZip(dir, "pack.zip", files);
        try (ZipFile zf = new ZipFile(zip)) {
            OrphanDetector.OrphanReport report = OrphanDetector.scan(zf, 10);
            Set<String> orphans = orphanPaths(report);
            assertFalse(orphans.contains("assets/minecraft/textures/block/stone.png"),
                    "directory source should cover stone.png");
            assertFalse(orphans.contains("assets/minecraft/textures/block/dirt.png"),
                    "directory source should cover dirt.png");
            assertTrue(orphans.contains("assets/minecraft/textures/item/unrelated.png"),
                    "item/unrelated.png is outside the atlas directory");
        }
    }

    @Test
    void referencedByAtlasSingle_isNotOrphan(@TempDir Path dir) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/textures/gui/logo.png", new byte[]{1});
        files.put("assets/minecraft/textures/gui/unused.png", new byte[]{2});
        files.put("assets/minecraft/atlases/gui.json", utf8("""
                {
                  "sources": [
                    { "type": "single", "resource": "minecraft:gui/logo", "sprite": "minecraft:gui/logo" }
                  ]
                }"""));

        File zip = writeZip(dir, "pack.zip", files);
        try (ZipFile zf = new ZipFile(zip)) {
            Set<String> orphans = orphanPaths(OrphanDetector.scan(zf, 10));
            assertTrue(orphans.contains("assets/minecraft/textures/gui/unused.png"));
            assertFalse(orphans.contains("assets/minecraft/textures/gui/logo.png"));
        }
    }

    @Test
    void referencedByFont_isNotOrphan(@TempDir Path dir) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/textures/font/icons.png", new byte[]{1});
        files.put("assets/minecraft/font/default.json", utf8("""
                { "providers": [ { "type": "bitmap", "file": "minecraft:font/icons" } ] }"""));

        File zip = writeZip(dir, "pack.zip", files);
        try (ZipFile zf = new ZipFile(zip)) {
            Set<String> orphans = orphanPaths(OrphanDetector.scan(zf, 10));
            assertFalse(orphans.contains("assets/minecraft/textures/font/icons.png"));
        }
    }

    @Test
    void referencedBySoundsJson_isNotOrphan(@TempDir Path dir) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/sounds/custom/hit.ogg", new byte[]{1});
        files.put("assets/minecraft/sounds/custom/unused.ogg", new byte[]{2});
        files.put("assets/minecraft/sounds.json", utf8("""
                {
                  "entity.custom.hit": {
                    "sounds": ["custom/hit"]
                  }
                }"""));

        File zip = writeZip(dir, "pack.zip", files);
        try (ZipFile zf = new ZipFile(zip)) {
            Set<String> orphans = orphanPaths(OrphanDetector.scan(zf, 10));
            assertFalse(orphans.contains("assets/minecraft/sounds/custom/hit.ogg"));
            assertTrue(orphans.contains("assets/minecraft/sounds/custom/unused.ogg"));
        }
    }

    @Test
    void modelVariableRef_notTreatedAsFileRef(@TempDir Path dir) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/textures/item/sword.png", new byte[]{1});
        files.put("assets/minecraft/models/item/template.json", utf8("""
                {
                  "textures": {
                    "layer0": "#icon"
                  }
                }"""));
        // Since the only reference is #icon (a variable, not a file), sword.png is orphan
        File zip = writeZip(dir, "pack.zip", files);
        try (ZipFile zf = new ZipFile(zip)) {
            Set<String> orphans = orphanPaths(OrphanDetector.scan(zf, 10));
            assertTrue(orphans.contains("assets/minecraft/textures/item/sword.png"),
                    "#icon is a slot variable, not a file reference");
        }
    }

    @Test
    void topLargest_sortedBySize(@TempDir Path dir) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/textures/a.png", new byte[10]);
        files.put("assets/minecraft/textures/b.png", new byte[100]);
        files.put("assets/minecraft/textures/c.png", new byte[50]);
        // No JSON references any of them → all orphan

        File zip = writeZip(dir, "pack.zip", files);
        try (ZipFile zf = new ZipFile(zip)) {
            OrphanDetector.OrphanReport report = OrphanDetector.scan(zf, 2);
            assertEquals(3, report.count());
            assertEquals(2, report.topLargest().size(), "topN=2 should limit list");
            assertEquals("assets/minecraft/textures/b.png", report.topLargest().get(0).path());
            assertEquals("assets/minecraft/textures/c.png", report.topLargest().get(1).path());
        }
    }

    @Test
    void blockstateModelRef_doesNotBlockTextureFromBeingOrphan(@TempDir Path dir) throws Exception {
        // Blockstate refs the model file (not a texture), but the texture referenced
        // by that model still needs to be followed. We don't traverse transitively in
        // this first cut — document it with a test so future changes that add transitive
        // traversal can update this.
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/blockstates/stone.json", utf8("""
                { "variants": { "": { "model": "minecraft:block/stone" } } }"""));
        files.put("assets/minecraft/models/block/stone.json", utf8("""
                { "textures": { "all": "block/stone" } }"""));
        files.put("assets/minecraft/textures/block/stone.png", new byte[10]);

        File zip = writeZip(dir, "pack.zip", files);
        try (ZipFile zf = new ZipFile(zip)) {
            Set<String> orphans = orphanPaths(OrphanDetector.scan(zf, 10));
            // The model's texture reference is picked up by the model scanner directly,
            // independently of the blockstate chain.
            assertFalse(orphans.contains("assets/minecraft/textures/block/stone.png"),
                    "model's own texture scan should mark the png as referenced");
        }
    }
}
