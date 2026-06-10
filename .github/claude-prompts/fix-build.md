## Task: Fix build errors

### Adapters to fix:
{{ADAPTERS}}

### Build errors by adapter:
{{ISSUES}}

### Release Notes URLs:
{{RELEASE_NOTES}}

### Instructions:
1. Read the build errors above carefully
2. For each adapter, fetch its Release Notes URL to find API changes and migration guide
3. Fix build errors in each adapter directory
4. Run `./gradlew :adapter:<adapter>:assembleProductionRelease` for each adapter to verify fix
5. Run `./gradlew ktlintFormat` to format code
6. Commit and push changes for ALL adapters in ONE commit:
   ```bash
   rm -f deprecated_warnings.txt build_output.log
   git add adapter/*/
   git commit -m "fix: resolve build errors in <list adapters>"
   git push
   ```

### IMPORTANT:
- Fix ALL listed adapters in this task
- Do not change core SDK
- Focus on fixing compilation errors
- If you cannot fix an adapter, report what you found and continue with others
- Do NOT add "Generated with Claude Code" footer to commits
- Do NOT add "Co-Authored-By" line to commits
- Write descriptive commit message explaining what was fixed
