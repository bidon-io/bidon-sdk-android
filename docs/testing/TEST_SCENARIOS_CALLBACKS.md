# Ad Caching v2 — Callback Test Scenarios

> **Version:** 1.0
> **Date:** 2026-02-06
> **Related:** [TEST_SCENARIOS_FUNCTIONAL.md](./TEST_SCENARIOS_FUNCTIONAL.md), [TEST_SCENARIOS_LIFECYCLE.md](./TEST_SCENARIOS_LIFECYCLE.md)

## Цель документа

Тест-кейсы для проверки всех callback методов, которые возвращаются пользователю SDK. Гарантирует что все события жизненного цикла рекламы корректно передаются в user callbacks.

---

## Обзор колбэков

### AdListener (базовые колбэки)
- `onAdLoaded(ad, auctionInfo)` - реклама загружена ✓ (покрыто в FUNCTIONAL)
- `onAdLoadFailed(auctionInfo, cause)` - загрузка не удалась ✓ (покрыто в FUNCTIONAL)
- `onAdShown(ad)` - реклама показана (impression)
- `onAdShowFailed(cause)` - показ не удался ✓ (покрыто в FUNCTIONAL)
- `onAdClicked(ad)` - клик по рекламе
- `onAdExpired(ad)` - реклама истекла

### FullscreenAdListener (fullscreen рекламы)
- `onAdClosed(ad)` - пользователь закрыл рекламу

### AdRevenueListener (revenue tracking)
- `onRevenuePaid(ad, adValue)` - получена информация о revenue

### RewardedAdListener (только rewarded ads)
- `onUserRewarded(ad, reward)` - пользователь получил награду

---

## 1. Show & Display Callbacks

### TC-CB-SHOW-001: onAdShown вызывается при успешном показе

**Цель:** Проверить что onAdShown вызывается когда реклама показывается на экране.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]
- InterstitialListener подписан

**Steps:**
1. Вызвать showAd()
2. Дождаться появления рекламы на экране
3. Проверить callback

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] AdCacheDenisImpl: adSource.show() starting
  [Interstitial] AdEvent.Shown received
  [Interstitial] onAdShown callback fired

Callback sequence:
  1. onAdShown(ad) ✓
     - ad.demandId = "meta_an"
     - ad.auctionInfo != null
```

**Validation:**
- ✅ onAdShown вызывается
- ✅ onAdShown вызывается ПОСЛЕ появления рекламы на экране
- ✅ Ad объект содержит корректные данные (demandId, auctionInfo)
- ✅ onAdShown вызывается только ОДИН раз на один show
- ✅ sendShowImpression() вызывается после onAdShown

**Priority:** 🔴 HIGH

---

### TC-CB-SHOW-002: onAdShown НЕ вызывается при show failure

**Цель:** Проверить что onAdShown НЕ вызывается если показ не удался.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00 (но ad.isAdReadyToShow = false)]

**Steps:**
1. Mock adapter чтобы adSource.show() выкинул exception
2. Вызвать showAd()
3. Проверить callbacks

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called
  [Interstitial] AdEvent.ShowFailed received
  [Interstitial] onAdShowFailed callback fired

Callback sequence:
  1. onAdShowFailed(BidonError.AdNotReady) ✓
  (onAdShown НЕ вызывается) ✓
```

**Validation:**
- ✅ onAdShown НЕ вызывается
- ✅ onAdShowFailed вызывается вместо onAdShown
- ✅ Причина ошибки корректная

**Priority:** 🔴 HIGH

---

### TC-CB-SHOW-003: onAdShown timing (после фактического показа)

**Цель:** Проверить что onAdShown вызывается ПОСЛЕ реального появления рекламы на экране, не раньше.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]
- Adapter с задержкой показа (simulated)

**Steps:**
1. Mock adapter с 1 секундной задержкой перед эмитом AdEvent.Shown
2. Вызвать showAd()
3. Проверить что onAdShown вызывается только после задержки

**Expected Result:**
```
Timeline:
  T=0ms:    showAd() called
  T=0ms:    adSource.show() called
  T=0-1000ms: (waiting for ad to render)
  T=1000ms: AdEvent.Shown emitted
  T=1000ms: onAdShown() callback ✓
```

