# Bidon SDK — Ad Caching & Auction Pipeline Spec

> **Version:** 2.0-final
> **Status:** Ready for Implementation
> **Scope:** Полная переработка auction pipeline с добавлением ad caching
> **Ad Types:** Interstitial, Rewarded (расширяемо)

---

## 1. Обзор изменений

### 1.1 Текущая архитектура (AS-IS)

```
loadAd()
  │
  ▼
getTokens() ──► /auction ──► waterfall (sequential RTB+CPM mixed) ──► winner ──► onAdLoaded
                                    │
                                    └── опрос строго по порядку, один за одним
                                        нет кэширования, нет разделения RTB/CPM
```

**Проблемы:**
- Весь waterfall блокирующий (~3–15 sec до `onAdLoaded`)
- RTB payload-ы теряются после первого fill
- Повторный `loadAd()` начинает весь цикл с нуля (tokens → /auction → waterfall)
- Нет переиспользования данных между аукционами

### 1.2 Целевая архитектура (TO-BE)

```
loadAd()
  │
  ├── READY_TO_SHOW не пуст? → onAdLoaded СРАЗУ (warm start optimization)
  │
  ├── getTokens() ← SKIP networks с валидным RTB_PAYLOAD cache
  │       │
  │       ▼
  │   /auction (pricefloor = динамический)
  │       │
  │       ▼
  │   Split waterfall → RTB group + CPM group
  │       │                    │
  │       │ (async)            │ (async)
  │       ▼                    ▼
  │   ┌─ RTB[0]: load → READY_TO_SHOW     CPM[0]: load → READY_TO_SHOW
  │   │  RTB[1..N]: save → RTB_PAYLOAD     CPM[1]: load → READY_TO_SHOW
  │   │                                    CPM[2]: load → ...
  │   │
  │   └──► onAdLoaded ← если кэш был пуст (первое появление в READY_TO_SHOW)
  │
  ▼
READY_TO_SHOW cache ──► showAd()
RTB_PAYLOAD cache   ──► используется в следующем аукционе
```

**Ключевые улучшения:**
- Разделение waterfall на RTB и CPM с параллельной обработкой
- Два уровня кэширования (READY_TO_SHOW + RTB_PAYLOAD)
- Быстрый `onAdLoaded` при warm start (немедленный если кэш не пуст)
- Переиспользование RTB payload-ов между аукционами
- Динамический pricefloor на основе кэша

---

## 2. Хранилища (Cache Stores)

### 2.1 READY_TO_SHOW

Хранилище полностью загруженных рекламных объявлений, готовых к показу.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          READY_TO_SHOW CACHE                             │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Scope: Application-wide, shared across all ad instances (Interstitial) │
│  Key: adUnitUid (String)                                                 │
│                                                                          │
│  Value: ReadyToShowEntry {                                               │
│      adSource: AdSource          // Загруженный адаптер с рекламой      │
│      adUnit: AdUnit              // Метаданные ad unit                   │
│      bidType: BidType            // RTB | CPM                            │
│      ecpm: Double                // Фактическая цена (= pricefloor)      │
│      demandId: String            // ID рекламной сети                    │
│      createdAt: Long             // Timestamp создания записи            │
│      auctionId: String           // ID аукциона, породившего запись      │
│  }                                                                       │
│                                                                          │
│  TTL: 30 минут от createdAt                                              │
│  Eviction: Lazy (проверка при доступе) + периодический sweep             │
│                                                                          │
│  Операции:                                                               │
│    put(entry)           → добавить загруженную рекламу                   │
│    getBest(): Entry?    → вернуть запись с максимальным eCPM             │
│    getAll(): List       → все валидные (не expired) записи               │
│    remove(uid)          → удалить запись (после показа или expiry)       │
│    getMaxEcpm(): Double → максимальный eCPM среди валидных записей       │
│    contains(uid): Bool  → проверка наличия                               │
│    clear()              → полная очистка                                 │
│    isEmpty(): Boolean   → проверка пустоты                               │
│    size(): Int          → количество валидных записей                    │
│                                                                          │
│  Duplicate Policy (один demandId):                                       │
│    Если запись с demandId уже существует:                                │
│      - Если новый eCPM выше → заменить (destroy старый, add новый)       │
│      - Если новый eCPM ниже или равен → оставить старый, отбросить новый │
│                                                                          │
│  Thread Safety: ConcurrentHashMap + atomic operations                    │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.2 RTB_PAYLOAD

Хранилище RTB payload-ов, полученных с сервера, но ещё не загруженных через адаптер. Позволяет при следующем аукционе не собирать токены для этих сетей и использовать сохранённые данные.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          RTB_PAYLOAD CACHE                               │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Scope: Application-wide, shared across all ad instances (Interstitial) │
│  Key: adUnitUid (String)                                                 │
│                                                                          │
│  Value: RtbPayloadEntry {                                                │
│      adUnit: AdUnit              // Полный AdUnit из ответа сервера      │
│      demandId: String            // ID рекламной сети                    │
│      payload: JsonObject         // RTB payload (bid response данные)    │
│      pricefloor: Double          // Pricefloor данного adUnit            │
│      createdAt: Long             // Timestamp создания записи            │
│      auctionId: String           // ID аукциона-источника                │
│  }                                                                       │
│                                                                          │
│  TTL: 30 минут от createdAt                                              │
│  Eviction: Lazy + периодический sweep                                    │
│                                                                          │
│  Операции:                                                               │
│    put(entry)                    → сохранить payload                     │
│    get(uid): Entry?              → получить по uid                       │
│    getAll(): List                → все валидные записи                   │
│    getCachedDemandIds(): Set     → Set demandId-ов с валидным кэшем      │
│    getMaxEcpm(): Double          → максимальный pricefloor               │
│    remove(uid)                   → удалить запись                        │
│    clear()                       → полная очистка                        │
│    isEmpty(): Boolean            → проверка пустоты                      │
│                                                                          │
│  Invalid Payload Handling:                                               │
│    Если adSource.load(payload) возвращает ошибку:                        │
│      → Удалить запись из RTB_PAYLOAD                                     │
│      → При следующем аукционе соберём свежие tokens для этой сети        │
│                                                                          │
│  Thread Safety: ConcurrentHashMap + atomic operations                    │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.3 TTL и Expiration

