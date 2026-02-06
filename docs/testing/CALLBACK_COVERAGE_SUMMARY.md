# Callback Coverage Summary

> **Date:** 2026-02-06
> **Status:** ✅ Complete

## Что было сделано

### Обнаружена неполная покрытие колбэков

Проанализированы все существующие тестовые документы и код SDK для выявления всех callback методов, доступных пользователю. Обнаружено, что **большинство важных колбэков отсутствовали в тестовой документации**.

---

## Все колбэки SDK

### ✅ Уже покрыты в тестах (FUNCTIONAL)
1. **onAdLoaded(ad, auctionInfo)** - когда реклама загружена
2. **onAdLoadFailed(auctionInfo, cause)** - когда загрузка не удалась
4. **onAdShowFailed(cause)** - когда показ не удался

### ❌ Отсутствовали в тестах (ДОБАВЛЕНЫ!)
3. **onAdShown(ad)** - когда реклама показана (impression)
5. **onAdClicked(ad)** - когда пользователь кликнул на рекламу
6. **onAdClosed(ad)** - когда пользователь закрыл рекламу ⭐ **КРИТИЧНО!**
7. **onAdExpired(ad)** - когда реклама истекла
8. **onRevenuePaid(ad, adValue)** - когда получена информация о revenue
9. **onUserRewarded(ad, reward)** - когда пользователь получил награду (rewarded ads)

---

## Критические находки

### 🔴 onAdClosed — Самый важный пропущенный колбэк!

**Почему критично:**
- Основной lifecycle event для fullscreen рекламы
- Позволяет пользователю знать, когда реклама закрыта и можно продолжать игру/app
- Без этого теста невозможно гарантировать корректное поведение при закрытии
- Должен вызываться при:
  - Нажатии кнопки "X"
  - Нажатии back button
  - Автоматическом закрытии после просмотра

**Добавлены тесты:**
- TC-CB-CLOSE-001: onAdClosed при закрытии ⭐
- TC-CB-CLOSE-002: onAdClosed при back button ⭐
- TC-CB-CLOSE-003: onAdClosed НЕ без show
- TC-CB-CLOSE-004: Multiple onAdClosed protection

---

## Созданные документы

### 1. TEST_SCENARIOS_CALLBACKS.md (НОВЫЙ!)
**Тест-кейсов:** 31
**Приоритет:** 🔴 HIGH (58%), 🟡 MEDIUM (39%), 🟢 LOW (3%)

**Разделы:**
1. Show & Display Callbacks (3 tests)
2. Close Callbacks (4 tests) ⭐
3. Click Callbacks (3 tests)
4. Expired Callbacks (3 tests)
5. Revenue Callbacks (3 tests)
6. Rewarded Callbacks (3 tests)
7. Callback Order & Timing (3 tests)
8. Edge Cases (3 tests)

**Файл:** [docs/testing/TEST_SCENARIOS_CALLBACKS.md](./TEST_SCENARIOS_CALLBACKS.md)

### 2. Обновлен TEST_CHECKLIST.md
- Добавлена секция "5. Callback Tests (31 test cases)"
- Обновлен раздел "Success Criteria" с callback тестами
- Обновлен счетчик: 90 → **121 тест-кейс**

**Файл:** [docs/testing/TEST_CHECKLIST.md](./TEST_CHECKLIST.md)

### 3. Обновлен README.md
- Добавлена секция "6. TEST_SCENARIOS_CALLBACKS.md"
- Обновлена таблица "Test Coverage Summary"
- Добавлена "Phase 5: Callbacks" в Test Execution Plan
- Обновлен список "Critical Tests"

**Файл:** [docs/testing/README.md](./README.md)

---

## Статистика изменений

### Было
- **Документов:** 5
- **Тест-кейсов:** 90
- **Покрытие колбэков:** Частичное (3/9 = 33%)

### Стало
- **Документов:** 6 (+1)
- **Тест-кейсов:** 121 (+31)
- **Покрытие колбэков:** Полное (9/9 = 100%) ✅

### Критичные тесты
- **Было:** 15 критичных тестов
- **Стало:** 24 критичных теста (+9 callback тестов)

---

## Ключевые тест-кейсы

