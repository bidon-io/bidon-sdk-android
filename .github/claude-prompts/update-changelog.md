## Task: Update changelog for adapter dependency updates

### Adapters to update:
{{ADAPTERS}}

### Version changes:
{{VERSION_CHANGES}}

### Release Notes URLs:
{{RELEASE_NOTES}}

### AI fixes applied (if any):
{{AI_FIXES}}

### Instructions:

1. **Fetch and summarize release notes:**
   - For each adapter, fetch its Release Notes URL using WebFetch
   - Extract key changes relevant to the specific version being updated
   - Post a PR comment with release notes summary:
     ```markdown
     ## Release Notes Summary

     ### AdapterName (OLD_VERSION -> NEW_VERSION)
     - Key change 1
     - Key change 2
     - ...

     [Full release notes](URL)
     ```
   - If fetch fails: note in comment that release notes were unavailable, but continue with other tasks

2. **Update build.gradle.kts:**
   - For each adapter, set `val adapterMinor = 0` in `adapter/{name}/build.gradle.kts`

3. **Update CHANGELOG.md:**
   - For each adapter, update `adapter/{name}/CHANGELOG.md`
   - First, check git diff for the adapter to understand what was actually changed:
     ```bash
     git diff origin/develop -- adapter/{name}/
     ```
   - Add entry after `## [Unreleased]` section with format:
     ```markdown
     ## [{NEW_VERSION}.0] - {TODAY_DATE}
     ### Changed
     - Updated SDK dependency from {OLD_VERSION} to {NEW_VERSION}
     ```
   - **If AI fixed deprecated code**: Add SPECIFIC entries describing what was migrated, NOT generic "Migrated deprecated APIs". Examples:
     - `- Migrated from deprecated \`isCached()\` to \`isReady()\` API`
     - `- Replaced deprecated \`setUserId()\` with \`setUserConsent()\``
     - `- Updated ad loading to use new \`load(AdRequest)\` signature`
   - **If AI fixed build errors**: Describe the specific fix:
     - `- Fixed compilation error due to removed \`AdListener.onAdClicked()\` method`
     - `- Updated constructor parameters for \`BannerAdView\` class`
   - **If AI fixed tests**: Describe what was fixed:
     - `- Updated mock setup for changed \`AdCallback\` interface`
     - `- Fixed test assertions for new response format`

4. **Commit and push:**
   ```bash
   git add adapter/*/CHANGELOG.md adapter/*/build.gradle.kts
   git commit -m "chore: update changelogs for dependency updates"
   git push
   ```

### IMPORTANT:
- Process ALL listed adapters
- Keep the existing CHANGELOG format (Keep a Changelog)
- Date format: YYYY-MM-DD
- Do NOT add "Generated with Claude Code" footer to commits
- Do NOT add "Co-Authored-By" line to commits
- NEVER use generic changelog entries like "Migrated deprecated APIs" - always check the actual code changes and describe SPECIFICALLY what was migrated or fixed