```
┌───────────────────────────────────────────────────────────────────┐
│                      EXPIRATION POLICY                             │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  DEFAULT_TTL = 30 minutes                                         │
│                                                                   │
│  Проверка expiry:                                                 │
│    isExpired = (System.currentTimeMillis() - createdAt) > TTL     │
│                                                                   │
│  Lazy eviction:                                                   │
│    При КАЖДОМ доступе к записи проверяется TTL.                   │
│    Если expired → удаляется, возвращается null.                   │
│                                                                   │
│  Periodic sweep:                                                  │
│    Lifecycle: Ad-instance scoped (отдельный для каждого ad obj)   │
│    Каждые 5 минут фоновая корутина чистит expired записи.         │
│    Для READY_TO_SHOW: вызывает adSource.destroy() перед удалением │
│    Для RTB_PAYLOAD: просто удаляет запись                         │
│                                                                   │
│  Destroy on expiry (READY_TO_SHOW):                               │
│    При удалении загруженной рекламы обязательно:                  │
│      1. adSource.destroy() — освобождение ресурсов адаптера       │
│      2. Emit AdEvent.Expired ТОЛЬКО для winner ad (из onAdLoaded) │
│      3. Удаление из хранилища                                     │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

---

## 3. Auction Pipeline (детальный flow)

### 3.1 Определение типа аукциона

```
loadAd(activity, pricefloor)
       │
       ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ОПРЕДЕЛЕНИЕ ТИПА ЗАПУСКА                                           │
│                                                                     │
│  hasCache = !READY_TO_SHOW.isEmpty() || !RTB_PAYLOAD.isEmpty()     │
│                                                                     │
│  if (hasCache):                                                     │
│      → WARM START (секция 3.3)                                      │
│  else:                                                              │
│      → COLD START (секция 3.2)                                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Cold Start (первый аукцион, кэш пуст)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         COLD START FLOW                                  │
└─────────────────────────────────────────────────────────────────────────┘

Step 1: СБОР ТОКЕНОВ
═══════════════════
    Опрашиваем ВСЕ зарегистрированные RTB адаптеры параллельно.
    Timeout: адаптерный (обычно ~5s)

    ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
    │ Meta AN  │  │  Mintegr │  │ BidMach  │  │ Amazon   │  ... все RTB
    │  token   │  │  token   │  │  token   │  │  token   │
    └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
         │             │             │             │
         └─────────────┼─────────────┼─────────────┘
                       ▼
              Map<demandId, TokenInfo>


Step 2: ЗАПРОС /auction
═══════════════════════
    POST /v2/auction/{adType}

    Request:
      ├─ auctionId: UUID
      ├─ pricefloor: userPricefloor ?: DEFAULT_PRICEFLOOR
      ├─ tokens: Map<demandId, TokenInfo>  ← ВСЕ собранные токены
      ├─ adapters: List<AdapterInfo>
      ├─ device / app / user / segment
      └─ ...

    ⚠️  cachedAdUnits НЕ ОТПРАВЛЯЕТСЯ (кэш влияет только на token collection)

    Response (AuctionResponse):
      ├─ auctionId
      ├─ pricefloor
      ├─ auctionTimeout
      ├─ adUnits: List<AdUnit>  ← WATERFALL (смесь RTB + CPM)
      │   └─ каждый: { demandId, pricefloor, bidType, timeout, uid, payload? }
      ├─ noBids: List<AdUnit>
      └─ externalWinNotificationsEnabled


Step 3: SPLIT WATERFALL
═══════════════════════
    Разделяем adUnits на две группы по bidType, СОХРАНЯЯ относительный порядок
    внутри каждой группы (порядок приоритета от сервера).

    Waterfall от сервера (пример):
    ┌─────────────────────────────────────────────────────────────────┐
    │  [RTB $5.00] [CPM $4.50] [RTB $3.00] [CPM $2.50] [CPM $1.00]  │
    └─────────────────────────────────────────────────────────────────┘
                            │
                    ┌───────┴───────┐
                    ▼               ▼
            ┌──────────────┐  ┌──────────────┐
            │  RTB Group   │  │  CPM Group   │
            │  [RTB $5.00] │  │  [CPM $4.50] │
            │  [RTB $3.00] │  │  [CPM $2.50] │
            └──────────────┘  │  [CPM $1.00] │
                              └──────────────┘


