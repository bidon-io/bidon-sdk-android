# Ad Caching v1 — Gap Analysis & Future Roadmap

> **Version:** 1.0
> **Date:** 2026-02-05
> **Related:** [AD_CACHING_SPEC.md](./AD_CACHING_SPEC.md)

## Цель документа

Этот документ описывает функциональность, которая **НЕ вошла** в v1 реализацию ad caching системы. Разделено на три категории:

1. **Сознательно исключено** (out of scope) — не будет реализовано по архитектурным причинам
2. **Отложено на v2** (deferred) — полезные фичи для будущих итераций
3. **Технический долг** (technical debt) — что нужно добавить для production-ready

---

## 1. Сознательно исключено (Out of Scope)

### 1.1 Fallback из кэша при failedToShow

**Что это:**
При ошибке показа рекламы (failedToShow) автоматически показывать следующую best ad из READY_TO_SHOW кэша.

**Почему НЕ реализуем:**
```
User calls showAd()
  ├─ getBest() → Ad A ($5.00)
  ├─ adSource.show() → FAIL (expired creative, network error)
  │
  └─ ❌ Показать Ad B ($4.50) из кэша?
```

**Проблемы:**
- **Auction integrity violation:** Backend ожидает одно win notification, получит два
- **Win/Loss accounting issues:** Какой ad считать winner? A или B?
- **Revenue tracking corruption:** Статистика становится некорректной
- **Policy violations:** Некоторые сети запрещают fallback без нового аукциона

**Решение в v1:**
При failedToShow выдаём `onAdShowFailed()` → пользователь вызывает новый `loadAd()`.

**Альтернатива (если очень нужно):**
Создать **новый аукцион** с высоким pricefloor (например, $4.50), используя warm start.

---

### 1.2 Win/Loss notifications при каждом showAd()

**Что это:**
Отправлять win notification показанной рекламе и loss notifications всем остальным ads в READY_TO_SHOW при каждом `showAd()`.

**Почему НЕ реализуем:**

```
Auction #1:
  Result: [Meta $5, AdMob $4, Unity $3]
  → onAdLoaded(Meta $5)
  → Win notification sent by auction

User calls showAd() 3 times:
  Show 1: Meta $5  ❌ send WIN again?
  Show 2: AdMob $4 ❌ send WIN again? (was LOSS before)
  Show 3: Unity $3 ❌ send WIN again? (was LOSS before)
```

**Проблемы:**
- **Double win notifications:** Meta получит win от аукциона И от show
- **Loss → Win conversion:** AdMob получил loss, потом win — нарушение контракта
- **Accounting nightmare:** Backend networks не ожидают повторных notifications
- **Billing issues:** Возможны duplicate charges

**Решение в v1:**
Win/Loss notifications отправляются **ОДИН РАЗ** по завершению аукциона. Кэш не влияет на notifications.

---

### 1.3 cachedAdUnits field в /auction request

**Что это:**
Отправлять список кэшированных ad units в запросе `/v2/auction` для backend optimization.

**Почему НЕ реализуем:**

```json
POST /v2/auction/interstitial
{
  "auctionId": "uuid",
  "pricefloor": 4.50,
  "tokens": { ... },
  "cachedAdUnits": [  ❌ НЕ НУЖНО
    {
      "demandId": "meta_an",
      "pricefloor": 5.00,
      "uid": "cached_uid_1"
    }
  ]
}
```

**Почему не нужно:**
- **Dynamic pricefloor уже решает проблему:** Backend получает высокий pricefloor ($4.50) и понимает, что у клиента есть качественная реклама
- **Backend не должен знать о клиентском кэше:** Архитектурное разделение ответственности
- **Избыточная информация:** Backend не может (и не должен) проверить валидность кэшированных ads
- **Privacy concerns:** Раскрываем внутреннее состояние клиента

**Решение в v1:**
Кэш влияет **только** на:
1. Token collection (skipDemandIds)
2. Dynamic pricefloor calculation

Backend получает результат (pricefloor), но не детали (список кэшированных ads).

---

### 1.4 Автоматическая замена winner ad при нахождении лучшего

**Что это:**
Если во время фонового аукциона загружается ad с более высоким eCPM, вызывать повторный `onAdLoaded()`.

