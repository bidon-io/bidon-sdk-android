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

### `sdk-size-check.yml` - **SDK Size Check**
**Triggers**: Pull requests (adapter build.gradle.kts changes), push to develop
**Purpose**: Compare APK size impact of adapter updates
- Caches develop branch APK on push to develop
- Compares PR APK size vs develop APK size
- Posts size diff comment on PR (runs only once per PR)
- Alerts on significant size increases (>5MB or >10%)

---

## Automation Workflows

### `claude-fix-deprecated.yml` - **Claude Fix Deprecated**
**Triggers**: workflow_run (after CI Adapter Quality fails)
**Purpose**: Automatically fix deprecated code using Claude AI
- Downloads deprecated warnings artifacts from CI
- Extracts Release Notes URL from adapter CHANGELOG
- Calls Claude to fix deprecated APIs
- Pushes fixes to PR branch
- Comments on PR with fix summary

**Requirements:**
- `ANTHROPIC_API_KEY` secret must be configured

### `automation-dependabot.yml` - **Automation Dependabot** (DISABLED)
**Status**: Currently disabled
**Triggers**: Manual only (pull_request trigger disabled)

**Purpose**: Was used for automatic CHANGELOG updates for Dependabot PRs
- Currently disabled in favor of manual CHANGELOG management
- To re-enable: Uncomment pull_request trigger in workflow file

### `automation-publish-adapters.yml` - **Automation Publish Adapters**
**Triggers**: Push to develop (adapter build.gradle.kts changes)
**Purpose**: Automatic adapter publication after merge to develop

**Flow:**
1. Detects which adapters changed
2. Calls `release-adapter.yml` to publish to `bidon-private` (internal)
3. Calls `release-adapter.yml` to publish to `bidon` (public)
4. Sends Slack notifications for each publication

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

### Call Graph

```
ci-adapter-quality.yml
  └─► claude-fix-deprecated.yml (on failure)
        └─► Fixes deprecated code and pushes to PR

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
- 22 adapters monitored weekly (Monday 03:00 UTC)
- PRs created to `develop` branch
- Custom Maven registries: Google Maven, Bidon Artifactory

**Current Process:**
1. Dependabot detects dependency update
2. Opens PR to `develop` with updated build.gradle.kts
3. `sdk-size-check.yml` triggers automatically:
   - Compares APK size with develop branch
   - Posts size impact comment on PR
4. `ci-adapter-quality.yml` triggers automatically:
   - Detects changed adapters
   - Runs quality checks (build, deprecated warnings, unit tests)
5. If deprecated warnings found, `claude-fix-deprecated.yml` triggers:
   - Reads Release Notes from CHANGELOG
   - Fixes deprecated code automatically
   - Pushes fixes to PR branch
6. **Manual step**: Update CHANGELOG.md for affected adapters
7. Team reviews and approves PR
8. After merge, `automation-publish-adapters.yml` triggers:
   - Publishes adapter to bidon-private
   - Publishes adapter to bidon (public)
   - Sends Slack notifications

**Note**: CHANGELOG updates currently require manual editing before merge.

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
- Verify `ANTHROPIC_API_KEY` secret is configured
- Check if deprecated warnings artifact was uploaded
- Review Claude action logs for API errors
- Ensure CHANGELOG has valid Release Notes URL

### Size check not running
- Verify PR changes `adapter/*/build.gradle.kts`
- Check if size comment already exists (runs once per PR)
- Ensure develop cache exists (created on push to develop)
