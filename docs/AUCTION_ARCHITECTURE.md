# Презентация: Архитектура аукциона Bidon SDK

## 1. Верхнеуровневая схема работы рекламного объекта

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ЖИЗНЕННЫЙ ЦИКЛ INTERSTITIAL                         │
└─────────────────────────────────────────────────────────────────────────────┘

    ┌──────────────────┐
    │ InterstitialAd() │  ← Создание объекта
    └────────┬─────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ setInterstitialListener()│  ← Установка callbacks
    └────────┬─────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ loadAd(activity, price)  │  ← Запуск аукциона
    └────────┬─────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │    АУКЦИОН (auction)     │  ← Подробно в секции 2
    └────────┬─────────────────┘
             │
    ┌────────┴────────┐
    │                 │
    ▼                 ▼
┌──────────┐    ┌───────────────┐
│ SUCCESS  │    │    FAILURE    │
│onAdLoaded│    │onAdLoadFailed │
└────┬─────┘    └───────────────┘
     │
     ▼
┌──────────────────────────┐
│      isReady() = true    │  ← Проверка готовности
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│    showAd(activity)      │  ← Показ рекламы
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────┐
│               CALLBACKS ПОКАЗА                    │
├──────────────────────────────────────────────────┤
│ • onAdShown()      - реклама показана            │
│ • onAdClicked()    - клик по рекламе             │
│ • onAdClosed()     - пользователь закрыл         │
│ • onRevenuePaid()  - получена информация о цене  │
│ • onAdShowFailed() - ошибка показа               │
└──────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────┐
│      destroyAd()         │  ← Очистка ресурсов
└──────────────────────────┘
```

---

## 2. Детальная схема аукциона

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              AUCTION FLOW                                    │
└─────────────────────────────────────────────────────────────────────────────┘

InterstitialImpl.loadAd()
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                          AdCache.cache()                                      │
│  bidon/src/main/java/org/bidon/sdk/ads/cache/AdCache.kt                      │
└────────┬─────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         Auction.start()                                       │
│  bidon/src/main/java/org/bidon/sdk/auction/impl/AuctionImpl.kt:58            │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 1. ПАРАЛЛЕЛЬНЫЙ СБОР ТОКЕНОВ (для RTB/Bidding адаптеров)                │ │
│  │    getTokens() - GetTokensUseCaseImpl                                   │ │
│  │                                                                          │ │
│  │    ┌──────────┐  ┌──────────┐  ┌──────────┐                             │ │
│  │    │ Adapter1 │  │ Adapter2 │  │ Adapter3 │   ... параллельно (async)   │ │
│  │    │  token   │  │  token   │  │  token   │                             │ │
│  │    └────┬─────┘  └────┬─────┘  └────┬─────┘                             │ │
│  │         │             │             │                                    │ │
│  │         └─────────────┼─────────────┘                                    │ │
│  │                       ▼                                                  │ │
│  │              Map<demandId, TokenInfo>                                    │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                │                                              │
│                                ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 2. ЗАПРОС НА СЕРВЕР: POST /v2/auction/{adType}                          │ │
│  │    getAuctionRequest.request()                                          │ │
│  │                                                                          │ │
│  │    REQUEST:                         RESPONSE (AuctionResponse):          │ │
│  │    ├─ auctionId                     ├─ auctionId                         │ │
│  │    ├─ pricefloor                    ├─ pricefloor                        │ │
│  │    ├─ tokens (от RTB адаптеров)     ├─ auctionTimeout                    │ │
│  │    ├─ device info                   ├─ adUnits: List<AdUnit>  ← WATERFALL│ │
│  │    ├─ app info                      │   ├─ demandId                      │ │
│  │    ├─ user/segment                  │   ├─ pricefloor                    │ │
│  │    └─ adapters info                 │   ├─ bidType (RTB/CPM)             │ │
│  │                                     │   ├─ timeout                       │ │
│  │                                     │   └─ uid                           │ │
│  │                                     ├─ noBids: List<AdUnit>              │ │
│  │                                     └─ externalWinNotificationsEnabled   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                │                                              │
│                                ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 3. ВЫПОЛНЕНИЕ АУКЦИОНА (WATERFALL)                                      │ │
│  │    executeAuction() - ExecuteAuctionUseCaseImpl                         │ │
│  │    (см. секцию 3 для деталей)                                           │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                │                                              │
│                                ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 4. ОПРЕДЕЛЕНИЕ ПОБЕДИТЕЛЯ                                               │ │
│  │    resultsCollector.saveWinners() → sortByDescending(price)             │ │
│  │    Максимум 2 результата (MaxAuctionResultsAmount = 2)                  │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                │                                              │
│                                ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 5. ОТПРАВКА СТАТИСТИКИ: POST /v2/stats/{adType}                         │ │
│  │    auctionStat.sendAuctionStats()                                       │ │
│  │    - Результаты всех adUnits (WIN/LOSE/NO_FILL/TIMEOUT...)              │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                │                                              │
│                                ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │ 6. УВЕДОМЛЕНИЕ АДАПТЕРОВ (WIN/LOSS)                                     │ │
│  │    notifyWinLoss()                                                      │ │
│  │    - Победитель: notifyWin() (если externalWinNotifications=false)      │ │
│  │    - Проигравшие: notifyLoss() + destroy()                              │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Waterfall: последовательный опрос AdUnits

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         WATERFALL EXECUTION                                  │
│  bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/                   │
│  ExecuteAuctionUseCaseImpl.kt:41                                            │
└─────────────────────────────────────────────────────────────────────────────┘

    Сервер возвращает упорядоченный список adUnits (waterfall).

    ⚠️  SDK НЕ ФИЛЬТРУЕТ список заранее - берёт как есть и кладёт в очередь:
        adUnitQueue = LinkedList(adUnits)

    Фильтрация происходит по одному элементу в цикле while.

    ┌─────────────────────────────────────────────────────────┐
    │  adUnits от сервера (отсортированы по приоритету)       │
    │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐    │
    │  │AdUnit #0│→ │AdUnit #1│→ │AdUnit #2│→ │AdUnit #3│    │
    │  │$5.00    │  │$3.00    │  │$2.00    │  │$1.00    │    │
    │  │RTB      │  │CPM      │  │CPM      │  │RTB      │    │
    │  └─────────┘  └─────────┘  └─────────┘  └─────────┘    │
    └─────────────────────────────────────────────────────────┘

    Логика обхода (ПОСЛЕДОВАТЕЛЬНО):

    ┌─────────────────────────────────────────────────────────────────────────┐
    │ while (adUnitQueue.isNotEmpty()) {                                       │
    │     val adUnit = adUnitQueue.peek()                                      │
    │                                                                          │
    │     // 1. Проверка pricefloor                                            │
    │     if (adUnit.pricefloor < currentPricefloor) {                         │
    │         SKIP → status = BelowPricefloor                                  │
    │         continue                                                         │
    │     }                                                                     │
    │                                                                          │
    │     // 2. Загрузка рекламы от адаптера                                   │
    │     val result = requestAdUnit.invoke(adSource, adUnit, timeout)         │
    │                                                                          │
    │     // 3. Проверка условия остановки                                     │
    │     if (result.status == Successful) {                                   │
    │         val loadedPrice = adSource.getStats().price                      │
    │         val nextPrice = nextAdUnit?.pricefloor                           │
    │                                                                          │
    │         if (loadedPrice >= nextPrice) {                                  │
    │             // Загруженная цена выше следующего pricefloor               │
    │             // ОСТАНАВЛИВАЕМ waterfall - нет смысла продолжать           │
    │             break                                                        │
    │         }                                                                │
    │     }                                                                     │
    │ }                                                                         │
    └─────────────────────────────────────────────────────────────────────────┘

    Пример выполнения:

    ┌────────────────────────────────────────────────────────────────────────┐
    │ AdUnit #0 ($5.00, RTB)                                                  │
    │ ├─ requestAdUnit() → timeout 5s                                         │
    │ ├─ adSource.load()                                                      │
    │ └─ Result: NO_FILL (RTB не вернул bid)                                  │
    │                                                                          │
    │ AdUnit #1 ($3.00, CPM)                                                  │
    │ ├─ requestAdUnit() → timeout 5s                                         │
    │ ├─ adSource.load()                                                      │
    │ └─ Result: SUCCESSFUL, loadedPrice = $3.50                              │
    │                                                                          │
    │ Проверка: $3.50 >= $2.00 (следующий pricefloor)?                        │
    │ → ДА → STOP WATERFALL                                                   │
    │                                                                          │
    │ AdUnit #2, #3 → status = BelowPricefloor (не запрашивались)             │
    └────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Система Callbacks

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            CALLBACK FLOW                                     │
└─────────────────────────────────────────────────────────────────────────────┘

    Адаптер (AdSource) → Flow<AdEvent> → InterstitialImpl → User Listener

    ┌─────────────────────────────────────────────────────────────────────────┐
    │ AdSource                                                                 │
    │ ├─ val adEvent: SharedFlow<AdEvent>                                      │
    │ │                                                                        │
    │ │   sealed class AdEvent {                                               │
    │ │       data class Fill(ad)              // Загрузка успешна             │
    │ │       data class LoadFailed(cause)     // Ошибка загрузки              │
    │ │       data class Shown(ad)             // Показ                        │
    │ │       data class Clicked(ad)           // Клик                         │
    │ │       data class Closed(ad)            // Закрытие                     │
    │ │       data class PaidRevenue(ad, val)  // Revenue                      │
    │ │       data class ShowFailed(cause)     // Ошибка показа                │
    │ │       data class Expired(ad)           // Истекло                      │
    │ │       data class OnReward(ad, reward)  // Награда (Rewarded only)      │
    │ │   }                                                                    │
    │ │                                                                        │
    │ └─► emits events...                                                      │
    └─────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
    ┌─────────────────────────────────────────────────────────────────────────┐
    │ InterstitialImpl.subscribeToWinner()                                     │
    │ bidon/src/main/java/org/bidon/sdk/ads/interstitial/InterstitialImpl.kt  │
    │                                                                          │
    │ adSource.adEvent.onEach { adEvent ->                                     │
    │     when (adEvent) {                                                     │
    │         is AdEvent.Clicked  → listener.onAdClicked(ad)                   │
    │                             + adSource.sendClickImpression()             │
    │         is AdEvent.Closed   → listener.onAdClosed(ad)                    │
    │         is AdEvent.Shown    → listener.onAdShown(ad)                     │
    │                             + adSource.sendShowImpression()              │
    │         is AdEvent.PaidRevenue → listener.onRevenuePaid(ad, value)       │
    │         is AdEvent.ShowFailed  → listener.onAdShowFailed(cause)          │
    │         is AdEvent.Expired     → listener.onAdExpired(ad)                │
    │     }                                                                    │
    │ }.launchIn(scope)                                                        │
    └─────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
    ┌─────────────────────────────────────────────────────────────────────────┐
    │ UserListener (InterstitialListener)                                      │
    │                                                                          │
    │ interface InterstitialListener : AdListener, FullscreenAdListener,       │
    │                                  AdRevenueListener {                     │
    │     // from AdListener:                                                  │
    │     fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo)                     │
    │     fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError)     │
    │     fun onAdShown(ad: Ad)                                                │
    │     fun onAdShowFailed(cause: BidonError)                                │
    │     fun onAdClicked(ad: Ad)                                              │
    │     fun onAdExpired(ad: Ad)                                              │
    │                                                                          │
    │     // from FullscreenAdListener:                                        │
    │     fun onAdClosed(ad: Ad)                                               │
    │                                                                          │
    │     // from AdRevenueListener:                                           │
    │     fun onRevenuePaid(ad: Ad, adValue: AdValue)                          │
    │ }                                                                        │
    └─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Ключевые файлы

| Компонент | Файл |
|-----------|------|
| Публичный API | `bidon/src/main/java/org/bidon/sdk/ads/interstitial/InterstitialAd.kt` |
| Реализация | `bidon/src/main/java/org/bidon/sdk/ads/interstitial/InterstitialImpl.kt` |
| Кэш | `bidon/src/main/java/org/bidon/sdk/ads/cache/AdCache.kt` |
| Аукцион | `bidon/src/main/java/org/bidon/sdk/auction/impl/AuctionImpl.kt` |
| Waterfall | `bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/ExecuteAuctionUseCaseImpl.kt` |
| Запрос AdUnit | `bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/RequestAdUnitUseCaseImpl.kt` |
| Токены RTB | `bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt` |
| Запрос к серверу | `bidon/src/production/java/org/bidon/sdk/auction/impl/GetAuctionRequestUseCaseImpl.kt` |
| Статистика | `bidon/src/main/java/org/bidon/sdk/stats/impl/StatsRequestUseCaseImpl.kt` |
| Модель ответа | `bidon/src/main/java/org/bidon/sdk/auction/models/AuctionResponse.kt` |

---

## 6. Текущие ограничения архитектуры

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     ТЕКУЩИЙ FLOW (СИНХРОННЫЙ)                               │
└─────────────────────────────────────────────────────────────────────────────┘

    loadAd()
        │
        ▼
    ┌────────────────────────────────────────────────────────────────────────┐
    │                    ВЕСЬ АУКЦИОН БЛОКИРУЮЩИЙ                             │
    │                                                                         │
    │  getTokens() ──► /auction ──► waterfall ──► saveWinners() ──► stats    │
    │       ↓              ↓            ↓              ↓                      │
    │    ~200ms         ~100ms      ~3-15sec        ~10ms                     │
    │                                                                         │
    └────────────────────────────────────────────────────────────────────────┘
        │
        ▼
    onAdLoaded() ← callback ТОЛЬКО после полного завершения waterfall


    ПРОБЛЕМА: Пользователь ждёт весь waterfall, даже если первый AdUnit
              уже загрузился с хорошей ценой.
```

