# Testing Dependabot Changelog Workflow

This guide explains how to test the automated changelog workflow before Dependabot creates its first PR.

## Method 1: Manual Workflow Dispatch (Recommended)

The workflow can be triggered manually from GitHub UI:

### Steps:

1. **Push the changes to GitHub** (if not done yet):
   ```bash
   git add .github/ adapter/*/CHANGELOG.md
   git commit -m "feat: configure Dependabot for adapter dependency management"
   git push
   ```

2. **Go to GitHub Actions**:
   - Navigate to: `https://github.com/{your-org}/bidon-sdk-android/actions`
   - Click on "Update Adapter Changelog" workflow in the left sidebar

3. **Run workflow**:
   - Click "Run workflow" button (top right)
   - Select your branch
   - Check "Test mode" checkbox
   - Click "Run workflow"

4. **Check the results**:
   - Click on the running workflow
   - View the "Summary" section to see what was processed
   - Check the logs for detailed output

## Method 2: Create a Test PR

Create a test PR with a manual version bump to simulate Dependabot behavior:

### Steps:

1. **Create a test branch**:
   ```bash
   git checkout -b test/dependabot-workflow
   ```

2. **Modify an adapter version** (e.g., admob):
   ```bash
   # Edit adapter/admob/build.gradle.kts
   # Change: val adapterSdkVersion = "24.5.0"
   # To:     val adapterSdkVersion = "24.6.0"
   ```

3. **Commit and push**:
   ```bash
   git add adapter/admob/build.gradle.kts
   git commit -m "chore(admob): bump version for testing"
   git push -u origin test/dependabot-workflow
   ```

4. **Create a PR**:
   ```bash
   gh pr create --title "Test: Dependabot workflow" --body "Testing automated changelog updates"
   ```

5. **Check the workflow**:
   - Go to the PR on GitHub
   - Check the "Checks" tab
   - The workflow won't run automatically (it requires Dependabot actor)

6. **Manually trigger the workflow**:
   - Go to Actions → "Update Adapter Changelog"
   - Click "Run workflow"
   - Select `test/dependabot-workflow` branch
   - Enable "Test mode"
   - Click "Run workflow"

7. **Verify the results**:
   - Check if `adapter/admob/CHANGELOG.md` was updated
   - A new commit should appear in the PR with the changelog update

8. **Clean up**:
   ```bash
   gh pr close test/dependabot-workflow --delete-branch
   ```

## Method 3: Simulate Dependabot Locally

Test the changelog update script locally before pushing:

### Steps:

1. **Create a test script**:
   ```bash
   cat > test-changelog-update.sh << 'EOF'
   #!/bin/bash

   # Test adapter
   ADAPTER="admob"
   FILE="adapter/$ADAPTER/build.gradle.kts"
   CHANGELOG="adapter/$ADAPTER/CHANGELOG.md"

   # Get current version
   CURRENT_VERSION=$(grep 'val adapterSdkVersion = ' "$FILE" | sed 's/.*"\(.*\)"/\1/')
   echo "Current version: $CURRENT_VERSION"

   # Simulate new version (increment patch)
   NEW_VERSION="24.6.0"
   echo "New version: $NEW_VERSION"

   # Get current date
   CURRENT_DATE=$(date +%Y-%m-%d)

   # Create changelog entry
   ENTRY="## [$NEW_VERSION] - $CURRENT_DATE\n### Changed\n- Updated SDK dependency from $CURRENT_VERSION to $NEW_VERSION\n"

   # Insert entry after [Unreleased] section
   awk -v entry="$ENTRY" '
     /## \[Unreleased\]/ {
       print $0
       print ""
       print entry
       next
     }
     {print}
   ' "$CHANGELOG" > "${CHANGELOG}.tmp"

   mv "${CHANGELOG}.tmp" "$CHANGELOG"

   echo "Updated $CHANGELOG"
   cat "$CHANGELOG"
   EOF

   chmod +x test-changelog-update.sh
   ```

2. **Run the test**:
   ```bash
   ./test-changelog-update.sh
   ```

3. **Check the result**:
   ```bash
   git diff adapter/admob/CHANGELOG.md
   ```

4. **Revert if needed**:
   ```bash
   git checkout adapter/admob/CHANGELOG.md
   rm test-changelog-update.sh
   ```

## What to Check

When testing, verify:

- ✅ Workflow detects changed `build.gradle.kts` files
- ✅ Old and new versions are correctly extracted
- ✅ CHANGELOG.md is updated with correct format
- ✅ Entry is inserted after `[Unreleased]` section
- ✅ Commit is created with proper message
- ✅ Changes are pushed back to the PR branch
- ✅ GitHub Actions summary shows updated adapters

## Expected CHANGELOG Format

After update, the CHANGELOG should look like:

```markdown
# Changelog

All notable changes to the AdMob adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [24.6.0] - 2025-11-05
### Changed
- Updated SDK dependency from 24.5.0 to 24.6.0

## [24.5.0] - 2025-10-15
### Changed
- Updated SDK dependency from 24.4.0 to 24.5.0
```

## Troubleshooting

### Workflow doesn't appear in Actions
- Make sure `.github/workflows/dependabot-changelog.yml` is pushed to the repository
- Check workflow syntax with a YAML validator

### Workflow runs but doesn't update CHANGELOG
- Check the workflow logs for error messages
- Verify CHANGELOG.md files exist in adapter directories
- Ensure the version format in build.gradle.kts matches the pattern

### Changes aren't committed
- Check if there are actual version changes
- Verify git config in the workflow step
- Check repository permissions for GitHub Actions

### Can't run workflow manually
- Ensure you have write access to the repository
- Check if workflow_dispatch trigger is properly configured
- Try refreshing the Actions page

## Next Steps

After successful testing:

1. Merge the Dependabot configuration PR
2. Wait for Dependabot's first scheduled run (03:00 UTC daily)
3. Or manually trigger Dependabot: Settings → Security → Dependabot → "Check for updates"
4. Review the first Dependabot PR to ensure everything works correctly

## Monitoring

To monitor Dependabot activity:

1. **Check logs**: Settings → Code security → Dependabot
2. **View scheduled runs**: Actions → Filters → "workflow:dependabot"
3. **List open PRs**: `gh pr list --label dependencies`
4. **Check adapter status**:
   ```bash
   gh pr list --label adapter --json number,title,url,updatedAt
   ```
