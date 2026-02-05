# Coding Conventions

**Analysis Date:** 2026-02-05

## Naming Patterns

**Files:**
- Interface files use PascalCase without suffix: `Adapter.kt`, `AdCache.kt`, `Logger.kt`
- Implementation files use PascalCase + "Impl" suffix: `AdCacheImpl.kt`, `LoggerImpl.kt`, `AuctionImpl.kt`
- Test files use PascalCase + "Test" suffix: `AdUnitParserTest.kt`, `AuctionStatImplTest.kt`
- Extension function files use PascalCase + "Ext" suffix: `AdTypeExt.kt`, `TrackingHolderExt.kt`

**Functions:**
- Public functions use camelCase: `logInfo()`, `cache()`, `peek()`, `registerAdapters()`
- Private functions use camelCase: `load()`, `updateCache()`, `asString()`
- Suspend functions named with imperative verbs: `poll()`, `request()`
- Extension functions on types: `fun AdTypeParam.copy()`, `fun List<AuctionResult>.asString()`
- Logging functions exported as top-level: `logInfo()`, `logError()`

**Variables:**
- Instance properties use camelCase: `demandAd`, `isLoading`, `results`, `previousBidStat`
- Companion object constants use UPPER_SNAKE_CASE: `DefaultTag = "BidonLog"`
- State flow variables use camelCase: `state`, `isLoading`, `results`
- Local variables use camelCase: `auctionId`, `context`, `scope`

**Types:**
- Data classes use PascalCase: `DemandId`, `AdUnit`, `AuctionResult`
- Sealed interfaces for event hierarchies: `sealed interface AdEvent { class Expired(...) : AdEvent }`
- Enum types use UPPER_SNAKE_CASE values: `BidType.CPM`, `AdType.Banner`
- Nested types for variants: `AuctionResult.Network`, `AuctionResult.Bidding`

**Package Structure:**
- Internal (SDK-only) implementations: `org.bidon.sdk.*.impl` or `org.bidon.sdk.*`
- Public API: No "impl" in package, direct exports in parent package
- Sealed hierarchies grouped in single file or parent package

## Code Style

**Formatting:**
- KtLint is the enforcer (see `.editorconfig`)
- Indentation: 4 spaces for Kotlin/KTS files
- Line length: No enforced maximum (disabled in ktlint)
- Trailing commas: Disabled on both declaration and call sites (allows flexible formatting)

**Linting:**
- Tool: KtLint with Gradle convention plugin
- Disabled rules (per `.editorconfig`):
  - `final-newline` - No required newline at end of file
  - `no-wildcard-imports` - Wildcard imports allowed
  - `max-line-length` - No line length limit
  - `trailing-comma-on-*` - Flexible trailing comma usage
  - `package-name` - Non-standard package naming allowed
  - `enum-entry-name-case` - Flexible enum naming
- Run `./gradlew ktlintFormat` to auto-fix violations
- CI enforces via `./gradlew ktlintCheck` in pull requests

**Visibility Modifiers:**
- Public API explicitly marked with `public` keyword (enforced by `explicitApi()` in CommonGradlePlugin)
- Internal components: `internal` keyword
- Private functions/properties: `private` keyword
- Public interfaces/data classes always explicit

## Import Organization

**Order:**
1. Kotlin standard library and kotlinx imports: `import kotlin.*`, `import kotlinx.coroutines.*`
2. Android framework imports: `import android.*`, `import androidx.*`
3. Google services/libraries: `import com.google.*`
4. Third-party libraries: `import io.mockk.*`, `import org.json.*`
5. Internal SDK imports: `import org.bidon.sdk.*`

**Path Aliases:**
- No explicit path aliases configured
- Imports use full qualified names from `org.bidon.sdk.*`

**Wildcard Imports:**
- Allowed (ktlint rule disabled)
- Used selectively in test files for common assertion libraries
- Example: Custom assertion extensions imported with `import org.bidon.sdk.config.models.json_scheme_utils.*`