Step 4: ПАРАЛЛЕЛЬНАЯ ОБРАБОТКА RTB + CPM (async)
════════════════════════════════════════════════

    ┌──────────────────────────────────┐  ┌──────────────────────────────────┐
    │       RTB PROCESSING (async)      │  │       CPM PROCESSING (async)     │
    │                                    │  │                                  │
    │  RTB[0] (highest priority):        │  │  CPM[0]: load via adapter        │
    │    → adSource.load(payload)        │  │    → success? → READY_TO_SHOW    │
    │    → success? → READY_TO_SHOW      │  │    → fail? → skip, next          │
    │    → fail? → try load RTB[1]       │  │                                  │
    │                                    │  │  CPM[1]: load via adapter        │
    │  RTB[1..N] (remaining):            │  │    → success? → READY_TO_SHOW    │
    │    → НЕ ЗАГРУЖАТЬ                  │  │    → fail? → skip, next          │
    │    → save payload → RTB_PAYLOAD    │  │                                  │
    │                                    │  │  CPM[2]: load via adapter        │
    │  * Если RTB[0] fail:               │  │    → success? → READY_TO_SHOW    │
    │    RTB[1] → load                   │  │    ...                           │
    │    RTB[2..N] → RTB_PAYLOAD         │  │                                  │
    │                                    │  │  (последовательно, один за одним │
    │  ⚠️ RTB payload = почти 100% fill  │  │   или по 2 параллельно — см. 3.5)│
    └──────────────────────────────────┘  └──────────────────────────────────┘
                    │                                      │
                    └──────────────┬───────────────────────┘
                                   ▼
                    ┌────────────────────────────────┐
                    │        onAdLoaded TRIGGER       │
                    │                                 │
                    │  Срабатывает при ПЕРВОМ         │
                    │  появлении записи в             │
                    │  READY_TO_SHOW cache            │
                    │                                 │
                    └────────────────────────────────┘
```

### 3.3 Warm Start (повторный аукцион, кэш не пуст)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         WARM START FLOW                                  │
└─────────────────────────────────────────────────────────────────────────┘

Step 0: НЕМЕДЛЕННЫЙ onAdLoaded (если READY_TO_SHOW не пуст)
════════════════════════════════════════════════════════════
    if (!READY_TO_SHOW.isEmpty()) {
        val bestAd = READY_TO_SHOW.getBest()
        listener.onAdLoaded(bestAd.ad, auctionInfo)
        adLoadedCallbackFired.set(true)  // Больше не вызываем
    }

    ⚠️  Это НЕ блокирует аукцион — он продолжается в фоне для обновления кэша


Step 1: ОПРЕДЕЛЕНИЕ SKIP-СПИСКА ДЛЯ ТОКЕНОВ
════════════════════════════════════════════
    cachedDemandIds = RTB_PAYLOAD.getCachedDemandIds()

    Пример:
      RTB_PAYLOAD содержит записи для: [meta_an, bidmachine]
      ──► эти сети НЕ опрашиваем для токенов

    Логика:
      allRtbAdapters = Registry.getAdapters(bidType = RTB)
      needTokenAdapters = allRtbAdapters.filter { it.demandId !in cachedDemandIds }

      ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
      │   Meta AN    │  │  BidMachine  │  │  Mintegral   │  │   Amazon     │
      │  ✗ SKIP     │  │  ✗ SKIP     │  │  ✓ COLLECT   │  │  ✓ COLLECT   │
      │  (cached)    │  │  (cached)    │  │   token      │  │   token      │
      └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘

    Результат: tokens собираются ТОЛЬКО для сетей БЕЗ кэша.


Step 2: ВЫЧИСЛЕНИЕ ДИНАМИЧЕСКОГО PRICEFLOOR
═══════════════════════════════════════════
    Берём максимальный eCPM из обоих хранилищ.

    readyMaxEcpm = READY_TO_SHOW.getMaxEcpm()     // e.g. $3.50
    payloadMaxEcpm = RTB_PAYLOAD.getMaxEcpm()      // e.g. $5.00

    dynamicPricefloor = max(readyMaxEcpm, payloadMaxEcpm)  // $5.00

    // Если пользователь передал свой pricefloor:
    finalPricefloor = if (userPricefloor != null) {
        max(userPricefloor, dynamicPricefloor)
    } else {
        dynamicPricefloor
    }

    ⚠️  Сервер получает динамический pricefloor и может отфильтровать
        adUnits с более низкими ценами, что даёт более качественный waterfall.


Step 3: ЗАПРОС /auction
═══════════════════════
    POST /v2/auction/{adType}

    Request:
      ├─ pricefloor: finalPricefloor   ← НЕ дефолтный, а вычисленный
      ├─ tokens: Map (только от некэшированных сетей)
      └─ ... (остальное как в cold start)

    ⚠️  Если сервер вернёт ПУСТОЙ waterfall (нет adUnits >= pricefloor):
        → onAdLoaded УЖЕ вызван в Step 0 с кэшированной рекламой
        → Аукцион завершается без ошибки


Step 4: SPLIT + ОБРАБОТКА
═════════════════════════
    Аналогично Cold Start (Step 3 + Step 4).
    Новые записи добавляются в существующие хранилища.

    ⚠️  При добавлении в READY_TO_SHOW:
        - Если запись с таким demandId уже есть и НЕ expired —
          заменяем ТОЛЬКО если новый eCPM выше.
        - Если expired — удаляем старую, добавляем новую.

    ⚠️  При добавлении в RTB_PAYLOAD:
        - Правильная фильтрация tokens предотвращает дубликаты
        - uid всегда уникален в пределах одного аукциона
```

### 3.4 RTB Processing — детальный алгоритм

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      RTB PROCESSING ALGORITHM                            │
└─────────────────────────────────────────────────────────────────────────┘

