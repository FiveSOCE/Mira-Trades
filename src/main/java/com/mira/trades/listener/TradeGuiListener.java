package com.mira.trades.listener;

import com.mira.trades.MiraTradesPlugin;
import com.mira.trades.gui.MoneyHolder;
import com.mira.trades.gui.TradeHolder;
import com.mira.trades.model.TradeSession;
import com.mira.trades.service.TradeService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;

public final class TradeGuiListener implements Listener {
    private final MiraTradesPlugin plugin;
    private final TradeService service;

    public TradeGuiListener(MiraTradesPlugin plugin, TradeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();

        if (top.getHolder() instanceof TradeHolder holder) {
            handleTradeClick(event, player, holder);
            return;
        }
        if (top.getHolder() instanceof MoneyHolder holder) {
            handleMoneyClick(event, player, holder);
        }
    }

    private void handleTradeClick(InventoryClickEvent event, Player player, TradeHolder holder) {
        TradeSession session = service.sessionById(holder.sessionId());
        if (session == null || session.finished() || !session.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        if (raw >= 0 && raw < event.getView().getTopInventory().getSize()) {
            if (service.ownOfferSlot(session, player.getUniqueId(), raw)) {
                if (event.getAction() == InventoryAction.DROP_ALL_SLOT
                        || event.getAction() == InventoryAction.DROP_ONE_SLOT) {
                    event.setCancelled(true);
                    return;
                }
                service.deferChanged(session);
                return;
            }

            event.setCancelled(true);
            boolean left = session.isLeft(player.getUniqueId());
            if ((left && raw == TradeService.LEFT_MONEY) || (!left && raw == TradeService.RIGHT_MONEY)) {
                service.openMoney(player, session);
            } else if ((left && raw == TradeService.LEFT_CONFIRM) || (!left && raw == TradeService.RIGHT_CONFIRM)) {
                service.toggleConfirm(player, session);
            }
            return;
        }

        if (event.isShiftClick() && event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getBottomInventory())) {
            event.setCancelled(true);
            ItemStack current = event.getCurrentItem();
            if (current == null || current.getType().isAir()) return;
            int destination = service.firstEmptyOfferSlot(session, player.getUniqueId());
            if (destination < 0) {
                plugin.msg(player, "&eYour trade offer area is full.");
                return;
            }
            session.inventory().setItem(destination, current.clone());
            event.setCurrentItem(null);
            service.changed(session);
        }
    }

    private void handleMoneyClick(InventoryClickEvent event, Player player, MoneyHolder holder) {
        event.setCancelled(true);
        if (!holder.playerId().equals(player.getUniqueId())) return;
        TradeSession session = service.sessionById(holder.sessionId());
        if (session == null || session.finished() || !session.contains(player.getUniqueId())) {
            player.closeInventory();
            return;
        }

        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) return;

        double delta = service.buttonAmount(raw);
        if (delta != 0D) {
            service.adjustMoney(player, session, delta);
            refreshMoneyDisplay(event.getView().getTopInventory(), session.money(player.getUniqueId()));
            return;
        }

        if (raw == 20) {
            service.clearMoney(player, session);
            refreshMoneyDisplay(event.getView().getTopInventory(), 0D);
        } else if (raw == 22) {
            service.maxMoney(player, session);
            refreshMoneyDisplay(event.getView().getTopInventory(), session.money(player.getUniqueId()));
        } else if (raw == 26) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();

        if (top.getHolder() instanceof MoneyHolder) {
            event.setCancelled(true);
            return;
        }
        if (!(top.getHolder() instanceof TradeHolder holder)) return;

        TradeSession session = service.sessionById(holder.sessionId());
        if (session == null || session.finished() || !session.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        boolean touchesOffer = false;
        for (int raw : event.getRawSlots()) {
            if (raw >= top.getSize()) continue;
            if (!service.ownOfferSlot(session, player.getUniqueId(), raw)) {
                event.setCancelled(true);
                return;
            }
            touchesOffer = true;
        }
        if (touchesOffer) service.deferChanged(session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();

        if (top.getHolder() instanceof TradeHolder holder) {
            if (service.consumeNavigation(player.getUniqueId())) return;
            TradeSession session = service.sessionById(holder.sessionId());
            if (session != null && !session.finished()) {
                service.cancel(player, "Trade cancelled because the trade window was closed.");
            }
            return;
        }

        if (top.getHolder() instanceof MoneyHolder holder) {
            service.returnFromMoney(player, holder.sessionId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.deliverPending(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }

    private void refreshMoneyDisplay(Inventory inventory, double value) {
        inventory.setItem(13, named(Material.GOLD_BLOCK, "Offer: " + formatMoney(value)));
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.customName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }

    private String formatMoney(double value) {
        if (Math.rint(value) == value) return "$" + String.format(Locale.US, "%,.0f", value);
        return "$" + String.format(Locale.US, "%,.2f", value);
    }
}
