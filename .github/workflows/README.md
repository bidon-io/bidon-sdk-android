# GitHub Actions Workflows

## CI Workflows

### `ci-pull-request.yml` - **CI Core SDK**
**Triggers**: Pull requests, manual
**Purpose**: Basic Android CI checks for core Bidon SDK
- KtLint code style validation
- CHANGELOG update verification
- Core SDK unit tests

### `ci-adapter-quality.yml` - **CI Adapter Quality**
**Triggers**: Pull requests (adapter changes), manual
**Purpose**: Quality checks for adapters
- Automatically detects changed adapters
- Build adapter (`assembleProductionRelease`)
- Check for deprecated code warnings (uploads artifact for Claude fix)
- Run adapter unit tests (`testProductionReleaseUnitTest`)
- Uploads artifacts: `build-output-{adapter}`, `deprecated-warnings-{adapter}`, `test-failures-{adapter}`

### `sdk-size-check.yml` - **SDK Size Check**
**Triggers**: Pull requests (adapter build.gradle.kts changes), push to develop
**Purpose**: Compare APK size impact of adapter updates
- Caches develop branch APK on push to develop
- Compares PR APK size vs develop APK size
- Posts size diff comment on PR (runs only once per PR)
- Alerts on significant size increases (>5MB or >10%)

---

## Automation Workflows

### `automation-post-dependabot.yml` - **Automation Post-Dependabot**
**Triggers**: workflow_run (after CI Adapter Quality completes)
**Purpose**: Orchestrates all Dependabot PR automation

**Flow (sequential with state machine):**
1. **check-state**: Determines PR info, labels, job statuses, available artifacts, and requests team review
2. **update-changelog**: Updates CHANGELOG for Dependabot PRs (adds `bot:changelog-updated` label)
3. **fix-build**: If build failed, calls Claude to fix (adds `bot:build-attempted` label)
4. **fix-deprecated**: If deprecated check failed, calls Claude to fix (adds `bot:deprecated-attempted` label)
5. **fix-tests**: If unit tests failed, calls Claude to fix (adds `bot:tests-attempted` label)

**Labels (State Machine):**
| Label | Color | Purpose |
|-------|-------|---------|
| `bot:changelog-updated` | Blue | CHANGELOG automatically updated |
| `bot:build-attempted` | Red | Claude attempted to fix build errors |
| `bot:deprecated-attempted` | Green | Claude attempted to fix deprecated warnings |
| `bot:tests-attempted` | Purple | Claude attempted to fix test failures |

**Requirements:**
- `CLAUDE_API_KEY_SDK` secret in Repository secrets (not Dependabot secrets)
- `BIDON_BOT_APP_ID` secret in Repository secrets (GitHub App ID)
- `BIDON_BOT_PRIVATE_KEY` secret in Repository secrets (GitHub App private key)

### `automation-publish-adapters.yml` - **Automation Publish Adapters**
**Triggers**: Push to develop (adapter build.gradle.kts changes)
**Purpose**: Automatic adapter publication after merge to develop

**Flow:**
1. Detects which adapters changed
2. Calls `release-adapter.yml` to publish to `bidon-private` (internal)
3. Calls `release-adapter.yml` to publish to `bidon` (public)
4. Sends Slack notifications for each publication

---

## Claude Reusable Workflows

### `claude-fix-changelog.yml`
**Triggers**: workflow_call
**Purpose**: Update CHANGELOG.md for Dependabot dependency updates
- Detects version changes in build.gradle.kts
- Resets adapterMinor to 0
- Creates changelog entry with date and version info
- Commits and pushes changes

### `claude-fix-build.yml`
**Triggers**: workflow_call
**Purpose**: Use Claude AI to fix build errors
- Downloads `build-output-{adapter}` artifact
- Extracts Release Notes URL from CHANGELOG
- Calls Claude Sonnet 4.5 to fix compilation errors
- Runs ktlintFormat and commits changes

### `claude-fix-deprecated.yml`
**Triggers**: workflow_call
**Purpose**: Use Claude AI to fix deprecated API warnings
- Downloads `deprecated-warnings-{adapter}` artifact
- Extracts Release Notes URL from CHANGELOG
- Calls Claude Sonnet 4.5 to migrate deprecated APIs
- Runs ktlintFormat and commits changes

### `claude-fix-tests.yml`
**Triggers**: workflow_call
**Purpose**: Use Claude AI to fix failing unit tests
- Downloads `test-failures-{adapter}` artifact
- Extracts Release Notes URL from CHANGELOG
- Calls Claude Sonnet 4.5 to fix test failures
- Runs ktlintFormat and commits changes

---

## Release Workflows

### `release-adapter.yml` - **Release Adapter** (reusable)
**Triggers**: workflow_call, manual
**Purpose**: Publish single adapter to Artifactory

**Parameters:**
- `adapter` - Adapter name (admob, applovin, etc.)
- `repository` - Target repo (bidon-private or bidon)

**Actions:**
- Extracts adapter version from build.gradle.kts
- Publishes to specified Artifactory repository
- Sends Slack notification with status

Can be triggered manually for specific adapter or called from other workflows.

### `release-sdk.yml` - **Release Bidon SDK**
**Triggers**: Manual only (workflow_dispatch)
**Purpose**: Publish core Bidon SDK to Artifactory

**Parameters:**
- `ORG_GRADLE_PROJECT_repo` - Repository (bidon-private or bidon)

**Validation:**
- Public repository (bidon) only allowed from `release/*` branches
- Sends Slack notifications with status

---

## Validation Workflows

### `validation-gitflow.yml` - **Validation Git-Flow** (DISABLED)
**Status**: Currently disabled
**Triggers**: Manual only (pull request triggers commented out)

