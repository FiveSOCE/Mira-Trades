# MiraTrades

MiraTrades is the secure player-to-player trading module for the Mira Paper server suite. It supports escrowed item trading, Vault-backed money offers, dual confirmation, persistent trade-request preferences, and recovery-safe cancellation handling.

## Current Version

**v0.1.1**

## Download

[**Download MiraTrades v0.1.1**](https://github.com/FiveSOCE/Mira-Trades/releases/download/v0.1.1/MiraTrades-0.1.1.jar)

[View All Releases](https://github.com/FiveSOCE/Mira-Trades/releases)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.3.0 or newer
- Vault
- A Vault-compatible economy provider

## Secure Trading

Trade items are placed into a shared escrow inventory rather than being copied or tracked only by lore/name.

A trade supports:

- item offers from both players
- Vault money offers from both players
- independent confirmation from both players
- a final countdown once both sides confirm
- automatic confirmation reset whenever an item or money offer changes
- cancellation when either player closes the trade
- cancellation and safe escrow recovery if either player disconnects
- inventory-capacity validation before final delivery
- Vault-balance validation immediately before completion
- persistent emergency returns if items cannot be returned immediately

Trade completion is deliberately transactional. MiraTrades validates both sides before committing the exchange and never intentionally consumes escrowed items on a failed trade.

## Money Offer GUI

The dedicated money screen provides configurable increment/decrement buttons, clear offer, and maximum available balance controls.

Money is not withdrawn simply because it is offered. Vault transactions occur only when the final trade successfully completes.

## Trade Request Privacy

Players can disable or re-enable incoming trade requests with `/trade toggle`.

That preference is stored persistently in `plugins/MiraTrades/settings.yml`.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/trade <player>` | `miratrades.use` | Sends a trade request. |
| `/trade accept <player>` | `miratrades.use` | Accepts a pending request. |
| `/trade deny [player]` | `miratrades.use` | Denies one request or all pending requests. |
| `/trade cancel` | `miratrades.use` | Cancels the active trade and returns escrow. |
| `/trade toggle` | `miratrades.use` | Enables/disables incoming requests. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miratrades.use` | Everyone | Allows normal MiraTrades use. |

## Configuration / Files

- `config.yml` - request expiry, confirmation countdown and money-button values
- `settings.yml` - persistent per-player request preference
- `returns.yml` - emergency escrow-return queue for items that could not be returned immediately

## MiraCore Integration

Trade start, completion and cancellation are recorded through the shared MiraCore audit service.

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.
