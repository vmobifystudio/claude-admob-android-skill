---
name: admob-android-integration
description: Use when integrating AdMob on Android (legacy play-services-ads or the GMA Next-Gen SDK), migrating from another ad SDK (Appodeal, AppLovin MAX, ironSource, etc.) to AdMob, auditing an AdMob implementation against Google's official guidelines, or troubleshooting crashes/show rate/revenue problems in existing ad code
---

# AdMob Android Integration

## Overview

End-to-end reference for shipping a production-grade AdMob implementation on Android, audited against Google's six official guides (banner, interstitial, rewarded, app open, native, optimize-initialization).

**Two SDKs exist. Decide which one the app uses before applying anything below.** Dependency
`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk` = Next-Gen → start with the
**GMA Next-Gen SDK** section, which overrides the legacy patterns wherever they differ.
Dependency `com.google.android.gms:play-services-ads` = legacy → the rest of this file applies.

Covers: current SDK versions, Gradle setup, manifest wiring, UMP consent integration, Meta Audience Network mediation, all 5 ad formats with correct lifecycle, frequency capping, crash prevention, native ad layouts, migration from Appodeal, and a 22-point audit checklist.

## When to use

- Starting fresh AdMob integration on an Android app
- Migrating from Appodeal / AppLovin MAX / ironSource / any other ad SDK
- Auditing existing ad code for crashes, memory leaks, or low show rate
- Wiring rewarded ads / app open ads / native ads with correct lifecycle
- Setting up UMP consent + Meta mediation
- Replacing MREC (AdMob no longer exposes it as a distinct format)

## Current versions (verify before starting)

Check the sources below and update these numbers — they change monthly:

| Component | Version | Source |
|---|---|---|
| **GMA Next-Gen `ads-mobile-sdk`** | **1.4.0 (2026-08-20)**, GA since 1.0.0 (2026-04-14) — verified 2026-09-24 | https://developers.google.com/admob/android/next-gen/rel-notes |
| `play-services-ads` (legacy) | 25.5.0 (2026-09-17) — verified 2026-09-24 | https://developers.google.com/admob/android/rel-notes |
| Meta adapter (both SDKs) | 6.22.0.0 → pulls Audience Network 6.22.0. 6.22.0.1 is "In progress" in Google's changelog — don't adopt until it is on Maven | https://developers.google.com/admob/android/mediation/meta |
| UMP SDK | 4.0.0 (2025-10-31), still latest 2026-09-24 | https://developers.google.com/admob/android/privacy/release-notes |
| min SDK | legacy 23 · Next-Gen 24 | |
| compile SDK | 35+ | (GMA requirement) |

~~Next-Gen is beta, do not ship~~ — **stale since 2026-04-14.** Next-Gen went GA with 1.0.0 and
Google now calls it the preferred Android SDK; the legacy SDK is in maintenance mode.

