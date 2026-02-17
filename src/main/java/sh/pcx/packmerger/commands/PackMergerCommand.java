package sh.pcx.packmerger.commands;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.pcx.packmerger.PackMerger;
import sh.pcx.packmerger.config.MessageManager;
import sh.pcx.packmerger.merge.PackValidator;

import java.io.File;
import java.util.List;

/**
 * Registers and handles all PackMerger admin commands using Paper's Brigadier command API.
 *
 * <p>Commands are registered via Paper's lifecycle event system rather than {@code plugin.yml},
 * which provides better tab completion and argument parsing. All commands require the
 * {@code packmerger.admin} permission (default: op).</p>
 *
 * <p>Available subcommands:</p>
 * <ul>
 *   <li>{@code /packmerger reload} — reloads config, re-initializes the upload provider,
 *       and triggers a full merge-upload cycle</li>
 *   <li>{@code /packmerger validate} — runs validation on the current merged pack</li>
 *   <li>{@code /packmerger status} — displays current plugin state (server name, provider,
 *       last merge time, pack URL, hash, size, etc.)</li>
 *   <li>{@code /packmerger apply [player]} — force-sends the pack to a specific player
 *       or all online players, bypassing the cache</li>
 * </ul>
 *
 * <p>The command is also registered with the alias {@code /pm} for convenience.</p>
 *
 * @see PackMerger
 */
@SuppressWarnings("UnstableApiUsage") // Paper's Brigadier API is marked as experimental
public class PackMergerCommand {

    /** Reference to the owning plugin for component access. */
    private final PackMerger plugin;

    /**
     * Creates the command handler and registers all commands.
     *
     * <p>Registration happens immediately in the constructor via Paper's lifecycle
     * event system.</p>
     *
     * @param plugin the owning PackMerger plugin
     */
    public PackMergerCommand(PackMerger plugin) {
        this.plugin = plugin;
        register();
    }

