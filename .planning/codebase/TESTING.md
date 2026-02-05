# Testing Patterns

**Analysis Date:** 2026-02-05

## Test Framework

**Runner:**
- JUnit 4.13.2 (via `junit:junit:4.13.2`)
- Kotlin test utilities: `org.jetbrains.kotlin:kotlin-test` with `kotlin-test-junit`
- Coroutine testing: `kotlinx-coroutines-test` for suspending functions
- Config: Gradle convention plugin in `build-logic/convention-plugins/src/main/kotlin/CommonGradlePlugin.kt`

**Assertion Library:**
- Google Truth: `com.google.truth:truth:1.1.4`
- Kotlin test assertions: `kotlin.test.Test`, `kotlin.test.assert*` functions
- Custom assertion extensions in `org.bidon.sdk.config.models.json_scheme_utils`

**Run Commands:**
```bash
# Run all tests in core SDK
./gradlew :bidon:testReleaseUnitTest

# Run specific test file
./gradlew :bidon:testReleaseUnitTest --tests "*AdUnitParserTest"

# Run all tests in project
./gradlew test

# Run with coverage
./gradlew :bidon:testReleaseUnitTest --cov  # If coverage plugin added
```

## Test File Organization

**Location:**
- Co-located with source: `src/test/java` mirrors `src/main/java` structure
- Examples:
  - Source: `bidon/src/main/java/org/bidon/sdk/auction/models/AdUnit.kt`
  - Test: `bidon/src/test/java/org/bidon/sdk/auction/models/AdUnitParserTest.kt`
  - Source: `bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheImpl.kt`
  - Test: Not found (implementation tested via integration tests)

**Naming:**
- Pattern: `[ClassName]Test.kt`
- Examples: `AdUnitParserTest.kt`, `AuctionStatImplTest.kt`, `UserSerializerTest.kt`

**Structure:**
```
bidon/src/test/java/
├── org/bidon/sdk/
│   ├── auction/
│   │   ├── impl/
│   │   │   └── ExecuteAuctionUseCaseImplTest.kt
│   │   ├── models/
│   │   │   └── AdUnitParserTest.kt
│   │   └── usecases/
│   │       └── AuctionStatImplTest.kt
│   ├── config/
│   │   ├── impl/
│   │   │   └── InitAndRegisterAdaptersUseCaseImplTest.kt
│   │   └── models/
│   │       ├── UserSerializerTest.kt
│   │       ├── json_scheme_utils/
│   │       │   ├── TestJsonExt.kt (fixtures)
│   │       │   └── JsonSchemeUtilities
│   │       └── auctions/
│   │           └── impl/
│   │               └── AuctionImplTest.kt
│   └── [feature]/
│       └── [FeatureName]Test.kt
```

## Test Structure

**Suite Organization:**

```kotlin
// From: bidon/src/test/java/org/bidon/sdk/auction/usecases/AuctionStatImplTest.kt

internal class AuctionStatImplTest : ConcurrentTest() {

    private val statRequestUseCase: StatsRequestUseCase = mockk(relaxed = true)
    private val testee: AuctionStat by lazy {
        AuctionStatImpl(
            statsRequest = statRequestUseCase,
            resolver = MaxPriceAuctionResolver
        )
    }

    @Before
    fun before() {
        mockkObject(DeviceInfo)
        every { DeviceInfo.init(any()) } returns Unit
        DI.init(mockk())
        mockkLog()
    }

    @After
    fun after() {
        unmockkAll()
        SimpleDiStorage.instances.clear()
    }

    @Test
    fun `it should save results, DSP winner`() = runTest {
        // PREPARE
        testee.markAuctionStarted(...)

        // ACT
        val actual = testee.addRoundResults(...)

        // ASSERT
        assertThat(actual).hasSize(...)
    }
}
```

**Patterns:**
- Inherit from `ConcurrentTest` for coroutine and dispatcher setup (from `org.bidon.sdk.config.models.base.ConcurrentTest`)
- Setup: `@Before` functions initialize mocks, DI, and fixtures
- Teardown: `@After` functions clean up mocks and storage
- Test naming: Backtick strings describing behavior in simple past tense: `` `it should save results, DSP winner`() ``
- Assertion pattern: Arrange-Act-Assert (PREPARE, ACT, ASSERT as comments)

## Mocking

**Framework:** MockK 1.13.5 (via `io.mockk:mockk`)
- Configured in: `build-logic/convention-plugins/src/main/kotlin/CommonGradlePlugin.kt` line 99-101
- Excludes slf4j to avoid conflicts

**Patterns:**

```kotlin
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.coEvery
import io.mockk.unmockkAll

// Simple mocking
private val activity: Activity by lazy { mockk() }
private val adaptersSource: AdaptersSource = mockk()

// Relaxed mocking (all calls allowed)
private val regulation: Regulation = mockk(relaxed = true)

// Object mocking
mockkObject(DeviceInfo)
every { DeviceInfo.init(any()) } returns Unit

// Coercing/answer blocks
private val statRequestUseCase: StatsRequestUseCase = mockk(relaxed = true)

// Suspend function mocking
coEvery { conductNetworkAuction.invoke(...) } returns NetworksResult(...)

// Slot capturing for verification
private val slot = slot<String>()
every { some.method(capture(slot)) } returns Unit
```

