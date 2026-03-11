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

### Failed Ad Source Cleanup

Failed `loadUnit()` calls create adapter SDK resources (listeners, ad spots, etc.) that persist even after the `AdSource` instance is garbage collected — some adapters register objects in process-lifetime singletons (e.g. DT Exchange's `InneractiveAdSpotManager`). To prevent accumulation, the load loop calls `adSource.destroy()` immediately on every non-successful result that carries a real ad source (i.e. `AuctionResult.Network` or `AuctionResult.Bidding`, but not `AuctionFailed`).

Note: DT Exchange's `destroy()` is a no-op when the load fails (the spot reference is never stored in the field), so their singleton leak cannot be fixed from outside the adapter. This is a known DT Exchange SDK limitation.

## Known Issues

### DT Exchange failed-load memory leak

DT Exchange's Fyber SDK creates ad spots via `InneractiveAdSpotManager.get().createSpot()` and registers them in a static `ConcurrentHashMap` (process-lifetime singleton). On successful load, the adapter stores the spot reference in its field, so `destroy()` can later call `spot.destroy()` to remove it from the map. On failed load, the spot reference is never stored — it remains a local variable that goes out of scope. Calling `adSource.destroy()` does nothing because the field is null, and the spot stays in the singleton forever (~200KB per spot).

This should be fixed in adapter. The fix is a one-line change in the adapter (`adapter/dtexchange/`): store the spot reference immediately after `createSpot()`, before calling `requestAd()`, so that `destroy()` can always clean it up regardless of load outcome.

The base `AdCacheImpl` has the same underlying issue but triggers it less frequently — it runs one auction per `cache()` call with no auto-restart, while vladimir cache version retries with exponential backoff, creating more failed-load opportunities.

## Files

| File | Purpose |
|------|---------|
| `AdCacheVladimirImpl.kt` | Orchestrator: loading, callbacks, auto-restart, state persistence |
| `CacheSlotManager.kt` | Two-slot cache with expiration and promotion |
| `WaterfallLoader.kt` | Auction round lifecycle (tokens, server request, per-unit loading, stats) |
| `RtbTokenStore.kt` | RTB token storage with 15-min TTL |
| `CachePersistedState.kt` | Cross-instance ad and token preservation |
| `Extensions.kt` | `AuctionResult` extensions and `CachedAd` data class |