### Must Pass для Production
1. **TC-CB-CLOSE-001**: onAdClosed при закрытии ⭐ **КРИТИЧНО**
2. **TC-CB-CLOSE-002**: onAdClosed при back button ⭐
3. **TC-CB-SHOW-001**: onAdShown при показе
4. **TC-CB-CLICK-001**: onAdClicked при клике
5. **TC-CB-ORDER-001**: Правильная последовательность callbacks
6. **TC-CB-ORDER-003**: Thread safety (Main thread)
7. **TC-CB-EDGE-002**: Listener = null (no crash)
8. **TC-CB-EDGE-003**: User exception в callback
9. **TC-CB-REWARD-001**: onUserRewarded для rewarded ads
10. **TC-CB-REWARD-002**: onUserRewarded НЕ при раннем закрытии

---

## Callback Sequence

### Успешный показ Interstitial
```
1. onAdLoaded(ad, auctionInfo)
2. showAd() called
3. onAdShown(ad)
4. onRevenuePaid(ad, adValue) [optional]
5. (user clicks)
6. onAdClicked(ad)
7. (user closes)
8. onAdClosed(ad)
```

### Успешный показ Rewarded
```
1. onAdLoaded(ad, auctionInfo)
2. showAd() called
3. onAdShown(ad)
4. onRevenuePaid(ad, adValue) [optional]
5. (user watches completely)
6. onUserRewarded(ad, reward)
7. onAdClosed(ad)
```

### Failure сценарии
```
Load failure:
  1. onAdLoadFailed(auctionInfo, cause)

Show failure:
  1. onAdLoaded(ad, auctionInfo)
  2. showAd() called
  3. onAdShowFailed(cause)
```

---

## Рекомендации для тестирования

### Приоритет 1 (Блокирующие)
✅ **TC-CB-CLOSE-001-002** — Тестировать в первую очередь!
- Самый важный lifecycle event
- Должен работать с кнопкой "X" и back button
- Должен вызываться ровно ОДИН раз

### Приоритет 2 (Высокий)
- TC-CB-SHOW-001: onAdShown timing
- TC-CB-CLICK-001: onAdClicked при клике
- TC-CB-ORDER-001: Последовательность всех callbacks
- TC-CB-ORDER-003: Thread safety (Main thread)

### Приоритет 3 (Error Handling)
- TC-CB-EDGE-002: Listener = null (no NPE)
- TC-CB-EDGE-003: Exception в user callback не крашит SDK

### Приоритет 4 (Rewarded Specific)
- TC-CB-REWARD-001-002: onUserRewarded только при полном просмотре

---

## Найденные gap'ы в коде

### Проверено в InterstitialImpl.kt
✅ Все колбэки правильно подписаны на AdEvent flow:
- AdEvent.Shown → onAdShown ✓
- AdEvent.Closed → onAdClosed ✓
- AdEvent.Clicked → onAdClicked ✓
- AdEvent.PaidRevenue → onRevenuePaid ✓
- AdEvent.ShowFailed → onAdShowFailed ✓
- AdEvent.Expired → onAdExpired ✓

### CallbackCoordinator
✅ Правильно обрабатывает:
- onAdLoaded (exactly-once semantics)
- onAdLoadFailed (только если кэш empty)

---

## Следующие шаги

### 1. Выполнить callback тесты
```bash
# Запустить Phase 5: Callbacks testing
# Focus on TC-CB-CLOSE-001 first (CRITICAL!)
# Проверить все callbacks вызываются на Main thread
```

### 2. Особое внимание
- **onAdClosed** — главный lifecycle event, must work!
- **Thread safety** — все callbacks на Main UI thread
- **Error handling** — null listener, user exceptions

### 3. Проверить на real devices
- Различные Android versions (API 26-34)
- Различные OEM (Samsung, Xiaomi, Pixel)
- Различные screen sizes

### 4. Rewarded ads specific
- Отдельно протестировать onUserRewarded
- Проверить что reward не дается при раннем закрытии

---

## Заключение

### ✅ Достигнуто
- Полное покрытие всех 9 колбэков (100%)
- 31 новый тест-кейс для callbacks
- Критичный gap (onAdClosed) обнаружен и документирован
- Приоритизация тестов

### ⚠️ Критично для Production
**onAdClosed** — самый важный lifecycle callback для fullscreen рекламы. Без него пользователи SDK не могут корректно обработать закрытие рекламы и продолжить работу app/игры. **MUST PASS для релиза!**

### 📊 Новая статистика тестов
- **Всего тестов:** 121 (было 90)
- **Критичных:** 24 (было 15)
- **Покрытие:** Functional + Edge Cases + Lifecycle + Performance + **Callbacks**
- **Готовность:** 100% документации, готово к тестированию

---

**Document Status:** Complete ✅
**Date:** 2026-02-06
**Impact:** HIGH — Critical callbacks now fully documented
**Next:** Execute callback tests on emulator
