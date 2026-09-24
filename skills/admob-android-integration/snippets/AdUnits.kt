package com.example.app.ads

import com.example.app.BuildConfig

/**
 * AdMob ad unit IDs. Debug builds use Google's official test IDs so you can't generate
 * invalid traffic against your real units. Release builds use the production IDs below.
 *
 * **TODO**: replace each `TODO_` placeholder with the real ca-app-pub-* ID from
 * AdMob Console → your app → Ad units.
 *
 * Mediation (Meta Audience Network) is configured in the AdMob Console per ad unit,
 * not in code. See Monetization Manager in Meta for the Meta-side pairing.
 */
object AdUnits {

    // ---- Production IDs (replace these) --------------------------------------------
    private const val PROD_APP_OPEN = "TODO_app_open"                       // App Open
    // BANNER_MAIN was previously used on the dashboard — now replaced by NATIVE_SMALL there for
    // higher eCPM. Kept here in case you want to A/B test banner vs. native. Otherwise skip
    // creating it in AdMob Console.
    private const val PROD_BANNER_MAIN = "TODO_banner_main_optional"
    private const val PROD_BANNER_RECOVERED = "TODO_banner_recovered"       // Anchored adaptive banner
    // MREC removed — AdMob no longer exposes 300x250 as a distinct unit type. The two
    // former MREC surfaces (RecoveredResult + progress dialog) now use NATIVE_LARGE.
    private const val PROD_INTERSTITIAL_MAIN = "TODO_interstitial_main"     // Main nav
    private const val PROD_INTERSTITIAL_TRANSITION = "TODO_interstitial_transition" // Post-scan + post-recovery
    private const val PROD_NATIVE_LARGE = "TODO_native_large"               // Scanning screen
    private const val PROD_NATIVE_SMALL = "TODO_native_small"               // Album + recover folder
    private const val PROD_REWARDED = "TODO_rewarded"                          // Rewarded unlock / remove-ads gate

    // ---- Google official test IDs (safe for dev) -----------------------------------
    private const val TEST_APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    private const val TEST_NATIVE = "ca-app-pub-3940256099942544/2247696110"
    private const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"

    // ---- Public accessors ----------------------------------------------------------
    val APP_OPEN: String get() = if (BuildConfig.DEBUG) TEST_APP_OPEN else PROD_APP_OPEN
    val BANNER_MAIN: String get() = if (BuildConfig.DEBUG) TEST_BANNER else PROD_BANNER_MAIN
    val BANNER_RECOVERED: String get() = if (BuildConfig.DEBUG) TEST_BANNER else PROD_BANNER_RECOVERED
    val INTERSTITIAL_MAIN: String get() = if (BuildConfig.DEBUG) TEST_INTERSTITIAL else PROD_INTERSTITIAL_MAIN
    val INTERSTITIAL_TRANSITION: String get() = if (BuildConfig.DEBUG) TEST_INTERSTITIAL else PROD_INTERSTITIAL_TRANSITION
    val NATIVE_LARGE: String get() = if (BuildConfig.DEBUG) TEST_NATIVE else PROD_NATIVE_LARGE
    val NATIVE_SMALL: String get() = if (BuildConfig.DEBUG) TEST_NATIVE else PROD_NATIVE_SMALL
    val REWARDED: String get() = if (BuildConfig.DEBUG) TEST_REWARDED else PROD_REWARDED
}
