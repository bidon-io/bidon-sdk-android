# GitHub Actions Workflows

## CI Workflows

### `ci-pull-request.yml` - **CI Core SDK**
**Triggers**: Pull requests, manual
**Purpose**: Basic Android CI checks for core Bidon SDK
- KtLint code style validation
- CHANGELOG update verification
- Core SDK unit tests

### `ci-adapter-quality.yml` - **CI Adapter Quality** (reusable)
**Triggers**: Pull requests (adapter changes), push to develop, workflow_call, manual
**Purpose**: Quality checks for adapters
- Build adapter (`assembleProductionRelease`)
- Check for deprecated code warnings
- Run adapter unit tests (`testProductionReleaseUnitTest`)

Can be called from other workflows with optional `adapters` input (JSON array).

---

## Automation Workflows

### `automation-dependabot.yml` - **Automation Dependabot**
**Triggers**: Pull requests (Dependabot only, adapter dependency updates), manual
**Purpose**: Complete Dependabot automation workflow

**Flow:**
1. Detects adapter version changes
2. Updates CHANGELOG.md files with version changes and vendor release notes
3. Commits CHANGELOG updates to PR
4. Calls `ci-adapter-quality.yml` to run quality checks

**Solves**: GITHUB_TOKEN workflow re-trigger limitation by running all steps in one workflow.

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

Two workflows can be called by other workflows:

**`ci-adapter-quality.yml`**
- Called by: `automation-dependabot.yml`
- Input: `adapters` (optional JSON array)
- Purpose: Run quality checks on specific adapters

**`release-adapter.yml`**
- Called by: `automation-publish-adapters.yml`
- Inputs: `adapter`, `repository`
- Purpose: Publish single adapter to Artifactory

### Call Graph

```
automation-dependabot.yml
  └─► ci-adapter-quality.yml
        └─► Runs quality checks for changed adapters

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

**Automated Process:**
1. Dependabot detects dependency update
2. Opens PR to `develop` with updated build.gradle.kts
3. `automation-dependabot.yml` triggers:
   - Updates CHANGELOG.md with version change and vendor release notes
   - Commits CHANGELOG to PR
   - Runs quality checks (build, deprecated warnings, unit tests)
4. Team reviews and approves PR
5. After merge, `automation-publish-adapters.yml` triggers:
   - Publishes adapter to bidon-private
   - Publishes adapter to bidon (public)
   - Sends Slack notifications

**All automated** - no manual intervention required except PR approval.

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

### Update CHANGELOG Manually
1. Go to **Actions** → **Automation Dependabot**
2. Click **Run workflow**
3. Optionally specify PR number
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
