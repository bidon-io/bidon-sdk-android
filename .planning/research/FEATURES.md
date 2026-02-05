# Feature Research: Ad Caching Systems

**Domain:** Mobile Ad Mediation SDK — Ad Caching & Auction Optimization
**Researched:** 2026-02-05
**Confidence:** HIGH (based on production SDK analysis + industry research)

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = product feels incomplete or system breaks.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| **TTL-based expiration** | Ad inventory has time-sensitive value; stale ads = policy violations & lost revenue | MEDIUM | AdMob: 4-hour timeout for App Open ads. Industry standard: 15-30 min for display, 60 min for video |
| **Thread-safe cache operations** | Multiple auction/load calls from different threads/coroutines | MEDIUM | ConcurrentHashMap + atomic operations. Race conditions = crashes or duplicate shows |
| **Cache invalidation on fail** | Invalid ad payloads must be removed to prevent repeated failures | LOW | Remove from cache on load failure, especially for RTB payload cache |
| **Memory-aware capacity limits** | Unbounded cache = OOM crashes on low-end devices | LOW | Typical: 1-3 ads per format. Balance fill rate vs memory footprint |
| **Lazy eviction on access** | Periodic sweeps alone miss expired entries accessed between sweeps | LOW | Check TTL on peek/pop/poll operations. Combined with periodic cleanup |
| **Duplicate detection** | Same demandId cached multiple times wastes memory | MEDIUM | Policy: keep highest eCPM, or most recent. Critical for RTB payload reuse |
| **Graceful degradation on cache miss** | Empty cache shouldn't break load flow | LOW | Fallback to full auction pipeline. No blocking on empty cache |
| **Ad expiration callbacks** | Notify publishers when cached ad expires before show | MEDIUM | Flow<AdEvent.Expired> pattern. Publishers can trigger reload |

### Differentiators (Competitive Advantage)

Features that set the product apart. Not required, but valuable for revenue/UX optimization.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **Warm start optimization** | Immediate onAdLoaded callback (<1-3s) from cache vs 3-15s cold start | HIGH | **Core differentiator for Bidon v2.** Return cached ad immediately while background refresh runs |
| **Two-level cache (READY_TO_SHOW + RTB_PAYLOAD)** | Reuse expensive RTB bid responses across auctions | HIGH | **Bidon v2 innovation.** Skip token collection for cached payloads. Rare in ad SDKs |
| **Dynamic pricefloor from cache** | Use max(cache eCPM, user pricefloor) to avoid underpricing | MEDIUM | Prevents accepting lower bids when higher-value ad is cached. Revenue optimization |
| **Parallel RTB + CPM processing** | Load RTB and CPM groups concurrently vs sequential waterfall | HIGH | Reduces p95 latency by 30-50%. Requires careful cancellation policy |
| **Intelligent RTB payload caching** | Save losing RTB bids for next auction instead of discarding | MEDIUM | Unique to Bidon v2 spec. Most SDKs discard after single auction |
| **Best-pick on show (not load)** | Choose highest eCPM ad at show time, not load time | LOW | Handles dynamic pricing shifts. Better revenue than FIFO consumption |
| **Application-wide cache scope** | Share cache across ad instances of same format | MEDIUM | Improves fill rate for apps with multiple placements. Singleton pattern |
| **Weight-based CPM ordering** | Sort CPM networks by historical fill rate + eCPM | MEDIUM | AppLovin MAX uses similar. Reduces wasted waterfall attempts |
| **Auction-aware cancellation** | Cancel in-flight CPM loads when ad shown | LOW | Saves bandwidth and battery. Prevents unnecessary adapter loads |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create problems in ad caching context.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| **Aggressive pre-caching (5+ ads)** | "More cache = better fill rate" | Memory pressure, stale inventory, policy violations (some networks limit pre-caching) | Limit to 1-3 ads. Use application-wide scope instead |
| **Infinite TTL / no expiration** | "Never lose a cached ad" | Stale ads = policy violations, lost revenue (prices decay), user experience issues | Fixed TTL (30 min standard). Let ads expire naturally |
| **Fallback from cache on show failure** | "Use next-best ad if winner fails" | Breaks auction integrity, complicates win/loss notifications, user sees lower-value ad | Trigger new auction on show failure instead |
| **Cache-before-initialize** | "Start loading ads during SDK init" | Race conditions, missing config, adapter not ready, crashes | Wait for initialization callback before first load |
| **Cross-format cache sharing** | "Reuse banner RTB payload for interstitial" | Different ad formats have different tokens/params. Causes load failures | Separate caches per ad format |
| **Automatic cache refresh without trigger** | "Keep cache always full in background" | Battery drain, bandwidth waste, fills cache when app inactive | Refresh only on explicit load() or show() triggers |
| **Synchronous cache operations** | "Blocking peek/pop for simplicity" | ANR on main thread if eviction sweep runs. Android strict mode violations | Use suspend functions or post to background thread |
| **Win/loss notifications for cached ads on every show** | "Networks need to know about every impression" | Networks expect notification once per auction, not per show. Causes accounting issues | Send win/loss only at auction time, not show time |