**Which SDK:**
- **New app → Next-Gen.**
- **Existing legacy app → stay on legacy for fixes; migrate in its own PR/release.** Never in the
  same release as ad-behaviour fixes — you can't attribute the revenue change. Legacy 25.x is
  **deprecated 2027-06-30, sunset 2028-06-30** (https://developers.google.com/admob/android/deprecation);
  migrate well before that.
- **Next-Gen + mediation:** adapters still depend on legacy classes. Exclude
  `play-services-ads` and `play-services-ads-lite` globally or the build gets duplicate classes.
  Next-Gen callbacks arrive on background threads — every UI/state touch in callbacks must hop to Main.
- Use the `google-mobile-ads-*` skills (Next-Gen) and `google-mobile-ads-validate` for the audit rubric;
  this skill covers what they don't (app-level lifecycle, mediation, policy, release gates).

## GMA Next-Gen SDK (verified against Google's docs 2026-09-24)

Next-Gen differs from legacy in ways that make several legacy rules below wrong for it:

| Topic | Next-Gen rule | Source |
|---|---|---|
| Init | `MobileAds.initialize(context, InitializationConfig.Builder(appId).build())` on a **background thread**, after UMP `canRequestAds()`. With mediation, wait for the adapter-completion listener before loading. minSdk 24, compileSdk 35, Kotlin 1.9+. | next-gen/quick-start |
| Callbacks | May arrive on a **background thread** — hop to Main before touching UI/state. | next-gen guides |
| Full-screen loading | **Preloaders are the documented primary path**: `InterstitialAdPreloader` / `AppOpenAdPreloader` / `RewardedAdPreloader` `.start(preloadId, PreloadConfiguration(request, bufferSize))`, then `pollAd(preloadId)` at show time. The SDK handles expiry ("refreshes ads before expiring"), managed retries and refill after each poll. Default buffer 2 (cap 15); app-wide max 6 preloaded ads. `start()` returns false if that ID is already running; `destroy(id)` stops it. **Never call `start()`/`pollAd()` inside `PreloadCallback`.** Manual single-ad load still exists ("Load a single … ad"). | next-gen/interstitial, /app-open, /rewarded, preloader reference |
| Banner | `AdView(context).loadAd(BannerAdRequest.Builder(unit, AdSize.getLargeAnchoredAdaptiveBannerAdSize(ctx, widthDp)).build(), cb)`. `BannerAd.load()` / `getView()` **deprecated since 0.22.0**; the other three anchored-adaptive helpers deprecated since 0.24. Refresh is configured in the **AdMob console** (Google-optimized or 30–150 s) and happens **only while visible**; with refresh on, don't re-request after a failure. `destroy()` the AdView when done (it also stops the view's own load retries). | next-gen/banner, AdMob help 3245199 |
| Native | `NativeAdLoader.load(NativeAdRequest.Builder(unit, listOf(NativeAdType.NATIVE))…, cb)`; register with `NativeAdView.registerNativeAd(ad, mediaView)`. Native never auto-refreshes. "Only cache the ads that are immediately visible"; "when precaching, clear your cache and reload after one hour"; `nativeAd.destroy()` when no longer used. `NativeAdView.destroy()` unbinds the view but does **not** destroy the `NativeAd` (bytecode of 1.4.0). | next-gen/native |
| App Open | "Don't show an app open ad on the very first app start." Expires 4 h after load (the preloader handles it). | next-gen/app-open |
| UMP | `requestConsentInfoUpdate` "on every app launch" (a launch — not every Activity recreation); check `canRequestAds()` right after it; show privacy options when status is REQUIRED. | next-gen/privacy |

### Request discipline (protects show rate)

Show rate = impressions ÷ matched requests. Every request that cannot become an impression lowers
it. The legacy advice "preload everything at startup + reload after every show + retry on a timer"
is the main way apps inflate requests. Audit for these, in this order:

1. **One request per screen visit** for inline ads. Reuse instead: keep a banner `AdView` per
   placement for the Activity and re-attach it on revisit (console refresh keeps it fresh); keep one
   native ad and replace it only after it has been seen ~60 s or is ~55 min old.
2. **All full-screen formats started at launch.** Start each preloader where it can first be shown
   (interstitial when the user reaches the step before the transition; rewarded where it is
   offered; App Open on first backgrounding) with bufferSize 1, and `pollAd` only when showing.
3. **Timer retries** (e.g. `postDelayed(retry, 60_000)` forever, also while backgrounded). Replace
   with demand-triggered requests + exponential backoff (30 s → … → 15 min cap), or let the
   preloader's managed retries do it.
4. **Reload immediately after every show** when no further show is likely.
5. **Re-requests caused by layout jitter**: size/height changes, rotation, list items scrolling in
   and out, recomposition keys that include volatile values.
6. **Consent/privacy re-checks that destroy and reload everything**, and UMP re-queried on every
   rotation.
7. **Empty reserved ad boxes** after no-fill (also a TalkBack false "Advertisement").

### Native eCPM checklist (Next-Gen)

- Register a `MediaView` >= 120x120dp (video eligibility) and set `mediaContent` before `registerNativeAd` (call it LAST).
- Show AND register the recommended assets, not only the required ones: body (<= 90 chars untruncated),
  advertiser (25), star rating/store/price where supplied. Required: badge, headline (25), CTA (15), icon if supplied.
- `setMediaAspectRatio(ANY)` unless the layout truly cannot take other ratios; render with `FIT_CENTER`
  (image may be cropped only up to 10% of width, never stretched).
