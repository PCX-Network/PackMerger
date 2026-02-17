package sh.pcx.packmerger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;

import java.util.logging.Level;

/**
 * Console logging system with MiniMessage-colored category tags and configurable log levels.
 *
 * <p>All messages are sent to the console via {@link Bukkit#getConsoleSender()} for colored
 * output. Message content is appended as {@link Component#text(String)} (not parsed by
 * MiniMessage) to prevent tag injection from file paths or user input.</p>
 *
 * <p>Log levels control which messages are shown:</p>
 * <ul>
 *   <li>{@code DEBUG} — show everything (verbose)</li>
 *   <li>{@code INFO} — normal operation (default)</li>
 *   <li>{@code WARNING} — only warnings and errors</li>
 *   <li>{@code ERROR} — only errors</li>
 * </ul>
 *
 * <p>Category methods ({@link #merge}, {@link #upload}) are cosmetic tags at INFO level.</p>
 */
public class PluginLogger {

    /** Log levels in ascending severity order. */
    public enum LogLevel {
        DEBUG, INFO, WARNING, ERROR;

        /**
         * Parses a log level from a string, defaulting to {@link #INFO} if unrecognized.
         *
         * @param name the level name (case-insensitive)
         * @return the matching log level, or INFO if invalid
         */
        public static LogLevel fromString(String name) {
            try {
                return valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                return INFO;
            }
        }
    }

    private final PackMerger plugin;
    private LogLevel minLevel;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * Creates a new plugin logger with the given minimum log level.
     *
     * @param plugin the owning plugin instance (used for stack trace logging)
     * @param level  the minimum log level as a string (e.g. "info", "debug")
     */
    public PluginLogger(PackMerger plugin, String level) {
        this.plugin = plugin;
        this.minLevel = LogLevel.fromString(level);
    }

    /**
     * Updates the minimum log level. Called on config reload.
     *
     * @param level the new minimum log level as a string
     */
    public void setLevel(String level) {
        this.minLevel = LogLevel.fromString(level);
    }

    private boolean isEnabled(LogLevel level) {
        return level.ordinal() >= minLevel.ordinal();
    }

    private void log(String tag, String message) {
        Component component = MINI_MESSAGE.deserialize(
                "<dark_gray>[PackMerger]</dark_gray> " + tag
        ).append(Component.text(" " + message));
        Bukkit.getConsoleSender().sendMessage(component);
    }

    /** General operational messages. */
    public void info(String message) {
        if (!isEnabled(LogLevel.INFO)) return;
        log("<white>[INFO]</white>", message);
    }

    /** Merge operation messages. */
    public void merge(String message) {
        if (!isEnabled(LogLevel.INFO)) return;
        log("<aqua>[MERGE]</aqua>", message);
    }

    /** Upload and hosting messages. */
    public void upload(String message) {
        if (!isEnabled(LogLevel.INFO)) return;
        log("<green>[UPLOAD]</green>", message);
    }

    /** Non-fatal issue warnings. */
    public void warning(String message) {
        if (!isEnabled(LogLevel.WARNING)) return;
        log("<yellow>[WARNING]</yellow>", message);
    }

    /** Non-fatal issue warnings with a stack trace. */
    public void warning(String message, Throwable throwable) {
        if (!isEnabled(LogLevel.WARNING)) return;
        log("<yellow>[WARNING]</yellow>", message);
        plugin.getLogger().log(Level.WARNING, "", throwable);
    }

    /** Failure messages. */
    public void error(String message) {
        if (!isEnabled(LogLevel.ERROR)) return;
        log("<red>[ERROR]</red>", message);
    }

    /** Failure messages with a stack trace. */
    public void error(String message, Throwable throwable) {
        if (!isEnabled(LogLevel.ERROR)) return;
        log("<red>[ERROR]</red>", message);
        plugin.getLogger().log(Level.SEVERE, "", throwable);
    }

    /** Verbose debug messages, disabled by default. */
    public void debug(String message) {
        if (!isEnabled(LogLevel.DEBUG)) return;
        log("<gray>[DEBUG]</gray>", message);
    }
}
