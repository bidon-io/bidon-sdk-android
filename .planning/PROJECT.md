# Bidon SDK — Ad Caching v2 Implementation

## What This Is

Новая система ad caching (v2) для Bidon Android SDK, реализующая двухуровневое кэширование рекламы (READY_TO_SHOW + RTB_PAYLOAD) с параллельной обработкой RTB/CPM групп, warm start оптимизацией и динамическим pricefloor. Ускоряет `onAdLoaded` callback с 3-15 секунд до <1-3 секунд при сохранении высокого eCPM.

## Core Value

Быстрый `onAdLoaded` callback (<1-3 сек вместо 3-15 сек) при сохранении или повышении revenue за счет умного переиспользования кэшированных bid responses и параллельной загрузки рекламы.

## Requirements

### Validated

<!-- Existing functionality that must continue working -->

- ✓ Базовая auction pipeline с последовательным waterfall — existing
- ✓ Адаптеры загружают рекламу через AdSource интерфейс — existing
- ✓ RTB token collection через GetTokensUseCaseImpl — existing
- ✓ Сервер /v2/auction возвращает waterfall — existing
- ✓ Callbacks через Flow<AdEvent> — existing
- ✓ Статистика отправляется на /v2/stats — existing
- ✓ Win/Loss notifications для адаптеров — existing
- ✓ AdCache интерфейс существует (текущая имплементация) — existing

### Active

<!-- New v2 ad caching system to be built -->

**Кэширование:**
- [ ] **CACHE-01**: READY_TO_SHOW cache — хранилище loaded ads ready to show
- [ ] **CACHE-02**: RTB_PAYLOAD cache — хранилище RTB bid responses для переиспользования
- [ ] **CACHE-03**: Application-wide scope через singleton objects
- [ ] **CACHE-04**: TTL 30 минут для обоих кэшей
- [ ] **CACHE-05**: Lazy eviction при доступе + periodic sweep каждые 5 минут
- [ ] **CACHE-06**: Duplicate demandId policy — заменять только если новый eCPM выше
- [ ] **CACHE-07**: Thread-safe operations через ConcurrentHashMap + atomic

**Auction Pipeline:**
- [ ] **AUCTION-01**: Определение типа аукциона (cold vs warm start)
- [ ] **AUCTION-02**: Warm start — немедленный onAdLoaded если READY_TO_SHOW не пуст
- [ ] **AUCTION-03**: Cold start — полный цикл getTokens → /auction → waterfall
- [ ] **AUCTION-04**: Skip token collection для сетей с валидным RTB_PAYLOAD кэшем
- [ ] **AUCTION-05**: Динамический pricefloor = max(READY_TO_SHOW.maxEcpm, RTB_PAYLOAD.maxEcpm, userPricefloor)
- [ ] **AUCTION-06**: Waterfall split на RTB группу и CPM группу

**RTB Processing:**
- [ ] **RTB-01**: Загрузить только первый RTB adUnit (highest priority)
- [ ] **RTB-02**: Остальные RTB adUnits сохранить payload → RTB_PAYLOAD cache
- [ ] **RTB-03**: Если первый RTB fail → попробовать следующий, остальные в кэш
- [ ] **RTB-04**: Success RTB → READY_TO_SHOW cache
- [ ] **RTB-05**: Invalid payload handling — удалять из RTB_PAYLOAD при ошибке load

**CPM Processing:**
- [ ] **CPM-01**: Последовательная загрузка CPM adUnits
- [ ] **CPM-02**: Каждый success CPM → READY_TO_SHOW cache
- [ ] **CPM-03**: Fail CPM → skip, продолжить следующий
- [ ] **CPM-04**: Базовая Weight Model — сортировка CPM по fill rate/eCPM из кэша

**Parallel Execution:**
- [ ] **PARALLEL-01**: RTB processing и CPM processing запускаются параллельно (async)
- [ ] **PARALLEL-02**: Использование Kotlin Coroutines (async/await)
- [ ] **PARALLEL-03**: onAdLoaded callback ровно один раз при первом fill
- [ ] **PARALLEL-04**: Cancellation policy — отменить CPM processing при showAd()

**Lifecycle:**
- [ ] **LIFE-01**: getBest() при showAd() — выбрать ad с максимальным eCPM из READY_TO_SHOW
- [ ] **LIFE-02**: Удалить показанную рекламу из READY_TO_SHOW
- [ ] **LIFE-03**: destroyAd() не очищает кэши (application-wide)
- [ ] **LIFE-04**: Periodic sweep job для expired entries (ad-instance scoped)
- [ ] **LIFE-05**: AdEvent.Expired только для winner ad (показанная реклама)

