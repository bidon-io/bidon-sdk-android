# AdCache V4 (Vladimir)

Two-slot ad cache with waterfall loading, RTB token reuse, and auto-restart.

## How It Works

### Cache Slots

Two slots managed by `CacheSlotManager`:
- **Slot1** — primary ad (returned by `peek()`/`pop()`)
- **Slot2** — backup ad (promoted to slot1 on pop or expiration)

Slot1 is never replaced — new ads fill slot2 or are discarded. Both slots always hold different networks.

### Loading

`cache()` runs an auction at the caller-provided pricefloor. Reuses stored RTB tokens, excludes cached networks, fills empty slots sequentially from the server-ordered waterfall.

On first load only, a 10s preferRtb timer skips CPM units if slot1 is still empty — reaches RTB units faster.

If slots aren't full after loading, auto-restart schedules a retry with exponential backoff (2s → 64s cap).

### Immediate Callback

If a cached ad already meets the pricefloor, `cache()` fires `onSuccess` immediately without starting an auction. If both slots are full but below the floor, slot2 is evicted to make room for a better ad.

### State Persistence

The host app recreates the cache instance on every show cycle (`clear()` → new instance). `CachePersistedState` (static singleton per AdType) preserves state across recreations:
- First-load completion flag (preferRtb tracking)
- RTB tokens (15-min per-network expiration)
- Cached ads (eagerly snapshot on `pop()`, extracted on `clear()`, restored in `init`)

## Files

| File | Purpose |
|------|---------|
| `AdCacheVladimirImpl.kt` | Orchestrator: loading, callbacks, auto-restart, state persistence |
| `CacheSlotManager.kt` | Two-slot cache with expiration and promotion |
| `WaterfallLoader.kt` | Auction round lifecycle (tokens, server request, per-unit loading, stats) |
| `RtbTokenStore.kt` | RTB token storage with 15-min TTL |
| `CachePersistedState.kt` | Cross-instance ad and token preservation |
| `Extensions.kt` | `AuctionResult` extensions and `CachedAd` data class |
