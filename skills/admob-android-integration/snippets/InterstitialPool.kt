package com.example.app.ads

import android.content.Context

/**
 * Cache of InterstitialAdManager instances — one per ad unit ID.
 * Preload on init so the first show() for each placement is cache-warm.
 */
object InterstitialPool {
    private val managers = mutableMapOf<String, InterstitialAdManager>()

    fun get(adUnitId: String): InterstitialAdManager =
        managers.getOrPut(adUnitId) { InterstitialAdManager(adUnitId) }

    fun preloadAll(context: Context) {
        get(AdUnits.INTERSTITIAL_MAIN).load(context)
        get(AdUnits.INTERSTITIAL_TRANSITION).load(context)
    }
}
