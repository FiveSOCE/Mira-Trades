package com.mira.trades;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.trades.listener.TradeGuiListener;
import com.mira.trades.service.TradeService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class MiraTradesPlugin extends JavaPlugin implements TabExecutor {
    private MiraCore core;
    private Economy economy;
    private TradeService trades;
    private File settingsFile;
    private YamlConfiguration settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();
        var registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            throw new IllegalStateException("Vault economy provider is required for MiraTrades.");
        }
        economy = registration.getProvider();

        settingsFile = new File(getDataFolder(), "settings.yml");
        settings = YamlConfiguration.loadConfiguration(settingsFile);

        trades = new TradeService(this, core, economy);
        getServer().getPluginManager().registerEvents(new TradeGuiListener(this, trades), this);

        var command = Objects.requireNonNull(getCommand("trade"), "trade command missing");
        command.setExecutor(this);
        command.setTabCompleter(this);

        core.modules().register(this, "MiraTrades");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Secure escrow item trading, Vault money offers and request privacy controls ready");
        getLogger().info("MiraTrades v" + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (trades != null) trades.shutdown();
        saveSettings();
        if (core != null) core.modules().unregister(this);
    }

    public boolean requestsEnabled(UUID player) {
        return settings.getBoolean("players." + player + ".requests-enabled", true);
    }

    public boolean toggleRequests(UUID player) {
        boolean next = !requestsEnabled(player);
        settings.set("players." + player + ".requests-enabled", next);
        saveSettings();
        return next;
    }

    private void saveSettings() {
        try {
            getDataFolder().mkdirs();
            settings.save(settingsFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save settings.yml: " + ex.getMessage());
        }
    }

    public void msg(CommandSender sender, String raw) {
        core.messages().send(sender, raw);
    }

    public MiraCore core() { return core; }
    public Economy economy() { return economy; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayer-only command.");
            return true;
        }
        if (!player.hasPermission("miratrades.use")) {
            msg(player, "&cYou do not have permission.");
            return true;
        }

        if (args.length == 0) {
            help(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "toggle" -> {
                boolean enabled = toggleRequests(player.getUniqueId());
                msg(player, enabled ? "&aTrade requests enabled." : "&eTrade requests disabled.");
            }
            case "accept" -> {
                if (args.length < 2) {
                    msg(player, "&eUsage: /trade accept <player>");
                } else {
                    trades.accept(player, args[1]);
                }
            }
            case "deny" -> trades.deny(player, args.length >= 2 ? args[1] : null);
            case "cancel" -> trades.cancel(player, "Cancelled by player.");
            default -> {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    msg(player, "&cThat player is not online.");
                } else {
                    trades.request(player, target);
                }
            }
        }
        return true;
    }

    private void help(Player player) {
        msg(player, "&d/trade <player> &7- Send a trade request");
        msg(player, "&d/trade accept <player> &7- Accept a request");
        msg(player, "&d/trade deny [player] &7- Deny request(s)");
        msg(player, "&d/trade cancel &7- Cancel your active trade");
        msg(player, "&d/trade toggle &7- Enable/disable incoming requests");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("accept", "deny", "cancel", "toggle"));
            values.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            return complete(args[0], values);
        }
        if (args.length == 2 && List.of("accept", "deny").contains(args[0].toLowerCase(Locale.ROOT))) {
            return complete(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }
}
