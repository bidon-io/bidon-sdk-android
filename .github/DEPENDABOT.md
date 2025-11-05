# Dependabot Configuration

This document describes the Dependabot setup for adapter dependency management.

## Overview

Dependabot is configured to automatically check for updates to adapter SDK dependencies daily and create pull requests when updates are available.

## Configuration

### Files Created

1. **`.github/dependabot.yml`** - Main Dependabot configuration
   - Monitors all 22 adapter modules in `/adapter/`
   - Creates PRs against `develop` branch
   - Runs daily checks at 03:00 UTC
   - Auto-assigns reviewers: `da2gl`, `copilot`
   - Applies labels: `dependencies`, `adapter` (or `google-adapter` for admob/gam)
   - Limit: 10 open PRs at a time

2. **`adapter/*/CHANGELOG.md`** - Changelog file for each adapter
   - Follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) format
   - Automatically updated by GitHub Actions when Dependabot creates PRs
   - Includes automatic links to vendor release notes

   Example format:
   ```markdown
   ## [24.6.0] - 2025-11-05
   ### Changed
   - Updated SDK dependency from 24.5.0 to 24.6.0
   - 📋 [View Release Notes](https://developers.google.com/admob/android/rel-notes)
   ```

3. **`.github/workflows/dependabot-changelog.yml`** - Automated changelog updater
   - Triggers when Dependabot creates/updates a PR
   - Parses version changes from `build.gradle.kts`
   - Updates the corresponding adapter's CHANGELOG.md
   - Automatically adds links to vendor release notes
   - Commits changes back to the PR

4. **`.github/workflows/publish-adapter-on-merge.yml`** - Automated publishing
   - Triggers when Dependabot PR is merged to `develop`
   - Detects which adapters were updated
   - Publishes to both `bidon-private` and `bidon` (public) repositories
   - Sends Slack notifications about publication status

## How It Works

1. **Daily**: Dependabot checks all adapter dependencies at 03:00 UTC
2. **Update Found**: Creates a PR to `develop` branch with the dependency update in `build.gradle.kts`
3. **Automatic**: GitHub Action workflows run:
   - `dependabot-changelog.yml` - updates the adapter's CHANGELOG.md
   - `adapter-checks.yml` - runs build, deprecated check, and tests
4. **Review**: Assigned reviewers (`da2gl`, `copilot`) are notified
5. **Merge**: After approval and successful checks, the PR can be merged to `develop`
6. **Auto-Publish**: After merge, `publish-adapter-on-merge.yml` automatically:
   - Detects which adapter was updated
   - Publishes to `bidon-private` (internal repository)
   - Publishes to `bidon` (public repository)
   - Sends Slack notifications for each publication

## Testing

The workflow can be tested manually before the first Dependabot run. See [TESTING_DEPENDABOT.md](TESTING_DEPENDABOT.md) for detailed instructions.

**Quick test**:
1. Go to Actions → "Update Adapter Changelog"
2. Click "Run workflow"
3. Enable "Test mode"
4. View results in the workflow summary

## PR Behavior

- **New Updates**: Dependabot creates a new draft PR (if possible)
- **Existing PRs**: If a PR is already open for an adapter, Dependabot updates it with the new version (force push)
- **Grouping**: Google adapters (admob + gam) have the `google-adapter` label for easier batch merging, but they create separate PRs

### Important: CHANGELOG Updates on Existing PRs

When Dependabot updates an existing PR (e.g., bumps version from 24.5.0 → 24.6.0 → 24.7.0):
- The workflow will create **multiple entries** in the CHANGELOG
- Example:
  ```markdown
  ## [24.7.0] - 2025-11-10
  ### Changed
  - Updated SDK dependency from 24.5.0 to 24.7.0

  ## [24.6.0] - 2025-11-08
  ### Changed
  - Updated SDK dependency from 24.5.0 to 24.6.0
  ```

**Before merging**: Manually clean up the CHANGELOG to keep only the final version:
```markdown
## [24.7.0] - 2025-11-10
### Changed
- Updated SDK dependency from 24.5.0 to 24.7.0
```

**Tip**: Merge Dependabot PRs quickly to avoid multiple updates.

## Adding a New Adapter

When adding a new adapter to the project, **you must manually update** `.github/dependabot.yml`:

```yaml
- package-ecosystem: "gradle"
  directory: "/adapter/new-adapter-name"
  target-branch: "develop"
  schedule:
    interval: "daily"
    time: "03:00"
    timezone: "UTC"
  open-pull-requests-limit: 10
  reviewers:
    - "da2gl"
    - "copilot"
  commit-message:
    prefix: "chore(new-adapter-name)"
    include: "scope"
  labels:
    - "dependencies"
    - "adapter"
```

Also create a CHANGELOG.md file:

```bash
cat > adapter/new-adapter-name/CHANGELOG.md << 'EOF'
# Changelog

All notable changes to the NewAdapter adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]
EOF
```

**Add release notes URL** to `.github/workflows/dependabot-changelog.yml`:

Find the case statement (around line 124) and add your adapter:

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

## Labels

- `dependencies` - Applied to all Dependabot PRs
- `adapter` - Applied to regular adapter PRs
- `google-adapter` - Applied to admob and gam PRs (for easier batch operations)

## Commit Message Format

Dependabot uses the following commit message format:
```
chore(adapter-name): bump dependency-name from X.Y.Z to A.B.C
```

The changelog updater uses:
```
docs: update adapter changelogs for dependency updates
```

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

## Automatic Publication

After merging a Dependabot PR to `develop`, the updated adapter is automatically published to both repositories:

### Publication Flow

1. **Trigger**: Push to `develop` with changes in `adapter/*/build.gradle.kts`
2. **Detection**: Workflow identifies which adapter(s) were updated
3. **Publish to bidon-private**: Internal repository for testing
4. **Publish to bidon**: Public repository for production use
5. **Slack Notifications**: Team receives updates about publication status

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

**Verify published artifacts**:
```bash
# Check bidon-private
curl -u user:pass https://artifactory.bidon.org/bidon-private/org/bidon/adapter-<name>/

# Check bidon (public)
curl https://artifactory.bidon.org/bidon/org/bidon/adapter-<name>/
```

## Current Adapters (22)

admob, amazon, applovin, appsflyer, bidmachine, bigoads, chartboost, dtexchange, fyber, gam, inmobi, ironsource, meta, mintegral, mobilefuse, moloco, startio, taurusx, unityads, vkads, vungle, yandex

## References

- [Dependabot Documentation](https://docs.github.com/en/code-security/dependabot)
- [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)
- [Configuration Options](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file)