## Error Handling

**Patterns:**
- Result types using `Result<T>`: `Result.success()` and `Result.failure()`
- Lambda-based error callbacks: `onFailure: (AuctionInfo?, Throwable) -> Unit`
- runCatching for exception wrapping: Used in `AuctionImpl.start()` for auction lifecycle
- Sealed result types for domain-specific outcomes: `RoundResult.Results`, `BiddingResult.FilledAd`

**Exception Strategy:**
- Custom exceptions via `BidonError` sealed class
- Thrown from use cases when validation fails
- Caught at boundary layers (ad managers, auction coordinator)
- Logged with `logError()` when level is Verbose or Error

## Logging

**Framework:** Android's `Log` class (indirect via wrapper functions)

**Patterns:**
- Entry point functions: `logInfo(tag, "Auction started")`
- Error conditions: `logError(tag, message, error)`
- Tag creation: Use `TAG` extension property (from `org.bidon.sdk.utils.ext.TAG`)
- Composite tags for context: `val tag = "${TAG}_${demandAd.adType.code}"`
- Conditional logging based on `BidonSdk.loggerLevel`:
  - `Verbose`: All logs
  - `Error`: Only error logs
  - Other: No logs

**Usage:**
```kotlin
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.logs.logging.impl.logError

logInfo(tag, "Cache loaded with ${results.size} ads")
logError(tag, "Auction failed unexpectedly", exception)
```

## Comments

**When to Comment:**
- File header: Creator and date (convention: "Created by [Name] on [date]")
- Explain why: Document non-obvious design decisions
- API documentation: All public methods documented
- Complex algorithms: Explain before math-heavy code sections
- Example: Inline comments in test data setup explaining test scenario

**JSDoc/KDoc:**
- Used for all public API: functions, classes, interfaces
- Format: Standard KDoc with `@param`, `@return`, `@throws`
- Example:
```kotlin
/**
 * Created by Aleksei Cherniaev on 28/09/2023.
 */
internal interface AdCache : Cacheable {
    /**
     * Caches ads.
     */
    fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    )

    /**
     * Exposes only, if exists
     */
    fun peek(): AuctionResult?
}
```

## Function Design

**Size:**
- Target: 30-40 lines for public methods
- Large functions (250+ lines): `AdCacheImpl.kt` (180 lines actual logic), `BannerView.kt` (397 lines)
- Frequent in complex domains: auction coordination, ad rendering, state management
- Acceptable when: Sequential steps with clear control flow, high cohesion

**Parameters:**
- Named parameters used at call sites for clarity
- Lambdas as last parameter: Common pattern `fun cache(..., onSuccess: () -> Unit, onFailure: () -> Unit)`
- Data classes for complex parameter groups: `AdTypeParam.Interstitial(activity, pricefloor, auctionKey)`
- Destructuring in test setup: `val (key, value) = pair`

**Return Values:**
- Nullable returns for optional results: `fun peek(): AuctionResult?`
- Suspend functions return wrapped types: `suspend fun poll(): AuctionResult`
- Callbacks for async results: `fun cache(..., onSuccess: (...) -> Unit, onFailure: (...) -> Unit)`
- Unit returns for side-effect operations: `fun clear()`, `fun cache(...)`

## Module Design

**Exports:**
- Sealed interfaces exported from package root for public use
- Implementation classes internal: `internal class AdCacheImpl(...) : AdCache`
- Factory patterns for creating implementations: `AdCacheFactory` returns `AdCache` interface

**Barrel Files:**
- No explicit barrel exports observed
- Each interface/implementation in own file
- Test fixtures/utilities in shared `json_scheme_utils`, `models.base` packages

**Package Organization:**
- By feature layer: `ads/`, `auction/`, `adapter/`, `config/`
- By function within layer: `impl/` subdirectory for implementations
- Test mirrors source structure: `src/test/java` mirrors `src/main/java`

---

*Convention analysis: 2026-02-05*
