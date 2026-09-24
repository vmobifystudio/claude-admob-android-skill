package com.example.app.ads

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.WindowManager
import android.util.Log
import android.widget.FrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * Anchored adaptive banner. GMA 25.0+ exposes the new "large" variant which picks
 * a taller optimized height per orientation — typically +15-30% eCPM vs. fixed 320×50.
 */
class BannerAd(val context: Context) {
    companion object { private const val TAG = "BannerAd" }

    fun showBanner(bannerLayout: FrameLayout, adUnitId: String) {
        if (AdsSuppression.isAdFree(context)) {
            bannerLayout.removeAllViews()
            return
        }
        if (!MobileAdsInit.isInitialized()) return
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (bannerLayout.childCount > 0 && bannerLayout.getChildAt(0) is AdView) return

        val adView = AdView(context).apply {
            this.adUnitId = adUnitId
            setAdSize(adaptiveAdSize(activity, bannerLayout))
            // Per docs: set AdListener BEFORE loadAd() so load events fire on first request.
            adListener = object : AdListener() {
                override fun onAdLoaded() { Log.d(TAG, "loaded") }
                override fun onAdImpression() { Log.d(TAG, "impression") }
                override fun onAdClicked() { Log.d(TAG, "clicked") }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    Log.w(TAG, "load failed: ${err.message}")
                }
            }
        }
        bannerLayout.removeAllViews()
        bannerLayout.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    fun pause(bannerLayout: FrameLayout) {
        (bannerLayout.getChildAt(0) as? AdView)?.pause()
    }

    fun resume(bannerLayout: FrameLayout) {
        (bannerLayout.getChildAt(0) as? AdView)?.resume()
    }

    fun destroy(bannerLayout: FrameLayout) {
        (bannerLayout.getChildAt(0) as? AdView)?.destroy()
        bannerLayout.removeAllViews()
    }

    /**
     * Compute the adaptive banner size using the recommended "large anchored adaptive"
     * API introduced in GMA 25.0. Uses WindowMetrics on API 30+ (non-deprecated) and
     * falls back to DisplayMetrics for older devices.
     */
    private fun adaptiveAdSize(activity: Activity, container: ViewGroup): AdSize {
        val wm = activity.windowManager
        val widthPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars()
            )
            metrics.bounds.width() - insets.left - insets.right
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(dm)
            dm.widthPixels
        }
        val density = activity.resources.displayMetrics.density
        val containerWidthPx = if (container.width == 0) widthPx else container.width
        val adWidthDp = (containerWidthPx / density).toInt()
        return AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, adWidthDp)
    }
}
