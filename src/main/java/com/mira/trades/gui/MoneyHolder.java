package com.mira.trades.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public record MoneyHolder(UUID sessionId, UUID playerId) implements InventoryHolder {
    @Override public Inventory getInventory() { return null; }
}