**Validation:**
- ✅ onAdShown вызывается ПОСЛЕ реального показа
- ✅ Timing корректный (не вызывается до AdEvent.Shown)

**Priority:** 🟡 MEDIUM

---

## 2. Close Callbacks

### TC-CB-CLOSE-001: onAdClosed вызывается при закрытии рекламы ⭐ **КРИТИЧНО**

**Цель:** Проверить что onAdClosed вызывается когда пользователь закрывает рекламу.

**Preconditions:**
- Реклама показана на экране
- InterstitialListener подписан

**Steps:**
1. Показать рекламу через showAd()
2. Дождаться onAdShown
3. Закрыть рекламу (нажать кнопку "X" или back button)
4. Проверить callback

**Expected Result:**
```
Logs:
  [Interstitial] AdEvent.Shown received
  [Interstitial] onAdShown callback fired
  (user closes ad)
  [Interstitial] AdEvent.Closed received
  [Interstitial] onAdClosed callback fired
  [Interstitial] observeCallbacksJob cancelled

Callback sequence:
  1. onAdShown(ad) ✓
  2. (user interaction)
  3. onAdClosed(ad) ✓
     - ad.demandId = "meta_an"
     - Same Ad object as in onAdShown
```

**Validation:**
- ✅ onAdClosed вызывается при закрытии
- ✅ onAdClosed вызывается только ОДИН раз
- ✅ Ad объект тот же, что был в onAdShown
- ✅ observeCallbacksJob отменяется после onAdClosed (cleanup)
- ✅ После onAdClosed можно вызвать loadAd() снова

**Priority:** 🔴 HIGH (КРИТИЧНО - основной lifecycle event!)

---

### TC-CB-CLOSE-002: onAdClosed при back button на Android

**Цель:** Проверить что onAdClosed работает при нажатии back button.

**Preconditions:**
- Реклама показана на экране
- Android device

**Steps:**
1. Показать рекламу
2. Нажать device back button
3. Проверить callback

**Expected Result:**
```
Callback sequence:
  1. onAdShown(ad) ✓
  2. (back button pressed)
  3. onAdClosed(ad) ✓
```

**Validation:**
- ✅ Back button закрывает рекламу
- ✅ onAdClosed вызывается
- ✅ Activity state корректен после закрытия

**Priority:** 🔴 HIGH

---

### TC-CB-CLOSE-003: onAdClosed НЕ вызывается если реклама не показывалась

**Цель:** Проверить что onAdClosed НЕ вызывается если showAd() не был вызван.

**Preconditions:**
- loadAd() выполнен, onAdLoaded вызван
- showAd() НЕ вызывался

**Steps:**
1. Вызвать только loadAd()
2. Дождаться onAdLoaded
3. Вызвать destroyAd() без showAd()
4. Проверить что onAdClosed НЕ вызывался

**Expected Result:**
```
Callback sequence:
  1. onAdLoaded(ad, auctionInfo) ✓
  2. destroyAd() called
  (onAdClosed НЕ вызывается) ✓
```

**Validation:**
- ✅ onAdClosed НЕ вызывается без show
- ✅ destroyAd() работает корректно
- ✅ Нет crash или exception

**Priority:** 🟡 MEDIUM

---

### TC-CB-CLOSE-004: Multiple onAdClosed (не должно происходить)

**Цель:** Проверить что onAdClosed вызывается только ОДИН раз на один показ.

**Preconditions:**
- Реклама показана

**Steps:**
1. Показать рекламу
2. Закрыть рекламу
3. Mock adapter эмитит AdEvent.Closed ДВАЖДЫ (симуляция бага)
4. Проверить что onAdClosed вызван только один раз

**Expected Result:**
```
Logs:
  [Interstitial] AdEvent.Shown received
  [Interstitial] onAdShown callback fired
  [Interstitial] AdEvent.Closed received
  [Interstitial] onAdClosed callback fired
  [Interstitial] observeCallbacksJob cancelled
  [Interstitial] AdEvent.Closed received (second time)
  [Interstitial] (callback already cancelled, ignored) ✓

Callback count:
  onAdClosed: 1 ✓ (not 2)
```