---

## 7. Открытый вопрос для обсуждения

**Как изменить текущую имплементацию, чтобы:**

1. **Быстро отдавать callback `onAdLoaded()`** при первом успешном fill
2. **Продолжать опрашивать waterfall** в фоне для поиска более дорогой цены
3. **Обновлять "победителя"** если найден AdUnit с более высокой ценой

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ЖЕЛАЕМЫЙ FLOW (АСИНХРОННЫЙ)                              │
└─────────────────────────────────────────────────────────────────────────────┘

    loadAd()
        │
        ├──► getTokens() ──► /auction ──► waterfall начинается
        │                                      │
        │                                      ▼
        │                              ┌──────────────────┐
        │                              │ AdUnit #0 FILL!  │
        │                              │ price = $3.00    │
        │                              └────────┬─────────┘
        │                                       │
        │◄──────────────────────────────────────┘
        │
        ▼
    onAdLoaded(ad, auctionInfo)  ← БЫСТРЫЙ callback (~1-3 sec)
        │
        │   ┌─────────────────────────────────────────────────────────────┐
        │   │ ФОНОВЫЙ ПРОЦЕСС (продолжение waterfall)                     │
        │   │                                                              │
        │   │ AdUnit #1 → NO_FILL                                          │
        │   │ AdUnit #2 → FILL! price = $4.50  ← ЛУЧШЕ!                    │
        │   │                                                              │
        │   │ → Обновить победителя (если externalWinNotifications=true)   │
        │   │ → Или новый callback onBetterAdFound()?                      │
        │   └─────────────────────────────────────────────────────────────┘
        │
        ▼
    showAd() → показать лучший доступный результат
```

**Вопросы для обсуждения:**
- Как уведомлять о найденном более дорогом объявлении?
- Нужен ли новый callback или достаточно "тихой" замены в кэше?
- Как обрабатывать случай когда `showAd()` вызван во время фонового поиска?
- Как синхронизировать win/loss уведомления при динамической смене победителя?
