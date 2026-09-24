# AdMob Android Integration — a Claude Code skill

A production-tested **Claude Code skill** for shipping, auditing and fixing **Google AdMob on Android**:
legacy `play-services-ads` **and** the **GMA Next-Gen SDK**, **Meta Audience Network** mediation,
**UMP consent**, and all five ad formats — banner, interstitial, rewarded, app open and native.

It is not a tutorial copied from the docs. Every rule in the **Lifecycle rules** and **Red flags**
sections exists because the bug it prevents shipped (or nearly shipped) in a real app with real
traffic, and was caught by code review, adversarial review, automated PR review or unit tests.

> Once installed, Claude loads the skill automatically whenever you work on AdMob code in any
> Android project — "add a rewarded ad", "why is my app-open show rate 20%?", "audit our ads",
> "migrate from Appodeal to AdMob", "should we move to Next-Gen?".

![Claude Code](https://img.shields.io/badge/Claude%20Code-skill-8A63D2)
![Android](https://img.shields.io/badge/Android-Kotlin%20%7C%20Compose-3DDC84)
![GMA](https://img.shields.io/badge/GMA-25.5.0%20%7C%20Next--Gen%201.4.0-4285F4)
![License](https://img.shields.io/badge/license-MIT-blue)

---

## Contents

- [What the skill covers](#what-the-skill-covers)
- [Install](#install)
- [Usage](#usage)
- [What's inside](#whats-inside)
- [Versions verified](#versions-verified)
- [Companion skills](#companion-skills)
- [Keeping it current](#keeping-it-current)
- [Contributing](#contributing)
- [License & disclaimer](#license--disclaimer)

## What the skill covers

| Area | What you get |
|---|---|
| **SDK choice** | When to start on GMA Next-Gen vs stay on legacy; legacy deprecation (2027-06-30) and sunset (2028-06-30); Next-Gen + mediation dependency exclusions and background-thread callbacks |
| **GMA Next-Gen** | Init with `InitializationConfig`, preloaders (`InterstitialAdPreloader`, `AppOpenAdPreloader`, `RewardedAdPreloader`), banner/native APIs and deprecations, UMP rules — verified against Google's docs |
| **Setup** | Gradle, manifest (`APPLICATION_ID`, `AD_ID`, optimize-initialization flags), ProGuard/R8, test IDs |
| **Consent** | UMP flow order, cached-consent fast path, consent form blocking fullscreen ads safely |
| **Mediation** | Meta Audience Network bidding: console setup, partner lists, native `MediaView` requirement, `app-ads.txt` |
| **Formats** | Banner (anchored adaptive, Compose), interstitial, rewarded, app open (cold + warm), native (layouts, eCPM checklist) |
| **Lifecycle rules** | 14 production rules: one mediation-readiness barrier, one fullscreen owner, lost-callback recovery, cancellation-safe reservations, STARTED vs RESUMED, once-per-process cold start, late fills, Compose banner re-parent crash, native ownership, reward-exactly-once, premium tri-state |
| **Show rate** | Request discipline: why "preload everything + reload after every show + timer retries" kills show rate, and what to do instead |
| **Policy** | Interstitial placement rules (one per two user actions), no-ad zones, app-open on first launch, disruptive-ads risks |
| **Release gates** | Gradle validation of production ad IDs (configuration-cache safe), AAB test-ID scan, telemetry scope, no-ad-zone blocklists |
| **Audit** | 22-point checklist + a red-flags table for reviewing existing ad code |
| **Migration** | Mechanical steps from Appodeal / AppLovin MAX / ironSource to AdMob + Meta |

## Install

The skill is a single folder: [`skills/admob-android-integration`](skills/admob-android-integration)
(`SKILL.md` + `snippets/`). Pick one method.

### Option A — Claude Code plugin (recommended)

Inside Claude Code:

```text
/plugin marketplace add vmobifystudio/claude-admob-android-skill
/plugin install admob-android-integration@admob-android
```

Updates: `/plugin marketplace update admob-android`.

### Option B — Personal skill, available in every project

```bash
git clone https://github.com/vmobifystudio/claude-admob-android-skill.git
mkdir -p ~/.claude/skills
cp -R claude-admob-android-skill/skills/admob-android-integration ~/.claude/skills/
```

Prefer a symlink if you want `git pull` to update it (and to edit/version it yourself):

```bash
ln -s "$(pwd)/claude-admob-android-skill/skills/admob-android-integration" ~/.claude/skills/admob-android-integration
```

### Option C — One project only (shared with your team via git)

```bash
mkdir -p .claude/skills
cp -R /path/to/claude-admob-android-skill/skills/admob-android-integration .claude/skills/
```

### Other agents

The skill follows the [Agent Skills](https://agentskills.io/specification) format (`SKILL.md` with
YAML frontmatter), so agents that read `~/.agents/skills/` (e.g. Codex, Gemini CLI, Copilot CLI)
can use the same folder:

```bash
mkdir -p ~/.agents/skills && cp -R skills/admob-android-integration ~/.agents/skills/
```

### Verify

Start Claude Code in an Android project and ask *"What skills do you have for AdMob?"* — it should
list `admob-android-integration`. The skill loads automatically when a task matches its description.

## Usage

Just describe the task; Claude decides when to load the skill. Examples:

- *"Add AdMob with Meta mediation to this app — banner on Home, interstitial after export, rewarded to remove the watermark."*
- *"Audit our ad implementation against Google's guidelines and our show rate."*
- *"App-open matches 97% but only 20% show. Why?"*
- *"We're on play-services-ads. Should we migrate to GMA Next-Gen now?"*
- *"Migrate this app from Appodeal to AdMob."*
- *"Review this PR's ad changes for policy and lifecycle bugs."*

## What's inside

```
.claude-plugin/
  plugin.json              # plugin metadata
  marketplace.json         # lets /plugin marketplace add find it
skills/admob-android-integration/
  SKILL.md                 # the skill (frontmatter + reference)
  snippets/                # legacy play-services-ads reference code (Kotlin + XML)
```

### `SKILL.md` sections

| Section | Purpose |
|---|---|
| Current versions · Which SDK | Version table with sources; decision rules for Next-Gen vs legacy |
| GMA Next-Gen SDK | Next-Gen-specific rules that override legacy patterns |
| Request discipline · Native eCPM checklist | Show-rate and eCPM levers |
| Gradle · Manifest · Architecture · Init · UMP | Setup |
| Ad format cheat sheet | Console type, class, expiry, cap per format |
| Crash prevention — 10 commandments | Core SDK-level rules |
| **Lifecycle rules learned in production** | 14 rules, each paired with the bug it prevents |
| **App-level release gates** | What to verify before shipping |
| Placement strategy · Rewarded gate · Meta mediation | Revenue design |
| ProGuard · Native layout requirements | Build + layout rules |
| Migration from Appodeal · Ad units to create | Mechanical steps |
| 22-point audit checklist · Red flags · Common pitfalls | Review tooling |
| Test ad unit IDs · Official sources | References |

### Snippets (legacy `play-services-ads`, Kotlin)

Reference implementations to adapt — package `com.example.app.ads`. Do not paste them into a
Next-Gen app.

| File | What it is |
|---|---|
| `MobileAdsInit.kt` | Shared, bounded mediation-readiness barrier (signals ready from the completion listener) |
| `FullScreenAdCoordinator.kt` | One fullscreen owner + `releaseWhenResumed` / `awaitResumed` helpers (production-proven) |
| `AppOpenAdManager.kt` | Real `AppOpenAd` with 4-hour expiry and process-lifecycle warm starts |
| `InterstitialAdManager.kt`, `InterstitialPool.kt` | Interstitial manager + pool keyed by ad unit |
| `RewardedAd.kt` | Rewarded singleton with 1-hour expiry and mediation grace window |
| `BannerAd.kt` | Anchored adaptive banner with lifecycle wiring |
| `AdMobNativeLoader.kt` | `AdLoader` + `populateNativeAdView` (media binding, `setNativeAd` last) |
| `admob_native_large.xml`, `admob_native_small.xml` | Native layouts (use `com.intuit.sdp` dimens — swap for your own) |
| `AdUnits.kt` | Debug→test IDs / release→production IDs switch (placeholders) |
| `AdsSuppression.kt`, `AdsExt.kt`, `EnumAdType.kt` | Ad-free window, show helpers, format enum |

## Versions verified

Last verified **2026-09-24** against Google's release notes:

| Component | Version |
|---|---|
| GMA Next-Gen `ads-mobile-sdk` | 1.4.0 (GA since 1.0.0, 2026-04-14) |
| `play-services-ads` (legacy) | 25.5.0 (maintenance mode; deprecated 2027-06-30) |
| Meta adapter `com.google.ads.mediation:facebook` | 6.22.0.0 |
| UMP `user-messaging-platform` | 4.0.0 |

Versions move monthly — the skill tells Claude to re-verify against the linked sources before
pinning anything.

## Companion skills

Google publishes official **GMA Next-Gen** agent skills
([agent-skills page](https://developers.google.com/admob/android/next-gen/agent-skills),
[google/skills](https://github.com/google/skills)): `google-mobile-ads-get-started`, `-banner`,
`-interstitial`, `-rewarded`, `-validate` and `-android-migrate-to-next-gen`. They cover Next-Gen API
usage and a validation rubric. This skill complements them with what they don't cover: legacy SDK
apps, mediation, consent, app-level lifecycle/ownership rules, policy, request discipline and
release gates. Installing both is recommended.

## Keeping it current

- Versions and dates are tracked in [`CHANGELOG.md`](CHANGELOG.md) using SemVer.
- When Google ships a new SDK/adapter version or a guide changes, update the version table and the
  affected rule, bump the version in `.claude-plugin/plugin.json` and `marketplace.json`, and add a
  changelog entry.
- New lifecycle rules should follow the existing pattern: **rule + the concrete bug it prevents**.

## Contributing

Issues and PRs welcome — especially real-world failure modes with a reproducible scenario.
Please keep rules evidence-based (link the Google doc or describe the observed bug) and never
include real ad unit IDs, publisher IDs or app-specific code.

## License & disclaimer

[MIT](LICENSE). Not affiliated with or endorsed by Google, AdMob, Meta or Anthropic. AdMob, Google
Mobile Ads and Meta Audience Network are trademarks of their respective owners. Always verify
policy-sensitive decisions against the current official documentation.