Input: rtbGroup: List<AdUnit>  (отсортирован по приоритету от сервера)

    fun processRtbGroup(rtbGroup: List<AdUnit>) {
        if (rtbGroup.isEmpty()) return

        val toLoad = rtbGroup.first()       // Только первый RTB unit
        val toCache = rtbGroup.drop(1)      // Остальные → в payload cache

        // 1. Попытка загрузки первого RTB
        launch {
            val result = loadAdUnit(toLoad)

            if (result.isSuccess) {
                // ✓ Загрузился — кладём в READY_TO_SHOW
                val ecpm = result.adSource.getStats().price  // = toLoad.pricefloor

                READY_TO_SHOW.put(
                    ReadyToShowEntry(
                        adSource = result.adSource,
                        adUnit = toLoad,
                        bidType = BidType.RTB,
                        ecpm = ecpm,
                        demandId = toLoad.demandId,
                        createdAt = now(),
                        auctionId = currentAuctionId
                    )
                )
                notifyAdLoadedIfNeeded()  // Trigger onAdLoaded если не вызван

                // Оставшиеся RTB → payload cache
                toCache.forEach { unit ->
                    RTB_PAYLOAD.put(
                        RtbPayloadEntry(
                            adUnit = unit,
                            demandId = unit.demandId,
                            payload = unit.payload,
                            pricefloor = unit.pricefloor,
                            createdAt = now(),
                            auctionId = currentAuctionId
                        )
                    )
                }

            } else {
                // ✗ Первый RTB не загрузился → пробуем следующий
                if (toCache.isNotEmpty()) {
                    val fallbackToLoad = toCache.first()
                    val fallbackToCache = toCache.drop(1)

                    // Рекурсивно: пробуем загрузить следующий,
                    // остальные — в payload
                    processRtbGroup(listOf(fallbackToLoad) + fallbackToCache)
                }
                // Если все RTB fail → ничего не кладём, RTB pipeline завершён
                // Все fail отправляются в статистику
            }
        }
    }
```

### 3.5 CPM Processing — детальный алгоритм

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      CPM PROCESSING ALGORITHM                            │
└─────────────────────────────────────────────────────────────────────────┘

Input: cpmGroup: List<AdUnit>  (отсортирован по приоритету от сервера)

    ╔══════════════════════════════════════════════════════════════════╗
    ║  СТРАТЕГИЯ ЗАГРУЗКИ CPM:                                        ║
    ║                                                                  ║
    ║  Вариант A: Строго последовательно (sequential)                  ║
    ║    CPM[0] → wait → CPM[1] → wait → CPM[2] → ...                ║
    ║                                                                  ║
    ║  Вариант B: Параллельно по 2 (recommended)                      ║
    ║    [CPM[0], CPM[1]] → wait → [CPM[2], CPM[3]] → ...            ║
    ║                                                                  ║
    ║  Решение: Начинаем с Варианта A. Вариант B — оптимизация.       ║
    ╚══════════════════════════════════════════════════════════════════╝

    fun processCpmGroup(cpmGroup: List<AdUnit>) {
        launch {
            for (adUnit in cpmGroup) {
                // Проверка: не был ли аукцион отменён
                if (isAuctionCancelled()) break

                val result = loadAdUnit(adUnit)

                if (result.isSuccess) {
                    val ecpm = result.adSource.getStats().price  // = adUnit.pricefloor

                    READY_TO_SHOW.put(
                        ReadyToShowEntry(
                            adSource = result.adSource,
                            adUnit = adUnit,
                            bidType = BidType.CPM,
                            ecpm = ecpm,
                            demandId = adUnit.demandId,
                            createdAt = now(),
                            auctionId = currentAuctionId
                        )
                    )
                    notifyAdLoadedIfNeeded()  // Trigger если не вызван
                }
                // fail → skip, продолжаем следующий CPM
            }
        }
    }

    // --- Вариант B: параллельно по 2 (future optimization) ---
    fun processCpmGroupParallel(cpmGroup: List<AdUnit>, parallelism: Int = 2) {
        launch {
            cpmGroup.chunked(parallelism).forEach { chunk ->
                if (isAuctionCancelled()) return@launch

                val results = chunk.map { adUnit ->
                    async { adUnit to loadAdUnit(adUnit) }
                }.awaitAll()

                results.forEach { (adUnit, result) ->
                    if (result.isSuccess) {
                        READY_TO_SHOW.put(/* ... */)
                        notifyAdLoadedIfNeeded()
                    }
                }
            }
        }
    }

    ⚠️  При параллельной загрузке (Вариант B):
        - Оба успешных CPM попадают в READY_TO_SHOW
        - onAdLoaded вызывается при первом fill (если ещё не вызван)
        - getBest() при showAd() вернёт запись с максимальным eCPM
```

---