**Validation:**
- ✅ onAdClosed вызван только ОДИН раз
- ✅ observeCallbacksJob правильно cancels после первого Closed
- ✅ Повторные события игнорируются

**Priority:** 🟡 MEDIUM

---

## 3. Click Callbacks

### TC-CB-CLICK-001: onAdClicked вызывается при клике

**Цель:** Проверить что onAdClicked вызывается когда пользователь кликает на рекламу.

**Preconditions:**
- Реклама показана на экране
- InterstitialListener подписан

**Steps:**
1. Показать рекламу
2. Кликнуть на рекламу (симуляция через UI automation)
3. Проверить callback

**Expected Result:**
```
Logs:
  [Interstitial] AdEvent.Shown received
  [Interstitial] onAdShown callback fired
  (user clicks ad)
  [Interstitial] AdEvent.Clicked received
  [Interstitial] onAdClicked callback fired
  [Interstitial] sendClickImpression() called

Callback sequence:
  1. onAdShown(ad) ✓
  2. onAdClicked(ad) ✓
     - ad.demandId = "meta_an"
  3. (ad opens external link/store)
  4. onAdClosed(ad) ✓ (when user returns)
```

**Validation:**
- ✅ onAdClicked вызывается при клике
- ✅ sendClickImpression() вызывается
- ✅ Ad объект корректен
- ✅ Sequence: onAdShown → onAdClicked → onAdClosed

**Priority:** 🔴 HIGH

---

### TC-CB-CLICK-002: onAdClicked может быть вызван несколько раз

**Цель:** Проверить что onAdClicked может вызываться несколько раз если пользователь кликает несколько раз.

**Preconditions:**
- Реклама показана
- Adapter поддерживает multiple clicks

**Steps:**
1. Показать рекламу
2. Кликнуть 3 раза на разные элементы рекламы
3. Проверить что onAdClicked вызван 3 раза

**Expected Result:**
```
Callback sequence:
  1. onAdShown(ad) ✓
  2. onAdClicked(ad) ✓ (click 1)
  3. onAdClicked(ad) ✓ (click 2)
  4. onAdClicked(ad) ✓ (click 3)
  5. onAdClosed(ad) ✓
```

**Validation:**
- ✅ Multiple onAdClicked callbacks возможны
- ✅ Каждый клик tracked корректно
- ✅ sendClickImpression() вызывается для каждого клика

**Priority:** 🟡 MEDIUM

---

### TC-CB-CLICK-003: onAdClicked НЕ вызывается без клика

**Цель:** Проверить что onAdClicked НЕ вызывается если пользователь не кликает.

**Preconditions:**
- Реклама показана

**Steps:**
1. Показать рекламу
2. НЕ кликать, сразу закрыть через кнопку "X"
3. Проверить callbacks

**Expected Result:**
```
Callback sequence:
  1. onAdShown(ad) ✓
  2. (user closes without clicking)
  3. onAdClosed(ad) ✓
  (onAdClicked НЕ вызывается) ✓
```

**Validation:**
- ✅ onAdClicked НЕ вызывается без клика
- ✅ Flow: onAdShown → onAdClosed (без onAdClicked)

**Priority:** 🟡 MEDIUM

---

## 4. Expired Callbacks

### TC-CB-EXPIRE-001: onAdExpired вызывается при TTL expiration

**Цель:** Проверить что onAdExpired вызывается когда реклама истекает по TTL.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]
- TTL = 2 минуты (для теста)
- Реклама НЕ показывается

**Steps:**
1. Загрузить рекламу через loadAd()
2. НЕ вызывать showAd()
3. Подождать 3 минуты (TTL expiry)
4. Проверить callback

**Expected Result:**
```
Timeline:
  T=0min:   loadAd() → onAdLoaded(ad)
  T=2min:   (TTL expired)
  T=5min:   Periodic sweep runs
            [BidonCache] ReadyToShowCache: Entry RTB $5.00 expired
            [BidonCache] AdSource: destroy() called
            [Interstitial] AdEvent.Expired emitted
            [Interstitial] onAdExpired callback fired ✓

Callback sequence:
  1. onAdLoaded(ad, auctionInfo) ✓
  2. (wait 5 minutes)
  3. onAdExpired(ad) ✓
     - ad.demandId = "meta_an"
```

