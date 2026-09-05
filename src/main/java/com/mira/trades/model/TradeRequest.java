package com.mira.trades.model;

import java.util.UUID;

public record TradeRequest(UUID requester, UUID target, long expiresAt) {
    public boolean expired(long now) { return now >= expiresAt; }
}
