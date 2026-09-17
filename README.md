# MiraTrades

Secure player-to-player trading for the Mira Paper server suite.

MiraTrades supports escrowed item trading, Vault-backed money offers, dual confirmation, persistent trade-request preferences and recovery-safe cancellation handling.

## Current Release

**v0.1.2** — compatible with Paper/Minecraft **1.21.11 through 26.2** using Java 21 bytecode.

[View releases](https://github.com/FiveSOCE/Mira-Trades/releases)

## Requirements

- Paper 1.21.11 through 26.2
- Java 21
- MiraCore 0.3.0+
- Vault
- a Vault-compatible economy provider

## Secure Trading

Trade items are placed into a shared escrow inventory rather than copied or tracked only by display metadata.

A trade supports:

- item offers from both players
- Vault money offers from both players
- independent confirmation from both players
- final countdown once both sides confirm
- automatic confirmation reset whenever an offer changes
- cancellation when either player closes the trade
- cancellation/recovery if either player disconnects
- inventory-capacity validation before delivery
- Vault balance validation immediately before completion
- persistent emergency returns when items cannot be returned immediately

Trade completion is deliberately transactional. Both sides are validated before the exchange commits, and escrowed items are not intentionally consumed when a trade fails.

## Money Offers

The dedicated money-offer GUI provides configurable increment/decrement buttons, clear-offer controls and a maximum available balance option.

Money is not withdrawn simply because it is offered. Vault transactions occur only when the final trade completes successfully.

## Trade Request Privacy

Players can disable or re-enable incoming trade requests with:

```text
/trade toggle
```

The preference persists in:

```text
plugins/MiraTrades/settings.yml
```

## Commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/trade <player>` | `miratrades.use` | Sends a trade request. |
| `/trade accept <player>` | `miratrades.use` | Accepts a pending request. |
| `/trade deny [player]` | `miratrades.use` | Denies one or all pending requests. |
| `/trade cancel` | `miratrades.use` | Cancels the active trade and safely returns escrow. |
| `/trade toggle` | `miratrades.use` | Enables/disables incoming requests. |

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `miratrades.use` | Everyone | Normal MiraTrades use. |

## Data Files

```text
config.yml   - request expiry, countdown and money button values
settings.yml - persistent per-player trade request preference
returns.yml  - emergency escrow-return queue
```

## MiraCore Integration

Trade start, completion and cancellation are recorded through MiraCore's shared audit service.

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.