**Почему НЕ реализуем:**

```
Warm Start:
  T=0s:  onAdLoaded(Meta $5) ← из кэша

Background auction:
  T=3s:  RTB загрузился → BidMachine $7
         ❌ onAdLoaded(BidMachine $7) AGAIN?
```

**Проблемы:**
- **Confusing UX:** Пользователь уже получил callback, UI готов к показу, и вдруг снова callback?
- **Race conditions:** Что если пользователь уже вызвал `showAd()` между двумя callbacks?
- **Breaking contract:** `onAdLoaded` должен вызываться **ровно один раз** за `loadAd()`
- **SDK complexity:** Нужна state machine для отслеживания "can update winner"

**Решение в v1:**
`onAdLoaded()` вызывается **ровно один раз**. Лучший ad автоматически будет использован при **следующем** `showAd()` через `getBest()`.

**Benefit:**
Простота, предсказуемость, соответствие контракту SDK.

---

### 1.5 Параллельная загрузка CPM по 2 (chunked parallel)

**Что это:**
Загружать CPM ad units не последовательно, а параллельно по 2.

**Текущая реализация (v1):**
```kotlin
// Sequential loading
for (adUnit in cpmGroup) {
    val result = loadAdUnit(adUnit)  // wait for completion
    if (result.isSuccess) {
        cache.put(result)
    }
}
```

**Предложенная оптимизация (v2):**
```kotlin
// Chunked parallel loading
cpmGroup.chunked(2).forEach { chunk ->
    val results = chunk.map { adUnit ->
        async { loadAdUnit(adUnit) }
    }.awaitAll()

    results.forEach { result ->
        if (result.isSuccess) cache.put(result)
    }
}
```

**Почему отложили на v2:**
- **Complexity vs Benefit:** Parallel loading усложняет код, но gain небольшой (CPM networks быстрые)
- **Risk of overload:** Параллельные запросы могут перегрузить устройство или сеть
- **Ordering discipline:** Последовательная загрузка проще для debugging и understanding
- **First iteration priority:** Фокус на корректности, не на micro-optimization

**Когда добавить:**
Если профилирование покажет, что CPM loading — bottleneck (маловероятно).

---

### 1.6 Advanced Weight Model с ML

**Что это:**
Использовать machine learning для предсказания fill rate и оптимизации CPM ordering.

**Текущая реализация (v1):**
```kotlin
// Basic weight model
weight = (fillCount * 10.0) / max(attemptCount, 1)
score = adUnit.pricefloor * (weight / 10.0)
```

**Предложенная оптимизация (v2):**
```kotlin
// ML-based prediction
class MLWeightModel {
    fun predict(context: PredictionContext): Double {
        // TensorFlow Lite model
        // Features: time of day, day of week, user segment,
        //           historical fill rate, eCPM, network latency
        return mlModel.predict(features)
    }
}
```

**Почему отложили на v2:**
- **Overkill for v1:** Базовая weight model достаточно эффективна
- **Data requirements:** ML требует большого объёма исторических данных
- **Model maintenance:** Нужна инфраструктура для training, deployment, versioning
- **ROI uncertain:** Unclear если ML даст значительное улучшение revenue

**Когда добавить:**
После накопления статистики (3-6 месяцев) и анализа opportunity size.

---

### 1.7 Изменения в старой ad cache имплементации

**Что это:**
Модифицировать существующую `AdCacheImpl` вместо создания новой `AdCacheDenisImpl`.

**Почему НЕ реализуем:**
- **Risk of breaking existing functionality:** Старая система работает, не трогаем
- **Easier rollback:** Новый пакет `.denis` можно просто удалить
- **A/B testing:** Factory pattern позволяет сравнивать версии
- **Cleaner git history:** Новые файлы vs mixed changes
- **Parallel development:** Команда может работать над старой системой параллельно

**Решение в v1:**
Новая реализация в отдельном пакете `org.bidon.sdk.ads.cache.denis`.

---

## 2. Отложено на v2 (Deferred Features)

### 2.1 Unit Tests

**Что нужно:**
- Unit tests для всех cache stores (READY_TO_SHOW, RTB_PAYLOAD)
- Unit tests для processors (RtbProcessor, CpmProcessor)
- Unit tests для CoordinationLayer (state machine, pricefloor calculation)
- Unit tests для lifecycle components (sweep, cancellation)
- Integration tests для полного auction flow

