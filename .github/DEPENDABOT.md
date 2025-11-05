# Dependabot Configuration

Automated dependency management for adapter SDK updates with automatic changelog updates, testing, and publication to Artifactory.

## Overview

Dependabot automatically:
- Checks for adapter SDK updates weekly at 03:00 UTC
- Creates PRs to `develop` branch with dependency updates
- Updates CHANGELOGs with version changes and vendor release notes links
- Triggers build and test checks
- After merge, publishes updated adapters to both `bidon-private` and `bidon` (public) repositories
- Sends Slack notifications for publication status

## Complete Workflow

```
Weekly (03:00 UTC)
  └─ Dependabot checks all 22 adapters for updates
      ↓
Update Found (e.g., admob 24.5.0 → 24.6.0)
  └─ Creates PR to develop branch
      ↓
Automatic (on PR creation)
  ├─ dependabot-changelog.yml
  │   ├─ Updates adapter/admob/CHANGELOG.md
  │   ├─ Adds link to vendor release notes
  │   └─ Commits changes to PR
  └─ adapter-checks.yml
      ├─ Build adapter
      ├─ Check deprecated warnings
      └─ Run unit tests
      ↓
Manual Review
  ├─ Reviewer (bidon-android team) receives notification
  └─ Approves and merges to develop
      ↓
Automatic (after merge)
  └─ publish-adapter-on-merge.yml
      ├─ Detects which adapter was updated
      ├─ Publishes to bidon-private
      ├─ Publishes to bidon (public)
      └─ Sends Slack notifications
```

## Configuration Files

### 1. `.github/dependabot.yml`
Main Dependabot configuration:
- Monitors all 22 adapter modules in `/adapter/`
- Creates PRs against `develop` branch
- Runs weekly checks at 03:00 UTC
- Auto-assigns reviewer: `bidon-android` team
- Applies labels: `dependencies`, `adapter` (or `google-adapter` for admob/gam)
- Limit: 10 open PRs per adapter

### 2. `adapter/*/CHANGELOG.md`
Per-adapter changelog files:
- Follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) format
- Automatically updated by GitHub Actions
- Includes automatic links to vendor release notes

Example format:
```markdown
## [24.6.0] - 2025-11-05
### Changed
- Updated SDK dependency from 24.5.0 to 24.6.0
- 📋 [View Release Notes](https://developers.google.com/admob/android/rel-notes)
```

### 3. `.github/workflows/dependabot-changelog.yml`
Automated changelog updater:
- Triggers when Dependabot creates/updates a PR
- Parses version changes from `build.gradle.kts`
- Updates the corresponding adapter's CHANGELOG.md
- Automatically adds links to vendor release notes (22 adapters mapped)
- Commits changes back to the PR

### 4. `.github/workflows/publish-adapter-on-merge.yml`
Automated publishing:
- Triggers when Dependabot PR is merged to `develop`
- Detects which adapters were updated
- Publishes to both `bidon-private` and `bidon` (public) repositories
- Sends Slack notifications about publication status

## PR Behavior

**New Updates**: Dependabot creates a new PR to `develop` branch

**Existing PRs**: If a PR is already open for an adapter, Dependabot updates it with the new version (force push)

**Grouping**: Google adapters (admob + gam) have the `google-adapter` label for easier identification, but they create separate PRs (Dependabot cannot group updates from different directories)

### Important: CHANGELOG Updates on Existing PRs

When Dependabot updates an existing PR (e.g., bumps version from 24.5.0 → 24.6.0 → 24.7.0):
- The workflow will create **multiple entries** in the CHANGELOG
- Example:
  ```markdown
  ## [24.7.0] - 2025-11-10
  ### Changed
  - Updated SDK dependency from 24.5.0 to 24.7.0
  - 📋 [View Release Notes](...)

  ## [24.6.0] - 2025-11-08
  ### Changed
  - Updated SDK dependency from 24.5.0 to 24.6.0
  - 📋 [View Release Notes](...)
  ```

**Before merging**: Manually clean up the CHANGELOG to keep only the final version.

**Tip**: Merge Dependabot PRs quickly to avoid multiple updates.