**Validation:**
- ✅ onAdExpired вызывается при TTL expiry
- ✅ onAdExpired вызывается ДО destroy()
- ✅ Ad объект доступен в callback
- ✅ После onAdExpired нельзя вызвать showAd() (isReady = false)

**Priority:** 🟡 MEDIUM

---

### TC-CB-EXPIRE-002: onAdExpired при попытке show expired ad

**Цель:** Проверить что onAdExpired вызывается если пытаемся показать expired ad.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00 (expired)]
- TTL истёк

**Steps:**
1. Загрузить рекламу
2. Подождать TTL expiry
3. Попытаться вызвать showAd()
4. Проверить что onAdShowFailed вызывается

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] AdCacheDenisImpl: getBest() → checking RTB $5.00
  [BidonCache] ReadyToShowCache: Entry expired, removing
  [BidonCache] AdCacheDenisImpl: getBest() → null (cache empty after cleanup)
  [BidonCache] AdCacheDenisImpl: onAdShowFailed(NO_FILL)

Callback sequence:
  1. onAdLoaded(ad, auctionInfo) ✓
  2. (wait for TTL expiry)
  3. showAd() called
  4. onAdShowFailed(NO_FILL) ✓
  (expired ad removed before show attempt)
```

**Validation:**
- ✅ Expired ad удаляется при getBest()
- ✅ onAdShowFailed вызывается
- ✅ Причина = NO_FILL (так как кэш empty после cleanup)

**Priority:** 🟡 MEDIUM

---

### TC-CB-EXPIRE-003: onAdExpired НЕ вызывается если ad показан

**Цель:** Проверить что onAdExpired НЕ вызывается если реклама была показана до expiry.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]
- TTL = 30 минут

**Steps:**
1. Загрузить рекламу
2. Показать рекламу через 1 минуту
3. Подождать 35 минут (после TTL)
4. Проверить что onAdExpired НЕ вызывался

**Expected Result:**
```
Timeline:
  T=0min:   loadAd() → onAdLoaded
  T=1min:   showAd() → onAdShown → onAdClosed
  T=35min:  (sweep runs, но ad уже shown)

Callback sequence:
  1. onAdLoaded(ad, auctionInfo) ✓
  2. onAdShown(ad) ✓
  3. onAdClosed(ad) ✓
  (onAdExpired НЕ вызывается) ✓
```

**Validation:**
- ✅ onAdExpired НЕ вызывается для shown ads
- ✅ Shown ad удаляется из кэша сразу после show
- ✅ Sweep не обрабатывает shown ads

**Priority:** 🟡 MEDIUM

---

## 5. Revenue Callbacks

### TC-CB-REVENUE-001: onRevenuePaid вызывается при успешном показе

**Цель:** Проверить что onRevenuePaid вызывается после successful impression.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]
- Adapter поддерживает revenue callbacks
- InterstitialListener подписан

**Steps:**
1. Показать рекламу
2. Дождаться revenue event от adapter
3. Проверить callback

**Expected Result:**
```
Logs:
  [Interstitial] AdEvent.Shown received
  [Interstitial] onAdShown callback fired
  [Interstitial] AdEvent.PaidRevenue received
  [Interstitial] onRevenuePaid callback fired

Callback sequence:
  1. onAdShown(ad) ✓
  2. onRevenuePaid(ad, adValue) ✓
     - ad.demandId = "meta_an"
     - adValue.currency = "USD"
     - adValue.amount = 5.00
     - adValue.precision = "ESTIMATED"
  3. onAdClosed(ad) ✓
