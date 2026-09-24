# Changelog

All notable changes to this skill. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versioning: [SemVer](https://semver.org/).

## [1.0.0] — 2026-09-25

First public release.

### Added
- **Which SDK** decision rules: new apps on GMA Next-Gen; legacy apps migrate in a dedicated release; legacy 25.x deprecation 2027-06-30 / sunset 2028-06-30; Next-Gen + mediation exclusions.
- **Lifecycle rules learned in production** — 14 rules, each paired with the bug it prevents (mediation-readiness barrier, single fullscreen owner, lost-callback recovery via RESUMED host, cancellation-safe reservations, show only on RESUMED host, STARTED-vs-RESUMED in `ProcessLifecycleOwner.onStart`, process-scoped cold-start guard, caching late fills, long-lived contextual preloads, Compose banner re-parent crash, native media binding/ownership, UMP blocker clearing, reward-exactly-once, premium tri-state).
- **App-level release gates**: configuration-cache-safe Gradle validation of production ad IDs, AAB test-ID scan, telemetry scope, no-ad-zone blocklists.
- Six new red flags for the bug patterns above.
- `snippets/FullScreenAdCoordinator.kt` (fullscreen ownership + `releaseWhenResumed` / `awaitResumed`).
- Claude Code plugin + marketplace manifests.

### Changed
- `snippets/MobileAdsInit.kt` rewritten as a shared, bounded readiness barrier that signals from the `initialize()` completion listener (the previous version signalled after `initialize()` returned — before adapters were ready).
- Versions: `play-services-ads` 25.5.0, Meta adapter 6.22.0.0, GMA Next-Gen 1.4.0.
- Retry guidance: error-aware bounded retry with a no-fill cooldown (replaces blanket exponential retry); refill after dismiss only when another show is likely.

### Removed
- App-specific identifiers from snippets (placeholder IDs, generic `com.example.app` package).