**Почему отложили:**
- **Focus on implementation:** Первая итерация — функциональность, потом покрытие
- **Faster iteration:** Без тестов быстрее экспериментировать с архитектурой
- **Manual testing available:** Можно тестировать через https://github.com/AlexGladkov/claude-in-mobile

**Приоритет для v2:** 🔴 HIGH

**Coverage target:** 80%+ для core logic

---

### 2.2 Adaptive TTL based on ad format

**Текущая реализация:**
```kotlin
const val DEFAULT_TTL_MS = 30 * 60 * 1000L  // Fixed 30 minutes
```

**Предложенная оптимизация:**
```kotlin
object AdaptiveTtlConfig {
    val BANNER_TTL = 15.minutes      // Banners refresh faster
    val INTERSTITIAL_TTL = 30.minutes // Standard
    val REWARDED_TTL = 45.minutes     // Users wait longer for rewarded
}

fun getTtl(adType: AdType): Duration = when (adType) {
    AdType.BANNER -> AdaptiveTtlConfig.BANNER_TTL
    AdType.INTERSTITIAL -> AdaptiveTtlConfig.INTERSTITIAL_TTL
    AdType.REWARDED -> AdaptiveTtlConfig.REWARDED_TTL
}
```

**Benefits:**
- Banners загружаются чаще → shorter TTL prevents stale inventory
- Rewarded ads показываются реже → longer TTL increases fill rate

**Effort:** Low (1-2 hours)
**Impact:** Medium (5-10% fill rate improvement)

---

### 2.3 Cache warming on app resume from background

**Что это:**
Автоматически запускать background auction при возвращении app из background.

**Реализация:**
```kotlin
class AppLifecycleObserver : DefaultLifecycleObserver {
    override fun onResume(owner: LifecycleOwner) {
        if (shouldWarmCache()) {
            // Check if cache is stale
            val cacheAge = now() - ReadyToShowCache.getOldestTimestamp()

            if (cacheAge > 10.minutes) {
                // Silent background auction
                adCache.cache(
                    silent = true,  // No onAdLoaded callback
                    pricefloor = null
                )
            }
        }
    }
}
```

**Benefits:**
- Пользователь возвращается → ads уже loaded
- Улучшает warm start hit rate

**Risks:**
- Battery drain
- Unexpected network traffic
- Policy violations (некоторые сети запрещают invisible loads)

**Effort:** Medium (1 day)
**Impact:** High (20-30% faster perceived load time)

---

### 2.4 Per-placement cache configuration

**Что это:**
Разные настройки кэша для разных ad placements.

**Текущая реализация:**
```kotlin
// Global singleton caches
object ReadyToShowCache { ... }
object RtbPayloadCache { ... }
```

**Предложенная оптимизация:**
```kotlin
class PlacementCacheConfig(
    val maxReadyAds: Int = 3,
    val maxRtbPayloads: Int = 10,
    val ttl: Duration = 30.minutes,
    val enablePeriodicSweep: Boolean = true
)

// Per-placement configuration
val mainScreenCache = PlacementCacheConfig(
    maxReadyAds = 5,      // High-traffic placement
    ttl = 20.minutes      // Shorter TTL for freshness
)

val rewardedCache = PlacementCacheConfig(
    maxReadyAds = 2,      // Low-traffic placement
    ttl = 60.minutes      // Longer TTL for fill rate
)
```

**Benefits:**
- Оптимизация под traffic pattern каждого placement
- Более гранулярный контроль над memory usage

**Effort:** High (2-3 days)
**Impact:** Medium (10-15% memory optimization)

---

### 2.5 Cross-session persistent cache (disk storage)

**Что это:**
Сохранять RTB_PAYLOAD на диск для переиспользования между app sessions.

**Текущая реализация:**
```kotlin
// In-memory only
object RtbPayloadCache {
    private val cache = ConcurrentHashMap<String, RtbPayloadEntry>()
    // Lost on app restart
}
```

