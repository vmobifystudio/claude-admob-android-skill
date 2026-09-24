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
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd as GmsRewardedAd

/**
 * Rewarded video — official AdMob implementation audited against
 * https://developers.google.com/admob/android/rewarded
 *
 * Key points:
 *  - Load via RewardedAd.load + RewardedAdLoadCallback.
 *  - FullScreenContentCallback set ONCE at load (onAdLoaded).
 *  - Reference nulled in onAdDismissedFullScreenContent and onAdFailedToShowFullScreenContent
 *    per docs ("Don't forget to set the ad reference to null so you don't show the ad a
 *    second time").
 *  - 1-hour expiration checked on every show attempt (docs: ads expire after 1 hour).
 *  - OnUserEarnedRewardListener fires before dismiss for Google ads, but AFTER dismiss is
 *    possible for some mediation networks. We debounce the "user skipped" decision by
 *    [POST_DISMISS_REWARD_GRACE_MS] so delayed reward callbacks from Meta/AppLovin etc.
 *    don't cause us to invoke both onReward() AND onUnavailable().
 *  - Exponential backoff retry (1s → 2s → 4s, max 3) on load failure.
 *  - Activity-alive guard in show() to avoid crashing on destroyed context.
 */
object RewardedAd {
    private const val TAG = "RewardedAd"
    private const val EXPIRE_MS = 60 * 60 * 1000L // Docs: 1 hour
    private const val MAX_RETRIES = 3
    /** Grace window to wait for a delayed reward callback from mediation networks. */
    private const val POST_DISMISS_REWARD_GRACE_MS = 400L

    @Volatile private var loaded: GmsRewardedAd? = null
    @Volatile private var isLoading = false
    @Volatile private var loadTime = 0L
    @Volatile private var retryAttempt = 0

    // Main-thread-only state: the "user skipped" callback for the current show.
    // onReward is captured directly by OnUserEarnedRewardListener's closure; no need
    // to hold a reference here.
    private var pendingOnUnavailable: (() -> Unit)? = null
    private var rewardEarned = false

    private val mainHandler = Handler(Looper.getMainLooper())

    fun preload(context: Context) {
        if (isLoading) return
        if (isReady()) return
        loaded = null
        if (!MobileAdsInit.isInitialized()) return
        isLoading = true

        GmsRewardedAd.load(
            context.applicationContext,
            AdUnits.REWARDED,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: GmsRewardedAd) {
                    loaded = ad
                    loadTime = System.currentTimeMillis()
                    retryAttempt = 0
                    isLoading = false
                    attachCallback(ad, context.applicationContext)
                }

                override fun onAdFailedToLoad(err: LoadAdError) {
                    Log.w(TAG, "Load failed: ${err.message} [attempt ${retryAttempt + 1}]")
                    loaded = null
                    isLoading = false
                    scheduleRetry(context.applicationContext)
                }
            }
        )
    }

    private fun scheduleRetry(context: Context) {
        if (retryAttempt >= MAX_RETRIES) {
            Log.w(TAG, "Max retries reached; will retry on next preload() call")
            retryAttempt = 0
            return
        }
        val delayMs = 1000L shl retryAttempt // 1s, 2s, 4s
        retryAttempt++
        mainHandler.postDelayed({ preload(context) }, delayMs)
    }

    private fun attachCallback(ad: GmsRewardedAd, context: Context) {
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Shown")
            }

            override fun onAdImpression() {
                Log.d(TAG, "Impression recorded")
            }

            override fun onAdClicked() {
                Log.d(TAG, "Clicked")
            }

            override fun onAdFailedToShowFullScreenContent(err: AdError) {
                Log.w(TAG, "Show failed: ${err.message}")
                loaded = null
                val cb = pendingOnUnavailable
                pendingOnUnavailable = null
                rewardEarned = false
                cb?.invoke()
                preload(context)
            }

            override fun onAdDismissedFullScreenContent() {
                loaded = null
                // For mediation networks (Meta, AppLovin, etc.), onUserEarnedReward may fire
                // AFTER onAdDismissedFullScreenContent. Wait briefly before deciding the user
                // skipped — otherwise we'd fire both onReward() and onUnavailable().
                mainHandler.postDelayed({
                    if (!rewardEarned) pendingOnUnavailable?.invoke()
                    pendingOnUnavailable = null
                    rewardEarned = false
                }, POST_DISMISS_REWARD_GRACE_MS)
                preload(context)
            }
        }
    }

    fun isReady(): Boolean {
        val ad = loaded ?: return false
        if (System.currentTimeMillis() - loadTime >= EXPIRE_MS) {
            loaded = null
            return false
        }
        return true
    }

    /**
     * Show the rewarded ad.
     *
     * @param onReward invoked when the user completes the ad and earns the reward.
     * @param onUnavailable invoked if the ad can't show (not loaded / expired / destroyed
     * activity) OR if the user dismisses without earning the reward.
     *
     * Exactly one of onReward / onUnavailable fires per call. Both are dispatched on the
     * main thread.
     */
    fun show(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) { onUnavailable(); return }
        val ad = loaded
        if (ad == null || !isReady()) {
            onUnavailable()
            preload(activity.applicationContext)
            return
        }
        pendingOnUnavailable = onUnavailable
        rewardEarned = false
        ad.show(activity, OnUserEarnedRewardListener { reward ->
            rewardEarned = true
            Log.d(TAG, "Reward earned: ${reward.amount} ${reward.type}")
            onReward()
        })
    }
}
