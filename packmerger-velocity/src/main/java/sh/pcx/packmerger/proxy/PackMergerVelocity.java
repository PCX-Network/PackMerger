package sh.pcx.packmerger.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import sh.pcx.packmerger.common.PackInfo;
import sh.pcx.packmerger.common.PackMessaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Velocity proxy companion to PackMerger. Offers the merged resource pack to every
 * player as they join the proxy, so distribution is network-wide rather than
 * per-backend.
 *
 * <p>The pack URL is set in this plugin's {@code config.properties}; backends may
 * also push live URL/hash updates over the {@link PackMessaging#CHANNEL} plugin
 * channel (so content-addressed URLs stay current). The proxy reuses whatever
 * hosting the backend already uses — it only needs the URL (and ideally the SHA-1).</p>
 */
@Plugin(
        id = "packmerger",
        name = "PackMerger",
        version = "1.2.0",
        description = "Network-wide resource pack distribution for PackMerger",
        authors = {"PCX Network"}
)
public final class PackMergerVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(PackMessaging.CHANNEL);

    private volatile PackInfo current = new PackInfo(null, null);
    private volatile boolean required;
    private volatile String prompt = "";

    @Inject
    public PackMergerVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(channel);
        loadConfig();
        if (current.hasUrl()) {
            logger.info("PackMerger proxy ready — offering pack from {}", current.url());
        } else {
            logger.warn("PackMerger proxy: no pack URL configured. Set 'url' in "
                    + "plugins/packmerger/config.properties or have a backend push one.");
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        sendPack(event.getPlayer());
    }

    /** Receives live pack updates from a backend PackMerger over the plugin channel. */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!channel.getId().equals(event.getIdentifier().getId())) return;
        // Only trust messages coming from a backend server, never from a client.
        if (!(event.getSource() instanceof ServerConnection)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        try {
            PackInfo info = PackMessaging.decode(event.getData());
            if (info.hasUrl()) {
                current = info;
                logger.info("PackMerger proxy: pack updated from backend → {}", info.url());
            }
        } catch (RuntimeException e) {
            logger.warn("PackMerger proxy: ignored malformed pack message ({})", e.getMessage());
        }
    }

    private void sendPack(Player player) {
        PackInfo info = current;
        if (!info.hasUrl()) return;
        ResourcePackInfo.Builder builder = proxy.createResourcePackBuilder(info.url())
                .setShouldForce(required);
        if (info.hasHash()) {
            byte[] hash = hexToBytes(info.sha1Hex());
            if (hash != null) builder.setHash(hash);
        }
        if (prompt != null && !prompt.isBlank()) {
            builder.setPrompt(Component.text(prompt));
        }
        player.sendResourcePackOffer(builder.build());
    }

    // --- config ---

    private void loadConfig() {
        Properties props = new Properties();
        try {
            Path file = dataDir.resolve("config.properties");
            if (Files.notExists(file)) {
                Files.createDirectories(dataDir);
                props.setProperty("url", "");
                props.setProperty("sha1", "");
                props.setProperty("required", "false");
                props.setProperty("prompt", "");
                try (OutputStream out = Files.newOutputStream(file)) {
                    props.store(out, "PackMerger Velocity — set 'url' to your merged pack's download URL. "
                            + "A backend PackMerger can also push live updates.");
                }
            } else {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            logger.warn("PackMerger proxy: could not read config.properties ({})", e.getMessage());
        }
        String url = props.getProperty("url", "").trim();
        String sha1 = props.getProperty("sha1", "").trim();
        this.current = new PackInfo(url.isEmpty() ? null : url, sha1.isEmpty() ? null : sha1);
        this.required = Boolean.parseBoolean(props.getProperty("required", "false"));
        this.prompt = props.getProperty("prompt", "");
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        int len = hex.length();
        if (len % 2 != 0) return null;
        byte[] out = new byte[len / 2];
        try {
            for (int i = 0; i < len; i += 2) {
                out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