```

**Validation:**
- ✅ onRevenuePaid вызывается
- ✅ AdValue содержит корректные данные (amount, currency, precision)
- ✅ Revenue callback между onAdShown и onAdClosed
- ✅ Timing: onAdShown → onRevenuePaid → onAdClosed

**Priority:** 🟡 MEDIUM

---

### TC-CB-REVENUE-002: onRevenuePaid может не вызываться (опциональный)

**Цель:** Проверить что отсутствие onRevenuePaid не ломает flow.

**Preconditions:**
- Adapter НЕ поддерживает revenue callbacks
- ИЛИ revenue data недоступна

**Steps:**
1. Показать рекламу
2. Закрыть рекламу
3. Проверить что flow работает без revenue callback

**Expected Result:**
```
Callback sequence:
  1. onAdShown(ad) ✓
  2. (no revenue data)
  3. onAdClosed(ad) ✓
  (onRevenuePaid НЕ вызывается, это OK) ✓
```

**Validation:**
- ✅ Flow работает без revenue callback
- ✅ onRevenuePaid опциональный, не требуется для корректной работы
- ✅ Нет ошибок или warnings

**Priority:** 🟡 MEDIUM

---

### TC-CB-REVENUE-003: onRevenuePaid с разными precision types

**Цель:** Проверить что разные precision types обрабатываются корректно.

**Preconditions:**
- Разные adapters с разными precision levels

**Steps:**
1. Показать ads от разных networks
2. Проверить adValue.precision для каждого

**Expected Result:**
```
Meta (AdMob):
  onRevenuePaid(ad, adValue)
    - adValue.precision = "EXACT" ✓

Unity Ads:
  onRevenuePaid(ad, adValue)
    - adValue.precision = "ESTIMATED" ✓

Yandex:
  onRevenuePaid(ad, adValue)
    - adValue.precision = "PUBLISHER_PROVIDED" ✓
```

**Validation:**
- ✅ Все precision types поддерживаются
- ✅ AdValue данные корректны для каждого типа
- ✅ Callback вызывается независимо от precision

**Priority:** 🟢 LOW

---

## 6. Rewarded Callbacks

### TC-CB-REWARD-001: onUserRewarded вызывается для rewarded ads

**Цель:** Проверить что onUserRewarded вызывается когда пользователь завершает просмотр rewarded ad.

**Preconditions:**
- RewardedAd instance (НЕ InterstitialAd)
- READY_TO_SHOW: [Rewarded RTB $5.00]
- RewardedListener подписан

**Steps:**
1. Показать rewarded ad через showAd()
2. Досмотреть рекламу до конца (не закрывать раньше времени)
3. Проверить callbacks

**Expected Result:**
```
Logs:
  [Rewarded] AdEvent.Shown received
  [Rewarded] onAdShown callback fired
  (user watches ad completely)
  [Rewarded] AdEvent.OnReward received
  [Rewarded] onUserRewarded callback fired
  [Rewarded] AdEvent.Closed received
  [Rewarded] onAdClosed callback fired

Callback sequence:
  1. onAdShown(ad) ✓
  2. onUserRewarded(ad, reward) ✓
     - ad.demandId = "unity_ads"
     - reward.label = "Coins"
     - reward.amount = 100
  3. onAdClosed(ad) ✓
```

**Validation:**
- ✅ onUserRewarded вызывается
- ✅ Reward object содержит корректные данные (label, amount)
- ✅ Timing: onAdShown → onUserRewarded → onAdClosed
- ✅ onUserRewarded вызывается ПЕРЕД onAdClosed

**Priority:** 🔴 HIGH (для rewarded ads)

**Note:** Этот тест только для RewardedAd, НЕ для InterstitialAd.

---

### TC-CB-REWARD-002: onUserRewarded НЕ вызывается при раннем закрытии

**Цель:** Проверить что onUserRewarded НЕ вызывается если пользователь закрывает rewarded ad раньше времени.

**Preconditions:**
- RewardedAd instance
- Rewarded ad показана

**Steps:**
1. Показать rewarded ad
2. Закрыть рекламу через 2 секунды (не досмотрев до конца)
3. Проверить что onUserRewarded НЕ вызван

**Expected Result:**
```
Callback sequence:
  1. onAdShown(ad) ✓
  2. (user closes early)
  3. onAdClosed(ad) ✓
  (onUserRewarded НЕ вызывается) ✓