**Purpose**: Enforces Git-Flow branching rules when enabled
- Validates merge patterns (feature → develop, release → main, etc.)
- Allows dependabot/* branches to merge into develop
- Validates PR title and description requirements

**To re-enable**: Uncomment pull_request trigger in workflow file.

---

## Other Workflows

### `sync_repo.yml`
Legacy repository synchronization workflow.

---

## Workflow Architecture

### Reusable Workflows

**`release-adapter.yml`**
- Called by: `automation-publish-adapters.yml`
- Inputs: `adapter`, `repository`
- Purpose: Publish single adapter to Artifactory

**`claude-fix-changelog.yml`**
- Called by: `automation-post-dependabot.yml`
- Inputs: `pr_number`, `pr_branch`
- Purpose: Update CHANGELOG for dependency updates

**`claude-fix-build.yml`**
- Called by: `automation-post-dependabot.yml`
- Inputs: `pr_number`, `pr_branch`, `adapters`, `triggering_run_id`
- Purpose: Fix build errors with Claude AI

**`claude-fix-deprecated.yml`**
- Called by: `automation-post-dependabot.yml`
- Inputs: `pr_number`, `pr_branch`, `adapters`, `triggering_run_id`
- Purpose: Fix deprecated code with Claude AI

**`claude-fix-tests.yml`**
- Called by: `automation-post-dependabot.yml`
- Inputs: `pr_number`, `pr_branch`, `adapters`, `triggering_run_id`
- Purpose: Fix test failures with Claude AI

### Call Graph

```
ci-adapter-quality.yml
  └─► automation-post-dependabot.yml (workflow_run)
        ├─► claude-fix-changelog.yml (always for Dependabot)
        ├─► claude-fix-build.yml (if build failed)
        ├─► claude-fix-deprecated.yml (if deprecated check failed)
        └─► claude-fix-tests.yml (if unit tests failed)

sdk-size-check.yml
  └─► Comments size diff on PR

automation-publish-adapters.yml
  ├─► release-adapter.yml (bidon-private)
  │     └─► Publishes to internal repository
  └─► release-adapter.yml (bidon)
        └─► Publishes to public repository
```

---

## Dependabot Workflow

**Configuration**: `.github/dependabot.yml`
- 22 adapters monitored weekly (Wednesday 03:00 UTC)
- PRs created to `develop` branch
- Custom Maven registries: Google Maven, Bidon Artifactory

**Automated Process:**
1. Dependabot detects dependency update
2. Opens PR to `develop` with updated build.gradle.kts
3. `sdk-size-check.yml` triggers automatically:
   - Compares APK size with develop branch
   - Posts size impact comment on PR
4. `ci-adapter-quality.yml` triggers automatically:
   - Detects changed adapters
   - Runs quality checks (build, deprecated warnings, unit tests)
   - Uploads artifacts for any failures
5. `automation-post-dependabot.yml` triggers (workflow_run):
   - Requests team review from `bidon-android` via GitHub App
   - Updates CHANGELOG automatically
   - If build failed → Claude fixes build errors
   - If deprecated check failed → Claude fixes deprecated code
   - If tests failed → Claude fixes unit tests
   - Each fix adds a label to prevent infinite loops
6. CI re-runs to verify fixes
7. Team reviews and approves PR
8. After merge, `automation-publish-adapters.yml` triggers:
   - Publishes adapter to bidon-private
   - Publishes adapter to bidon (public)
   - Sends Slack notifications

---

## Manual Actions

### Publish Specific Adapter
1. Go to **Actions** → **Release Adapter**
2. Click **Run workflow**
3. Select adapter name
4. Select repository (bidon-private or bidon)
5. Run workflow

### Publish SDK
1. Go to **Actions** → **Release Bidon SDK**
2. Click **Run workflow**
3. Select repository (bidon-private or bidon)
4. Note: Public repo only works from `release/*` branches
5. Run workflow

### Run Quality Checks Manually
1. Go to **Actions** → **CI Adapter Quality**
2. Click **Run workflow**
3. Will auto-detect changed adapters
4. Run workflow

---

## Troubleshooting

### Dependabot PR not running checks
- Verify workflow is not skipped in Actions tab
- Check if actor is `dependabot[bot]`
- Ensure adapter build.gradle.kts was modified

### Publication failed
- Check Artifactory credentials in repository secrets
- Verify `ARTIFACTORY_USER` and `ARTIFACTORY_PASSWORD` are set
- Review workflow logs in Actions tab

### Quality checks failing
- Review build errors in workflow logs
- Check for deprecated code warnings
- Verify unit tests pass locally

### Workflow not triggering
- Verify file paths match trigger patterns
- Check branch matches trigger configuration
- Ensure workflow file is in `.github/workflows/` (not a subdirectory)

### Claude fix not working
- Verify `CLAUDE_API_KEY_SDK` secret is in **Repository secrets** (not Dependabot secrets)
- Verify `BIDON_BOT_APP_ID` and `BIDON_BOT_PRIVATE_KEY` secrets are in **Repository secrets**
- Check if artifacts were uploaded by CI
- Review Claude action logs for API errors
- Ensure CHANGELOG has valid Release Notes URL
- Check for `bot:*-attempted` labels (indicates Claude already tried)

### Size check not running
- Verify PR changes `adapter/*/build.gradle.kts`
- Check if size comment already exists (runs once per PR)
- Ensure develop cache exists (created on push to develop)

### Infinite loop prevention
- Labels `bot:*-attempted` prevent Claude from retrying the same fix
- If fix didn't work, manual intervention is required
- Remove the label to allow Claude to retry (use with caution)