    /**
     * Registers the {@code /packmerger} command tree with Paper's Brigadier registrar.
     *
     * <p>The command tree structure:</p>
     * <pre>
     * /packmerger (requires packmerger.admin)
     *   ├── reload    — reload config and re-merge
     *   ├── validate  — validate current merged pack
     *   ├── status    — show plugin status
     *   └── apply     — force-send pack
     *       └── [player] — optional player selector
     * </pre>
     */
    private void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                    Commands.literal("packmerger")
                            .requires(source -> source.getSender().hasPermission("packmerger.admin"))
                            .then(Commands.literal("reload")
                                    .executes(this::handleReload))
                            .then(Commands.literal("validate")
                                    .executes(this::handleValidate))
                            .then(Commands.literal("status")
                                    .executes(this::handleStatus))
                            .then(Commands.literal("apply")
                                    .executes(this::handleApplyAll)
                                    .then(Commands.argument("player", ArgumentTypes.player())
                                            .executes(this::handleApplyPlayer)))
                            .build(),
                    "PackMerger admin commands",
                    List.of("pm") // Register /pm as an alias for /packmerger
            );
        });
    }

    /**
     * Handles {@code /packmerger reload} — reloads the config and triggers a full
     * merge-upload cycle.
     *
     * @param ctx the Brigadier command context
     * @return 1 (success)
     */
    private int handleReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        sender.sendMessage(msg.getMessage("reload.starting"));

        plugin.reload();
        plugin.mergeAndUpload(sender);

        return 1;
    }

    /**
     * Handles {@code /packmerger validate} — runs pack validation asynchronously
     * and reports results to the sender.
     *
     * <p>Validation runs on an async thread to avoid blocking the main thread for
     * large packs. Results are dispatched back to the main thread for messaging.</p>
     *
     * @param ctx the Brigadier command context
     * @return 1 if validation started, 0 if no merged pack exists
     */
    private int handleValidate(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        File outputFile = plugin.getOutputFile();
        if (!outputFile.exists()) {
            sender.sendMessage(msg.getMessage("validate.no-pack"));
            return 0;
        }

        sender.sendMessage(msg.getMessage("validate.starting"));

        // Run validation off the main thread to avoid blocking the server
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PackValidator.ValidationResult result = plugin.getValidator().validate(outputFile);

            // Return to the main thread to send player messages
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(msg.getMessage("validate.complete",
                        "warnings", String.valueOf(result.warnings()),
                        "errors", String.valueOf(result.errors())));

                if (!result.messages().isEmpty()) {
                    for (String line : result.messages()) {
                        if (line.startsWith("ERROR:")) {
                            sender.sendMessage(msg.getMessage("validate.error-line",
                                    "message", line));
                        } else {
                            sender.sendMessage(msg.getMessage("validate.warning-line",
                                    "message", line));
                        }
                    }
                }
            });
        });

        return 1;
    }

    /**
     * Handles {@code /packmerger status} — displays comprehensive plugin state information.
     *
     * @param ctx the Brigadier command context
     * @return 1 (success)
     */
    private int handleStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        sender.sendMessage(msg.getMessage("status.header"));
        sender.sendMessage(msg.getMessage("status.server",
                "server", plugin.getConfigManager().getServerName()));
        sender.sendMessage(msg.getMessage("status.upload-provider",
                "provider", plugin.getConfigManager().getUploadProvider()));
        sender.sendMessage(msg.getMessage("status.last-merge",
                "time", plugin.getFormattedLastMergeTime()));

        String url = plugin.getCurrentPackUrl();
        sender.sendMessage(msg.getMessage("status.pack-url",
                "url", url != null ? url : "N/A"));

        String hash = plugin.getCurrentPackHashHex();
        sender.sendMessage(msg.getMessage("status.sha1",
                "hash", hash != null ? hash : "N/A"));

        // Display the output file size if it exists
        File outputFile = plugin.getOutputFile();
        if (outputFile.exists()) {
            long size = outputFile.length();
            String sizeStr;
            if (size < 1024) sizeStr = size + " B";
            else if (size < 1024 * 1024) sizeStr = String.format("%.1f KB", size / 1024.0);
            else sizeStr = String.format("%.1f MB", size / (1024.0 * 1024));
            sender.sendMessage(msg.getMessage("status.pack-size",
                    "size", sizeStr));
        }

        sender.sendMessage(msg.getMessage("status.merging",
                "status", plugin.isMerging() ? "Yes" : "No"));

        // Count and display the number of packs in the packs folder
        File packsFolder = plugin.getPacksFolder();
        File[] packs = packsFolder.listFiles();
        if (packs != null) {
            int count = 0;
            for (File f : packs) {
                // Skip override files and hidden files
                if (f.getName().equals("pack.mcmeta") || f.getName().equals("pack.png")) continue;
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory() || f.getName().endsWith(".zip")) count++;
            }
            sender.sendMessage(msg.getMessage("status.packs-in-folder",
                    "count", String.valueOf(count)));
        }

        return 1;
    }

    /**
     * Handles {@code /packmerger apply} (no player argument) — force-sends the pack
     * to all online players, bypassing the cache.
     *
     * @param ctx the Brigadier command context
     * @return 1 if the pack was sent, 0 if no merged pack is available
     */
    private int handleApplyAll(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        if (plugin.getCurrentPackUrl() == null) {
            sender.sendMessage(msg.getMessage("apply.no-pack"));
            return 0;
        }

        int count = Bukkit.getOnlinePlayers().size();
        plugin.getDistributor().sendToAll(true);
        sender.sendMessage(msg.getMessage("apply.sent-all",
                "count", String.valueOf(count)));
        return 1;
    }

    /**
     * Handles {@code /packmerger apply <player>} — force-sends the pack to a specific
     * player (or players matching the selector), bypassing the cache.
     *
     * @param ctx the Brigadier command context with a "player" argument
     * @return 1 if the pack was sent, 0 if no pack is available or no player matched
     */
    private int handleApplyPlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        if (plugin.getCurrentPackUrl() == null) {
            sender.sendMessage(msg.getMessage("apply.no-pack"));
            return 0;
        }

        try {
            // Resolve the player selector argument (supports @a, @p, player names, etc.)
            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
            List<Player> players = resolver.resolve(ctx.getSource());

            if (players.isEmpty()) {
                sender.sendMessage(msg.getMessage("apply.no-match"));
                return 0;
            }

            for (Player player : players) {
                plugin.getDistributor().sendPack(player, true);
                sender.sendMessage(msg.getMessage("apply.sent-player",
                        "player", player.getName()));
            }
        } catch (Exception e) {
            sender.sendMessage(msg.getMessage("apply.player-error",
                    "error", e.getMessage()));
            return 0;
        }

        return 1;
    }
}
