## Task: Fix test failures

### Adapters to fix:
{{ADAPTERS}}

### Test failures by adapter:
{{ISSUES}}

### Release Notes URLs:
{{RELEASE_NOTES}}

### Instructions:
1. Read the test failures above carefully
2. For each adapter, fetch its Release Notes URL to understand API changes
3. Fix failing tests in each adapter's src/test/ directory
4. Run `./gradlew :adapter:<adapter>:testProductionReleaseUnitTest` for each adapter to verify
5. Run `./gradlew ktlintFormat` to format code
6. Commit and push changes for ALL adapters in ONE commit:
   ```bash
   rm -f deprecated_warnings.txt build_output.log test_failures.txt
   git add adapter/*/
   git commit -m "fix: fix unit tests in <list adapters>"
   git push
   ```

### IMPORTANT:
- Fix ALL listed adapters in this task
- Do not change core SDK
- Focus on updating tests to match new API, not changing adapter implementation
- If you cannot fix a test, report what you found and continue with others
- Do NOT add "Generated with Claude Code" footer to commits
- Do NOT add "Co-Authored-By" line to commits
- Write descriptive commit message explaining what tests were fixed
