package com.mira.trades.service;

import com.mira.core.api.MiraCore;
import com.mira.trades.MiraTradesPlugin;
import com.mira.trades.gui.MoneyHolder;
import com.mira.trades.model.TradeRequest;
import com.mira.trades.model.TradeSession;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class TradeService {
    public static final int[] LEFT_SLOTS = {0,1,2,3,9,10,11,12,18,19,20,21,27,28,29,30,36,37,38,39};
    public static final int[] RIGHT_SLOTS = {5,6,7,8,14,15,16,17,23,24,25,26,32,33,34,35,41,42,43,44};
    public static final int LEFT_MONEY = 46;
    public static final int LEFT_CONFIRM = 47;
    public static final int RIGHT_CONFIRM = 51;
    public static final int RIGHT_MONEY = 52;

    private final MiraTradesPlugin plugin;
    private final MiraCore core;
    private final Economy economy;
    private final Map<UUID, Map<UUID, TradeRequest>> requests = new HashMap<>();
    private final Map<UUID, TradeSession> sessionsByPlayer = new HashMap<>();
    private final Map<UUID, TradeSession> sessionsById = new HashMap<>();
    private final Set<UUID> navigating = new HashSet<>();
    private final File returnsFile;
    private final YamlConfiguration returns;

    public TradeService(MiraTradesPlugin plugin, MiraCore core, Economy economy) {
        this.plugin = plugin;
        this.core = core;
        this.economy = economy;
        this.returnsFile = new File(plugin.getDataFolder(), "returns.yml");
        this.returns = YamlConfiguration.loadConfiguration(returnsFile);
    }

    public void request(Player requester, Player target) {
        cleanupRequests();
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            plugin.msg(requester, "&cYou cannot trade with yourself.");
            return;
        }
        if (sessionsByPlayer.containsKey(requester.getUniqueId()) || sessionsByPlayer.containsKey(target.getUniqueId())) {
            plugin.msg(requester, "&cOne of you is already trading.");
            return;
        }
        if (!plugin.requestsEnabled(target.getUniqueId())) {
            plugin.msg(requester, "&e" + target.getName() + " is not accepting trade requests.");
            return;
        }

        long ttl = Math.max(5L, plugin.getConfig().getLong("requests.expire-seconds", 30L)) * 1000L;
        TradeRequest request = new TradeRequest(requester.getUniqueId(), target.getUniqueId(), System.currentTimeMillis() + ttl);
        requests.computeIfAbsent(target.getUniqueId(), ignored -> new LinkedHashMap<>()).put(requester.getUniqueId(), request);

        plugin.msg(requester, "&aTrade request sent to &f" + target.getName() + "&a.");
        plugin.msg(target, "&f" + requester.getName() + " &ewants to trade. &f/trade accept " + requester.getName()
                + " &7or &f/trade deny " + requester.getName());
    }

    public void accept(Player target, String requesterName) {
        cleanupRequests();
        Player requester = Bukkit.getPlayerExact(requesterName);
        if (requester == null) {
            plugin.msg(target, "&cThat requester is no longer online.");
            return;
        }

        Map<UUID, TradeRequest> incoming = requests.getOrDefault(target.getUniqueId(), Map.of());
        TradeRequest request = incoming.get(requester.getUniqueId());
        if (request == null || request.expired(System.currentTimeMillis())) {
            plugin.msg(target, "&cNo active trade request from that player.");
            return;
        }
        if (sessionsByPlayer.containsKey(target.getUniqueId()) || sessionsByPlayer.containsKey(requester.getUniqueId())) {
            plugin.msg(target, "&cOne of you is already trading.");
            return;
        }

        incoming.remove(requester.getUniqueId());
        if (incoming.isEmpty()) requests.remove(target.getUniqueId());
        start(requester, target);
    }

    public void deny(Player target, String requesterName) {
        cleanupRequests();
        Map<UUID, TradeRequest> incoming = requests.get(target.getUniqueId());
        if (incoming == null || incoming.isEmpty()) {
            plugin.msg(target, "&7You have no pending trade requests.");
            return;
        }

        if (requesterName == null) {
            incoming.clear();
            requests.remove(target.getUniqueId());
            plugin.msg(target, "&eAll pending trade requests denied.");
            return;
        }

        UUID match = null;
        for (UUID requesterId : incoming.keySet()) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(requesterId).getName()).orElse("");
            if (name.equalsIgnoreCase(requesterName)) {
                match = requesterId;
                break;
            }
        }
        if (match == null) {
            plugin.msg(target, "&cNo pending request from that player.");
            return;
        }

        incoming.remove(match);
        if (incoming.isEmpty()) requests.remove(target.getUniqueId());
        Player requester = Bukkit.getPlayer(match);
        if (requester != null) plugin.msg(requester, "&e" + target.getName() + " declined your trade request.");
        plugin.msg(target, "&eTrade request denied.");
    }

    private void start(Player left, Player right) {
        TradeSession session = new TradeSession(left.getUniqueId(), right.getUniqueId());
        sessionsById.put(session.id(), session);
        sessionsByPlayer.put(left.getUniqueId(), session);
        sessionsByPlayer.put(right.getUniqueId(), session);
        refresh(session);

        left.openInventory(session.inventory());
        right.openInventory(session.inventory());
        plugin.msg(left, "&aTrade started with &f" + right.getName() + "&a.");
        plugin.msg(right, "&aTrade started with &f" + left.getName() + "&a.");

        core.audit().record("MiraTrades", "TRADE_STARTED", left.getUniqueId(), left.getName(),
                right.getUniqueId().toString(), "Secure player trade started",
                Map.of("otherName", right.getName(), "session", session.id().toString()));
    }

    public TradeSession session(UUID player) { return sessionsByPlayer.get(player); }
    public TradeSession sessionById(UUID id) { return sessionsById.get(id); }

    public boolean ownOfferSlot(TradeSession session, UUID player, int rawSlot) {
        return contains(session.isLeft(player) ? LEFT_SLOTS : RIGHT_SLOTS, rawSlot);
    }

    public boolean oppositeOfferSlot(TradeSession session, UUID player, int rawSlot) {
        return contains(session.isLeft(player) ? RIGHT_SLOTS : LEFT_SLOTS, rawSlot);
    }

    public int firstEmptyOfferSlot(TradeSession session, UUID player) {
        for (int slot : session.isLeft(player) ? LEFT_SLOTS : RIGHT_SLOTS) {
            ItemStack item = session.inventory().getItem(slot);
            if (item == null || item.getType().isAir()) return slot;
        }
        return -1;
    }

    public void changed(TradeSession session) {
        if (session == null || session.finished()) return;
        cancelCountdown(session);
        session.resetConfirmations();
        refresh(session);
    }

    public void deferChanged(TradeSession session) {
        Bukkit.getScheduler().runTask(plugin, () -> changed(session));
    }

    public void toggleConfirm(Player player, TradeSession session) {
        if (session == null || session.finished()) return;
        session.confirmed(player.getUniqueId(), !session.confirmed(player.getUniqueId()));
        refresh(session);
        if (session.bothConfirmed()) startCountdown(session);
        else cancelCountdown(session);
    }

    public void openMoney(Player player, TradeSession session) {
        if (session == null || session.finished()) return;
        navigating.add(player.getUniqueId());
        player.openInventory(moneyInventory(player, session));
    }

    public boolean consumeNavigation(UUID player) {
        return navigating.remove(player);
    }

    public void returnFromMoney(Player player, UUID sessionId) {
        TradeSession session = sessionsById.get(sessionId);
        if (session == null || session.finished() || !session.contains(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!session.finished() && player.isOnline()) player.openInventory(session.inventory());
        });
    }

    public void adjustMoney(Player player, TradeSession session, double delta) {
        if (session == null || session.finished()) return;
        double current = session.money(player.getUniqueId());
        double maxConfigured = Math.max(0D, plugin.getConfig().getDouble("trade.max-money", 1_000_000_000_000D));
        double max = Math.min(maxConfigured, Math.max(0D, economy.getBalance(player)));
        double next = Math.max(0D, Math.min(max, current + delta));
        if (!Double.isFinite(next)) return;
        session.money(player.getUniqueId(), next);
        changed(session);
    }

    public void clearMoney(Player player, TradeSession session) {
        if (session == null || session.finished()) return;
        session.money(player.getUniqueId(), 0D);
        changed(session);
    }

    public void maxMoney(Player player, TradeSession session) {
        if (session == null || session.finished()) return;
        double configured = Math.max(0D, plugin.getConfig().getDouble("trade.max-money", 1_000_000_000_000D));
        double max = Math.min(configured, Math.max(0D, economy.getBalance(player)));
        if (!Double.isFinite(max)) return;
        session.money(player.getUniqueId(), max);
        changed(session);
    }

    public void cancel(Player actor, String reason) {
        TradeSession session = sessionsByPlayer.get(actor.getUniqueId());
        if (session == null) {
            plugin.msg(actor, "&7You are not in an active trade.");
            return;
        }
        cancelSession(session, reason, actor.getUniqueId());
    }

    public void handleQuit(Player player) {
        TradeSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session != null) cancelSession(session, "Trade cancelled because a player disconnected.", player.getUniqueId());
    }

    public void deliverPending(Player player) {
        String path = "returns." + player.getUniqueId();
        List<ItemStack> pending = readItems(path);
        if (pending.isEmpty()) return;

        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack item : pending) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
            remaining.addAll(leftovers.values().stream().map(ItemStack::clone).toList());
        }

        if (remaining.isEmpty()) {
            returns.set(path, null);
            plugin.msg(player, "&aYour pending MiraTrades escrow items were returned.");
        } else {
            returns.set(path, remaining);
            plugin.msg(player, "&eSome MiraTrades escrow items are still waiting for inventory space.");
        }
        saveReturns();
    }

    public void shutdown() {
        for (TradeSession session : new HashSet<>(sessionsById.values())) {
            cancelSession(session, "Server/plugin shutdown.", null);
        }
        saveReturns();
    }

    public void refresh(TradeSession session) {
        if (session == null || session.finished()) return;
        Inventory inv = session.inventory();

        ItemStack divider = item(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int slot : new int[]{4,13,22,31,40,45,48,49,50,53}) inv.setItem(slot, divider.clone());

        inv.setItem(LEFT_MONEY, item(Material.GOLD_INGOT,
                moneyName(session.left(), session.money(session.left()))));
        inv.setItem(RIGHT_MONEY, item(Material.GOLD_INGOT,
                moneyName(session.right(), session.money(session.right()))));

        inv.setItem(LEFT_CONFIRM, confirmItem(session.left(), session.confirmed(session.left())));
        inv.setItem(RIGHT_CONFIRM, confirmItem(session.right(), session.confirmed(session.right())));
    }

    private Inventory moneyInventory(Player player, TradeSession session) {
        Inventory inv = Bukkit.createInventory(new MoneyHolder(session.id(), player.getUniqueId()), 27, "Trade Money");
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler.clone());

        double small = button("small", 100D);
        double medium = button("medium", 1000D);
        double large = button("large", 10000D);
        double huge = button("huge", 100000D);

        inv.setItem(0, item(Material.LIME_DYE, "+" + formatMoney(small)));
        inv.setItem(1, item(Material.LIME_DYE, "+" + formatMoney(medium)));
        inv.setItem(2, item(Material.LIME_DYE, "+" + formatMoney(large)));
        inv.setItem(3, item(Material.LIME_DYE, "+" + formatMoney(huge)));
        inv.setItem(5, item(Material.RED_DYE, "-" + formatMoney(small)));
        inv.setItem(6, item(Material.RED_DYE, "-" + formatMoney(medium)));
        inv.setItem(7, item(Material.RED_DYE, "-" + formatMoney(large)));
        inv.setItem(8, item(Material.RED_DYE, "-" + formatMoney(huge)));

        inv.setItem(13, item(Material.GOLD_BLOCK, "Offer: " + formatMoney(session.money(player.getUniqueId()))));
        inv.setItem(20, item(Material.BARRIER, "Clear Offer"));
        inv.setItem(22, item(Material.EMERALD_BLOCK, "Offer Maximum Available"));
        inv.setItem(26, item(Material.ARROW, "Back to Trade"));
        return inv;
    }

    public double buttonAmount(int slot) {
        return switch (slot) {
            case 0 -> button("small", 100D);
            case 1 -> button("medium", 1000D);
            case 2 -> button("large", 10000D);
            case 3 -> button("huge", 100000D);
            case 5 -> -button("small", 100D);
            case 6 -> -button("medium", 1000D);
            case 7 -> -button("large", 10000D);
            case 8 -> -button("huge", 100000D);
            default -> 0D;
        };
    }

    private double button(String key, double fallback) {
        return Math.max(0D, plugin.getConfig().getDouble("trade.money-buttons." + key, fallback));
    }

    private void startCountdown(TradeSession session) {
        cancelCountdown(session);
        int seconds = Math.max(1, plugin.getConfig().getInt("trade.confirm-countdown-seconds", 3));

        BukkitTask task = new BukkitRunnable() {
            private int remaining = seconds;

            @Override
            public void run() {
                if (session.finished() || !session.bothConfirmed()) {
                    cancel();
                    session.countdown(null);
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    session.countdown(null);
                    complete(session);
                    return;
                }
                notifyBoth(session, "&aTrade locked. Completing in &f" + remaining + "&a...");
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
        session.countdown(task);
    }

    private void complete(TradeSession session) {
        if (session.finished() || !session.bothConfirmed()) return;

        Player left = Bukkit.getPlayer(session.left());
        Player right = Bukkit.getPlayer(session.right());
        if (left == null || right == null || !left.isOnline() || !right.isOnline()) {
            cancelSession(session, "Trade cancelled because a player disconnected.", null);
            return;
        }

        double leftMoney = session.money(left.getUniqueId());
        double rightMoney = session.money(right.getUniqueId());
        if (!validMoney(leftMoney) || !validMoney(rightMoney)
                || economy.getBalance(left) + 1.0E-7 < leftMoney
                || economy.getBalance(right) + 1.0E-7 < rightMoney) {
            failConfirmation(session, "&cTrade could not complete because a money offer is no longer available.");
            return;
        }

        List<ItemStack> leftItems = offered(session.inventory(), LEFT_SLOTS);
        List<ItemStack> rightItems = offered(session.inventory(), RIGHT_SLOTS);

        ItemStack[] leftOriginal = cloneContents(left.getInventory().getStorageContents());
        ItemStack[] rightOriginal = cloneContents(right.getInventory().getStorageContents());
        ItemStack[] leftResult = mergeIntoStorage(leftOriginal, rightItems);
        ItemStack[] rightResult = mergeIntoStorage(rightOriginal, leftItems);
        if (leftResult == null || rightResult == null) {
            failConfirmation(session, "&cTrade could not complete because a receiving inventory is full.");
            return;
        }

        MoneyTransaction transaction = transferMoney(left, right, leftMoney, rightMoney);
        if (!transaction.success()) {
            failConfirmation(session, "&cTrade could not complete because the economy rejected the transaction.");
            return;
        }

        try {
            left.getInventory().setStorageContents(leftResult);
            right.getInventory().setStorageContents(rightResult);
        } catch (RuntimeException exception) {
            try { left.getInventory().setStorageContents(leftOriginal); } catch (RuntimeException ignored) { }
            try { right.getInventory().setStorageContents(rightOriginal); } catch (RuntimeException ignored) { }
            rollbackMoney(left, right, leftMoney, rightMoney, transaction);
            plugin.getLogger().severe("Trade item commit failed for session " + session.id() + ": " + exception.getMessage());
            failConfirmation(session, "&cTrade could not complete safely. Nothing was intentionally consumed.");
            return;
        }

        clearSlots(session.inventory(), LEFT_SLOTS);
        clearSlots(session.inventory(), RIGHT_SLOTS);
        session.finished(true);
        cancelCountdown(session);
        removeSession(session);
        left.closeInventory();
        right.closeInventory();

        plugin.msg(left, "&aTrade completed with &f" + right.getName() + "&a.");
        plugin.msg(right, "&aTrade completed with &f" + left.getName() + "&a.");

        core.audit().record("MiraTrades", "TRADE_COMPLETED", left.getUniqueId(), left.getName(),
                right.getUniqueId().toString(), "Secure trade completed",
                Map.of("session", session.id().toString(),
                        "leftMoney", Double.toString(leftMoney),
                        "rightMoney", Double.toString(rightMoney),
                        "leftItems", Integer.toString(leftItems.size()),
                        "rightItems", Integer.toString(rightItems.size())));
    }

    private MoneyTransaction transferMoney(Player left, Player right, double leftMoney, double rightMoney) {
        boolean leftWithdrawn = leftMoney <= 0D;
        boolean rightWithdrawn = rightMoney <= 0D;
        boolean leftDeposited = rightMoney <= 0D;
        boolean rightDeposited = leftMoney <= 0D;

        if (leftMoney > 0D) {
            EconomyResponse response = economy.withdrawPlayer(left, leftMoney);
            leftWithdrawn = response != null && response.transactionSuccess();
            if (!leftWithdrawn) return new MoneyTransaction(false, false, false, false, false);
        }
        if (rightMoney > 0D) {
            EconomyResponse response = economy.withdrawPlayer(right, rightMoney);
            rightWithdrawn = response != null && response.transactionSuccess();
            if (!rightWithdrawn) {
                if (leftMoney > 0D) economy.depositPlayer(left, leftMoney);
                return new MoneyTransaction(false, leftWithdrawn, false, false, false);
            }
        }
        if (rightMoney > 0D) {
            EconomyResponse response = economy.depositPlayer(left, rightMoney);
            leftDeposited = response != null && response.transactionSuccess();
            if (!leftDeposited) {
                if (leftMoney > 0D) economy.depositPlayer(left, leftMoney);
                if (rightMoney > 0D) economy.depositPlayer(right, rightMoney);
                return new MoneyTransaction(false, leftWithdrawn, rightWithdrawn, false, false);
            }
        }
        if (leftMoney > 0D) {
            EconomyResponse response = economy.depositPlayer(right, leftMoney);
            rightDeposited = response != null && response.transactionSuccess();
            if (!rightDeposited) {
                if (rightMoney > 0D) economy.withdrawPlayer(left, rightMoney);
                if (leftMoney > 0D) economy.depositPlayer(left, leftMoney);
                if (rightMoney > 0D) economy.depositPlayer(right, rightMoney);
                return new MoneyTransaction(false, leftWithdrawn, rightWithdrawn, leftDeposited, false);
            }
        }
        return new MoneyTransaction(true, leftWithdrawn, rightWithdrawn, leftDeposited, rightDeposited);
    }

    private void rollbackMoney(Player left, Player right, double leftMoney, double rightMoney, MoneyTransaction transaction) {
        if (transaction.leftDeposited() && rightMoney > 0D) economy.withdrawPlayer(left, rightMoney);
        if (transaction.rightDeposited() && leftMoney > 0D) economy.withdrawPlayer(right, leftMoney);
        if (transaction.leftWithdrawn() && leftMoney > 0D) economy.depositPlayer(left, leftMoney);
        if (transaction.rightWithdrawn() && rightMoney > 0D) economy.depositPlayer(right, rightMoney);
    }

    private void failConfirmation(TradeSession session, String message) {
        cancelCountdown(session);
        session.resetConfirmations();
        refresh(session);
        notifyBoth(session, message);
    }

    private void cancelSession(TradeSession session, String reason, UUID actor) {
        if (session == null || session.finished()) return;
        session.finished(true);
        cancelCountdown(session);

        returnEscrow(session.left(), offered(session.inventory(), LEFT_SLOTS));
        returnEscrow(session.right(), offered(session.inventory(), RIGHT_SLOTS));
        clearSlots(session.inventory(), LEFT_SLOTS);
        clearSlots(session.inventory(), RIGHT_SLOTS);
        removeSession(session);

        Player left = Bukkit.getPlayer(session.left());
        Player right = Bukkit.getPlayer(session.right());
        if (left != null) {
            left.closeInventory();
            plugin.msg(left, "&e" + reason);
        }
        if (right != null) {
            right.closeInventory();
            plugin.msg(right, "&e" + reason);
        }

        core.audit().record("MiraTrades", "TRADE_CANCELLED", actor,
                actor == null ? "system" : Optional.ofNullable(Bukkit.getOfflinePlayer(actor).getName()).orElse("player"),
                session.id().toString(), reason);
    }

    private void returnEscrow(UUID owner, List<ItemStack> items) {
        if (items.isEmpty()) return;
        Player player = Bukkit.getPlayer(owner);
        List<ItemStack> pending = new ArrayList<>();
        if (player != null && player.isOnline()) {
            for (ItemStack item : items) {
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
                pending.addAll(leftovers.values().stream().map(ItemStack::clone).toList());
            }
        } else {
            pending.addAll(items.stream().map(ItemStack::clone).toList());
        }
        if (!pending.isEmpty()) queueReturn(owner, pending);
    }

    private void queueReturn(UUID owner, List<ItemStack> items) {
        String path = "returns." + owner;
        List<ItemStack> existing = readItems(path);
        existing.addAll(items.stream().map(ItemStack::clone).toList());
        returns.set(path, existing);
        saveReturns();
    }

    private List<ItemStack> readItems(String path) {
        List<?> raw = returns.getList(path, List.of());
        List<ItemStack> items = new ArrayList<>();
        for (Object value : raw) if (value instanceof ItemStack item) items.add(item.clone());
        return items;
    }

    private void saveReturns() {
        try {
            plugin.getDataFolder().mkdirs();
            returns.save(returnsFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save returns.yml: " + ex.getMessage());
        }
    }

    private void removeSession(TradeSession session) {
        sessionsById.remove(session.id());
        sessionsByPlayer.remove(session.left(), session);
        sessionsByPlayer.remove(session.right(), session);
        navigating.remove(session.left());
        navigating.remove(session.right());
    }

    private void cancelCountdown(TradeSession session) {
        BukkitTask task = session.countdown();
        if (task != null) {
            task.cancel();
            session.countdown(null);
        }
    }

    private void notifyBoth(TradeSession session, String message) {
        Player left = Bukkit.getPlayer(session.left());
        Player right = Bukkit.getPlayer(session.right());
        if (left != null) plugin.msg(left, message);
        if (right != null) plugin.msg(right, message);
    }

    private ItemStack confirmItem(UUID player, boolean confirmed) {
        String name = Optional.ofNullable(Bukkit.getOfflinePlayer(player).getName()).orElse("Player");
        return item(confirmed ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                name + ": " + (confirmed ? "CONFIRMED" : "Click to Confirm"));
    }

    private String moneyName(UUID player, double money) {
        String name = Optional.ofNullable(Bukkit.getOfflinePlayer(player).getName()).orElse("Player");
        return name + " Money: " + formatMoney(money);
    }

    private ItemStack item(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.customName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }

    private static List<ItemStack> offered(Inventory inventory, int[] slots) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) items.add(item.clone());
        }
        return items;
    }

    private static void clearSlots(Inventory inventory, int[] slots) {
        for (int slot : slots) inventory.setItem(slot, null);
    }

    private static ItemStack[] mergeIntoStorage(ItemStack[] source, List<ItemStack> additions) {
        ItemStack[] result = cloneContents(source);

        for (ItemStack addition : additions) {
            ItemStack remaining = addition.clone();

            for (int i = 0; i < result.length && remaining.getAmount() > 0; i++) {
                ItemStack current = result[i];
                if (current == null || current.getType().isAir() || !current.isSimilar(remaining)) continue;
                int space = Math.max(0, current.getMaxStackSize() - current.getAmount());
                if (space <= 0) continue;
                int moved = Math.min(space, remaining.getAmount());
                current.setAmount(current.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
            }

            while (remaining.getAmount() > 0) {
                int empty = firstEmpty(result);
                if (empty < 0) return null;
                int moved = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
                ItemStack placed = remaining.clone();
                placed.setAmount(moved);
                result[empty] = placed;
                remaining.setAmount(remaining.getAmount() - moved);
            }
        }
        return result;
    }

    private static int firstEmpty(ItemStack[] items) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null || items[i].getType().isAir()) return i;
        }
        return -1;
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }

    private static boolean contains(int[] slots, int value) {
        for (int slot : slots) if (slot == value) return true;
        return false;
    }

    private static boolean validMoney(double value) {
        return Double.isFinite(value) && value >= 0D;
    }

    private static String formatMoney(double value) {
        if (Math.rint(value) == value) return "$" + String.format(Locale.US, "%,.0f", value);
        return "$" + String.format(Locale.US, "%,.2f", value);
    }

    private void cleanupRequests() {
        long now = System.currentTimeMillis();
        requests.values().forEach(map -> map.values().removeIf(request -> request.expired(now)));
        requests.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private record MoneyTransaction(boolean success, boolean leftWithdrawn, boolean rightWithdrawn,
                                    boolean leftDeposited, boolean rightDeposited) { }
}
