package com.example.app.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.lang.ref.WeakReference

/**
 * Preload-and-show interstitial manager (one per ad unit ID).
 *
 * Official best practices applied:
 *  - FullScreenContentCallback set ONCE at load time.
 *  - Reference nulled after dismiss/fail to prevent reuse.
 *  - Exponential backoff retry (1s → 2s → 4s, max 3) on load failure.
 *  - Reload called from onAdDismissedFullScreenContent (docs recommend).
 *  - 45s global frequency cap across all instances.
 *  - Weak-ref activity + isFinishing/isDestroyed guards.
 */
class InterstitialAdManager(private val adUnitId: String) {

    companion object {
        private const val TAG = "InterstitialAd"
        private const val MIN_INTERVAL_MS = 45_000L
        private const val MAX_RETRIES = 3

        @Volatile private var lastShownAt: Long = 0L
        fun canShowNow(): Boolean =
            System.currentTimeMillis() - lastShownAt >= MIN_INTERVAL_MS
    }

    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var retryAttempt = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(context: Context) {
        if (isLoading || interstitialAd != null) return
        if (!MobileAdsInit.isInitialized()) return
        isLoading = true
        InterstitialAd.load(
            context.applicationContext, adUnitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isLoading = false
                    retryAttempt = 0
                    attachCallback(context.applicationContext)
                }

                override fun onAdFailedToLoad(err: LoadAdError) {
                    Log.w(TAG, "[$adUnitId] load failed: ${err.message}")
                    interstitialAd = null
                    isLoading = false
                    scheduleRetry(context.applicationContext)
                }
            }
        )
    }

    private fun scheduleRetry(context: Context) {
        if (retryAttempt >= MAX_RETRIES) {
            retryAttempt = 0
            return
        }
        val delayMs = 1000L shl retryAttempt
        retryAttempt++
        mainHandler.postDelayed({ load(context) }, delayMs)
    }

    private fun attachCallback(context: Context) {
        interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastShownAt = System.currentTimeMillis()
            }

            override fun onAdImpression() {
                Log.d(TAG, "[$adUnitId] impression")
            }

            override fun onAdClicked() {
                Log.d(TAG, "[$adUnitId] clicked")
            }

            override fun onAdFailedToShowFullScreenContent(err: AdError) {
                Log.w(TAG, "[$adUnitId] show failed: ${err.message}")
                interstitialAd = null
                val work = pendingWork; pendingWork = null
                work?.invokeIfAlive()
                load(context)
            }

            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                val work = pendingWork; pendingWork = null
                work?.invokeIfAlive()
                load(context) // preload immediately per docs
            }
        }
    }

    fun isReady(): Boolean = interstitialAd != null

    private var pendingWork: PendingWork? = null

    private class PendingWork(
        val activityRef: WeakReference<Activity>,
        val afterAdWork: () -> Unit
    ) {
        fun invokeIfAlive() {
            val a = activityRef.get() ?: return
            if (a.isFinishing || a.isDestroyed) return
            afterAdWork()
        }
    }

    fun show(activity: Activity, afterAdWork: () -> Unit) {
        if (AdsSuppression.isAdFree(activity) || !canShowNow() || interstitialAd == null) {
            afterAdWork()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            afterAdWork()
            return
        }
        pendingWork = PendingWork(WeakReference(activity), afterAdWork)
        interstitialAd?.show(activity)
    }
}