## 4. onAdLoaded Callback Logic

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      onAdLoaded TRIGGER LOGIC                            │
└─────────────────────────────────────────────────────────────────────────┘

    Callback onAdLoaded отдаётся РОВНО ОДИН РАЗ за вызов loadAd().

    ┌─────────────────────────────────────────────────────────────────────┐
    │                                                                     │
    │  var adLoadedCallbackFired = AtomicBoolean(false)                   │
    │                                                                     │
    │  fun notifyAdLoadedIfNeeded() {                                     │
    │      if (adLoadedCallbackFired.compareAndSet(false, true)) {        │
    │          val bestAd = READY_TO_SHOW.getBest()                       │
    │          if (bestAd != null) {                                      │
    │              listener.onAdLoaded(bestAd.ad, auctionInfo)            │
    │          }                                                          │
    │      }                                                              │
    │  }                                                                  │
    │                                                                     │
    └─────────────────────────────────────────────────────────────────────┘

    Сценарии:

    Case 1: Warm Start (READY_TO_SHOW не пуст)
      → onAdLoaded вызывается СРАЗУ в начале loadAd() (Step 0)
      → Аукцион продолжается в фоне для обновления кэша
      → Новые загрузки НЕ триггерят повторный onAdLoaded

    Case 2: Cold Start, RTB[0] загрузился первым
      → RTB[0] → READY_TO_SHOW → onAdLoaded(RTB[0])
      → CPM загрузки продолжают наполнять READY_TO_SHOW фоново
      → Повторный onAdLoaded НЕ вызывается

    Case 3: RTB[0] fail, CPM[0] загрузился первым
      → CPM[0] → READY_TO_SHOW → onAdLoaded(CPM[0])

    Case 4: Все fail
      → READY_TO_SHOW пуст по завершении обоих pipeline-ов
      → listener.onAdLoadFailed(auctionInfo, cause)

    Case 5: Empty waterfall в warm start
      → READY_TO_SHOW уже содержал данные
      → onAdLoaded был вызван СРАЗУ (Case 1)
      → Новый пустой waterfall не влияет на результат

    ⚠️  Важно: после вызова onAdLoaded фоновые процессы (CPM loading,
        RTB caching) ПРОДОЛЖАЮТ работать, наполняя хранилища.
```

---

## 5. showAd() и выбор рекламы из кэша

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          showAd() LOGIC                                  │
└─────────────────────────────────────────────────────────────────────────┘

    showAd(activity)
         │
         ▼
    ┌──────────────────────────────────────────────────────────────┐
    │  1. Получить лучшую запись из READY_TO_SHOW                  │
    │     bestEntry = READY_TO_SHOW.getBest()                      │
    │                                                              │
    │  2. Проверить валидность                                     │
    │     if (bestEntry == null || bestEntry.isExpired()) {        │
    │         → onAdShowFailed(NO_FILL)                            │
    │         return                                               │
    │     }                                                         │
    │                                                              │
    │  3. Показ                                                    │
    │     bestEntry.adSource.show(activity)                        │
    │                                                              │
    │  4. После успешного show:                                    │
    │     READY_TO_SHOW.remove(bestEntry.uid)                      │
    │     cancelOngoingAuction()  ← отмена незавершённых CPM       │
    │     sendStats(bestEntry.auctionId)  ← auctionId из entry    │
    │                                                              │
    │  ⚠️  WIN/LOSS УВЕДОМЛЕНИЯ:                                   │
    │     НЕ ОТПРАВЛЯЮТСЯ. Кэш не трогается.                       │
    │     Проигравшие записи остаются в READY_TO_SHOW до expiry.   │
    │                                                              │
    └──────────────────────────────────────────────────────────────┘

    ⚠️  showAd() без loadAd():
        Если пользователь вызывает showAd() без предварительного loadAd(),
        но в READY_TO_SHOW есть валидные записи:
        → Используем isReady() для проверки
        → Если isReady() = true → показываем из кэша
        → Если isReady() = false → onAdShowFailed()
```

---

## 6. Pricefloor Strategy

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       PRICEFLOOR STRATEGY                                │
└─────────────────────────────────────────────────────────────────────────┘

    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  ПЕРВЫЙ АУКЦИОН (cold start):                                     │
    │    pricefloor = userPricefloor ?: DEFAULT_PRICEFLOOR              │
    │                                                                    │
    │  ПОВТОРНЫЙ АУКЦИОН (warm start):                                  │
    │    dynamicPricefloor = max(                                        │
    │        READY_TO_SHOW.getMaxEcpm(),                                │
    │        RTB_PAYLOAD.getMaxEcpm()                                   │
    │    )                                                               │
    │                                                                    │
    │    finalPricefloor = if (userPricefloor != null) {                 │
    │        max(userPricefloor, dynamicPricefloor)                      │
    │    } else {                                                         │
    │        dynamicPricefloor                                            │
    │    }                                                                │
    │                                                                    │
    │    Rationale:                                                      │
    │    Мы уже знаем что можем показать рекламу за $X.                 │
    │    Нет смысла запрашивать adUnits дешевле — поднимаем планку.     │
    │                                                                    │
    │  Пример:                                                           │
    │    READY_TO_SHOW: [AdMob CPM $3.50, Unity CPM $2.00]              │
    │    RTB_PAYLOAD:   [Meta AN $5.00, BidMachine $4.00]               │
    │    userPricefloor: $10.00                                          │
    │    ──► finalPricefloor = max($10.00, $5.00) = $10.00               │
    │                                                                    │
    │    Сервер вернёт только adUnits с price >= $10.00                 │
    │    → более качественный waterfall, меньше бесполезных загрузок    │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

---

## 7. Заложенные механизмы (будет реализовано позже)

> Эти механизмы являются частью архитектуры и ДОЛЖНЫ быть учтены при проектировании интерфейсов и структур данных, даже если их полная реализация запланирована на следующие итерации.