## Adding a New Adapter

When adding a new adapter to the project, you must manually update three places:

### 1. Update `.github/dependabot.yml`

Add configuration for the new adapter:

```yaml
- package-ecosystem: "gradle"
  directory: "/adapter/new-adapter-name"
  target-branch: "develop"
  schedule:
    interval: "weekly"
    time: "03:00"
    timezone: "UTC"
  open-pull-requests-limit: 10
  reviewers:
    - "bidon-android"
  commit-message:
    prefix: "⬆️"
  labels:
    - "dependencies"
    - "adapter"
```

### 2. Create CHANGELOG.md file

```bash
cat > adapter/new-adapter-name/CHANGELOG.md << 'EOF'
# Changelog

All notable changes to the NewAdapter adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]
EOF
```

### 3. Add release notes URL

Update `.github/workflows/dependabot-changelog.yml` (around line 124):

```bash
case "$ADAPTER_NAME" in
  # ... existing adapters
  new-adapter-name)
    RELEASE_NOTES_URL="https://vendor.com/new-adapter/release-notes"
    ;;
  # ... rest
esac
```

This ensures the CHANGELOG will automatically include a link to the vendor's release notes.

## Automatic Publication

After merging a Dependabot PR to `develop`, the updated adapter is automatically published to both repositories.

### Publication Flow

1. **Trigger**: Push to `develop` with changes in `adapter/*/build.gradle.kts`
2. **Detection**: Workflow identifies which adapter(s) were updated
3. **Publish to bidon-private**: Internal repository for testing (published first)
4. **Publish to bidon**: Public repository for production use (published after private succeeds)
5. **Slack Notifications**: Team receives updates about publication status for each repository

### Publication Details

**Gradle task used**:
```bash
./gradlew :adapter:<adapter-name>:publishAllPublicationsToBidonRepository
```

**Repositories**:
- `bidon-private`: Internal repository (always published first)
- `bidon`: Public repository (published after private succeeds)

**What gets published**:
- Only the adapter(s) that were updated in the merged PR
- If multiple adapters were updated (e.g., admob + gam), all are published

### Monitoring Publications

**Check publication status**:
1. Go to Actions → "Publish Adapter After Dependabot Merge"
2. View the workflow run for your merge commit
3. Check Slack notifications for success/failure
4. View the Summary tab for publication details

## Troubleshooting

### Dependabot PRs not created
- Check `.github/dependabot.yml` syntax is valid
- Verify the adapter directory path is correct
- Check Dependabot logs in GitHub Settings → Security → Dependabot

### Changelog not updated automatically
- Verify the workflow has write permissions
- Check `.github/workflows/dependabot-changelog.yml` is present
- Review workflow run logs in Actions tab

### Multiple PRs for the same adapter
- Dependabot creates separate PRs for different dependency types
- Check if the PR is for a major vs minor/patch update

### Publication failed
- Check Artifactory credentials in repository secrets
- Verify `ARTIFACTORY_USER` and `ARTIFACTORY_PASSWORD` are set
- Check Slack webhook if notifications are not received
- Review workflow logs in Actions → "Publish Adapter After Dependabot Merge"

### Adapter checks failing
- Review build errors in Actions → "Adapter Quality Checks"
- Check for deprecated warnings in the adapter code
- Verify unit tests pass locally before merging

## Labels

- `dependencies` - Applied to all Dependabot PRs
- `adapter` - Applied to regular adapter PRs
- `google-adapter` - Applied to admob and gam PRs

## Commit Message Format

Dependabot uses:
```
⬆️ bump dependency-name from X.Y.Z to A.B.C
```

Changelog updater uses:
```
docs: update adapter changelogs for dependency updates
```

## Current Adapters (22)

admob, amazon, applovin, appsflyer, bidmachine, bigoads, chartboost, dtexchange, fyber, gam, inmobi, ironsource, meta, mintegral, mobilefuse, moloco, startio, taurusx, unityads, vkads, vungle, yandex

## References

- [Dependabot Documentation](https://docs.github.com/en/code-security/dependabot)
- [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)
- [Configuration Options](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file)
