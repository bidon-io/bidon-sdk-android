# runBlocking в TwoLevelAdManager.pop()

## Проблема

`AdCache.pop()` — не suspend (`fun pop(): AuctionResult?`).
`CacheStorage.popFirst()` и `FallbackCacheStorage.popFirst()` — suspend из-за `kotlinx.coroutines.sync.Mutex`.

Для вызова suspend из не-suspend используется `runBlocking`:

```kotlin
override fun pop(): AuctionResult? {
    if (!mainCache.state.value.hasContent && !fallbackCache.state.value.hasContent) return null
    val result = runBlocking {
        mainCache.popFirst() ?: fallbackCache.popFirst()
    }
    if (result != null) cancelAuction()
    return result
}
```

`pop()` вызывается с Main thread (из `InterstitialImpl.show()` / `BannerView` через `runOnUiThread`).
`runBlocking` блокирует Main thread на время ожидания Mutex.

## Риск

Если `routeBidToCache` (на `Dispatchers.Default`) держит Mutex в момент `pop()` — Main thread блокируется до освобождения. Операции внутри Mutex — наносекунды (list remove + sort), но формально это ANR-потенциал при экстремальном contention.

V1 (`AdCacheImpl.pop()`) использует `MutableStateFlow.getAndUpdate` — не suspend, без блокировки.

## Решение

Заменить `kotlinx.coroutines.sync.Mutex` на `synchronized` в `CacheStorage` и `FallbackCacheStorage`:

```kotlin
// CacheStorage
private val lock = Any()

fun insert(element: AuctionResult, sticky: Boolean): InsertResult = synchronized(lock) {
    // ... same logic ...
}

fun popFirst(): AuctionResult? = synchronized(lock) {
    // ... same logic ...
}
```

```kotlin
// TwoLevelAdManager
override fun pop(): AuctionResult? {
    val result = mainCache.popFirst() ?: fallbackCache.popFirst()
    if (result != null) cancelAuction()
    return result
}
```

Операции внутри lock — list add/remove/sort без suspend-точек. `synchronized` не хуже Mutex для этого случая и убирает `runBlocking`.
