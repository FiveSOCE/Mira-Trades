package com.mira.trades.model;

import com.mira.trades.gui.TradeHolder;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class TradeSession {
    private final UUID id = UUID.randomUUID();
    private final UUID left;
    private final UUID right;
    private final Inventory inventory;
    private double leftMoney;
    private double rightMoney;
    private boolean leftConfirmed;
    private boolean rightConfirmed;
    private boolean finished;
    private BukkitTask countdown;

    public TradeSession(UUID left, UUID right) {
        this.left = left;
        this.right = right;
        this.inventory = Bukkit.createInventory(new TradeHolder(id), 54, "Mira Trade");
    }

    public UUID id() { return id; }
    public UUID left() { return left; }
    public UUID right() { return right; }
    public Inventory inventory() { return inventory; }
    public boolean isLeft(UUID player) { return left.equals(player); }
    public boolean contains(UUID player) { return left.equals(player) || right.equals(player); }
    public UUID other(UUID player) { return left.equals(player) ? right : left; }
    public double money(UUID player) { return left.equals(player) ? leftMoney : rightMoney; }
    public void money(UUID player, double value) { if (left.equals(player)) leftMoney = value; else rightMoney = value; }
    public boolean confirmed(UUID player) { return left.equals(player) ? leftConfirmed : rightConfirmed; }
    public void confirmed(UUID player, boolean value) { if (left.equals(player)) leftConfirmed = value; else rightConfirmed = value; }
    public boolean bothConfirmed() { return leftConfirmed && rightConfirmed; }
    public void resetConfirmations() { leftConfirmed = false; rightConfirmed = false; }
    public boolean finished() { return finished; }
    public void finished(boolean value) { finished = value; }
    public BukkitTask countdown() { return countdown; }
    public void countdown(BukkitTask task) { countdown = task; }
}