### 7.1 Весовая модель для CPM (Weight Model)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    ⏳ WEIGHT MODEL FOR CPM                               │
│                    Status: PLACEHOLDER — будет описано позже             │
└─────────────────────────────────────────────────────────────────────────┘

    Описание:
      Содержимое READY_TO_SHOW и RTB_PAYLOAD будет влиять на приоритизацию
      CPM адаптеров при следующих аукционах.

    Идея:
      На основе исторических данных о fill rate, eCPM, и текущего кэша
      назначать веса CPM адаптерам, чтобы опрашивать в оптимальном порядке.

    Влияние на текущую архитектуру:
      - ReadyToShowEntry и RtbPayloadEntry должны хранить достаточно данных
        для вычисления весов (demandId, ecpm, bidType, timestamps).
      - CPM processing должен принимать опциональный comparator/sorter
        для переупорядочивания CPM group перед загрузкой.

    Заглушка в коде:
      interface CpmWeightCalculator {
          fun sort(cpmUnits: List<AdUnit>, cache: CacheSnapshot): List<AdUnit>
      }

      class DefaultCpmWeightCalculator : CpmWeightCalculator {
          override fun sort(cpmUnits, cache) = cpmUnits  // no-op, сохраняем порядок сервера
      }
```

---

## 8. Полная диаграмма состояний (State Machine)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     AD CACHE STATE MACHINE                               │
└─────────────────────────────────────────────────────────────────────────┘

                        ┌──────────┐
                        │   IDLE   │
                        └────┬─────┘
                             │ loadAd()
                             ▼
                     ┌───────────────┐
                     │  COLLECTING   │  ← getTokens (skip cached networks)
                     │   TOKENS      │
                     └───────┬───────┘
                             │ tokens collected
                             ▼
                     ┌───────────────┐
                     │  REQUESTING   │  ← POST /auction
                     │   AUCTION     │
                     └───────┬───────┘
                             │ waterfall received
                             ▼
                     ┌───────────────┐
                     │  PROCESSING   │  ← RTB + CPM async processing
                     │   WATERFALL   │
                     └───┬───────┬───┘
                         │       │
              first fill │       │ all fail
                         ▼       ▼
                  ┌──────────┐ ┌──────────┐
                  │  LOADED  │ │  FAILED  │
                  │(has ads) │ │(no fill) │
                  └────┬─────┘ └──────────┘
                       │
                       │ showAd()
                       ▼
                  ┌──────────┐
                  │ SHOWING  │
                  └────┬─────┘
                       │
              ┌────────┼────────┐
              ▼        ▼        ▼
         ┌────────┐┌───────┐┌────────┐
         │ SHOWN  ││CLICKED││ CLOSED │
         └────────┘└───────┘└───┬────┘
                                │
                                ▼
                         ┌──────────┐
                         │   IDLE   │  ← ready for next loadAd()
                         └──────────┘

    ⚠️  PROCESSING → LOADED может происходить пока PROCESSING ещё идёт.
        Background loading CPM продолжается после LOADED.
        Переход LOADED → SHOWING отменяет незавершённый PROCESSING.
```

---

## 9. Потокобезопасность и Concurrency

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    CONCURRENCY MODEL                                     │
└─────────────────────────────────────────────────────────────────────────┘

    Coroutine Scope:
      - Каждый loadAd() запускает auction в выделенном CoroutineScope
      - RTB processing и CPM processing — отдельные child coroutines
      - Cancel scope при showAd() или explicit cancel

    Структура Jobs:
      auctionScope
        ├── getTokensJob
        ├── auctionRequestJob
        ├── rtbProcessingJob
        │     └── loadRtbJob
        └── cpmProcessingJob
              ├── loadCpm0Job
              ├── loadCpm1Job
              └── ...

    Thread Safety:
      - READY_TO_SHOW: ConcurrentHashMap
      - RTB_PAYLOAD: ConcurrentHashMap
      - adLoadedCallbackFired: AtomicBoolean
      - isCancelled: AtomicBoolean
      - Все мутации хранилищ через synchronized блоки или atomic operations

    Race Condition Prevention:
      - showAd() и notifyAdLoadedIfNeeded() могут вызываться из разных coroutines
      - getBest() должен быть thread-safe и atomic
      - put() + notifyAdLoadedIfNeeded() должны быть atomic:
        synchronized(lock) {
            cache.put(entry)
            notifyAdLoadedIfNeeded()
        }

    Cancellation Policy (showAd):
      - При вызове showAd() отменяем незавершённые CPM загрузки:
        isCancelled.set(true)
        cpmProcessingJob?.cancel()
      - RTB payload caching НЕ отменяется (данные уже есть, только сохраняем)
```

---

## 10. Lifecycle и Memory Management

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    LIFECYCLE MANAGEMENT                                   │
└─────────────────────────────────────────────────────────────────────────┘

    destroyAd():
      1. cancelOngoingAuction()
      2. auctionScope.cancel()
      3. Reset state → IDLE

      ⚠️  КЭШИ НЕ ОЧИЩАЮТСЯ:
          READY_TO_SHOW и RTB_PAYLOAD остаются нетронутыми.
          Они application-scoped и могут использоваться другими ad instances.

    Activity/Fragment Lifecycle:
      - onDestroy() → destroyAd()
      - Кэши НЕ привязаны к Activity lifecycle (живут в Application scope)
      - Но загруженные adSource-ы могут держать ссылку на Activity
        → при expiry обязательно destroy()

    Memory Pressure:
      - При low memory callback можно очистить RTB_PAYLOAD (менее критичен)
      - READY_TO_SHOW очищать только в крайнем случае (потеря загруженных ads)
      - Рекомендация: оставить очистку на усмотрение TTL expiration
```

---