- `setVideoOptions(startMuted = true)`; set `setAdChoicesPlacement` to the corner you reserved.
- If the real creative doesn't fit, hide optional assets one by one - never clip, never truncate below policy counts.
- Mount only where it is on screen (viewability drives native eCPM); non-clickable background.
- Dev: Native Video demo unit `ca-app-pub-3940256099942544/1044960115` to exercise video.
- Console: native unit allows image + video; mediation/bidding adapters actually present in the build.

Interstitial placement policy (AdMob help 6201362, read 2026-09-24): not on app load/exit, only at
logical breaks, and "no more than one interstitial ad after every two user actions". Console
frequency caps are time-based — they do not enforce the per-action rule by themselves.

## Gradle setup

```gradle
dependencies {
    // Google Mobile Ads (AdMob) — primary
    implementation 'com.google.android.gms:play-services-ads:25.5.0'

    // Meta Audience Network via AdMob mediation
    implementation 'com.google.ads.mediation:facebook:6.22.0.0'

    // UMP consent
    implementation 'com.google.android.ump:user-messaging-platform:4.0.0'

    // lifecycle-process required for App Open manager
    implementation 'androidx.lifecycle:lifecycle-process:2.9.2'
}
```

`settings.gradle` must have `google()` and `mavenCentral()` repositories.

## AndroidManifest.xml

```xml
<manifest>
    <!-- Required since GMA reads Advertising ID on Android 13+ -->
    <uses-permission android:name="com.google.android.gms.permission.AD_ID" />

    <application>
        <!-- App ID (not ad unit ID) — get from AdMob Console → your app -->
        <meta-data
            android:name="com.google.android.gms.ads.APPLICATION_ID"
            android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />

        <!-- Explicit opt-in. Default-true on GMA 24.0+ but safer to declare. -->
        <meta-data android:name="com.google.android.gms.ads.flag.OPTIMIZE_INITIALIZATION"
            android:value="true" />
        <meta-data android:name="com.google.android.gms.ads.flag.OPTIMIZE_AD_LOADING"
            android:value="true" />
    </application>
</manifest>
```

## Architecture (one file per ad type)

```
app/src/main/java/.../ads/
├── AdUnits.kt              — ad unit IDs, test vs prod switched by BuildConfig.DEBUG
├── MobileAdsInit.kt        — shared mediation-readiness barrier (awaitReady before every load)
├── BannerAd.kt             — AdView + anchored adaptive + lifecycle
├── InterstitialAdManager.kt — one manager per ad unit, preload pool
├── InterstitialPool.kt     — cache of managers keyed by ad unit
├── RewardedAd.kt           — singleton with 1-hour expiration + mediation grace
├── AppOpenAdManager.kt     — real AppOpenAd with 4-hour expiration
├── AdMobNativeLoader.kt    — AdLoader + NativeAdView populate function
├── AdsSuppression.kt       — "remove ads for 1 hour" state (SharedPreferences)
└── AdsExt.kt               — showAdList/showAdAndGo helpers + 45s global cap
```

## Initialization pattern (correct)

```kotlin
object MobileAdsInit {
    private val initialized = AtomicBoolean(false)

    fun initialize(activity: Activity, onInitialized: (() -> Unit)? = null) {
        if (initialized.getAndSet(true)) { onInitialized?.invoke(); return }

        MobileAds.setRequestConfiguration(RequestConfiguration.Builder().build())

        // Docs: "Call MobileAds.initialize() on a background thread."
        // initialize() RETURNS before adapters are ready. Signal readiness ONLY from the
        // completion listener, or every format's first request goes out before Meta can bid.
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(activity.applicationContext) { status ->
                // log status.adapterStatusMap here
                CoroutineScope(Dispatchers.Main).launch { onInitialized?.invoke() }
            }
        }
    }

    fun isInitialized(): Boolean = initialized.get()
}
```

**Call once from MainActivity AFTER UMP consent resolves.** Never from Application.onCreate — consent isn't decided yet.

## UMP consent flow (must precede ad init)