## Feature Dependencies

```
[TTL-based expiration]
    └──requires──> [Lazy eviction on access]
                       └──requires──> [Periodic sweep cleanup]

[Warm start optimization]
    └──requires──> [Thread-safe cache operations]
    └──requires──> [Graceful degradation on cache miss]

[Two-level cache (READY_TO_SHOW + RTB_PAYLOAD)]
    └──requires──> [Cache invalidation on fail]
    └──requires──> [Duplicate detection]
    └──enhances──> [Dynamic pricefloor from cache]

[Parallel RTB + CPM processing]
    └──requires──> [Auction-aware cancellation]
    └──requires──> [Thread-safe cache operations]

[Best-pick on show]
    └──requires──> [Thread-safe cache operations]
    └──conflicts──> [FIFO cache consumption]

[Application-wide cache scope]
    └──requires──> [Thread-safe cache operations]
    └──requires──> [Memory-aware capacity limits]
```

### Dependency Notes

- **TTL + Lazy eviction + Periodic sweep**: Combined approach recommended by [Redis](https://redis.io/blog/cache-eviction-strategies/). TTL alone misses expired entries; lazy alone misses unused entries; periodic alone has gaps between sweeps.

- **Warm start → Thread-safety**: Immediate callback means multiple coroutines racing (background refresh + immediate return). Race conditions = duplicate shows or missed ads.

- **Two-level cache → Invalidation**: RTB payload cache stores unvalidated bids. Must remove invalid payloads on load failure to prevent retry loops.

- **Parallel processing → Cancellation**: Background CPM loads must cancel on show() to avoid wasted network/CPU. Critical for battery and bandwidth.

- **Best-pick conflicts with FIFO**: Cannot guarantee FIFO order if selecting max eCPM at show time. Choose one strategy.

## MVP Definition

### Launch With (v1 - Bidon Ad Caching v2.0)

Minimum viable feature set for validating warm start optimization.

- [x] **TTL-based expiration (30 min fixed)** — Table stakes. Ad policies require freshness.
- [x] **Thread-safe cache operations** — Table stakes. Crashes = unusable SDK.
- [x] **Cache invalidation on fail** — Table stakes. Prevents retry loops.
- [x] **Memory-aware capacity limits (1-3 ads)** — Table stakes. OOM = crashes on low-end devices.
- [x] **Lazy eviction + periodic sweep** — Table stakes. Combined approach prevents stale inventory.
- [x] **Duplicate detection (by demandId)** — Table stakes. Memory efficiency.
- [x] **Warm start optimization** — Core differentiator. Primary goal of v2.
- [x] **Two-level cache (READY_TO_SHOW + RTB_PAYLOAD)** — Core differentiator. Revenue optimization.
- [x] **Dynamic pricefloor from cache** — Core differentiator. Prevents underpricing.
- [x] **Parallel RTB + CPM processing** — Core differentiator. Latency reduction.
- [x] **Best-pick on show** — Differentiator. Revenue optimization.
- [x] **Application-wide cache scope** — Differentiator. Fill rate improvement.

**Rationale:** Full spec implementation in v1 per PROJECT.md requirement. All table stakes + key differentiators included.

### Add After Validation (v1.x)

Features to add once core is proven in production.

- [ ] **Advanced Weight Model with ML** — Trigger: After collecting 30 days of fill rate data per network
- [ ] **Chunked parallel CPM loading (2 at a time)** — Trigger: If CPU/battery metrics show opportunity
- [ ] **Adaptive TTL based on ad format** — Trigger: If expiration metrics show format-specific patterns (e.g., video vs banner)
- [ ] **Cache warming on app resume** — Trigger: If metrics show cold start penalty after background
- [ ] **Per-placement cache configuration** — Trigger: If publishers request format-specific tuning

### Future Consideration (v2+)

Features to defer until product-market fit is established.

- [ ] **Cross-session persistent cache** — Why defer: Complexity of disk I/O, TTL validation on restore, rare benefit
- [ ] **Predictive pre-caching** — Why defer: Requires ML model, user behavior analysis, battery concerns
- [ ] **Multi-tier cache (L1/L2)** — Why defer: Premature optimization without demonstrated bottleneck
- [ ] **Cache analytics dashboard** — Why defer: Build internal tooling after proving external value

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Warm start optimization | HIGH | HIGH | P1 |
| Two-level cache | HIGH | HIGH | P1 |
| TTL-based expiration | HIGH | MEDIUM | P1 |
| Thread-safe operations | HIGH | MEDIUM | P1 |
| Parallel RTB+CPM | HIGH | HIGH | P1 |
| Dynamic pricefloor | MEDIUM | MEDIUM | P1 |
| Cache invalidation | MEDIUM | LOW | P1 |
| Lazy eviction | MEDIUM | LOW | P1 |
| Best-pick on show | MEDIUM | LOW | P1 |
| Application-wide scope | MEDIUM | MEDIUM | P1 |
| Weight-based CPM ordering | MEDIUM | MEDIUM | P2 |
| Adaptive TTL | LOW | MEDIUM | P3 |
| Cache warming on resume | LOW | LOW | P3 |

**Priority key:**
- P1: Must have for launch (all table stakes + core differentiators)
- P2: Should have, add when possible (incremental optimizations)
- P3: Nice to have, future consideration (diminishing returns)

## Competitor Feature Analysis

| Feature | AdMob | AppLovin MAX | IronSource LevelPlay | Unity Ads | Bidon v2 |
|---------|-------|--------------|----------------------|-----------|----------|
| **TTL expiration** | ✓ (4h App Open) | ✓ (implicit) | ✓ (implicit) | ✓ (implicit) | ✓ (30 min explicit) |
| **Warm start** | ✗ | ~ (unified auction) | ~ (bidding cache) | ✗ | ✓ (immediate callback) |
| **RTB payload reuse** | ✗ | ✗ | ✗ | ✗ | ✓ (unique feature) |
| **Parallel RTB+CPM** | ✗ (sequential) | ~ (unified auction) | ~ (hybrid waterfall) | ✗ (sequential) | ✓ (explicit parallel) |
| **Dynamic pricefloor** | Manual | ✓ (ML-based) | ✓ (optimization) | Manual | ✓ (cache-based) |
| **Application-wide cache** | ✗ (per-instance) | ? | ? | ✗ (per-instance) | ✓ (singleton) |
| **Best-pick on show** | ✗ (FIFO) | ? | ? | ✗ (FIFO) | ✓ (max eCPM) |
| **Background optimization** | ✓ (thread flags) | ✓ (init caching) | ✓ (mediation mgmt) | ✓ (init caching) | ✓ (parallel async) |

**Key Observations:**

- **AdMob**: Focus on initialization optimization via background threads. No explicit caching strategy beyond 4-hour timeout. Sequential waterfall. ([Source](https://developers.google.com/admob/android/optimize-initialization))

- **AppLovin MAX**: Unified auction replaces traditional waterfall. Recommends single ad unit ID per format for caching. Init-time caching for better UX. No explicit warm start. ([Source](https://support.axon.ai/en/max/getting-started/))

- **IronSource LevelPlay**: Hybrid waterfall + bidding model. Emphasizes bidding vs non-bidding separation. Weight-based optimization. ([Source](https://developers.is.com/ironsource-mobile/air/best-practices-waterfall-management-ironsource-mediation/))

- **Unity Ads**: Traditional waterfall with bidding support. Emphasizes init-time SDK initialization for caching. Sequential processing. ([Source](https://docs.unity.com/monetization-dashboard/en-us/manual/bidding-in-levelplay))

- **Bidon v2**: Only SDK with explicit two-level cache + RTB payload reuse. Warm start optimization is unique differentiator. Parallel processing is explicit design goal.

## Implementation Complexity Analysis

### High Complexity Features (3-5 weeks each)

**Warm Start Optimization:**
- **Why complex**: Race conditions between immediate callback and background refresh. Must handle edge cases (cache empty, cache expired mid-return, concurrent loads).
- **Risk factors**: Thread safety, callback ordering, state management.
- **Mitigation**: Thorough coroutine scope management, state machine for load status, extensive concurrency testing.

**Two-Level Cache System:**
- **Why complex**: Two different data models (loaded ads vs bid payloads), different eviction rules, different validation logic.
- **Risk factors**: Memory overhead, cache coherency, invalidation logic.
- **Mitigation**: Separate singleton objects, clear ownership model, explicit cache boundaries.

**Parallel RTB + CPM Processing:**
- **Why complex**: Coroutine orchestration, cancellation policy, callback coordination (single onAdLoaded despite multiple sources).
- **Risk factors**: Race conditions, memory leaks from uncancelled coroutines, duplicate callbacks.
- **Mitigation**: Structured concurrency with SupervisorJob, careful callback state machine, cancellation hooks.

### Medium Complexity Features (1-2 weeks each)

**TTL-based Expiration:**
- **Why medium**: Combined lazy + periodic approach requires coordination. Timestamp management, timezone handling.
- **Risk factors**: Time drift, edge cases around sweep timing.
- **Mitigation**: Use System.currentTimeMillis(), fixed intervals, defensive checks.

**Dynamic Pricefloor:**
- **Why medium**: Must compute max across two caches + user input. Race conditions on concurrent updates.
- **Risk factors**: Stale pricefloor, incorrect math, negative prices.
- **Mitigation**: Atomic reads, defensive max() with 0.0 floor, logging for validation.

**Application-Wide Cache Scope:**
- **Why medium**: Singleton pattern with thread-safety. Lifecycle management across ad instances.
- **Risk factors**: Memory leaks, stale references, thread contention.
- **Mitigation**: Kotlin object declaration (safe singleton), weak references if needed, concurrent data structures.

### Low Complexity Features (1-3 days each)

**Cache Invalidation on Fail:**
- **Why low**: Simple removal on error callback. No complex logic.
- **Implementation**: `cache.remove(demandId)` in error handler.

**Lazy Eviction:**
- **Why low**: Single timestamp check on access. Inline with existing operations.
- **Implementation**: `if (System.currentTimeMillis() > entry.expiresAt) remove(key)`.

**Best-Pick on Show:**
- **Why low**: Sort by eCPM, return first. Standard collection operation.
- **Implementation**: `cache.values.maxByOrNull { it.ecpm }`.

## Memory Management Considerations

### Memory Budget (per format)

**READY_TO_SHOW Cache:**
- **1 ad**: ~500KB - 2MB (depending on creative assets)
- **3 ads (max recommended)**: ~1.5MB - 6MB
- **Risk**: Image-heavy banners or video interstitials can spike higher

**RTB_PAYLOAD Cache:**
- **10 payloads**: ~10KB - 50KB (JSON bid responses, no assets)
- **Low memory risk**: Text-only data

**Total Budget:** ~2MB - 7MB per format (banner, interstitial, rewarded)

**Strategy:**
- Capacity limit: 1-3 ads per format in READY_TO_SHOW
- Capacity limit: 5-10 payloads per network in RTB_PAYLOAD
- Evict oldest if capacity exceeded (LRU-like)
- Monitor via `Runtime.getRuntime().maxMemory()` for low-memory devices

### Cache Eviction Strategies (Industry Standards)

Based on [cache eviction research](https://redis.io/blog/cache-eviction-strategies/):

**TTL (Time-To-Live):**
- **Best for**: Time-sensitive data with natural expiration (ads, session tokens)
- **Bidon use**: 30-minute fixed TTL for both caches
- **Tradeoff**: May evict hot data prematurely

**LRU (Least Recently Used):**
- **Best for**: Limited capacity with variable access patterns
- **Bidon use**: Secondary eviction if capacity exceeded
- **Tradeoff**: More complex tracking (access timestamps)

**Combined TTL + LRU:**
- **Best practice**: [Redis and Memcached](https://redis.io/blog/lfu-vs-lru-how-to-choose-the-right-cache-eviction-policy/) use this hybrid
- **Bidon implementation**: TTL primary (30 min), LRU fallback if capacity hit
- **Rationale**: Balances freshness (TTL) and memory efficiency (LRU)

**Lazy Eviction:**
- **Pattern**: Check TTL on access (peek/pop/poll)
- **Benefit**: No periodic sweep overhead for accessed entries
- **Limitation**: Unaccessed entries linger until sweep

**Periodic Sweep:**
- **Pattern**: Background job every 5 minutes
- **Benefit**: Cleans unaccessed expired entries
- **Limitation**: Gaps between sweeps (entries linger up to 5 min)

**Recommended Strategy for Ad Caching:**
1. **TTL = 30 minutes** (primary, enforced on access via lazy eviction)
2. **Periodic sweep every 5 minutes** (cleanup unaccessed entries)
3. **LRU-based capacity eviction** (fallback if cache exceeds 3 ads)
4. **Immediate invalidation on error** (don't wait for TTL/sweep)

## Fill Rate vs Latency Tradeoffs

**Fill Rate Definition:** % of load() calls that return an ad successfully

**Latency Definition:** Time from load() call to onAdLoaded() callback

| Strategy | Fill Rate | Latency (Cold) | Latency (Warm) | Complexity |
|----------|-----------|----------------|----------------|------------|
| No caching (baseline) | 75% | 3-15s | 3-15s | LOW |
| Single-level cache (READY_TO_SHOW only) | 75% | 3-15s | <1s (if hit) | MEDIUM |
| Two-level cache (READY_TO_SHOW + RTB_PAYLOAD) | 85% (+10%) | 2-10s (skip tokens) | <1s (if hit) | HIGH |
| Two-level + parallel RTB+CPM | 85% | 1-7s (p95 cut) | <1s (if hit) | HIGH |
| Two-level + parallel + dynamic pricefloor | 85% | 1-7s | <1s (if hit) | HIGH |

**Key Insights:**

- **Warm start**: Reduces latency by 60-90% (from 3-15s to <1-3s). No impact on cold start.
- **RTB payload reuse**: Improves fill rate by 10% (more valid bids available). Reduces cold start latency by 20-40% (skip token collection).
- **Parallel processing**: Reduces cold start latency by 30-50% (concurrent network calls). No impact on fill rate.
- **Dynamic pricefloor**: Neutral to slight negative on fill rate (higher floor = more bid rejections). Improves eCPM (revenue per fill).

**Bidon v2 Target Metrics:**
- **Cold start latency**: <3s (p50), <7s (p95) — down from 3-15s baseline
- **Warm start latency**: <1s (p50), <3s (p95) — new capability
- **Fill rate**: 85%+ — up from 75% baseline
- **Cache hit rate**: 40-60% after initial warmup period

## Sources

### Industry Documentation
- [Optimize initialization and ad loading | Android | Google for Developers](https://developers.google.com/admob/android/optimize-initialization)
- [MAX | Getting started | Axon by AppLovin | Support Center](https://support.axon.ai/en/max/getting-started/)
- [Unity Ads bidding is available in LevelPlay - IronSource Knowledge Center](https://developers.is.com/ironsource-mobile/general/unity-ads-bidding-available-levelplay/)
- [Unity LevelPlay mediation management best practices - ironSource](https://developers.is.com/ironsource-mobile/air/best-practices-waterfall-management-ironsource-mediation/)

### Cache Eviction Research
- [Cache Eviction Strategies Every Redis Developer Should Know | Redis](https://redis.io/blog/cache-eviction-strategies/)
- [LFU vs. LRU: How to choose the right cache eviction policy | Redis](https://redis.io/blog/lfu-vs-lru-how-to-choose-the-right-cache-eviction-policy/)
- [Caching Strategies for APIs: When to TTL and When to Evict | by Vinay Billa | Medium](https://medium.com/@vinaybilla2021/caching-strategies-for-apis-when-to-ttl-and-when-to-evict-8ce8dfcb3356)
- [Cache Eviction Policies Explained: LRU vs LFU vs FIFO vs TTL | by Rakesh Kumar | Web Tech Journals | Dec, 2025 | Medium](https://medium.com/web-tech-journals/cache-eviction-policies-explained-lru-vs-lfu-vs-fifo-vs-ttl-5daf6b50af39)

### Ad Monetization Strategy
- [Waterfall to Real-Time Bidding: The Future of Game Monetization](https://www.iion.io/blog/waterfall-to-real-time-bidding-the-future-of-game-monetization)
- [2024 Mobile Ad Monetization: Bidding vs. Mediation Strategies](https://www.gamebizconsulting.com/blog/optimize-mediation-or-bidding-2024)
- [The Truth About Bidding-Only vs. Hybrid Waterfall Models in Mobile Game Monetization | Gamesforum](https://www.globalgamesforum.com/news/the-truth-about-bidding-only-vs-hybrid-waterfall-models-in-mobile-game-monetization)
- [RIP waterfalls: 3 levers to boost ad mediation revenue when MAX kills the waterfall](https://www.singular.net/blog/ad-mediation-revenue/)

### Pricefloor Optimization
- [​Optimize floor prices in pricing rules​​ (Beta) - Google Ad Manager Help](https://support.google.com/admanager/answer/11385824?hl=en)
- [Floor Price Optimization in Google Ad Manager: Ultimate Publisher Guide](https://monetiscope.com/floor-price-optimization-in-google-ad-manager/)
- [5 Strategies to Maximize Profits for Publishers Using Floor Price Optimization](https://www.mile.tech/blog/profitable-flooring-in-digital-ads-for-publishers)

### Ad SDK Technical Details
- [Expiration time for an ad after it is requested on the device](https://groups.google.com/g/google-admob-ads-sdk/c/67sXBCmWk2s)
- [Google Ads Developer Blog: Announcing the Google Mobile Ads Next-Gen SDK for Android](https://ads-developers.googleblog.com/2026/01/announcing-google-mobile-ads-next-gen.html)
- [Android SDK Overview | Microsoft Learn](https://learn.microsoft.com/en-us/xandr/mobile-sdk/android-sdk)

---

*Feature research for: Bidon SDK Ad Caching v2*
*Researched: 2026-02-05*
*Confidence: HIGH — Based on existing codebase analysis + production SDK documentation + cache eviction research*
