// Minimal example of consuming PackMerger's API and Bukkit events from another plugin.
//
// Drop this into your plugin's own source tree. It's not compiled as part of PackMerger —
// the filename is intentionally not named `*.java` inside `src/` so the Java compiler
// doesn't touch it. It lives here as a copy-paste starting point for integrators.
//
// You'll need PackMerger on your compile classpath. If you're using Gradle:
//
//   dependencies {
//       compileOnly 'com.github.PCX-Network:PackMerger:1.1.0'   // via JitPack, or a local jar
//   }

package com.example.myplugin;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.api.PackMergerApi;
import sh.pcx.packmerger.api.events.PackMergedEvent;
import sh.pcx.packmerger.api.events.PackUploadedEvent;
import sh.pcx.packmerger.api.events.PackValidationFailedEvent;

public class MyPlugin extends JavaPlugin implements Listener {

    private PackMergerApi packs;

    @Override
    public void onEnable() {
        PackMerger pm = (PackMerger) Bukkit.getPluginManager().getPlugin("PackMerger");
        if (pm == null) {
            getLogger().warning("PackMerger not installed — integration disabled");
            return;
        }
        this.packs = pm.getApi();

        Bukkit.getPluginManager().registerEvents(this, this);

        // Poll-style query
        String currentUrl = packs.getCurrentPackUrl();
        String currentSha1 = packs.getCurrentPackSha1Hex();
        getLogger().info("Current pack: " + currentUrl + " (sha1=" + currentSha1 + ")");
    }

    @EventHandler
    public void onPackMerged(PackMergedEvent e) {
        // Fired after merge + validate, before upload.
        int errors = e.getValidationResult().errors();
        int warnings = e.getValidationResult().warnings();
        getLogger().info("Pack merged: " + e.getOutputFile().getName()
                + " (" + errors + " errors, " + warnings + " warnings)");

        // Provenance: who contributed which file?
        e.getProvenance().filesByWinner().forEach((pack, count) ->
                getLogger().info("  " + pack + ": " + count + " files won"));
    }

    @EventHandler
    public void onPackUploaded(PackUploadedEvent e) {
        // Fired once the public URL is live. Good time to post to Discord, update
        // a signboard plugin, etc.
        getLogger().info("Pack uploaded: " + e.getUrl());
    }

    @EventHandler
    public void onValidationFailed(PackValidationFailedEvent e) {
        if (e.wasRolledBack()) {
            getLogger().severe("PackMerger rolled back to previous pack due to "
                    + e.getResult().errors() + " validation errors");
        } else {
            getLogger().severe("PackMerger shipped a pack with validation errors — no previous pack to roll back to");
        }
        // Page on-call, open incident, notify admins, etc.
    }

    // Triggering a merge programmatically (e.g. from your own command or webhook):
    public void merge() {
        packs.triggerMerge().thenRun(() ->
                getLogger().info("Merge cycle complete; pack URL is " + packs.getCurrentPackUrl()));
    }
}
