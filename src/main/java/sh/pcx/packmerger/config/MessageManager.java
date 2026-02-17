package sh.pcx.packmerger.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import sh.pcx.packmerger.PackMerger;

import java.io.File;

/**
 * Manages player-facing messages loaded from {@code messages_en.yml}.
 *
 * <p>Messages use MiniMessage formatting with {@code {placeholder}} syntax for dynamic
 * values. Placeholder values are escaped via {@link MiniMessage#escapeTags(String)} before
 * substitution to prevent MiniMessage injection from user-generated content.</p>
 */
public class MessageManager {

    private final PackMerger plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration messages;

    public MessageManager(PackMerger plugin) {
        this.plugin = plugin;
    }

    /**
     * Saves the default {@code messages_en.yml} if it doesn't exist, then loads it.
     */
    public void load() {
        File file = new File(plugin.getDataFolder(), "messages_en.yml");
        if (!file.exists()) {
            plugin.saveResource("messages_en.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    /**
     * Returns a parsed {@link Component} for the given message key with no placeholders.
     *
     * @param key the message key (e.g. {@code "reload.starting"})
     * @return the parsed component, or the key itself as a visible fallback if missing
     */
    public Component getMessage(String key) {
        String template = messages.getString(key);
        if (template == null) {
            return miniMessage.deserialize("<" + key + ">");
        }
        return miniMessage.deserialize(template);
    }

    /**
     * Returns a parsed {@link Component} for the given message key with placeholder substitution.
     *
     * <p>Replacements are provided as alternating key-value pairs. Values are escaped via
     * {@link MiniMessage#escapeTags(String)} to prevent injection.</p>
     *
     * @param key          the message key (e.g. {@code "validate.complete"})
     * @param replacements alternating placeholder name and value pairs
     *                     (e.g. {@code "warnings", "3", "errors", "1"})
     * @return the parsed component with placeholders resolved
     */
    public Component getMessage(String key, String... replacements) {
        String template = messages.getString(key);
        if (template == null) {
            return miniMessage.deserialize("<" + key + ">");
        }

        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = replacements[i];
            String value = MiniMessage.miniMessage().escapeTags(replacements[i + 1]);
            template = template.replace("{" + placeholder + "}", value);
        }

        return miniMessage.deserialize(template);
    }
}
