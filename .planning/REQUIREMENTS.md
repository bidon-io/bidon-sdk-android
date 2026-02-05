# Requirements: Bidon SDK — Ad Caching v2

**Defined:** 2026-02-05
**Core Value:** Быстрый onAdLoaded callback (<1-3 сек вместо 3-15 сек) при сохранении высокого eCPM через переиспользование кэшированных bid responses

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### Cache Stores

- [x] **CACHE-01**: Implement ReadyToShowCache — thread-safe хранилище loaded ads ready to show
- [x] **CACHE-02**: Implement RtbPayloadCache — thread-safe хранилище RTB bid responses для переиспользования
- [x] **CACHE-03**: Application-wide scope через singleton objects для обоих кэшей
- [x] **CACHE-04**: TTL 30 минут для записей в обоих кэшах (фиксированный)
- [x] **CACHE-05**: Lazy eviction — проверка TTL при каждом доступе (peek/pop/poll)
- [ ] **CACHE-06**: Periodic sweep — фоновая корутина каждые 5 минут для очистки expired entries
- [x] **CACHE-07**: Duplicate demandId policy — заменять только если новый eCPM выше
- [x] **CACHE-08**: Thread-safety через ConcurrentHashMap + atomic operations
- [x] **CACHE-09**: Memory-aware capacity limits (1-3 ads READY_TO_SHOW, 5-10 RTB_PAYLOAD)
- [x] **CACHE-10**: Graceful degradation — empty cache не ломает load flow

### Auction Flow

- [ ] **AUCTION-01**: Определение типа аукциона (cold start vs warm start)
- [ ] **AUCTION-02**: Warm start — немедленный onAdLoaded если READY_TO_SHOW не пуст
- [ ] **AUCTION-03**: Cold start — полный цикл getTokens → /auction → waterfall
- [ ] **AUCTION-04**: Skip token collection для сетей с валидным RTB_PAYLOAD кэшем
- [ ] **AUCTION-05**: Динамический pricefloor = max(READY_TO_SHOW.maxEcpm, RTB_PAYLOAD.maxEcpm, userPricefloor)
- [ ] **AUCTION-06**: Waterfall split на RTB группу и CPM группу

### RTB Processing

- [x] **RTB-01**: Загрузить только первый RTB adUnit (highest priority)
- [x] **RTB-02**: Остальные RTB adUnits сохранить payload → RTB_PAYLOAD cache
- [x] **RTB-03**: Если первый RTB fail → попробовать следующий, остальные в кэш
- [x] **RTB-04**: Success RTB → READY_TO_SHOW cache
- [x] **RTB-05**: Invalid payload handling — удалять из RTB_PAYLOAD при ошибке load()

### CPM Processing

- [x] **CPM-01**: Последовательная загрузка CPM adUnits (один за другим)
- [x] **CPM-02**: Каждый success CPM → READY_TO_SHOW cache
- [x] **CPM-03**: Fail CPM → skip, продолжить следующий
- [x] **CPM-04**: Базовая Weight Model — сортировка CPM по fill rate/eCPM из кэша

### Parallel Execution

- [x] **PARALLEL-01**: RTB processing и CPM processing запускаются параллельно (async)
- [x] **PARALLEL-02**: Использование Kotlin Coroutines (async/await, SupervisorJob)
- [x] **PARALLEL-03**: onAdLoaded callback ровно один раз при первом fill (AtomicBoolean)
- [ ] **PARALLEL-04**: Cancellation policy — отменить CPM processing при showAd() (deferred to Phase 4)

### Lifecycle Management

- [ ] **LIFE-01**: getBest() при showAd() — выбрать ad с максимальным eCPM из READY_TO_SHOW
- [ ] **LIFE-02**: Удалить показанную рекламу из READY_TO_SHOW после show
- [ ] **LIFE-03**: destroyAd() не очищает кэши (application-wide scope)
- [ ] **LIFE-04**: Periodic sweep job для expired entries (ad-instance scoped, каждые 5 мин)
- [ ] **LIFE-05**: AdEvent.Expired только для winner ad (показанная реклама)
- [ ] **LIFE-06**: Proper cleanup в finally blocks (NonCancellable context)
- [ ] **LIFE-07**: WeakReference pattern для Activity context (избежать memory leaks)

### Integration

- [ ] **INT-01**: AdCacheFactory — factory pattern для выбора версии (old vs v2)
- [ ] **INT-02**: Новая имплементация в пакете org.bidon.sdk.ads.cache.denis
- [ ] **INT-03**: Переиспользование GetTokensUseCase с поддержкой skipDemandIds
- [ ] **INT-04**: Переиспользование GetAuctionRequestUseCase (динамический pricefloor)
- [ ] **INT-05**: Совместимость с текущими адаптерами без изменений (AdSource интерфейс)

### Statistics & Tracking

- [ ] **STAT-01**: Новые статусы AdUnit: CachedPayload, CachedReady, Expired, SkippedTokens, CancelledByShow
- [ ] **STAT-02**: AuctionId tracking из показанной рекламы (entry.auctionId, не последний аукцион)
- [ ] **STAT-03**: Отправка всех RTB fail статусов на /v2/stats

### Thread Safety & Correctness

- [x] **SAFETY-01**: Monotonic time source для TTL (SystemClock.elapsedRealtime вместо currentTimeMillis)
- [x] **SAFETY-02**: Synchronized blocks для compound cache operations (put + notify)
- [x] **SAFETY-03**: Proper CoroutineScope injection (no GlobalScope)
- [x] **SAFETY-04**: Mutex для coroutine-friendly critical sections (вместо synchronized где возможно)

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Advanced Optimizations

