# AdCache V4 (Vladimir)

> **WARNING: This is an experimental implementation. Not intended for production use.**

Two-slot ad cache with discovery, rebid, and refill phases.

## How It Works

### Cache Slots

Two priority slots managed by `CacheSlotManager`:
- **Slot1** — highest-priced ad (returned by `peek()`/`pop()`)
- **Slot2** — backup ad (promoted to slot1 on pop or expiration)

Higher-priced inserts hot-swap slot1, demoting the old ad to slot2. Both slots always hold different networks.

### Loading Phases

**Discovery** (once per session) — walks waterfall at $0.01 pricefloor, stops on first fill. Stores RTB tokens.

**Rebid** (follows Discovery) — new auction at fill price so RTB networks compete. Excludes the Discovery winner. Walks remaining Discovery units if slots aren't full.

**Refill** (all subsequent loads) — auction at last shown price, reuses stored RTB tokens. Skips networks already cached. Walks remaining units from previous waterfalls.

### State Persistence

Appodeal recreates the cache instance on every show cycle (`clear()` → new instance). A companion object preserves state across recreations:
- Discovery completion flag
- RTB tokens (30-min per-network expiration)
- Price context (`lastShownPrice`, `rtbRequestedPrice`)
- Cached ads (eagerly snapshot on `pop()`, also extracted on `clear()`, restored in `init`)
- Remaining waterfall units

### Show Fallback

If the primary ad fails to show, the backup from slot2 is automatically shown.

## Files

| File | Purpose |
|------|---------|
| `AdCacheVladimirImpl.kt` | Orchestrator: phases, callbacks, state persistence |
| `CacheSlotManager.kt` | Two-slot priority cache with hot-swap |
| `WaterfallLoader.kt` | Auction round lifecycle (tokens, server request, per-unit loading, stats) |

See [AD_CACHING_FEATURE.md](AD_CACHING_FEATURE.md) for detailed documentation.
