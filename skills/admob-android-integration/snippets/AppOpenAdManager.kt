package com.example.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import java.lang.ref.WeakReference
import java.util.Date

/**
 * AdMob App Open ad manager — audited against
 * https://developers.google.com/admob/android/app-open
 *
 * Official pattern implemented:
 *  - 4-hour expiration per docs.
 *  - `isShowingAd` flag set BEFORE `show()` (docs): prevents Application.onActivityStarted
 *    from capturing the ad-framework's Activity as `currentActivity`.
 *  - Cold-start skip.
 *  - Excluded screens (Splash/Privacy/Language/Intro).
 *  - Exponential backoff retry (1s/2s/4s, max 3).
 *  - FullScreenContentCallback with all 5 methods.
 *  - Null ref + isShowingAd=false in dismiss + fail callbacks.
 *  - Reload after dismiss.
 *  - Shared 45s global cap with interstitial to avoid ad stacking.
 */
class AppOpenAdManager : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "AppOpenAd"
        private const val FOUR_HOURS_MS = 4 * 60 * 60 * 1000L
        private const val MAX_RETRIES = 3
    }

    private var appOpenAd: AppOpenAd? = null
    private var loadTime: Long = 0L
    private var isLoadingAd = false

    /**
     * Exposed to Application layer so it can skip updating currentActivity while the ad
     * overlay is showing. MUST be set true BEFORE appOpenAd.show(activity) per docs.
     */
    var isShowingAd: Boolean = false
        private set

    private var retryAttempt = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentActivityRef: WeakReference<Activity>? = null
    private var isColdStart = true

    private val excludedActivities = setOf(
        "SplashScreen", "PrivacyActivity", "LanguageActivity", "IntroSlides"
    )

    fun register(app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        app.registerActivityLifecycleCallbacks(this)
    }

    private fun loadAd(context: Context) {
        if (isLoadingAd || isAdAvailable()) return
        if (!MobileAdsInit.isInitialized()) return
        isLoadingAd = true
        AppOpenAd.load(
            context.applicationContext, AdUnits.APP_OPEN, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    loadTime = Date().time
                    isLoadingAd = false
                    retryAttempt = 0
                    attachCallback(context.applicationContext)
                }

                override fun onAdFailedToLoad(err: LoadAdError) {
                    Log.w(TAG, "load failed: ${err.message}")
                    isLoadingAd = false
                    scheduleRetry(context.applicationContext)
                }
            }
        )
    }

    private fun scheduleRetry(context: Context) {
        if (retryAttempt >= MAX_RETRIES) { retryAttempt = 0; return }
        val delayMs = 1000L shl retryAttempt
        retryAttempt++
        mainHandler.postDelayed({ loadAd(context) }, delayMs)
    }

    private fun attachCallback(context: Context) {
        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "showed")
            }

            override fun onAdImpression() {
                Log.d(TAG, "impression")
            }

            override fun onAdClicked() {
                Log.d(TAG, "clicked")
            }

            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                loadAd(context)
            }

            override fun onAdFailedToShowFullScreenContent(err: AdError) {
                Log.w(TAG, "show failed: ${err.message}")
                appOpenAd = null
                isShowingAd = false
                loadAd(context)
            }
        }
    }

    private fun isAdAvailable(): Boolean =
        appOpenAd != null && (Date().time - loadTime) < FOUR_HOURS_MS

    private fun showIfAvailable(activity: Activity) {
        if (isShowingAd) return
        if (!isAdAvailable()) { loadAd(activity); return }
        if (AdsSuppression.isAdFree(activity)) return
        if (!InterstitialAdManager.canShowNow()) return
        if (activity.isFinishing || activity.isDestroyed) return

        // CRITICAL: flag must flip BEFORE show(). See Application.onActivityStarted —
        // if flag is false when the ad overlay's Activity starts, we capture IT as
        // currentActivity and the next onStart() tries to show an ad on the ad overlay.
        isShowingAd = true
        appOpenAd?.show(activity)
    }

    // ---- ProcessLifecycle ---------------------------------------------------------
    override fun onStart(owner: LifecycleOwner) {
        if (isColdStart) {
            isColdStart = false
            currentActivityRef?.get()?.let { loadAd(it.applicationContext) }
            return
        }
        val act = currentActivityRef?.get() ?: return
        if (act.javaClass.simpleName in excludedActivities) return
        showIfAvailable(act)
    }

    // ---- ActivityLifecycleCallbacks ----------------------------------------------
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        // Per docs: do NOT capture an ad overlay's Activity as currentActivity.
        if (!isShowingAd) currentActivityRef = WeakReference(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        if (!isShowingAd) currentActivityRef = WeakReference(activity)
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() === activity) currentActivityRef = null
    }
}