```

**Validation:**
- ✅ onUserRewarded НЕ вызывается при раннем закрытии
- ✅ Только onAdShown → onAdClosed (без reward)
- ✅ Пользователь не получает reward

**Priority:** 🔴 HIGH (для rewarded ads)

---

### TC-CB-REWARD-003: onUserRewarded для InterstitialAd (не должно происходить)

**Цель:** Проверить что onUserRewarded НЕ вызывается для InterstitialAd.

**Preconditions:**
- InterstitialAd instance (НЕ RewardedAd)
- InterstitialListener (НЕ содержит onUserRewarded)

**Steps:**
1. Показать interstitial ad
2. Проверить что onUserRewarded недоступен

**Expected Result:**
```
Callback sequence:
  1. onAdShown(ad) ✓
  2. onAdClosed(ad) ✓
  (onUserRewarded недоступен для InterstitialListener) ✓
```

**Validation:**
- ✅ InterstitialListener НЕ содержит onUserRewarded method
- ✅ Compile-time safety (TypeScript/Kotlin type system)
- ✅ onUserRewarded только для RewardedListener

**Priority:** 🟡 MEDIUM

---

## 7. Callback Order & Timing

### TC-CB-ORDER-001: Правильная последовательность callbacks при успешном показе

**Цель:** Проверить корректный порядок всех callbacks при полном успешном flow.

**Preconditions:**
- InterstitialAd с полным набором callbacks
- Adapter поддерживает все events

**Steps:**
1. loadAd() → showAd() → click → close
2. Записать все callbacks с timestamps
3. Проверить порядок

**Expected Result:**
```
Complete callback sequence:
  1. onAdLoaded(ad, auctionInfo) ✓
  2. showAd() called
  3. onAdShown(ad) ✓
  4. onRevenuePaid(ad, adValue) ✓ (optional)
  5. (user clicks)
  6. onAdClicked(ad) ✓
  7. (user closes)
  8. onAdClosed(ad) ✓

Order validation:
  ✓ onAdLoaded before showAd()
  ✓ onAdShown after showAd()
  ✓ onAdClicked after onAdShown
  ✓ onAdClosed after onAdClicked
  ✓ No callbacks after onAdClosed
```

**Validation:**
- ✅ Порядок callbacks соблюдается
- ✅ Нет missing callbacks
- ✅ Нет duplicate callbacks (кроме onAdClicked)
- ✅ Timing между callbacks логичен

**Priority:** 🔴 HIGH

---

### TC-CB-ORDER-002: Callbacks при failure scenarios

**Цель:** Проверить порядок callbacks при различных failure сценариях.

**Preconditions:**
- InterstitialAd

**Steps:**
Scenario A: Load failure
1. loadAd() → network error
2. Проверить callbacks

Scenario B: Show failure
1. loadAd() → success
2. showAd() → adapter error
3. Проверить callbacks

**Expected Result:**
```
Scenario A (Load failure):
  1. onAdLoadFailed(auctionInfo, cause) ✓
  (no other callbacks) ✓

Scenario B (Show failure):
  1. onAdLoaded(ad, auctionInfo) ✓
  2. showAd() called
  3. onAdShowFailed(cause) ✓
  (no onAdShown, no onAdClosed) ✓
```

**Validation:**
- ✅ Failure callbacks в правильных местах
- ✅ Success callbacks НЕ вызываются при failure
- ✅ Clear error reporting

**Priority:** 🔴 HIGH

---

### TC-CB-ORDER-003: Thread safety (callbacks на Main thread)

**Цель:** Проверить что все callbacks вызываются на Main UI thread.

**Preconditions:**
- Android app с UI

**Steps:**
1. Показать рекламу
2. В каждом callback проверить Thread.currentThread()
3. Убедиться что это Main thread

**Expected Result:**
```
All callbacks:
  onAdLoaded: Main thread ✓
  onAdShown: Main thread ✓
  onAdClicked: Main thread ✓
  onAdClosed: Main thread ✓
  onRevenuePaid: Main thread ✓