## 11. Статистика и Tracking

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    STATS & TRACKING                                       │
└─────────────────────────────────────────────────────────────────────────┘

    Каждый AdUnit проходит через tracking (расширяем текущую модель):

    AdUnit Statuses (расширенные):
      ├─ Successful          // Загрузился и показался
      ├─ Fail                // Ошибка загрузки (отправить все RTB fail)
      ├─ NoBid / NoFill      // Нет рекламы
      ├─ Timeout             // Таймаут загрузки
      ├─ BelowPricefloor     // Ниже pricefloor
      ├─ CachedPayload       // ⭐ NEW: RTB payload сохранён в кэш
      ├─ CachedReady         // ⭐ NEW: Загружен и положен в READY_TO_SHOW
      ├─ Expired             // ⭐ NEW: Истёк TTL в кэше
      ├─ SkippedTokens       // ⭐ NEW: Пропущен при сборе токенов (есть кэш)
      └─ CancelledByShow     // ⭐ NEW: Отменён из-за вызова showAd()

    POST /v2/stats/{adType} дополняется новыми статусами.

    AuctionId Tracking:
      При showAd() отправляем auctionId из показанной рекламы:
        → entry.auctionId (НЕ последний аукцион)
      Это обеспечивает точное отслеживание источника показанной рекламы.
```

---

## 12. Интерфейсы (Contracts)

```kotlin
// ─── Cache Stores ───────────────────────────────────────────

interface ReadyToShowCache {
    fun put(entry: ReadyToShowEntry)
    fun getBest(): ReadyToShowEntry?
    fun getAll(): List<ReadyToShowEntry>
    fun remove(uid: String)
    fun getMaxEcpm(): Double
    fun contains(uid: String): Boolean
    fun clear()
    fun isEmpty(): Boolean
    fun size(): Int
}

interface RtbPayloadCache {
    fun put(entry: RtbPayloadEntry)
    fun get(uid: String): RtbPayloadEntry?
    fun getAll(): List<RtbPayloadEntry>
    fun getCachedDemandIds(): Set<String>
    fun getMaxEcpm(): Double
    fun remove(uid: String)
    fun clear()
    fun isEmpty(): Boolean
}

// ─── Processing ─────────────────────────────────────────────

interface RtbProcessor {
    suspend fun process(
        rtbGroup: List<AdUnit>,
        readyCache: ReadyToShowCache,
        payloadCache: RtbPayloadCache,
        auctionId: String,
        onFirstFill: () -> Unit
    )
}

interface CpmProcessor {
    suspend fun process(
        cpmGroup: List<AdUnit>,
        readyCache: ReadyToShowCache,
        auctionId: String,
        onFirstFill: () -> Unit,
        isCancelled: () -> Boolean
    )
}

// ─── Waterfall Splitter ─────────────────────────────────────

interface WaterfallSplitter {
    fun split(adUnits: List<AdUnit>): SplitResult

    data class SplitResult(
        val rtbGroup: List<AdUnit>,
        val cpmGroup: List<AdUnit>
    )
}

// ─── Pricefloor Calculator ──────────────────────────────────

interface PricefloorCalculator {
    fun calculate(
        readyCache: ReadyToShowCache,
        payloadCache: RtbPayloadCache,
        userPricefloor: Double?,
        defaultPricefloor: Double
    ): Double
}

// ─── Token Collector (modified) ─────────────────────────────

interface TokenCollector {
    suspend fun collect(
        skipDemandIds: Set<String> = emptySet()
    ): Map<String, TokenInfo>
}

// ─── Weight Model (placeholder) ─────────────────────────────

interface CpmWeightCalculator {
    fun sort(cpmUnits: List<AdUnit>, cacheSnapshot: CacheSnapshot): List<AdUnit>
}

data class CacheSnapshot(
    val readyToShow: List<ReadyToShowEntry>,
    val rtbPayloads: List<RtbPayloadEntry>
)
```

---

## 13. Sequence Diagram — полный цикл

```
    ┌──────┐  ┌──────────┐  ┌────────┐  ┌──────┐  ┌─────────┐  ┌─────────┐
    │ App  │  │ AdCache  │  │Auction │  │Server│  │RTB Proc │  │CPM Proc │
    └──┬───┘  └────┬─────┘  └───┬────┘  └──┬───┘  └────┬────┘  └────┬────┘
       │           │            │           │           │            │
       │ loadAd()  │            │           │           │            │
       │──────────►│            │           │           │            │
       │           │            │           │           │            │
       │           │ check caches           │           │            │
       │           │──────┐    │           │           │            │
       │           │      │    │           │           │            │
       │           │◄─────┘    │           │           │            │
       │           │            │           │           │            │
       │           │ if (!isEmpty) → onAdLoaded СРАЗУ   │            │
       │◄──────────│            │           │           │            │
       │           │            │           │           │            │
       │           │ start()    │           │           │            │
       │           │───────────►│           │           │            │
       │           │            │           │           │            │
       │           │            │ getTokens(skipCached) │            │
       │           │            │──────┐    │           │            │
       │           │            │◄─────┘    │           │            │
       │           │            │           │           │            │
       │           │            │ /auction  │           │            │
       │           │            │──────────►│           │            │
       │           │            │◄──────────│           │            │
       │           │            │ waterfall │           │            │
       │           │            │           │           │            │
       │           │            │ split(RTB, CPM)       │            │
       │           │            │──────┐    │           │            │
       │           │            │◄─────┘    │           │            │
       │           │            │           │           │            │
       │           │            │ process RTB│          │            │
       │           │            │───────────────────────►            │
       │           │            │           │           │            │
       │           │            │ process CPM│          │            │
       │           │            │──────────────────────────────────►│
       │           │            │           │           │            │
       │           │            │           │   RTB[0] loaded       │
       │           │◄──────────────────────────────────│            │
       │           │  put(READY_TO_SHOW)    │          │            │
       │           │            │           │  RTB[1..N] → PAYLOAD  │
       │           │◄──────────────────────────────────│            │
       │           │            │           │           │            │
       │onAdLoaded?│            │           │           │            │
       │ (if first)│            │           │           │            │
       │◄──────────│            │           │           │            │
       │           │            │           │           │            │
       │           │            │           │           │   CPM[0] loaded
       │           │◄──────────────────────────────────────────────│
       │           │  put(READY_TO_SHOW)    │           │          │
       │           │            │           │           │   CPM[1] loading...
       │           │            │           │           │            │
       │ showAd()  │            │           │           │            │
       │──────────►│            │           │           │            │
       │           │ getBest()  │           │           │            │
       │           │ show()     │           │           │            │
       │           │ cancel     │           │           │            │
       │           │──────────────────────────────────────────────►│
       │           │            │           │           │   CANCELLED│
       │           │            │           │           │            │
       │onAdShown  │            │           │           │            │
       │◄──────────│            │           │           │            │
       │           │            │           │           │            │