```kotlin
val ump = UserMessagingPlatform.getConsentInformation(this)
ump.requestConsentInfoUpdate(this, ConsentRequestParameters.Builder().build(), {
    UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { _ ->
        if (ump.canRequestAds()) lifecycleScope.launch { MobileAdsInit.awaitReady(this@MainActivity) }
    }
}, { /* error */ })
// Cached consent: start in parallel with the network round-trip (Google's pattern).
if (ump.canRequestAds()) lifecycleScope.launch { MobileAdsInit.awaitReady(this@MainActivity) }
// Then preload contextually (where each format is first shown), not "all at launch".
```

## Ad format cheat sheet

| Format | AdMob Console type | Class | Expires | Our cap |
|---|---|---|---|---|
| **Banner** | Banner → Anchored Adaptive | `BannerAd` → `AdView` with `getLargeAnchoredAdaptiveBannerAdSize` | auto-refresh | N/A |
| **Interstitial** | Interstitial | `InterstitialAdManager` → `InterstitialAd.load` | 1 hr | 45s global |
| **Rewarded** | Rewarded | `RewardedAd` → `GmsRewardedAd.load` | 1 hr | user-triggered |
| **App Open** | App Open | `AppOpenAdManager` → `AppOpenAd.load` | 4 hr | shared 45s |
| **Native** | Native Advanced | `AdMobNativeLoader` → `AdLoader.Builder.forNativeAd` | — | N/A |
| ~~MREC~~ | Banner + MEDIUM_RECTANGLE, or use Native instead | — | — | — |

Note: AdMob no longer shows MREC as a distinct format. Either create a Banner unit + request `AdSize.MEDIUM_RECTANGLE`, or drop MREC entirely and use Native (better eCPM on content screens).

## Crash prevention — the 10 commandments

These are the patterns that prevent the vast majority of AdMob crashes and leaks:

1. **Null the ad ref** inside `onAdDismissedFullScreenContent` AND `onAdFailedToShowFullScreenContent`. Docs: "so you don't show the ad a second time."
2. **Set `FullScreenContentCallback` ONCE** at load time (in `onAdLoaded`), not per-show.
3. **Set `isShowingAd = true` BEFORE `appOpenAd.show()`**, not in the callback. Otherwise `Application.onActivityStarted` captures the ad-framework's Activity as `currentActivity`.
4. **Check `isFinishing && !isDestroyed`** before `ad.show(activity)`.
5. **Destroy NativeAd** if it loads after the Activity is destroyed — use `WeakReference<Activity>` in `forNativeAd` callback.
6. **Destroy NativeAd in `Activity.onDestroy`** — wire via a `MutableSet<FrameLayout>` in your BaseActivity.
7. **Call `setNativeAd(ad)` LAST** in populate function, after all asset views registered.
8. **Bounded, error-aware retry** on `onAdFailedToLoad`: a short backoff (1s → 2s → 4s, max 3) only for transient errors; **no-fill (`ERROR_CODE_NO_FILL` / `ERROR_CODE_MEDIATION_NO_FILL`) → cooldown (≥30 s) and wait for the next natural opportunity**, never an immediate retry. Put a deadline on each load (e.g. 12 s). See Request discipline.
9. **Check ad expiration** on every show attempt: Rewarded = 1hr, App Open = 4hr.
10. **Mediation grace period** (~400ms) on rewarded dismiss: Meta/AppLovin can fire `onUserEarnedReward` AFTER `onAdDismissedFullScreenContent`. Don't decide "user skipped" until after the grace window.

## Lifecycle rules learned in production (2026-09, verified by review + tests)

Each rule prevents a bug that shipped or nearly shipped. Apply to both SDKs.