**Integration:**
- [ ] **INT-01**: AdCacheFactory — factory pattern для выбора версии (old vs v2)
- [ ] **INT-02**: Новая имплементация в пакете org.bidon.sdk.ads.cache.denis
- [ ] **INT-03**: Переиспользование существующего AuctionResolver/GetTokensUseCase
- [ ] **INT-04**: Совместимость с текущими адаптерами без изменений

**Statistics:**
- [ ] **STAT-01**: Новые статусы: CachedPayload, CachedReady, Expired, SkippedTokens, CancelledByShow
- [ ] **STAT-02**: AuctionId tracking из показанной рекламы (entry.auctionId, не последний аукцион)
- [ ] **STAT-03**: Отправка всех RTB fail статусов

### Out of Scope

- Fallback из кэша при failedToShow — не реализуется (удалено из спеки v2.0)
- Win/Loss уведомления при showAd — не отправляются для кэшированных ads
- cachedAdUnits field в /auction request — не отправляется
- Автоматическая замена winner ad при нахождении лучшего — не уведомляем пользователя
- Параллельная загрузка CPM по 2 (chunked) — оставляем последовательной на первую итерацию
- Advanced Weight Model с ML — базовая реализация достаточно
- Изменения в существующей (старой) ad cache имплементации — не трогаем
- Unit tests — отложены на следующую итерацию

## Context

**Existing Architecture:**
- Текущая архитектура описана в `docs/AUCTION_ARCHITECTURE.md`
- Полностью блокирующий waterfall: getTokens → /auction → sequential waterfall (3-15 сек) → onAdLoaded
- RTB payload-ы теряются после первого fill
- Повторный loadAd() начинает весь цикл заново

**Problem:**
- Долгое ожидание onAdLoaded (пользователь видит пустой экран)
- Неэффективное использование RTB responses (выбрасываются после аукциона)
- Нет переиспользования данных между аукционами
- RTB и CPM обрабатываются последовательно в смешанном порядке

**Solution:**
- Двухуровневое кэширование (READY_TO_SHOW + RTB_PAYLOAD)
- Warm start optimization — немедленный onAdLoaded если кэш не пуст
- Параллельная обработка RTB и CPM групп
- Динамический pricefloor на основе кэша
- Переиспользование RTB payload-ов

**Technical Environment:**
- Kotlin, Android SDK
- Kotlin Coroutines для асинхронности
- Существующие use cases (GetTokensUseCase, GetAuctionRequestUseCase)
- Flow-based callbacks (AdEvent)
- ConcurrentHashMap для thread-safety

**Reference Documentation:**
- Детальная спецификация: `docs/AD_CACHING_SPEC.md` (v2.0-final, 1100+ строк)
- Текущая архитектура: `docs/AUCTION_ARCHITECTURE.md`
- Стартовая точка: `bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheDenisImpl.kt`

**Testing:**
- Тестирование через https://github.com/AlexGladkov/claude-in-mobile на Android эмуляторе
- Unit tests отложены на следующую итерацию

## Constraints

- **Tech Stack**: Kotlin, Kotlin Coroutines (async/await) — обязательно для parallel processing
- **Package Structure**: Новая реализация в `org.bidon.sdk.ads.cache.denis` — изоляция от старого кода
- **Compatibility**: Не ломать существующую имплементацию — базовую оставить нетронутой
- **Adapter Compatibility**: Работа с текущими адаптерами без изменений — используем тот же AdSource интерфейс
- **Performance**: Non-blocking operations, параллельная загрузка — критично для UX
- **Memory**: Application-wide кэши через singleton objects — один экземпляр на тип рекламы
- **Timeline**: Full spec implementation в первой итерации — все функции из спеки v2.0

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Отдельный пакет .denis вместо .v2 | Экспериментальная реализация, легко откатить | — Pending |
| Singleton objects для кэшей | Простота, thread-safety out of the box в Kotlin | — Pending |
| Factory pattern для интеграции | Легко переключаться между версиями, A/B тесты | — Pending |
| Kotlin Coroutines вместо RxJava | Согласно спеке, современный подход в Android | — Pending |
| TTL 30 минут фиксированный | Баланс между актуальностью и fill rate | — Pending |
| Базовая Weight Model | Простая сортировка достаточна для первой итерации | — Pending |
| Tests later | Фокус на имплементацию, затем покрытие тестами | — Pending |

---
*Last updated: 2026-02-05 after project initialization*