```

---

## 14. Ключевые файлы (обновлённая таблица)

| Компонент | Текущий файл | Изменения |
|-----------|-------------|-----------|
| Публичный API | `InterstitialAd.kt` | Без изменений |
| Реализация | `InterstitialImpl.kt` | Интеграция с AdCache |
| **Ad Cache** | `AdCache.kt` | **ПОЛНАЯ ПЕРЕРАБОТКА** — основной координатор |
| **READY_TO_SHOW store** | `ReadyToShowCacheImpl.kt` | **NEW** |
| **RTB_PAYLOAD store** | `RtbPayloadCacheImpl.kt` | **NEW** |
| **RTB Processor** | `RtbProcessorImpl.kt` | **NEW** |
| **CPM Processor** | `CpmProcessorImpl.kt` | **NEW** |
| **Waterfall Splitter** | `WaterfallSplitterImpl.kt` | **NEW** |
| **Pricefloor Calculator** | `PricefloorCalculatorImpl.kt` | **NEW** |
| Аукцион | `AuctionImpl.kt` | Рефакторинг — делегирует в AdCache |
| Waterfall (старый) | `ExecuteAuctionUseCaseImpl.kt` | **DEPRECATED** — заменяется на RTB/CPM processors |
| Токены RTB | `GetTokensUseCaseImpl.kt` | Добавить skipDemandIds параметр |
| Запрос к серверу | `GetAuctionRequestUseCaseImpl.kt` | Добавить dynamicPricefloor |
| **Cpm Weight Calculator** | `CpmWeightCalculatorImpl.kt` | **NEW (placeholder)** |

---

## 15. Резюме ключевых решений

| # | Тема | Решение |
|---|------|---------|
| 1 | Cache scope | Application-wide, общий на тип рекламы |
| 2 | Cache size limit | Без ограничений |
| 3 | Duplicate demandId | Заменять только если новый eCPM выше |
| 4 | Stale ads | Полагаться на TTL (30 минут) |
| 5 | Concurrent loadAd() | Блокируется (проверить InterstitialAd) |
| 6 | Empty waterfall (warm) | onAdLoaded с кэшированной рекламой |
| 7 | Both RTB+CPM fill | Оба попадают в READY_TO_SHOW |
| 8 | User pricefloor | max(userPricefloor, dynamicPricefloor) |
| 9 | Actual eCPM | = pricefloor (не отличается) |
| 10 | onAdLoaded (warm start) | СРАЗУ если READY_TO_SHOW непустой |
| 11 | onAdLoaded frequency | Один раз на loadAd() |
| 12 | Better ad found | Нет, не уведомлять |
| 13 | onAdExpired | Только для winner ad |
| 14 | Cancel CPM on showAd() | Отменить немедленно |
| 15 | destroyAd() cleanup | НЕ очищать кэши |
| 16 | Periodic sweep | Ad-instance scoped |
| 17 | Win/Loss notifications | НЕ отправляются |
| 18 | Post-show cleanup | Кэш не трогается |
| 19 | RTB fail stats | Отправить все fail |
| 20 | Invalid payload | Удалить из RTB_PAYLOAD |
| 21 | cachedAdUnits field | НЕ отправляется в /auction |
| 22 | showAd() without loadAd() | isReady() для проверки |
| 23 | auctionId tracking | Из показанной рекламы (entry.auctionId) |
| 24 | RTB fill guarantee | RTB payload = почти 100% fill |

---

## 16. Что изменилось с версии 1.0

### Удалено:
- ❌ Секция 7.2 "FailedToShow — Fallback из кэша" (не будет реализовано)
- ❌ Секция 7.3 "Cancel Auction on Show" (перенесено в основной flow)
- ❌ Win/Loss уведомления при showAd()
- ❌ cachedAdUnits в /auction request
- ❌ Логика замены winner ad при нахождении лучшего

### Добавлено:
- ✅ Немедленный onAdLoaded при warm start (Step 0)
- ✅ Политика обработки дубликатов demandId
- ✅ Clarification что destroyAd() не очищает кэши
- ✅ Invalid payload handling
- ✅ User pricefloor logic
- ✅ Detailed cancellation policy
- ✅ AuctionId tracking specification
- ✅ showAd() without loadAd() behavior

### Уточнено:
- 📝 Cache scope (application-wide)
- 📝 onAdLoaded callback logic (один раз на loadAd)
- 📝 onAdExpired (только для winner ad)
- 📝 RTB fail stats (отправить все)
- 📝 Periodic sweep lifecycle (ad-instance scoped)

---

**Готово к имплементации! 🚀**