**What to Mock:**
- External dependencies: Activities, Services, API clients
- Data sources: Repositories, DAOs, network services
- System calls: DeviceInfo singleton, time functions
- Cross-boundary calls: Between major modules

**What NOT to Mock:**
- Value objects: Data classes, enums (`BidType.CPM`, `AdType.Banner`)
- Core business logic: Parsers, serializers, formatters
- Test utilities: Custom assertion helpers, fixtures
- Simple data containers: Sealed result types

## Fixtures and Factories

**Test Data:**

```kotlin
// From: bidon/src/test/java/org/bidon/sdk/auction/impl/ExecuteAuctionUseCaseImplTest.kt

private val auctionConfig = AuctionResponse(
    adUnits = listOf(
        AdUnit(
            demandId = "admob",
            label = "admob_banner",
            pricefloor = 0.25,
            uid = "12387837129819",
            bidType = BidType.CPM,
            timeout = 5000,
            ext = jsonObject { "ad_unit_id" hasValue "ca-app-pub-3940256099942544/6300978111" }.toString(),
        ),
        // ...
    ),
    pricefloor = 0.01,
    auctionId = "auctionId_123",
    // ...
)
```

**Builder/Assertion Patterns:**

```kotlin
// From json_scheme_utils
val actual = User(...).serialize()

actual.assertEquals(
    expectedJsonStructure {
        "idfa" hasValue "123"
        "tracking_authorization_status" hasValue "asd"
        "idg" hasValue "a.a.a"
    }
)

// Custom DSL for JSON assertion
assertEqualsTo(expectedJsonStructure { ... })
```

**Location:**
- Test data as class properties: `private val auctionConfig = AuctionResponse(...)`
- Shared utilities in: `bidon/src/test/java/org/bidon/sdk/config/models/json_scheme_utils/`
- Files:
  - `TestJsonExt.kt` (180 lines): Custom JSON comparison functions
  - Assertion extension: `.assertEquals()`, `.assertEqualsTo()`
  - Wildcard placeholders: `Whatever.String`, `Whatever.Int` for lenient assertions

## Coverage

**Requirements:** Not enforced (no minimum threshold detected in CI)

**View Coverage:**
- Not configured in analyzed files
- Could be added via JaCoCo plugin if needed

## Test Types

**Unit Tests:**
- Scope: Single class or function in isolation
- Approach: Mock all dependencies, verify behavior via assertions
- Examples:
  - `AdUnitParserTest.kt` - Tests JSON parsing into domain models
  - `UserSerializerTest.kt` - Tests serialization of User to JSON
  - Typical: 50-100 lines, 1-3 test methods

**Integration Tests:**
- Scope: Multiple components working together
- Approach: Real implementations with mocked external services
- Examples:
  - `AuctionStatImplTest.kt` (939 lines) - Tests auction stat collection with mocked stats request
  - `AuctionImplTest.kt` (426 lines) - Tests full auction coordination
  - Use `ConcurrentTest` base class for dispatcher isolation
  - Typical: 200-400 lines, 3-5 test methods per scenario

**E2E Tests:**
- Framework: Not detected (no instrumentation test configuration)
- Note: App module has demo app but no automated E2E tests found

## Common Patterns

**Async Testing:**

```kotlin
@Test
fun `it should detect winner in #round_2 when 2 rounds are completed`() = runTest {
    // runTest: Provides TestScope with virtual time
    // Automatically advances virtual time

    every { adaptersSource.adapters } returns setOf(...)

    val result = testee.start(
        demandAd = DemandAd(AdType.Interstitial),
        adTypeParam = AdTypeParam.Interstitial(activity, 1.0, "key"),
        onSuccess = { results, auctionInfo -> ... },
        onFailure = { auctionInfo, error -> ... }
    )

    // Assertions after completion
    assertThat(result).containsExactly(...)
}
```

**Coroutine Dispatcher Setup:**

```kotlin
// From ConcurrentTest base class
abstract class ConcurrentTest {
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    open fun setUp() {
        defaultDispatcherOverridden = mainThreadSurrogate
        ioDispatcherOverridden = mainThreadSurrogate
        singleDispatcherOverridden = mainThreadSurrogate
        mainDispatcherOverridden = mainThreadSurrogate
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    fun freezeTime(timeMs: Long): Long {
        SystemTimeNowTestOnly = timeMs
        ElapsedMonotonicTimeNowTestOnly = timeMs
        return timeMs
    }
}
```

**Error Testing:**

```kotlin
// From test files
coEvery {
    conductNetworkAuction.invoke(any(), ...)
} throws NetworkException("Connection timeout")

// Then verify exception handling
val result = testee.execute(...)
assertThat(result).isInstanceOf(AuctionResult.AuctionFailed::class.java)
```

**Relaxed Mocking for Setup:**

```kotlin
private val statRequestUseCase: StatsRequestUseCase = mockk(relaxed = true)
// Any call to statRequestUseCase returns Unit or empty default
// Allows tests to focus on specific behaviors without exhaustive stubbing
```

**Object Mocking (Singletons):**

```kotlin
@Before
fun before() {
    mockkObject(DeviceInfo)
    every { DeviceInfo.init(any()) } returns Unit
    DI.init(mockk())
    mockkLog()
}

@After
fun after() {
    unmockkAll()
}
```

---

*Testing analysis: 2026-02-05*
