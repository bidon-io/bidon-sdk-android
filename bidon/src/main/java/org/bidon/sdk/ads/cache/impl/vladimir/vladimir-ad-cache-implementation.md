# Vladimir CacheStrategy  — Two-Slot Cache with Waterfall Loading

## Cache Configuration

| Parameter | Value | Description |
|-----------|-------|-------------|
| Slot capacity | 2 | Maximum ads held simultaneously (slot1 + slot2) |
| Global timeout | 29 000 ms | Maximum time for a single load round |
| PreferRtb timeout | 10 000 ms | First-load timer: skip CPM units if slot1 still empty |
| RTB token TTL | 15 min (900 000 ms) | Per-network token expiration |
| Auto-restart base delay | 2 s | First retry delay after incomplete cache fill |
| Auto-restart max delay | 64 s | Cap on exponential backoff (`2^min(6, attempt)` seconds) |

---

## Class Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      AdCacheVladimirImpl                        │
│                  (orchestrator, implements AdCache)              │
│                                                                 │
│  ┌──────────────────┐  ┌───────────────┐  ┌──────────────────┐  │
│  │ CacheSlotManager │  │WaterfallLoader│  │  RtbTokenStore   │  │
│  │ (2-slot storage) │  │(auction round │  │ (token TTL mgmt) │  │
│  │                  │  │ + unit load)  │  │                  │  │
│  └────────┬─────────┘  └───────────────┘  └────────┬─────────┘  │
│           │                                        │            │
│  ┌────────┴───────────┐  ┌─────────────────────────┴──┐         │
│  │ShowFallbackHandler │  │   CachePersistedState      │         │
│  │(fullscreen show    │  │   (cross-instance ads +    │         │
│  │ retry on backup)   │  │    tokens preservation)    │         │
│  └────────────────────┘  └────────────────────────────┘         │
└─────────────────────────────────────────────────────────────────┘
```

---

## Data Types

### CachedAd

```kotlin
data class CachedAd(
    val result: AuctionResult,
    val auctionInfo: AuctionInfo,
)
```

Pairs an auction result with the auction metadata from the round that produced it. Used for slot storage and cross-instance preservation.

### AuctionResult extensions

```kotlin
val AuctionResult.demandId: String   // e.g. "admob", "applovin"
val AuctionResult.price: Double      // winning bid/CPM price
```

### CacheSlot (internal to CacheSlotManager)

```kotlin
data class CacheSlot(
    val auctionResult: AuctionResult,
    val auctionInfo: AuctionInfo,
    val observeJob: Job?,       // subscription to Expired events
    val price: Double,
    val demandId: String,
)
```

### StoredToken (internal to RtbTokenStore)

```kotlin
data class StoredToken(
    val tokenInfo: TokenInfo,
    val storedAt: Long,         // System.currentTimeMillis() at storage time
) {
    fun isExpired(now: Long): Boolean = now - storedAt > RTB_TOKEN_EXPIRATION_MS
}
```

---

## 1. AdCacheVladimirImpl — Orchestrator

Implements `AdCache`. Coordinates all subsystems: slot management, waterfall loading, token storage, show fallback, and cross-instance persistence.

### LoadingState

```kotlin
private enum class LoadingState { IDLE, LOADING }
```

- `IDLE` — no load in progress, cache() can start a new load.
- `LOADING` — a load is in progress, subsequent cache() calls are skipped (atomic guard).

### Fields

```kotlin
class AdCacheVladimirImpl(
    val demandAd: DemandAd,
) : AdCache {

    private val persistedState = CachePersistedState.getState(demandAd.adType)
    private val scope = CoroutineScope(Main + SupervisorJob())
    private val slots = CacheSlotManager(scope)
    private val loader = WaterfallLoader(demandAd)
    private val tokenStore = RtbTokenStore(persistedState.rtbTokens)
    private val fallbackHandler = ShowFallbackHandler(scope, slots)

    private var isFirstLoad: Boolean           // true until first load completes
    private val loadingState: MutableStateFlow<LoadingState>  // IDLE or LOADING
    private val callbackFired: AtomicBoolean   // ensures onSuccess/onFailure fires once per cache() call
    private var loadingJob: Job?               // current runLoad coroutine
    private var autoRestartJob: Job?           // pending auto-restart timer
    private var retryAttempt: Int              // exponential backoff counter
    private var lastAdTypeParam: AdTypeParam?  // saved for vacancy-triggered restarts
}
```

### init

```
init:
  │
  ├─ persistedState.restoreInto(slots)
  │    Restores preserved ads from previous instance into slot1/slot2.
  │
  └─ slots.onSlotVacancy = ::onSlotVacancy
       Registers callback: when a slot empties due to expiration,
       onSlotVacancy() is called to trigger cache replenishment.