**Предложенная оптимизация:**
```kotlin
class PersistentRtbCache(private val storage: CacheStorage) {
    suspend fun save() {
        val entries = RtbPayloadCache.getAll()
        storage.write("rtb_cache.json", entries.toJson())
    }

    suspend fun restore() {
        val entries = storage.read("rtb_cache.json")?.parseJson()
        entries?.forEach { RtbPayloadCache.put(it) }
    }
}

// On app start
BidonSdk.initialize {
    persistentCache.restore()
}

// On app background
onAppPause {
    persistentCache.save()
}
```

**Benefits:**
- Первый `loadAd()` после app restart использует cached payloads
- Холодный старт становится тёплым

**Risks:**
- Disk I/O overhead
- Stale data (payloads могут expiry к моменту restore)
- Encryption requirements (sensitive bid data)

**Effort:** High (3-4 days)
**Impact:** High (первый load после restart ~5x faster)

---

### 2.6 Predictive pre-caching на основе user behavior

**Что это:**
Предсказывать когда пользователь вызовет `showAd()` и pre-load ads заранее.

**Реализация:**
```kotlin
class PredictivePreCacher {
    private val userBehaviorAnalyzer = UserBehaviorAnalyzer()

    fun analyze() {
        // User patterns:
        // - Shows ad every 5 levels
        // - Shows ad after 10 minutes gameplay
        // - Shows ad when life count = 0

        val prediction = userBehaviorAnalyzer.predictNextShowTime()

        if (prediction.confidence > 0.8 && prediction.timeUntilShow < 30.seconds) {
            // Pre-cache silently
            adCache.cache(silent = true)
        }
    }
}
```

**Benefits:**
- Zero perceived load time (ads уже loaded когда нужны)
- Улучшает UX

**Risks:**
- Wasted network/battery на неиспользованные ads
- Сложность prediction logic
- Privacy concerns (tracking user behavior)

**Effort:** Very High (1-2 weeks)
**Impact:** Very High (instant ad display)

---

### 2.7 Multi-tier cache (L1/L2)

**Что это:**
Двухуровневый кэш: быстрый in-memory L1 + медленный persistent L2.

**Архитектура:**
```kotlin
class TieredCache {
    private val l1 = InMemoryCache(maxSize = 3)  // Hot cache
    private val l2 = DiskCache(maxSize = 10)      // Cold cache

    suspend fun getBest(): AdEntry? {
        return l1.getBest()
            ?: l2.getBest()?.also {
                l1.put(it)  // Promote to L1
            }
    }

    suspend fun put(entry: AdEntry) {
        if (l1.size() < l1.maxSize) {
            l1.put(entry)  // Add to hot cache
        } else {
            l2.put(entry)  // Spill to cold cache
        }
    }
}
```

**Benefits:**
- Хранение большего количества ads без memory overhead
- Automatic tier promotion на основе access patterns

**Effort:** Very High (2 weeks)
**Impact:** Medium (5-10% fill rate improvement)

---

### 2.8 Cache analytics dashboard

**Что это:**
Telemetry и monitoring для понимания эффективности кэша.

**Metrics to track:**
```kotlin
data class CacheMetrics(
    val hitRate: Double,              // % warm starts
    val avgWarmStartLatency: Duration, // <1s target
    val avgColdStartLatency: Duration, // ~5-7s baseline
    val cacheSize: Int,                // Current READY_TO_SHOW size
    val expiredCount: Int,             // Ads expired before show
    val fillRate: Double,              // % successful auctions
    val avgEcpm: Double,               // Average eCPM in cache
    val dynamicPricefloorAvg: Double   // Average dynamic pricefloor
)

// Backend dashboard
POST /v2/analytics/cache-metrics
{
    "sessionId": "uuid",
    "metrics": { ... },
    "timestamp": "2026-02-05T12:00:00Z"
}
```

**Benefits:**
- Data-driven optimization decisions
- A/B testing validation
- ROI measurement

**Effort:** Medium (3-4 days)
**Impact:** Indirect (enables future optimizations)

---

## 3. Технический долг (Technical Debt)

### 3.1 Unit tests (CRITICAL)

**Статус:** ❌ Not implemented
**Priority:** 🔴 HIGH
**Effort:** 1 week

**What needs testing:**
- Cache stores (thread-safety, TTL, duplicate handling)
- Processors (parallel execution, cleanup, error handling)
- Coordination layer (state machine, pricefloor calculation)
- Lifecycle components (sweep, cancellation)