- **OPT-01**: Advanced Weight Model с ML для CPM ordering
- **OPT-02**: Chunked parallel CPM loading (по 2 одновременно)
- **OPT-03**: Adaptive TTL based on ad format (banner vs interstitial vs rewarded)
- **OPT-04**: Cache warming on app resume from background
- **OPT-05**: Per-placement cache configuration
- **OPT-06**: Cross-session persistent cache (disk storage)
- **OPT-07**: Predictive pre-caching на основе user behavior
- **OPT-08**: Multi-tier cache (L1/L2) для редко используемых ads
- **OPT-09**: Cache analytics dashboard для мониторинга

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Fallback из кэша при failedToShow | Breaks auction integrity, complicates win/loss notifications. New auction instead. |
| Win/Loss уведомления при каждом showAd | Networks expect notification once per auction, not per show. Causes accounting issues. |
| cachedAdUnits field в /auction request | Не нужен — кэш влияет только на token collection, не на запрос к серверу. |
| Автоматическая замена winner ad при нахождении лучшего | Не уведомляем пользователя — onAdLoaded один раз. |
| Параллельная загрузка CPM по 2 (chunked) | Оставляем последовательной на первую итерацию для простоты. |
| Advanced Weight Model с ML | Базовая реализация (fill rate sorting) достаточно для v1. |
| Изменения в существующей (старой) ad cache имплементации | Не трогаем — новая версия в отдельном пакете. |
| Unit tests | Отложены на следующую итерацию — фокус на имплементацию. |
| Aggressive pre-caching (5+ ads) | Memory pressure, stale inventory, policy violations. Limit 1-3 ads. |
| Infinite TTL / no expiration | Stale ads = policy violations, lost revenue. Fixed 30 min TTL. |
| Cache-before-initialize | Race conditions, missing config, adapter not ready. Wait for init callback. |
| Cross-format cache sharing | Different ad formats have different tokens/params. Separate caches per format. |
| Automatic cache refresh without trigger | Battery drain, bandwidth waste. Refresh only on explicit load() or show(). |
| Synchronous cache operations | ANR on main thread. Use suspend functions. |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CACHE-01 | Phase 1 | Complete |
| CACHE-02 | Phase 1 | Complete |
| CACHE-03 | Phase 1 | Complete |
| CACHE-04 | Phase 1 | Complete |
| CACHE-05 | Phase 1 | Complete |
| CACHE-06 | Phase 1, Phase 4 | Pending |
| CACHE-07 | Phase 1 | Complete |
| CACHE-08 | Phase 1 | Complete |
| CACHE-09 | Phase 1 | Complete |
| CACHE-10 | Phase 1 | Complete |
| AUCTION-01 | Phase 3 | Pending |
| AUCTION-02 | Phase 3 | Pending |
| AUCTION-03 | Phase 3 | Pending |
| AUCTION-04 | Phase 3 | Pending |
| AUCTION-05 | Phase 3 | Pending |
| AUCTION-06 | Phase 3 | Pending |
| RTB-01 | Phase 2 | Pending |
| RTB-02 | Phase 2 | Pending |
| RTB-03 | Phase 2 | Pending |
| RTB-04 | Phase 2 | Pending |
| RTB-05 | Phase 2 | Pending |
| CPM-01 | Phase 2 | Pending |
| CPM-02 | Phase 2 | Pending |
| CPM-03 | Phase 2 | Pending |
| CPM-04 | Phase 2 | Pending |
| PARALLEL-01 | Phase 2 | Pending |
| PARALLEL-02 | Phase 2 | Pending |
| PARALLEL-03 | Phase 2 | Pending |
| PARALLEL-04 | Phase 2 | Pending |
| LIFE-01 | Phase 5 | Pending |
| LIFE-02 | Phase 5 | Pending |
| LIFE-03 | Phase 4 | Pending |
| LIFE-04 | Phase 4 | Pending |
| LIFE-05 | Phase 4 | Pending |
| LIFE-06 | Phase 4 | Pending |
| LIFE-07 | Phase 4 | Pending |
| INT-01 | Phase 5 | Pending |
| INT-02 | Phase 5 | Pending |
| INT-03 | Phase 3 | Pending |
| INT-04 | Phase 3 | Pending |
| INT-05 | Phase 5 | Pending |
| STAT-01 | Phase 5 | Pending |
| STAT-02 | Phase 5 | Pending |
| STAT-03 | Phase 5 | Pending |
| SAFETY-01 | Phase 1 | Complete |
| SAFETY-02 | Phase 1 | Complete |
| SAFETY-03 | Phase 2 | Pending |
| SAFETY-04 | Phase 2 | Pending |

**Coverage:**
- v1 requirements: 48 total
- Mapped to phases: 48
- Unmapped: 0 ✓

**Coverage by Phase:**
- Phase 1 (Foundation): 12 requirements
- Phase 2 (Parallel Processing): 14 requirements
- Phase 3 (Coordination Layer): 8 requirements
- Phase 4 (Lifecycle Management): 6 requirements
- Phase 5 (Entry Point & Integration): 8 requirements

---
*Requirements defined: 2026-02-05*
*Last updated: 2026-02-05 after roadmap creation with 100% coverage*
