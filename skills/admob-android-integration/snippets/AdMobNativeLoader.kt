package com.example.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.app.R
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import java.lang.ref.WeakReference

/**
 * AdMob native ad loader + renderer following official guidance:
 *  - NativeAdView is the root container.
 *  - Required asset: headline (all others are optional per docs).
 *  - MediaView handles video + main image automatically.
 *  - AdChoices overlay is added by the SDK — we reserve top-start corner.
 *  - destroy() called on the NativeAd when the container goes away (BaseActivity).
 *  - Guards against callback firing after Activity destroyed (WeakReference + isDestroyed).
 */
object AdMobNativeLoader {
    private const val TAG = "AdMobNative"

    fun load(
        activity: Activity,
        container: FrameLayout,
        adUnitId: String,
        adType: EnumAdType,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (AdsSuppression.isAdFree(activity)) {
            container.removeAllViews(); container.visibility = View.GONE; return
        }
        if (!MobileAdsInit.isInitialized()) return

        val weakActivity = WeakReference(activity)
        val weakContainer = WeakReference(container)

        val videoOptions = VideoOptions.Builder().setStartMuted(true).build()
        val nativeAdOptions = NativeAdOptions.Builder().setVideoOptions(videoOptions).build()

        AdLoader.Builder(activity.applicationContext, adUnitId)
            .forNativeAd { nativeAd ->
                val act = weakActivity.get()
                val cont = weakContainer.get()
                if (act == null || act.isFinishing || act.isDestroyed || cont == null) {
                    // Activity gone between load and callback — per docs, destroy the ad
                    // or it leaks.
                    nativeAd.destroy()
                    return@forNativeAd
                }
                renderInto(cont, nativeAd, adType)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(err: LoadAdError) {
                    Log.w(TAG, "load failed: ${err.message}")
                    val cont = weakContainer.get() ?: return
                    cont.removeAllViews()
                    cont.visibility = View.GONE
                }
            })
            .withNativeAdOptions(nativeAdOptions)
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    private fun renderInto(
        container: FrameLayout,
        nativeAd: NativeAd,
        adType: EnumAdType,
    ) {
        // Destroy any previous NativeAd attached to this container (docs: destroy() required).
        (container.getChildAt(0) as? NativeAdView)?.let { existing ->
            (existing.getTag(R.id.ad_tag_native) as? NativeAd)?.destroy()
        }

        val layoutRes = when (adType) {
            EnumAdType.Small -> R.layout.admob_native_small
            EnumAdType.Large -> R.layout.admob_native_large
            else -> R.layout.admob_native_large
        }
        val adView = LayoutInflater.from(container.context)
            .inflate(layoutRes, container, false) as NativeAdView
        populate(adView, nativeAd)
        container.removeAllViews()
        container.addView(adView)
        container.visibility = View.VISIBLE
        adView.setTag(R.id.ad_tag_native, nativeAd)
    }

    private fun populate(adView: NativeAdView, ad: NativeAd) {
        // Headline — REQUIRED per docs.
        val headline = adView.findViewById<TextView>(R.id.ad_headline)
        headline.text = ad.headline
        adView.headlineView = headline

        // Body — optional.
        adView.findViewById<TextView?>(R.id.ad_body)?.let { body ->
            if (ad.body.isNullOrBlank()) body.visibility = View.GONE
            else {
                body.visibility = View.VISIBLE
                body.text = ad.body
                adView.bodyView = body
            }
        }

        // Icon — optional.
        adView.findViewById<ImageView?>(R.id.ad_icon)?.let { icon ->
            val iconAsset = ad.icon
            if (iconAsset?.drawable == null) icon.visibility = View.GONE
            else {
                icon.visibility = View.VISIBLE
                icon.setImageDrawable(iconAsset.drawable)
                adView.iconView = icon
            }
        }

        // MediaView — register when present (handles video + main image).
        adView.findViewById<MediaView?>(R.id.ad_media)?.let { media ->
            adView.mediaView = media
            media.mediaContent = ad.mediaContent
        }

        // Call to action — optional (per docs) but strongly recommended.
        adView.findViewById<TextView?>(R.id.ad_call_to_action)?.let { cta ->
            cta.text = ad.callToAction ?: "OPEN"
            adView.callToActionView = cta
        }

        // Advertiser — optional.
        adView.findViewById<TextView?>(R.id.ad_advertiser)?.let { adv ->
            if (ad.advertiser.isNullOrBlank()) adv.visibility = View.GONE
            else {
                adv.visibility = View.VISIBLE
                adv.text = ad.advertiser
                adView.advertiserView = adv
            }
        }

        // Store — optional.
        adView.findViewById<TextView?>(R.id.ad_store)?.let { store ->
            if (ad.store.isNullOrBlank()) store.visibility = View.GONE
            else {
                store.visibility = View.VISIBLE
                store.text = ad.store
                adView.storeView = store
            }
        }

        // MUST be called LAST per docs.
        adView.setNativeAd(ad)
    }

    /** Destroy any NativeAd attached to the container. Called from BaseActivity.onDestroy. */
    fun destroy(container: ViewGroup) {
        val adView = container.getChildAt(0) as? NativeAdView ?: return
        (adView.getTag(R.id.ad_tag_native) as? NativeAd)?.destroy()
        adView.destroy()
    }
}

/** Legacy signature kept so callers don't churn. */
fun Activity.loadNativeAd(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") view: ViewGroup,
    adFrame: FrameLayout,
    adType: EnumAdType,
    adUnitId: String
) {
    AdMobNativeLoader.load(this, adFrame, adUnitId, adType)
}
