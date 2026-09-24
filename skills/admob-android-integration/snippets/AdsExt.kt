package com.example.app.ads

import android.app.Activity

private var listCounter = 0

/**
 * Show interstitial on every 2nd click, gated by global 45s cap + ad-free suppression.
 * Uses the MAIN_NAV interstitial ad unit.
 */
fun Activity.showAdList(
    adUnitId: String = AdUnits.INTERSTITIAL_MAIN,
    afterAdWork: () -> Unit
) {
    val mgr = InterstitialPool.get(adUnitId)
    if (listCounter == 1 && InterstitialAdManager.canShowNow() && mgr.isReady()) {
        listCounter = 0
        mgr.show(this) { afterAdWork() }
    } else {
        listCounter = (listCounter + 1) % 2
        afterAdWork()
    }
}

/** Always try to show interstitial; falls through if not ready, capped, or ad-free. */
fun Activity.showAdAndGo(
    adUnitId: String = AdUnits.INTERSTITIAL_TRANSITION,
    afterAdWork: () -> Unit
) {
    InterstitialPool.get(adUnitId).show(this) { afterAdWork() }
}