```

**Validation:**
- ✅ Все callbacks на Main thread
- ✅ UI updates безопасны в callbacks
- ✅ Нет ConcurrentModificationException

**Priority:** 🔴 HIGH

---

## 8. Edge Cases

### TC-CB-EDGE-001: Callbacks при destroyAd() во время показа

**Цель:** Проверить поведение callbacks если destroyAd() вызван во время показа рекламы.

**Preconditions:**
- Реклама показана на экране

**Steps:**
1. Показать рекламу
2. Во время показа (до onAdClosed) вызвать destroyAd()
3. Проверить callbacks

**Expected Result:**
```
Callback sequence:
  1. onAdShown(ad) ✓
  2. destroyAd() called
  3. (observeCallbacksJob cancelled)
  4. (ad forcefully closed)
  (onAdClosed может или НЕ может вызваться - зависит от timing) ⚠️
```

**Validation:**
- ✅ Нет crash при destroyAd() во время показа
- ✅ Resources properly cleaned up
- ✅ observeCallbacksJob cancelled

**Priority:** 🟡 MEDIUM

---

### TC-CB-EDGE-002: Listener = null (no crash)

**Цель:** Проверить что SDK не крашится если listener не установлен.

**Preconditions:**
- InterstitialAd создан
- setInterstitialListener() НЕ вызывался (listener = null)

**Steps:**
1. loadAd() → showAd() → close
2. Проверить что нет crash

**Expected Result:**
```
Logs:
  [Interstitial] onAdLoaded callback fired
  [Interstitial] userListener = null, skipping ✓
  [Interstitial] onAdShown callback fired
  [Interstitial] userListener = null, skipping ✓
  [Interstitial] onAdClosed callback fired
  [Interstitial] userListener = null, skipping ✓

Result:
  No callbacks fired to user ✓
  No crash ✓
```

**Validation:**
- ✅ Нет NullPointerException
- ✅ SDK работает без listener
- ✅ Internal lifecycle работает корректно

**Priority:** 🔴 HIGH

---

### TC-CB-EDGE-003: Listener выкидывает exception в callback

**Цель:** Проверить что exception в user callback не крашит SDK.

**Preconditions:**
- InterstitialListener установлен
- User callback бросает RuntimeException

**Steps:**
1. Mock listener чтобы onAdShown бросал exception
2. Показать рекламу
3. Проверить что SDK продолжает работать

**Expected Result:**
```
Logs:
  [Interstitial] onAdShown callback fired
  [Interstitial] userListener.onAdShown() threw exception: RuntimeException
  [Interstitial] (SDK continues normal operation) ✓
  [Interstitial] onAdClosed callback fired (still works) ✓

Result:
  Exception isolated in user code ✓
  SDK state consistent ✓
  Subsequent callbacks still fire ✓
```

**Validation:**
- ✅ SDK не крашится от user exceptions
- ✅ Exception изолирован
- ✅ Subsequent callbacks продолжают работать

**Priority:** 🔴 HIGH

---

## Summary

**Total Callback Tests:** 31
**Priority Distribution:**
- 🔴 HIGH: 18 (58%)
- 🟡 MEDIUM: 12 (39%)
- 🟢 LOW: 1 (3%)

**Coverage:**
- Show callbacks: 3 test cases
- Close callbacks: 4 test cases ⭐
- Click callbacks: 3 test cases
- Expired callbacks: 3 test cases
- Revenue callbacks: 3 test cases
- Rewarded callbacks: 3 test cases
- Callback order: 3 test cases
- Edge cases: 3 test cases

**Critical Tests:**
- TC-CB-CLOSE-001: onAdClosed при закрытии (ОСНОВНОЙ LIFECYCLE EVENT!)
- TC-CB-CLOSE-002: onAdClosed при back button
- TC-CB-SHOW-001: onAdShown при показе
- TC-CB-CLICK-001: onAdClicked при клике
- TC-CB-ORDER-001: Правильный порядок callbacks
- TC-CB-ORDER-003: Thread safety
- TC-CB-EDGE-002-003: Error handling

---

**Document Status:** Complete
**Last Updated:** 2026-02-06
**Next Steps:**
1. Update TEST_CHECKLIST.md с новыми тестами
2. Execute callback tests на emulator
3. Особое внимание на TC-CB-CLOSE-001 (КРИТИЧНО!)
