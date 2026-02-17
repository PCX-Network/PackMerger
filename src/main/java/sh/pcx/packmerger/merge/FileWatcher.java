package sh.pcx.packmerger.merge;

import sh.pcx.packmerger.PackMerger;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Monitors the packs folder for file system changes and triggers automatic re-merges
 * (hot-reload functionality).
 *
 * <p>The watcher runs on a dedicated daemon thread that uses the Java NIO
 * {@link WatchService} API to receive file create/modify/delete events from the OS.
 * To avoid excessive re-merges during bulk file operations (e.g. copying a new pack
 * folder), events are debounced — the merge is only triggered after no new events
 * have been received for the configured debounce period.</p>
 *
 * <p>Lifecycle: created and started by {@link PackMerger#startFileWatcher()}, stopped
 * by {@link PackMerger#stopFileWatcher()}. The watcher thread is set as a daemon thread
 * so it won't prevent JVM shutdown.</p>
 *
 * @see PackMerger#startFileWatcher()
 * @see PackMerger#stopFileWatcher()
 */
public class FileWatcher {

    /** Reference to the owning plugin for triggering merges and reading config. */
    private final PackMerger plugin;

    /** The dedicated watch thread, or {@code null} if not running. */
    private Thread watchThread;

    /** Flag indicating whether the watcher loop should continue running. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** The NIO watch service handle, used for cleanup on stop. */
    private WatchService watchService;

    /**
     * Creates a new file watcher bound to the given plugin.
     *
     * @param plugin the owning PackMerger plugin
     */
    public FileWatcher(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the file watcher on a dedicated daemon thread.
     *
     * <p>If already running, this method is a no-op. The watcher registers for
     * CREATE, MODIFY, and DELETE events on the packs folder. Events are debounced
     * using a timestamp-based approach: each relevant event updates a "last event time"
     * counter, and the merge only fires when the polling loop detects that the debounce
     * period has elapsed since the last event.</p>
     */
    public void start() {
        if (running.get()) return;

        Path packsPath = plugin.getPacksFolder().toPath();
        if (!packsPath.toFile().exists()) {
            packsPath.toFile().mkdirs();
        }

        running.set(true);
        watchThread = new Thread(() -> {
            try {
                watchService = FileSystems.getDefault().newWatchService();
                // Register for all three event types on the packs directory
                packsPath.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);

                plugin.getLogger().info("File watcher started for: " + packsPath);

                // Debounce state: tracks the timestamp of the last relevant file event.
                // When this is non-zero and the debounce period elapses, a merge is triggered.
                AtomicLong lastEventTime = new AtomicLong(0);
                int debounceMs = plugin.getConfigManager().getHotReloadDebounceSeconds() * 1000;

                while (running.get()) {
                    WatchKey key;
                    try {
                        // Poll with a 1-second timeout so we can check the debounce timer regularly
                        key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (key == null) {
                        // No events this cycle — check if we've been waiting long enough to trigger
                        long lastTime = lastEventTime.get();
                        if (lastTime > 0 && System.currentTimeMillis() - lastTime >= debounceMs) {
                            // Debounce period elapsed since last event — trigger the merge
                            lastEventTime.set(0);
                            triggerMerge();
                        }
                        continue;
                    }

                    // Process all events in this batch
                    boolean hasRelevantEvent = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        // OVERFLOW events indicate the OS event buffer overflowed — skip them
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                        Path changed = (Path) event.context();
                        String fileName = changed.toString();

                        // Only consider pack-related file changes:
                        // - pack.mcmeta and pack.png (custom overrides)
                        // - .zip files (pack archives)
                        // - directories (unzipped packs, detected by having no file extension)
                        if (fileName.equals("pack.mcmeta") || fileName.equals("pack.png") ||
                                fileName.endsWith(".zip") || !fileName.contains(".")) {
                            hasRelevantEvent = true;
                            if (plugin.getConfigManager().isDebug()) {
                                plugin.getLogger().info("File change detected: " + kind.name() + " " + fileName);
                            }
                        }
                    }

                    if (hasRelevantEvent) {
                        // Reset the debounce timer — we'll wait for the full debounce period
                        // from this point before triggering
                        lastEventTime.set(System.currentTimeMillis());
                        plugin.getLogger().info("Pack file change detected, waiting " +
                                plugin.getConfigManager().getHotReloadDebounceSeconds() + "s before merging...");
                    }

                    // Reset the watch key so we continue receiving events
                    boolean valid = key.reset();
                    if (!valid) {
                        // The watched directory was deleted or became inaccessible
                        plugin.getLogger().warning("File watcher key invalidated, stopping watcher");
                        break;
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "File watcher error", e);
            }
        }, "PackMerger-FileWatcher");

        // Daemon thread so it doesn't prevent server shutdown
        watchThread.setDaemon(true);
        watchThread.start();
    }

    /**
     * Stops the file watcher and releases all resources.
     *
     * <p>Closes the watch service (which unblocks the polling thread), interrupts the
     * thread, and clears the reference. Safe to call multiple times.</p>
     */
    public void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // Swallowed intentionally — we're shutting down and the resource will be
                // cleaned up by the JVM regardless
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        plugin.getLogger().info("File watcher stopped");
    }

    /**
     * Called after the debounce period elapses to initiate an automatic merge.
     * Delegates to the plugin's merge-and-upload pipeline with no command sender
     * (automated merge, no in-game feedback).
     */
    private void triggerMerge() {
        plugin.getLogger().info("Debounce complete, triggering auto-merge...");
        // null sender = no in-game messages (this is an automated merge)
        plugin.mergeAndUpload(null);
    }
}
