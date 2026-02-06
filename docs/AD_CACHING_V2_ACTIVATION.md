# Ad Caching v2 — Activation Guide

> **Date:** 2026-02-06
> **Version:** Ad Caching v2 (Denis Implementation)

## Активация Ad Caching v2

### Метод 1: Через Extras API (Рекомендуется для тестирования)

Самый простой способ активировать Ad Caching v2 — использовать метод `addExtra()`:

```kotlin
// Для Interstitial Ads
val interstitial = InterstitialAd(auctionKey = "YOUR_KEY").apply {
    addExtra("cache_size", 2)  // Активирует Ad Caching v2
}

// Для Rewarded Ads
val rewarded = RewardedAd(auctionKey = "YOUR_KEY").apply {
    addExtra("cache_size", 2)  // Активирует Ad Caching v2
}

// Для Banner Ads
val banner = BannerAd().apply {
    addExtra("cache_size", 2)  // Активирует Ad Caching v2
}
```

### Версии Ad Cache

| Версия | cache_size value | Реализация | Статус |
|--------|-----------------|------------|--------|
| v1 | 1 (default) | AdCacheImpl | ✅ Production |
| v2 | 2 | AdCacheDenis | 🧪 Testing |
| v3 | 3 | AdCacheAndrei | 🧪 Testing |
| v4 | 4 | AdCacheVladimir | 🧪 Testing |
| v5 | 5 | AdCacheAlex | 🧪 Testing |

### Метод 2: Через код инициализации (Глобально)

Если вы хотите активировать v2 для всех ad instances, можно установить это глобально:

```kotlin
// В Application.onCreate() или перед созданием любых ads
BidonSdk.apply {
    // ... другие настройки ...
}.initialize(context, appKey)

// Затем для каждого ad добавляйте extra
```

**Примечание:** На данный момент нет глобального API для установки версии кэша. Нужно устанавливать для каждого ad instance индивидуально через `addExtra()`.

## Проверка активации

### Через logcat

После активации v2 вы должны видеть логи с тегом `BidonCache`:

```bash
# v1 (старая версия)
[AdCacheImpl_interstitial] Auction completed

# v2 (новая версия)
[BidonCache] CoordinationLayer: determineStartState() → PureColdStart
[BidonCache] CoordinationLayer: READY_TO_SHOW.getBest() → RTB $5.00
[BidonCache] ReadyToShowCache: Entry added
```

### Logcat фильтры для мониторинга v2

```bash
# Все логи Ad Caching v2
adb logcat -s BidonCache:D

# Только coordination layer
adb logcat -s BidonCache:D | grep CoordinationLayer

# Cache operations
adb logcat -s BidonCache:D | grep -E "(READY_TO_SHOW|RTB_PAYLOAD)"

# Lifecycle events
adb logcat -s BidonCache:D | grep -E "(PeriodicSweepJob|WeakContextValidator)"
```

## Отличия v2 от v1

### Основные улучшения v2:

1. **Warm Start Optimization (<1s)** ⭐
   - Немедленный `onAdLoaded()` callback из кэша
   - Фоновый auction для обновления кэша

2. **Dynamic Pricefloor**
   - Автоматический расчет на основе cached eCPM
   - Защита кэша от низких eCPM

3. **RTB Payload Caching**
   - Сохранение RTB bid responses для следующего auction
   - Пропуск token collection для cached networks

4. **Application-wide Cache**
   - Shared кэш между ad instances
   - Persistent cache (не очищается при destroyAd)

5. **Periodic Sweep Job**
   - Автоматическая очистка expired ads каждые 5 минут
   - Memory leak prevention через WeakReference

6. **Enhanced Logging**
   - Детальные логи для debugging
   - Visibility в cache state и lifecycle events

## Изменения в Demo App

### До активации (commit: experiment/ad-caching-gl)

```kotlin
// app/src/main/java/org/bidon/demoapp/ui/InterstitialScreen.kt
val interstitial by lazy {
    InterstitialAd(auctionKey = auctionKeyState.value.ifBlank { null }).apply {
        setInterstitialListener(...)
    }
}
```

### После активации

```kotlin
// app/src/main/java/org/bidon/demoapp/ui/InterstitialScreen.kt
val interstitial by lazy {
    InterstitialAd(auctionKey = auctionKeyState.value.ifBlank { null }).apply {
        // Enable Ad Caching v2 (Denis)
        addExtra("cache_size", 2)
        setInterstitialListener(...)
    }
}
```

Аналогично для `RewardedScreen.kt` и `BannerScreen.kt`.

## Тестирование v2

После активации запустите тесты согласно [TEST_CHECKLIST.md](testing/TEST_CHECKLIST.md):

### Priority Tests:
1. **TC-COLD-001**: Pure cold start (5-7s)
2. **TC-WARM-001**: Warm start <1s ⭐ (MAIN FEATURE!)
3. **TC-CB-CLOSE-001**: onAdClosed callback ⭐
4. **TC-WEAK-001**: No memory leaks ⭐

## Откат на v1

Если обнаружены проблемы с v2, можно быстро откатиться на v1:

```kotlin
// Удалите строку addExtra или установите значение 1
addExtra("cache_size", 1)  // v1

// Или просто удалите эту строку (v1 по умолчанию)
```

## FAQ

**Q: Почему нужно устанавливать для каждого ad instance?**
A: Текущая архитектура предполагает per-instance конфигурацию через Extras API. Это даёт гибкость для A/B тестирования разных версий.

**Q: Можно ли смешивать v1 и v2 в одном app?**
A: Да! Можно создать одни ads с v1, другие с v2. Кэши v2 application-wide, но v1 и v2 используют разные реализации.

**Q: Как проверить, что v2 активирован?**
A: Смотрите logcat с фильтром `BidonCache:D`. Если видите логи `[BidonCache]`, значит v2 активен. Если `[AdCacheImpl]`, то v1.

**Q: Влияет ли v2 на размер APK?**
A: Минимально. v2 добавляет ~20-30 классов, увеличение размера ~10-15KB.

---

**Document Status:** Complete ✅
**Last Updated:** 2026-02-06
**Related:** [AD_CACHING_SPEC.md](AD_CACHING_SPEC.md), [TEST_CHECKLIST.md](testing/TEST_CHECKLIST.md)
