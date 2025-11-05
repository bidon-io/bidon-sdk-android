# Dependabot Quick Start Guide

This is a quick reference for the Dependabot setup for adapter dependency management.

## 🎯 What It Does

Automatically updates adapter SDK dependencies, creates PRs, runs tests, and publishes to Artifactory.

## 🔄 Complete Flow

```
Day 1 (03:00 UTC):
  ├─ Dependabot checks for updates
  ├─ Finds admob 24.5.0 → 24.6.0
  └─ Creates PR to develop branch

Automatic (when PR created):
  ├─ Updates adapter/admob/CHANGELOG.md
  ├─ Runs adapter-checks:
  │   ├─ Build
  │   ├─ Deprecated warnings check
  │   └─ Unit tests
  └─ Assigns reviewers: da2gl, copilot

Manual Review:
  ├─ Review PR
  └─ Approve & Merge to develop

Automatic (after merge):
  ├─ Publish to bidon-private ✅
  ├─ Publish to bidon (public) ✅
  └─ Send Slack notifications
```

## 📋 Key Files

| File | Purpose |
|------|---------|
| `.github/dependabot.yml` | Main config - 22 adapters monitored |
| `.github/workflows/dependabot-changelog.yml` | Auto-updates CHANGELOG.md + release notes links |
| `.github/workflows/adapter-checks.yml` | Runs build & tests |
| `.github/workflows/publish-adapter-on-merge.yml` | Auto-publishes to Artifactory |
| `adapter/*/CHANGELOG.md` | Per-adapter changelog with vendor links |

## ⚙️ Configuration

**Schedule**: Daily at 03:00 UTC
**Target Branch**: `develop`
**Reviewers**: da2gl, copilot
**Labels**: `dependencies`, `adapter` (or `google-adapter`)
**Max Open PRs**: 10 per adapter

## 🚀 What Happens After Merge

1. **bidon-private** - Published first (internal testing)
2. **bidon** - Published second (public production)
3. **Slack** - Notifications sent for both

## 📦 Adding New Adapter

When adding `adapter/new-adapter`, update `.github/dependabot.yml`:

```yaml
- package-ecosystem: "gradle"
  directory: "/adapter/new-adapter"
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
    prefix: "chore(new-adapter)"
    include: "scope"
  labels:
    - "dependencies"
    - "adapter"
```

And create `adapter/new-adapter/CHANGELOG.md`:
```markdown
# Changelog

All notable changes to the NewAdapter adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]
```

## ⚠️ Important Notes

### Multiple Updates
If Dependabot updates an existing PR (e.g., 24.5.0 → 24.6.0 → 24.7.0), CHANGELOG will have multiple entries. Clean up before merging to keep only the final version.

### Google Adapters
`admob` and `gam` are labeled with `google-adapter` for easier identification. They often need to be updated together but will create separate PRs.

### Publication
Publication happens **automatically** after merge. No manual action needed unless there's a failure.

## 🔧 Troubleshooting

**PR not created**: Check Dependabot logs in Settings → Security → Dependabot
**CHANGELOG not updated**: Check workflow logs in Actions tab
**Publication failed**: Verify Artifactory credentials in repository secrets
**Slack not working**: Check `SLACK_WEBHOOK` secret

## 📊 Monitoring

- **Dependabot**: Settings → Security → Dependabot
- **Workflows**: Actions → Filter by workflow name
- **Publications**: Actions → "Publish Adapter After Dependabot Merge"
- **Slack**: #bidon-releases (or your configured channel)

## 🔗 Full Documentation

See [DEPENDABOT.md](DEPENDABOT.md) for complete documentation.

## 🧪 Testing

See [TESTING_DEPENDABOT.md](TESTING_DEPENDABOT.md) for testing instructions.

## 📝 Current Adapters (22)

admob, amazon, applovin, appsflyer, bidmachine, bigoads, chartboost, dtexchange, fyber, gam, inmobi, ironsource, meta, mintegral, mobilefuse, moloco, startio, taurusx, unityads, vkads, vungle, yandex