**Coverage target:** 80%+

---

### 3.2 Memory leak validation

**Статус:** ⚠️ Partially implemented (WeakReference pattern)
**Priority:** 🔴 HIGH
**Effort:** 2 days

**What to validate:**
- Activity context не retained singleton caches
- AdSource.destroy() вызывается для всех expired ads
- Coroutine scopes properly cancelled on destroyAd()

**Tools:**
- LeakCanary integration
- Memory profiling в Android Studio

---

### 3.3 Stress testing

**Статус:** ❌ Not implemented
**Priority:** 🟡 MEDIUM
**Effort:** 3 days

**Scenarios to test:**
- 100 consecutive `loadAd()` calls
- Rapid `loadAd()` → `showAd()` → `loadAd()` cycles
- Cache под memory pressure (low memory device)
- High-latency network conditions
- Adapter failures (100% fail rate)

---

### 3.4 Backend integration testing

**Статус:** ❌ Not implemented
**Priority:** 🟡 MEDIUM
**Effort:** 2 days

**What to test:**
- Dynamic pricefloor accepted by backend
- skipDemandIds корректно фильтрует tokens
- Statistics новые статусы (CachedPayload, etc.) accepted
- AuctionId tracking корректный

---

### 3.5 Performance benchmarking

**Статус:** ❌ Not implemented
**Priority:** 🟢 LOW
**Effort:** 1 day

**Metrics to measure:**
- Warm start latency (target: <1s)
- Cold start latency (target: 5-7s)
- Cache operation overhead (<10ms)
- Memory footprint (target: <5MB)

---

### 3.6 Error handling edge cases

**Статус:** ⚠️ Partially implemented
**Priority:** 🟡 MEDIUM
**Effort:** 2 days

**Cases to handle:**
- Empty waterfall from backend (warm start saves the day)
- All RTB payloads invalid (fallback to CPM)
- All CPM fails (user gets onAdLoadFailed)
- Concurrent `loadAd()` calls (should block)
- `showAd()` during ongoing auction (cancel auction)

---

### 3.7 Documentation

**Статус:** ⚠️ Partial (AD_CACHING_SPEC.md exists)
**Priority:** 🟡 MEDIUM
**Effort:** 1 day

**Missing docs:**
- Integration guide для app developers
- Migration guide from old cache to v2
- Troubleshooting guide (common issues)
- Architecture decision records (ADRs)

---

## 4. Приоритизация v2 roadmap

### Must Have (v2.1)
1. ✅ Unit tests (CRITICAL for production)
2. ✅ Memory leak validation
3. ✅ Stress testing

**Timeline:** 1-2 weeks
**Goal:** Production-ready stability

---

### Should Have (v2.2)
1. Adaptive TTL based on ad format
2. Cache warming on app resume
3. Performance benchmarking
4. Backend integration testing

**Timeline:** 2-3 weeks
**Goal:** Performance optimization

---

### Nice to Have (v2.3+)
1. Per-placement cache configuration
2. Cross-session persistent cache
3. Cache analytics dashboard
4. Predictive pre-caching
5. Multi-tier cache (L1/L2)

**Timeline:** 2-3 months
**Goal:** Advanced features

---

## 5. Выводы

### Что покрывает v1:
✅ Двухуровневое кэширование (READY_TO_SHOW + RTB_PAYLOAD)
✅ Warm start optimization (<1s onAdLoaded)
✅ Parallel RTB/CPM processing
✅ Dynamic pricefloor
✅ Token collection optimization
✅ Periodic sweep и cancellation
✅ Thread-safety и memory leak prevention

### Что НЕ покрывает v1:
❌ Unit tests
❌ Advanced optimizations (ML, predictive caching)
❌ Production stress testing
❌ Cross-session persistence

### Рекомендации:
1. **Завершить v1:** Phase 4 gap + Phase 5 integration
2. **Запустить v2.1 immediately:** Unit tests + stress testing
3. **Collect data 1-2 months:** Analyze cache hit rate, revenue impact
4. **Prioritize v2.2+ features** based on data

---

**Document Status:** Draft
**Last Updated:** 2026-02-05
**Next Review:** After v1 completion