```

### cache(adTypeParam, onSuccess, onFailure)

Entry point. Called by the host app to request an ad. May fire `onSuccess` immediately if a cached ad meets the pricefloor, otherwise starts a background load.

```
cache(adTypeParam, onSuccess, onFailure):
  │
  │  1. IMMEDIATE CALLBACK CHECK
  │     Read slot1 via peek().
  │     If slot1 ad exists AND slot1.price >= adTypeParam.pricefloor:
  │       → Fire onSuccess(slot1.result, slot1.auctionInfo) on main thread.
  │       → Set callbackFired = true (so loading won't fire it again).
  │
  │  2. SMART EVICTION
  │     If both slots full AND slot1.price < pricefloor:
  │       → Call slots.evictBackup() — destroys slot2.
  │       → Now there's room for a potentially better ad.
  │
  │  3. FULL CHECK
  │     If both slots full → return (nothing to load).
  │
  │  4. ATOMIC LOADING GUARD
  │     Atomically transition loadingState from IDLE → LOADING.
  │     If was already LOADING → return (load already in progress).
  │
  │  5. CANCEL PENDING AUTO-RESTART
  │     If autoRestartJob is active → cancel it.
  │
  │  6. SET UP STATE
  │     callbackFired = (true if immediate callback fired, false otherwise)
  │     fallbackHandler.lastActivity = adTypeParam.activity
  │     Save lastAdTypeParam for future vacancy-triggered restarts.
  │
  │  7. LAUNCH runLoad()
  │     Start runLoad(adTypeParam, onSuccess, onFailure) in scope.
  │     Wrap in runCatching:
  │       On CancellationException → ignore (expected during clear).
  │       On other exception → set state=IDLE, fire onFailure if not yet fired,
  │                            schedule auto-restart.
  │
  └─ return
```

### runLoad(adTypeParam, onSuccess, onFailure)

Core loading logic. Runs a complete auction round: fetches tokens, requests waterfall from server, walks units sequentially.

```
runLoad(adTypeParam, onSuccess, onFailure):
  │
  │  1. CAPTURE FIRST LOAD FLAG
  │     val isFirstLoadRun = isFirstLoad
  │     If true: set isFirstLoad = false, persistedState.firstLoadCompleted = true
  │
  │  2. GET VALID RTB TOKENS
  │     val validTokens = tokenStore.getValidTokens()
  │     Returns non-expired tokens. Removes expired ones automatically.
  │
  │  3. PREFER-RTB TIMER (first load only)
  │     If isFirstLoadRun:
  │       Start a 10-second timer in scope.
  │       When timer fires: if slots.peek() == null (slot1 still empty),
  │         set preferRtb = true.
  │     Purpose: On first load, if no ad fills within 10s, skip CPM units
  │     to reach RTB units faster. RTB bids are typically higher-value.
  │
  │  4. GLOBAL TIMEOUT (29s) wraps steps 5–7
  │
  │  5. START AUCTION ROUND
  │     round = loader.startRound(
  │       adTypeParam, pricefloor, existingTokens=validTokens,
  │       excludedDemandIds=slots.cachedDemandIds
  │     )
  │     → Fetches new RTB tokens from adapters (excluding already-cached networks)
  │     → Merges new tokens with existing valid tokens
  │     → Sends auction request to server with all tokens
  │     → Server returns ordered list of adUnits (waterfall)
  │
  │  6. WATERFALL WALK (sequential, unit by unit)
  │     for each adUnit in round.adUnits:
  │       │
  │       ├─ if slots.isFull() → BREAK
  │       │
  │       ├─ if preferRtb == true AND adUnit.bidType == CPM → SKIP
  │       │    (skip CPM units to reach RTB faster)
  │       │
  │       ├─ if adUnit.demandId in slots.cachedDemandIds → SKIP
  │       │    (avoid duplicate networks in slots)
  │       │
  │       └─ result = loader.loadUnit(adUnit, round)
  │            │
  │            ├─ SUCCESS (roundStatus == Successful):
  │            │    handleFill(result, round, adTypeParam, onSuccess)
  │            │    if preferRtb == true → BREAK waterfall
  │            │      (got a fill in preferRtb mode — abandon this round,
  │            │       will start a fresh round for slot2)
  │            │
  │            └─ FAILURE:
  │                 Log failure, continue to next unit.
  │
  │  7. FIRE onFailure IF NO FILLS
  │     If fillCount == 0 AND callbackFired was false:
  │       → Fire onFailure(auctionInfo, NoAuctionResults) on main thread.
  │
  │  8. CANCEL PREFER-RTB TIMER
  │
  │  9. IF PREFER-RTB FILLED
  │     If preferRtb == true AND slot1 has an ad:
  │       → Store RTB tokens from this round
  │       → Collect stats, send to server
  │       → Recursively call runLoad() to start fresh round for slot2
  │       → return (skip finalization for the preferRtb round)
  │
  │  10. STORE RTB TOKENS
  │      tokenStore.storeFromRound(round) — saves tokens for future rounds.
  │
  │  11. COLLECT STATS
  │      roundStat = loader.collectStats(round) — sends results to server.
  │
  │  12. FINALIZE
  │      finalizeLoad(round, roundStat, adTypeParam, onSuccess, onFailure)
  │
  └─ return
```

### handleFill(result, round, adTypeParam, onSuccess)

Bridge between loader and slots. Called when a waterfall unit successfully loads an ad.

```
handleFill(result, round, adTypeParam, onSuccess):
  │
  │  1. Reset retryAttempt = 0 (successful fill resets backoff).
  │
  │  2. Build auctionInfo from round.response.
  │
  │  3. val primaryUpdated = slots.insert(result, auctionInfo)
  │     Inserts the ad into slot1 or slot2 (see CacheSlotManager.insert() below).
  │     Returns true ONLY if slot1 was filled (was empty before).
  │
  │  4. If primaryUpdated AND callbackFired.compareAndSet(false, true):
  │       → Fire onSuccess(result, auctionInfo) on main thread.
  │       → This happens exactly once: when slot1 transitions from empty to filled.
  │
  │  Note: primaryUpdated=true can only happen once per cache() call because
  │  slot1 is never replaced. Once filled, all subsequent fills go to slot2
  │  (primaryUpdated=false) and no callback fires.
  │
  └─ return
```

### finalizeLoad(round, roundStat, adTypeParam, onSuccess, onFailure)

Called after waterfall walk completes (or times out).

```
finalizeLoad(round, roundStat, adTypeParam, onSuccess, onFailure):
  │
  │  1. NOTIFY WINNER
  │     notifyWinner(round.response.externalWinNotificationsEnabled)
  │     Calls markWin() on the slot1 ad source.
  │     If external win notifications are disabled AND winner is not RTB bidding:
  │       → Also call notifyWin() on the ad source.
  │
  │  2. SET STATE → IDLE
  │     loadingState = IDLE
  │
  │  3. FIRE CALLBACK IF NOT YET FIRED
  │     If callbackFired was false:
  │       If slot1 has an ad → fire onSuccess(slot1.result, auctionInfo)
  │       Else → fire onFailure(auctionInfo, NoAuctionResults)
  │
  │  4. SCHEDULE AUTO-RESTART IF NEEDED
  │     If slotCount < 2 (not both slots filled):
  │       → scheduleAutoRestart(adTypeParam)
  │     Else:
  │       → Reset retryAttempt = 0
  │
  └─ return
```

### peek() → AuctionResult?

```
peek():
  │
  └─ return slots.peek()
     Returns slot1's AuctionResult without removing it.
     Returns null if slot1 is empty.
```

### pop() → AuctionResult?

```
pop():
  │
  │  1. result = slots.pop()
  │     Removes slot1, promotes slot2 → slot1. Returns the removed ad.
  │
  │  2. If result != null:
  │     │
  │     ├─ tokenStore.removeToken(result.demandId)
  │     │    The shown ad's token was consumed — cannot be reused.
  │     │
  │     ├─ fallbackHandler.observe(result)
  │     │    For fullscreen ads: watch the popped ad for ShowFailed events.
  │     │    If show fails, automatically pop and show the backup (now slot1).
  │     │
  │     └─ persistedState.snapshotOnPop(slots)
  │          Eagerly save remaining slot(s) to persisted state.
  │          Protects against: new cache instance created before clear() is called.
  │
  └─ return result
```

### poll() → AuctionResult (suspend)

```
poll():
  │
  │  1. Suspend until slot1 becomes non-null.
  │     Uses Flow.first { it != null } on the slot1 StateFlow.
  │
  │  2. Call pop() and return the result.
  │
  └─ return pop()!!
```

### clear()

Called when the host app destroys the ad instance. Preserves ads for next instance.

```
clear():
  │
  │  1. Reset retryAttempt = 0.
  │
  │  2. Cancel autoRestartJob and loadingJob.
  │
  │  3. val extracted = slots.extractAll()
  │     Removes all ads from slots WITHOUT destroying them.
  │     Cancels observe jobs but keeps ad sources alive.
  │
  │  4. persistedState.preserveOnClear(extracted)
  │     Saves extracted ads to static persisted state.
  │     Next instance will restore them in init.
  │
  │  5. loadingState → IDLE
  │
  └─ return
```

### scheduleAutoRestart(adTypeParam)

Schedules a delayed cache() call to fill empty slots.

```
scheduleAutoRestart(adTypeParam):
  │
  │  1. retryAttempt++
  │
  │  2. Calculate delay: 2^min(6, retryAttempt) seconds.
  │     Sequence: 2s, 4s, 8s, 16s, 32s, 64s, 64s, 64s, ...
  │
  │  3. Launch coroutine:
  │     delay(delayMs)
  │     cache(adTypeParam, onSuccess={}, onFailure={})
  │       ↑ no-op callbacks because auto-restart fills silently.
  │
  └─ autoRestartJob = the launched job
```

### onSlotVacancy()

Called by CacheSlotManager when a slot empties due to ad expiration.

```
onSlotVacancy():
  │
  │  If lastAdTypeParam == null → return (no context to restart).
  │  If loadingState == LOADING → return (already loading).
  │  If autoRestartJob != null → return (restart already scheduled).
  │
  └─ scheduleAutoRestart(lastAdTypeParam)
```

---

## 2. CacheSlotManager — Two-Slot Storage

Manages two ad slots: slot1 (primary, served to the host app) and slot2 (backup, promotes on pop/expiration).

### Fields

```kotlin
class CacheSlotManager(private val scope: CoroutineScope) {

    var onSlotVacancy: (() -> Unit)?       // callback when slot empties due to expiration

    private val slot1: MutableStateFlow<CacheSlot?>  // primary (served on peek/pop)
    private val slot2: MutableStateFlow<CacheSlot?>  // backup (promotes on pop/expiration)
}
```

### Computed properties

```kotlin
val primaryPrice: Double?           // slot1 price, or null if empty
val bestPrice: Double               // max(slot1.price, slot2.price), or 0.0
val cachedDemandIds: Set<String>    // {slot1.demandId, slot2.demandId} (non-null)
val slotCount: Int                  // 0, 1, or 2
```

### description() → String

```
description():
  │
  │  Build a human-readable string of current slot state.
  │  Format: "[demandId:price | demandId:price]" or "[empty | empty]"
  │
  │  Example outputs:
  │    "[admob:2.5 | unityads:1.8]"
  │    "[admob:2.5 | empty]"
  │    "[empty | empty]"
  │
  └─ return formatted string
```

Used for logging throughout the system.

### peek() → AuctionResult?

```
peek():
  └─ return slot1.auctionResult (or null if slot1 empty)
```

### peekAuctionInfo() → AuctionInfo?

```
peekAuctionInfo():
  └─ return slot1.auctionInfo (or null if slot1 empty)
```

### pop() → AuctionResult?

```
pop():
  │
  │  1. Atomically read and replace slot1:
  │     old = slot1
  │     slot1 = slot2       ← promote backup to primary
  │     slot2 = null         ← clear backup
  │
  │  2. Cancel old.observeJob (stop watching for Expired events).
  │
  └─ return old.auctionResult (or null if slot1 was empty)
```

### poll() → AuctionResult (suspend)

```
poll():
  │
  │  1. Suspend on slot1 Flow until non-null.
  │
  └─ return pop()!!
```

### isFull() → Boolean

```
isFull():
  └─ return slot1 != null AND slot2 != null
```

### insert(result, auctionInfo) → Boolean

Inserts an ad into the cache. Returns `true` if slot1 was filled (was empty → now filled).

```
insert(result, auctionInfo):
  │
  │  1. Create observeJob = observeSlotEvents(result)
  │     Subscribes to the ad's event flow for Expired events.
  │
  │  2. Create newSlot = CacheSlot(result, auctionInfo, observeJob, price, demandId)
  │
  │  3. Read currentSlot1, currentSlot2.
  │
  │  4. Decide:
  │
  │     ┌─ BRANCH 1: slot1 is empty
  │     │   slot1 = newSlot
  │     │   return true (primary filled)
  │     │
  │     │   Example: [empty | empty] + AdMob@$2.50
  │     │          → [AdMob@$2.50 | empty]
  │     │
  │     ├─ BRANCH 2: slot1 occupied, slot2 is empty
  │     │   slot2 = newSlot
  │     │   return false (backup filled silently)
  │     │
  │     │   Example: [AdMob@$2.50 | empty] + Unity@$1.80
  │     │          → [AdMob@$2.50 | Unity@$1.80]
  │     │
  │     ├─ BRANCH 3: both occupied, new price > slot2 price
  │     │   slot2 = newSlot
  │     │   destroySlot(oldSlot2)  ← cancel observe, destroy ad source
  │     │   return false
  │     │
  │     │   Example: [AdMob@$2.50 | Meta@$0.90] + Unity@$1.80
  │     │          → [AdMob@$2.50 | Unity@$1.80]
  │     │            Meta@$0.90 is destroyed.
  │     │
  │     └─ BRANCH 4: both occupied, new price <= slot2 price
  │         Cancel observeJob.
  │         result.adSource.destroy()
  │         return false (discarded)
  │
  │         Example: [AdMob@$2.50 | Unity@$1.80] + Meta@$0.50
  │                → [AdMob@$2.50 | Unity@$1.80]
  │                  Meta@$0.50 is destroyed.
  │
  └─ return true/false as described above
```

**Important**: Slot1 is never replaced. The first ad to fill slot1 stays there until pop() or expiration. This means slot1 may not hold the highest-priced ad if a cheaper network responded first in the waterfall, but this is rare (waterfall is ordered highest-price-first) and resolves naturally on the next pop().

### evictBackup()

```
evictBackup():
  │
  │  Atomically read and clear slot2.
  │  If old slot2 existed:
  │    Cancel observeJob.
  │    Destroy ad source.
  │
  └─ return
```

### snapshotAll() → List\<CachedAd\>

```
snapshotAll():
  │
  │  Read slot1 and slot2 WITHOUT removing them.
  │  Return a list of CachedAd(result, auctionInfo) for each non-null slot.
  │
  └─ return list (0, 1, or 2 elements)
```

### extractAll() → List\<CachedAd\>

```
extractAll():
  │
  │  Atomically read and clear both slots.
  │  For each non-null slot:
  │    Cancel observeJob (stop watching Expired events).
  │    Do NOT destroy ad source (ads preserved for next instance).
  │    Add CachedAd(result, auctionInfo) to result list.
  │
  └─ return list (0, 1, or 2 elements)
```

### clear()

```
clear():
  │
  │  Atomically read and clear both slots.
  │  For each non-null slot:
  │    Cancel observeJob.
  │    Destroy ad source.
  │
  └─ return
```

### Expiration Handling (internal)

#### observeSlotEvents(result) → Job

```
observeSlotEvents(result):
  │
  │  Subscribe to result.adSource.adEvent flow.
  │  On each event:
  │    If event is AdEvent.Expired:
  │      → removeExpiredSlot(result)
  │
  └─ return the subscription Job
```

#### removeExpiredSlot(result)

```
removeExpiredSlot(result):
  │
  │  Check which slot holds this result (by reference equality):
  │
  │  ┌─ If slot1.auctionResult === result:
  │  │   Cancel slot1.observeJob.
  │  │   Destroy slot1.adSource.
  │  │   slot1 = slot2       ← promote backup
  │  │   slot2 = null
  │  │   Call onSlotVacancy()  ← triggers cache replenishment
  │  │
  │  ├─ If slot2.auctionResult === result:
  │  │   Cancel slot2.observeJob.
  │  │   Destroy slot2.adSource.
  │  │   slot2 = null
  │  │   Call onSlotVacancy()
  │  │
  │  └─ Else: result not found in any slot (already removed). No-op.
  │
  └─ return
```

---

## 3. WaterfallLoader — Auction Round Lifecycle

Manages a single auction round: fetches tokens, sends server request, loads individual ad units. Does NOT manage slot insertion — returns results for the orchestrator to handle.

### AuctionRound

```kotlin
data class AuctionRound(
    val adTypeParam: AdTypeParam,
    val response: AuctionResponse,
    val tokens: Map<String, TokenInfo>,
) {
    val adUnits: List<AdUnit>     // ordered waterfall from server (highest price first)
    val auctionTimeout: Long      // server-provided timeout
}
```

### startRound(adTypeParam, pricefloor, existingTokens, excludedDemandIds) → AuctionRound

Creates a new auction round. Fetches tokens, sends server request, initializes stats collector.

```
startRound(adTypeParam, pricefloor, existingTokens, excludedDemandIds):
  │
  │  1. Generate auctionId (UUID).
  │
  │  2. Mark auction started (for stats).
  │
  │  3. FETCH NEW RTB TOKENS
  │     Call getTokens() from all registered adapters.
  │     Filter out:
  │       - Networks in existingTokens (already have valid tokens)
  │       - Networks in excludedDemandIds (already cached in slots)
  │
  │  4. MERGE TOKENS
  │     tokens = existingTokens (excluding excluded) + newTokens
  │     Existing tokens take precedence over new ones.
  │
  │  5. SERVER REQUEST
  │     Send auction request with:
  │       - adTypeParam (ad type, pricefloor, activity)
  │       - auctionId
  │       - demandAd
  │       - registered adapters info
  │       - merged tokens
  │     Server responds with:
  │       - Ordered list of adUnits (waterfall, highest price first)
  │       - pricefloor
  │       - auctionTimeout
  │       - noBids (networks that declined)
  │
  │  6. INITIALIZE STATS COLLECTOR
  │     Clear previous results.
  │     Start round tracking.
  │     Record server bidding results and noBids.
  │
  └─ return AuctionRound(adTypeParam, response, tokens)
```

### loadUnit(adUnit, round, tokens?, adTypeParam?) → AuctionResult?

Loads a single ad unit from the waterfall. Returns the result for the orchestrator to handle.

```
loadUnit(adUnit, round, tokens=round.tokens, adTypeParam=round.adTypeParam):
  │
  │  1. Call requestSingleUnit(adUnit, tokens, round.response, adTypeParam):
  │     │
  │     ├─ If adUnit.pricefloor < auction pricefloor:
  │     │     Return BelowPricefloor/Lose result (no actual load).
  │     │
  │     ├─ Find adapter by adUnit.demandId.
  │     │   If not found → return UnknownAdapter result.
  │     │
  │     ├─ Create adSource from adapter for the ad type.
  │     │   If null → return UnknownAdapter result.
  │     │
  │     ├─ Apply regulation settings.
  │     │
  │     ├─ If RTB: set tokenInfo on adSource.
  │     │
  │     ├─ Apply auction params (auctionId, configId, pricefloor, etc.).
  │     │
  │     └─ Request ad from adapter. Return AuctionResult.
  │
  │  2. Add result to stats collector (if non-null).
  │
  └─ return result (or null)
```

### fetchTokens(adTypeParam) → Map\<String, TokenInfo\>

```
fetchTokens(adTypeParam):
  │
  │  Call getTokens() from all registered adapters.
  │
  └─ return Map<demandId, TokenInfo>
```

### collectStats(round) → RoundStat?

```
collectStats(round):
  │
  │  1. Get round results from stats collector.
  │
  │  2. Add round results to auction stats.
  │
  │  3. Send auction stats to server.
  │
  └─ return RoundStat (demands, noBids)
```

---

## 4. RtbTokenStore — RTB Token TTL Management

Stores RTB tokens per network with 15-minute expiration. Backed by the same mutable map reference from CachePersistedState, so tokens persist across cache instance recreations.

### Fields

```kotlin
class RtbTokenStore(
    private val storedTokens: MutableMap<String, StoredToken>
    // This map is the same object as CachePersistedState.rtbTokens
)
```

### getValidTokens() → Map\<String, TokenInfo\>

```
getValidTokens():
  │
  │  1. now = System.currentTimeMillis()
  │
  │  2. Remove all entries where storedToken.isExpired(now) == true.
  │     (now - storedAt > 15 minutes)
  │
  │  3. Map remaining entries: demandId → tokenInfo.
  │
  └─ return Map<demandId, TokenInfo>
```

### storeFromRound(adUnits, noBidDemandIds, roundTokens)

```
storeFromRound(adUnits, noBidDemandIds, roundTokens):
  │
  │  1. Filter adUnits to RTB units only.
  │
  │  2. Filter out units whose demandId is in noBidDemandIds.
  │     (Networks that returned no-bid shouldn't have their tokens refreshed.)
  │
  │  3. For each remaining RTB unit:
  │     If roundTokens contains a token for this demandId:
  │       storedTokens[demandId] = StoredToken(token, now)
  │
  └─ return
```

### removeToken(demandId)

```
removeToken(demandId):
  │
  │  Remove storedTokens[demandId] if it exists.
  │  Called when an ad is shown — the token was consumed and cannot produce
  │  a valid bid again.
  │
  └─ return
```

---

## 5. ShowFallbackHandler — Show Retry for Fullscreen Ads

For interstitial and rewarded ads: if the primary ad's show() fails, automatically pop the backup ad from slot1 (which was promoted from slot2 after the initial pop) and show it. Forwards the backup's events to the primary's event flow so the host app receives them transparently.

### Fields

```kotlin
class ShowFallbackHandler(
    private val scope: CoroutineScope,
    private val slots: CacheSlotManager,
) {
    var lastActivity: Activity?   // set by cache(), used for backup show
}
```

### observe(result)

Called after pop(). Subscribes to the popped ad's event flow to watch for ShowFailed.

```
observe(result):
  │
  │  Subscribe to result.adSource.adEvent flow:
  │
  │  On each event:
  │    │
  │    ├─ If ShowFailed AND fallback not yet attempted:
  │    │   │
  │    │   │  Mark fallback attempted (only try once).
  │    │   │
  │    │   │  backup = slots.pop()
  │    │   │    ← Pop the backup (which was promoted to slot1 by the earlier pop).
  │    │   │
  │    │   │  If backup != null AND lastActivity != null:
  │    │   │    │
  │    │   │    │  Subscribe to backup.adSource.adEvent:
  │    │   │    │    Forward every event to primary's flow via source.emitEvent(event).
  │    │   │    │    On Closed: clear lastActivity.
  │    │   │    │
  │    │   │    │  Show backup:
  │    │   │    │    If Interstitial → backupSource.show(activity)
  │    │   │    │    If Rewarded → backupSource.show(activity)
  │    │   │    │    Else → cannot show (e.g., Banner)
  │    │   │    │
  │    │   │  If backup == null OR lastActivity == null:
  │    │   │    → Fallback failed (no backup available or no activity).
  │    │   │      Clear lastActivity.
  │    │   │
  │    │
  │    └─ If Closed:
  │         Clear lastActivity.
  │
  └─ return
```

**Event flow to host app:**

```
Primary succeeds:       Shown → Closed  (normal flow)
Primary fails, backup:  ShowFailed → Shown → Closed  (ShowFailed from primary, then backup events)
Both fail:              ShowFailed → ShowFailed  (primary fails, backup also fails)
```

---

## 6. CachePersistedState — Cross-Instance Preservation

Static singleton per AdType. Preserves ads and RTB tokens across cache instance recreations. The host app creates a new cache instance on every show cycle (clear → new), so this state ensures continuity.

### Fields

```kotlin
class CachePersistedState {

    companion object {
        private val stateByAdType: MutableMap<AdType, CachePersistedState>

        fun getState(adType: AdType): CachePersistedState
            // Returns existing or creates new for this ad type.
    }

    var firstLoadCompleted: Boolean        // tracks whether preferRtb has been used
    val rtbTokens: MutableMap<String, StoredToken>  // shared reference with RtbTokenStore
    private val preservedAds: MutableList<CachedAd> // ads saved for next instance
}
```

### restoreInto(slots)

Called from AdCacheVladimirImpl.init. Restores preserved ads into the new instance's slots.

```
restoreInto(slots):
  │
  │  1. Copy preservedAds to local list.
  │
  │  2. Clear preservedAds.
  │
  │  3. For each preserved ad:
  │     slots.insert(ad.result, ad.auctionInfo)
  │     ← Insert into slot1/slot2 using normal insert logic.
  │
  └─ return
```

### preserveOnClear(extractedAds)

Called from AdCacheVladimirImpl.clear(). Saves extracted ads for the next instance.

```
preserveOnClear(extractedAds):
  │
  │  1. Clear preservedAds.
  │
  │  2. Add all extractedAds to preservedAds.
  │
  └─ return
```

### snapshotOnPop(slots)

Called from AdCacheVladimirImpl.pop(). Eagerly saves remaining slots to persisted state. Protects against: new cache instance created before clear() is called.

```
snapshotOnPop(slots):
  │
  │  1. remaining = slots.snapshotAll()
  │     ← Read without removing.
  │
  │  2. Clear preservedAds.
  │
  │  3. Add all remaining to preservedAds.
  │
  └─ return
```

### wipe()

Clears all persisted state for this ad type. Called during permanent teardown.

```
wipe():
  │
  │  Clear preservedAds.
  │  Clear rtbTokens.
  │  Set firstLoadCompleted = false.
  │
  └─ return
```

---

## Complete Flow Diagrams

### Flow 1: cache() → Load → Show (full lifecycle)

```
Host app                   AdCacheVladimirImpl          CacheSlotManager
   │                              │                           │
   │  cache(pricefloor=1.0)       │                           │
   │─────────────────────────────>│                           │
   │                              │  peek()                   │
   │                              │──────────────────────────>│
   │                              │  null (empty)             │
   │                              │<──────────────────────────│
   │                              │                           │
   │                              │  IDLE → LOADING (atomic)  │
   │                              │                           │
   │                              │  runLoad() launched       │
   │                              │         │                 │
   │                              │    ┌────┴────┐            │
   │                              │    │Waterfall│            │
   │                              │    │Loader   │            │
   │                              │    │         │            │
   │                              │    │ startRound()         │
   │                              │    │ (tokens + server)    │
   │                              │    │         │            │
   │                              │    │ loadUnit(#1)         │
   │                              │    │ → SUCCESS            │
   │                              │    └────┬────┘            │
   │                              │         │                 │
   │                              │  handleFill()             │
   │                              │──── insert() ────────────>│
   │                              │  slot1 FILLED             │
   │                              │<──── true ────────────────│
   │                              │                           │
   │  onSuccess(result, info)     │                           │
   │<─────────────────────────────│                           │
   │                              │                           │
   │  ... waterfall continues ... │                           │
   │                              │  loadUnit(#2) → SUCCESS   │
   │                              │──── insert() ────────────>│
   │                              │  slot2 FILLED             │
   │                              │<──── false ───────────────│
   │                              │                           │
   │                              │  finalizeLoad()           │
   │                              │  state → IDLE             │
   │                              │                           │
   │  showAd(activity)            │                           │
   │─────────────────────────────>│                           │
   │                              │  pop()                    │
   │                              │──────────────────────────>│
   │                              │  slot1 removed            │
   │                              │  slot2 → slot1            │
   │                              │<──────────────────────────│
   │                              │                           │
   │                              │  fallbackHandler.observe()│
   │                              │  persistedState.snapshot()│
   │                              │                           │
   │  adSource.show(activity)     │                           │
   │                              │                           │
```

### Flow 2: Auto-Restart (only one slot filled)

```
                          AdCacheVladimirImpl
                                 │
  finalizeLoad():                │
    slotCount == 1               │
    (only slot1 filled)          │
                                 │
    scheduleAutoRestart()        │
    retryAttempt = 1             │
    delay = 2s                   │
         │                       │
         │  ... 2 seconds ...    │
         │                       │
         ▼                       │
    cache(adTypeParam,           │
      onSuccess={}, onFailure={})│
         │                       │
         │  → runLoad()          │
         │  → waterfall walk     │
         │  → fill slot2         │
         │                       │
    finalizeLoad():              │
      slotCount == 2             │
      retryAttempt = 0           │
      No auto-restart scheduled  │
```

### Flow 3: Expiration with Promotion

```
                     CacheSlotManager
                           │
   State: [AdMob@$2.50 | Unity@$1.80]
                           │
   AdMob emits AdEvent.Expired
                           │
   removeExpiredSlot():    │
     slot1 === AdMob       │
     → destroy AdMob       │
     → slot1 = slot2       │  (promote Unity to primary)
     → slot2 = null        │
                           │
   State: [Unity@$1.80 | empty]
                           │
   onSlotVacancy()         │
     → orchestrator schedules auto-restart
     → new auction fills empty slot2
```

### Flow 4: Immediate Callback (cached ad meets floor)

```
Host app                   AdCacheVladimirImpl          CacheSlotManager
   │                              │                           │
   │  State: [AdMob@$2.50 | Unity@$1.80]                     │
   │                              │                           │
   │  cache(pricefloor=1.0)       │                           │
   │─────────────────────────────>│                           │
   │                              │  peek() → AdMob@$2.50    │
   │                              │  2.50 >= 1.0 ✓            │
   │                              │                           │
   │  onSuccess(AdMob, info)      │                           │
   │<─────────────────────────────│  (immediate, no auction)  │
   │                              │                           │
   │                              │  isFull() → true          │
   │                              │  → return (no load needed)│
```

### Flow 5: Smart Eviction

```
Host app                   AdCacheVladimirImpl          CacheSlotManager
   │                              │                           │
   │  State: [Meta@$0.50 | Unity@$0.30]                      │
   │                              │                           │
   │  cache(pricefloor=1.0)       │                           │
   │─────────────────────────────>│                           │
   │                              │  peek() → Meta@$0.50     │
   │                              │  0.50 < 1.0 ✗            │
   │                              │  (no immediate callback)  │
   │                              │                           │
   │                              │  isFull() → true          │
   │                              │  primaryPrice=0.50 < 1.0  │
   │                              │                           │
   │                              │  evictBackup()            │
   │                              │──────────────────────────>│
   │                              │  Unity@$0.30 destroyed    │
   │                              │                           │
   │  State: [Meta@$0.50 | empty] │                           │
   │                              │                           │
   │                              │  isFull() → false         │
   │                              │  → start loading          │
   │                              │  → auction may find ad    │
   │                              │    above $1.0 floor       │
```

### Flow 6: PreferRtb Timer (First Load Only)

```
                          AdCacheVladimirImpl
                                 │
  runLoad() (isFirstLoad=true):  │
                                 │
  Start 10s timer ─────────┐     │
                           │     │
  Start waterfall walk:    │     │
    loadUnit(RTB#1) → FAIL │     │
    loadUnit(RTB#2) → FAIL │     │
    loadUnit(CPM#3) → FAIL │     │
    ...                    │     │
                           │     │
  10 seconds elapsed ──────┘     │
  slot1 still empty              │
  → preferRtb = true             │
                                 │
  Continue waterfall:            │
    loadUnit(CPM#4) → SKIP  (preferRtb active, CPM skipped)
    loadUnit(CPM#5) → SKIP
    loadUnit(RTB#6) → SUCCESS ← slot1 filled!
                                 │
  preferRtb fill → BREAK         │
                                 │
  Store tokens, collect stats.   │
  Start fresh round for slot2.   │
  → runLoad() recursion          │
```

### Flow 7: ShowFallbackHandler (Fullscreen)

```
Host app                   ShowFallbackHandler         CacheSlotManager
   │                              │                           │
   │  pop() → primary ad          │                           │
   │  State: [backup | empty]     │                           │
   │                              │                           │
   │  observe(primary)            │                           │
   │─────────────────────────────>│                           │
   │                              │  subscribe to primary     │
   │                              │  adEvent flow             │
   │                              │                           │
   │  primary.show(activity)      │                           │
   │         │                    │                           │
   │  ShowFailed ────────────────>│                           │
   │                              │  pop() backup             │
   │                              │──────────────────────────>│
   │                              │  backup returned          │
   │                              │<──────────────────────────│
   │                              │                           │
   │                              │  subscribe to backup      │
   │                              │  forward events to primary│
   │                              │                           │
   │                              │  backup.show(activity)    │
   │                              │                           │
   │  Shown (from backup) ◄───────│  (forwarded)              │
   │  Closed (from backup) ◄──────│  (forwarded)              │
```

---

## Network Exclusion Logic

During waterfall walk, networks already cached in slots are skipped to avoid holding two ads from the same network.

```
Cached: [AdMob@$2.50 | Unity@$1.80]
cachedDemandIds = {"admob", "unityads"}

Waterfall from server:
  #1 applovin/RTB/$3.00   → LOAD (not cached)
  #2 admob/RTB/$2.80      → SKIP (already in slot1)
  #3 meta/CPM/$2.00       → LOAD (not cached)
  #4 unityads/CPM/$1.50   → SKIP (already in slot2)
```

The same exclusion is applied when calling `startRound()` — cached networks are excluded from token fetching and server-side bidding to avoid wasting adapter calls.

---

## Constants Summary

```kotlin
private const val LOADING_TIMEOUT_MS = 10_000L    // 10s preferRtb timer
private const val GLOBAL_TIMEOUT_MS = 29_000L     // 29s overall load timeout
private const val RTB_TOKEN_EXPIRATION_MS = 15 * 60 * 1000L  // 15 min token TTL
// Auto-restart backoff: 2^min(6, attempt) seconds → 2s, 4s, 8s, 16s, 32s, 64s cap
```