| Rule | Bug it prevents |
|---|---|
| One **mediation-readiness barrier** (shared deferred, bounded ~15 s, retried after timeout); every format awaits it before any load | Requests go out before Meta's adapter is ready → no Meta bids; a second caller told "ready" early |
| One **fullscreen owner** across interstitial / app-open / rewarded, also blocked by an open UMP form and paywall routes. Non-ad fullscreen UI (automatic paywall) checks "screen taken" = ad owner **or** consent form | Two fullscreens stacked; paywall opening under the consent form |
| **Lost terminal callback** (all fullscreen formats): bound the caller's wait (~60 s — long enough for any real ad's close button, not a UX pace) but release ownership only when the host Activity is **RESUMED** again (the ad is its own Activity) **or destroyed**, then settle once (`releaseWhenResumed` in snippets) | Never releasing → every fullscreen format dead for the process; releasing on a timer → overlap |
| **Cancellation between reserving and `show()`** (`withContext(Main)` dispatch window): track `showInvoked`; if false in the `CancellationException` path, release and return the ad to the cache | Reservation leaked forever → all fullscreen ads dead |
| **Show only on a RESUMED host** (interstitial and rewarded too) | A retry-load finishing after Home shows an ad over another app (API 24–28) |
| `ProcessLifecycleOwner.onStart` runs while the Activity is only **STARTED**; if you gate warm app-open on RESUMED, **await** it (bounded), don't test it | Warm app-open never shows when an ad is already cached |
| Cold-start app-open/init guard is **process-scoped** (companion/object flag). `remember` replays after config change; `rememberSaveable` survives process death | Unexpected app-open on dark-mode/locale/fold (policy); new process never initialises ads |
| Late fill after the caller's load timeout: **cache it** if the load generation is still valid | Matched ads thrown away → lower show rate |
| Contextual preloads (rewarded near where it's offered) launched in a **long-lived scope**, not a screen effect | Navigation cancels the request and drops the fill |
| Compose banner: **never remove/re-add an `AndroidView` around a remembered `AdView`** — keep it composed and toggle `View.INVISIBLE` (keeps reserved space); detach from old parent in `factory` | `IllegalStateException: child already has a parent` on every hide/show (e.g. app-open scrim) |
| Native: set `mediaView.mediaContent = ad.mediaContent`; slot keys scoped per owner screen; never destroy a visible ad; destroy on Main | Meta native impressions don't count; one screen's cleanup kills another's ad |
| UMP form blocker is cleared on form callback, **on a throw**, and **when the host Activity is destroyed** | A form that never calls back blocks ads for the whole process |
| Rewarded: grant exactly once (atomic decision), ~400 ms grace for reward-after-dismiss, forced settle on lost callback; keep the offer visible when no ad is ready and let the tap retry (bypassing no-fill cooldown, waiting for an in-flight load) | Double reward; lost reward; offer that silently does nothing |
| Premium is **tri-state** (Unknown/Free/Premium); Unknown suppresses ads; resolve it within a bound that covers connect + ownership query | Paying user sees ads during billing startup / after reinstall |

## App-level release gates

- **Prod ID validation in Gradle**, wired to `preProdReleaseBuild`: exact app ID + every unit ID,
  rejects test IDs. Configuration-cache safe: compute failures into a **local inside
  `tasks.register {}`**; never reference script-level vals from `doLast` ("cannot serialize Gradle
  script object references"). The same applies to **any** custom task with a `doLast`/`doFirst`. Verify with
  `bundleProdRelease --dry-run` AND a bad-value run.
- Test IDs only in the dev flavor; check the built AAB's dex for `3940256099942544` (must be 0).
- **Telemetry scope:** per-event `OnPaidEventListener`/lifecycle events for **fullscreen formats only**;
  banner/native refresh too often (event bloat) — AdMob→Firebase linking already reports their revenue.
- **No-ad zones** as a route blocklist (capture, editing, onboarding, paywall, system-picker return),
  not an allowlist — allowlists silently kill warm app-open inventory.
- Keep the two-actions-per-interstitial rule and fullscreen ownership local; only commercial
  caps can move to the AdMob console.
- `app-ads.txt` hosted and matching the publisher ID; single-source Ad Inspector test per Meta format.

## Placement strategy (revenue-optimized)

| Screen type | Recommended format | Why |
|---|---|---|
| Main/dashboard (short sessions) | Small Native | 3-5× banner eCPM; banner refresh rarely fires before user leaves |
| Scanning/progress (user waits) | Large Native OR MREC | High viewability, user captive |
| Result / "success" screen | Large Native | Peak attention = peak eCPM |
| List/content screen | Small Native or Banner | Either works |
| Settings / low-traffic | Small Native | Free revenue |
| Language / pickers | Small Native | Free revenue |
| Splash | **no ads** | Play policy forbids |
| Privacy / consent | **no ads** | Policy safety |
| Onboarding (first-time) | **no ads or minimal** | Retention impact |
| App resume (warm start) | App Open | +20-40% total revenue |
| Explicit "unlock X" gate | Rewarded Video | Highest eCPM, user opt-in |
| Natural transition | Interstitial (with 45s cap) | Core monetization |

## Rewarded gate + ad-free suppression pattern

The most effective rewarded format for utility apps:

```kotlin
// "Remove Ads for 1 hour" button
RewardedAd.show(activity,
    onReward = {
        AdsSuppression.grant(context)  // stored in SharedPreferences with expiry
        showToast("Ad-free for 60 min")
    },
    onUnavailable = { showToast("Ad not ready") }
)

// Every ad load path checks suppression
if (AdsSuppression.isAdFree(context)) {
    container.removeAllViews(); return
}
```

All banner, MREC, native, interstitial, and app-open paths must check `AdsSuppression.isAdFree(context)` before requesting/showing.

## Meta Audience Network mediation

1. AdMob Console → Mediation → create mediation groups per format.
2. Add Meta as bidding source in each group.
3. In Meta Business Manager → Monetization Manager → create placements matching AdMob ad units, bind via partnership agreement.
4. AdMob Console → Privacy & messaging → add Meta to GDPR + US States consent partner list.
5. No code changes required for Meta once `com.google.ads.mediation:facebook` dep is added. Settings live entirely in dashboards.

## ProGuard rules

```proguard
# AdMob — consumer rules ship automatically, but keep defensively
-keep public class com.google.android.gms.ads.** { public *; }
-keep public class com.google.ads.mediation.** { public *; }
-dontwarn com.google.android.gms.ads.**

# Meta via AdMob mediation
-keep class com.google.ads.mediation.facebook.** { *; }
-keep class com.facebook.ads.** { *; }
-dontwarn com.facebook.ads.**

# UMP
-keep class com.google.android.ump.** { *; }
```

## Native ad layout requirements

**Required asset**: headline. All others (body, icon, media, CTA, advertiser, store, price, starRating) are optional.

**MediaView** handles both video + main image automatically. Register it even if hidden — docs: "If a video asset is available, it's buffered and starts playing inside the MediaView... If the ad does not contain a video asset, the mainImage asset is downloaded."

**AdChoices overlay** is added automatically by the SDK. Reserve top-start or top-end corner — don't render anything there.

**populateNativeAdView pattern** (critical — `setNativeAd` must be LAST):

```kotlin
val headline = adView.findViewById<TextView>(R.id.ad_headline)
headline.text = ad.headline
adView.headlineView = headline  // register with NativeAdView

// ... populate body, icon, mediaView, CTA, advertiser, store ...

adView.setNativeAd(ad)  // LAST — this triggers impression tracking
```

## Migration from Appodeal — the mechanical steps

1. **Remove from `app/build.gradle`**: `com.appodeal.ads.sdk:core:*`, all `com.appodeal.ads.sdk.adapters:*`, all `io.bidmachine:ads.networks.*`.
2. **Add**: `play-services-ads:25.5.0` + `com.google.ads.mediation:facebook:6.22.0.0` (or Next-Gen for a new app — see Which SDK).
3. **Remove from `settings.gradle`**: `maven { url "https://artifactory.appodeal.com/appodeal" }` from both `pluginManagement` and `dependencyResolutionManagement`.
4. **Delete files**: `AppodealInit.kt`, `SingleInterstitialObject.kt`, and any `Placements.kt` file (AdMob doesn't use named placements — per-ad-unit CPM floors give you the same control).
5. **Keep class names** (`BannerAd`, `MrecAd`/skip, `RewardedAd`, `AppOpenAdManager`, `AdsSuppression`) so activity call sites don't churn — just rewrite their internals.
6. **Update `privacy_body` string**: replace "Appodeal" with "Google AdMob and Meta Audience Network".
7. **Update Play Console Data Safety form**: remove Appodeal, add AdMob + Meta as data collectors for Device ID + Advertising data.
8. **APK size drop**: expect ~40-60% reduction (e.g., 36MB → 11MB).

## Ad units you need to create

**Minimum**: 6 units if using native everywhere (skip banner units):
1. App Open
2. Interstitial (main nav)
3. Interstitial (transitions)
4. Native (large — for content/wait screens)
5. Native (small — for dashboard/lists/pickers)
6. Rewarded

**With banners** (some surfaces prefer auto-refresh for longer sessions): add Banner (anchored adaptive). Max useful split: 12 units with separate placements for CPM floor tuning.

**Always skip "Partner bidding" checkbox** when creating an ad unit unless you're using a non-AdMob bidding platform.

## 22-point audit checklist

Run this against ANY existing AdMob implementation:

### Init / manifest
- [ ] One shared readiness barrier; ready signalled from the initialize() completion listener, bounded, every load awaits it
- [ ] Init happens on background thread (`Dispatchers.IO`)
- [ ] Init gated behind `ConsentInformation.canRequestAds()` (UMP)
- [ ] Manifest has `APPLICATION_ID` meta-data with real ca-app-pub-* app ID
- [ ] Manifest has `AD_ID` permission
- [ ] Manifest has `OPTIMIZE_INITIALIZATION` + `OPTIMIZE_AD_LOADING` flags

### Banner
- [ ] Uses `AdSize.getLargeAnchoredAdaptiveBannerAdSize` (GMA 25.0 API)
- [ ] `AdListener` set BEFORE `loadAd()`
- [ ] `pause()` / `resume()` / `destroy()` wired to Activity lifecycle
- [ ] Container de-parented before `destroy()`

### Interstitial / App Open / Rewarded (full-screen)
- [ ] `FullScreenContentCallback` set ONCE at load time
- [ ] Ad ref nulled in `onAdDismissedFullScreenContent`
- [ ] Ad ref nulled in `onAdFailedToShowFullScreenContent`
- [ ] Refill after dismiss only where another show is likely (see Request discipline)
- [ ] Error-aware bounded retry; no-fill cooldown; per-load deadline
- [ ] Lifecycle rules table below (lost callback, cancellation, RESUMED host) all hold
- [ ] Activity-alive guard (`!isFinishing && !isDestroyed`) before `show()`
- [ ] Rewarded: 1-hour expiration check on every show
- [ ] App Open: 4-hour expiration check
- [ ] App Open: `isShowingAd = true` set BEFORE `show()` (not in callback)
- [ ] App Open: `currentActivity` NOT updated when `isShowingAd == true`

### Native
- [ ] `setNativeAd(ad)` called LAST in populate
- [ ] `destroy()` called in Activity.onDestroy (tracked via Set<FrameLayout>)
- [ ] `destroy()` called if ad loads after Activity destroyed (WeakReference check in `forNativeAd`)

## Red flags in existing code

| Red flag | Why it's wrong |
|---|---|
| `ad.fullScreenContentCallback = ...` inside `show()` | Should be set once at load, not per-show (docs) |
| No null ref after dismiss | Causes "show ad second time" crash per docs |
| `show()` called from background thread | All SDK calls must be main thread |
| App Open init via `Appodeal.INTERSTITIAL` on resume | Fake app-open — use real `AppOpenAd` class |
| Re-registering `setInterstitialCallbacks` globally per show | Race condition — callbacks overwrite |
| `MobileAds.initialize` on main thread | Docs recommend background thread |
| Any `printStackTrace()` on ad callbacks | Silent failures; use `Log.w` or real handler |
| Native ad loaded but never destroyed | Memory leak per docs |
| `forNativeAd` callback without isDestroyed check | Crash on orientation change / navigation |
| "initialized" signalled after `MobileAds.initialize()` returns (not in its listener) | Loads before adapters are ready |
| `rememberSaveable`/`remember` as a once-per-process ad guard | Replay on config change / skipped init after process death |
| Fullscreen show timeout that clears `isShowingAd`/ownership immediately — or never | Overlapping ads — or all fullscreen ads dead for the session |
| `if (lifecycle.currentState.isAtLeast(RESUMED))` inside `ProcessLifecycleOwner.onStart` | Always false there; the show never happens |
| `AndroidView(factory = { rememberedAdView })` inside an `if` | Re-parent crash when the condition flips |
| Retry immediately on no-fill / timer retries | Request inflation, lower show rate |

## Common pitfalls (from real migrations)

1. **"Partner bidding" checkbox** in AdMob Console — leave UNCHECKED unless you're using a non-AdMob bidding platform. Once checked it can't be undone.
2. **MediaView in small native layouts** — some devs omit it. Ads with video-only creative will fail. Include the view (hidden is OK).
3. **UMP dialog fires during `onStop`** — if you show UMP after a config change, it can throw `IllegalStateException`. UMP 4.0 handles this internally but only if your Activity is in RESUMED state.
4. **`app-ads.txt`** — host it on your developer website. Play Console auto-detects it from the "Website" field. Without it, Meta bidding often won't fill.
5. **Test device IDs** — register yours in AdMob Console → Settings → Test devices. Otherwise real IDs during QA get flagged as invalid traffic.
6. **Release build must be tested with real IDs** — test ad IDs work differently. R8 stripping sometimes affects adapter reflection.

## Test ad unit IDs (safe for dev)

```
App ID:       ca-app-pub-3940256099942544~3347511713
Banner:       ca-app-pub-3940256099942544/9214589741
Interstitial: ca-app-pub-3940256099942544/1033173712
Rewarded:     ca-app-pub-3940256099942544/5224354917
Native:       ca-app-pub-3940256099942544/2247696110
App Open:     ca-app-pub-3940256099942544/9257395921
```

Gate these behind `BuildConfig.DEBUG` in an `AdUnits` singleton; swap to production IDs automatically in release.

## Official sources (always verify latest)

Next-Gen: [release notes](https://developers.google.com/admob/android/next-gen/rel-notes) ·
[quick start](https://developers.google.com/admob/android/next-gen/quick-start) ·
[banner](https://developers.google.com/admob/android/next-gen/banner) ·
[native](https://developers.google.com/admob/android/next-gen/native) ·
[interstitial](https://developers.google.com/admob/android/next-gen/interstitial) ·
[app open](https://developers.google.com/admob/android/next-gen/app-open) ·
[rewarded](https://developers.google.com/admob/android/next-gen/rewarded) ·
[UMP](https://developers.google.com/admob/android/next-gen/privacy) ·
[banner refresh](https://support.google.com/admob/answer/3245199) ·
[disallowed interstitials](https://support.google.com/admob/answer/6201362)

Legacy:

- [GMA SDK release notes](https://developers.google.com/admob/android/rel-notes)
- [Banner](https://developers.google.com/admob/android/banner) + [Anchored adaptive](https://developers.google.com/admob/android/banner/anchored-adaptive)
- [Interstitial](https://developers.google.com/admob/android/interstitial)
- [Rewarded](https://developers.google.com/admob/android/rewarded)
- [App Open](https://developers.google.com/admob/android/app-open)
- [Native](https://developers.google.com/admob/android/native) + [Native Advanced](https://developers.google.com/admob/android/native/advanced)
- [Optimize initialization](https://developers.google.com/admob/android/optimize-initialization)
- [Meta mediation](https://developers.google.com/admob/android/mediation/meta)
- [UMP consent SDK](https://developers.google.com/admob/android/privacy)

## Reference implementations in this skill's folder

These snippets are **legacy `play-services-ads`** code. Do not paste them into a Next-Gen app.

- `snippets/BannerAd.kt` — full anchored-adaptive banner with lifecycle
- `snippets/InterstitialAdManager.kt` — preload pool with backoff + 45s cap
- `snippets/RewardedAd.kt` — singleton with 1-hour expiration + mediation grace
- `snippets/AppOpenAdManager.kt` — real AppOpenAd with 4-hour expiration
- `snippets/AdMobNativeLoader.kt` — AdLoader + populateNativeAdView
- `snippets/MobileAdsInit.kt` — shared, bounded mediation-readiness barrier (await before every load)
- `snippets/FullScreenAdCoordinator.kt` — one fullscreen owner + lost-callback / RESUMED helpers (production-proven)
- `snippets/AdsSuppression.kt` — ad-free hour state
- `snippets/admob_native_large.xml` — native ad layout (media-heavy)
- `snippets/admob_native_small.xml` — native ad layout (list item)
