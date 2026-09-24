package com.example.app.ads

import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One process-wide mediation readiness barrier (legacy play-services-ads).
 *
 * Call awaitReady() before EVERY ad load, from every format, only after UMP
 * canRequestAds() is true. Readiness comes from the initialize() COMPLETION
 * LISTENER — initialize() itself returns before adapters (e.g. Meta) are ready,
 * so signalling "done" after it returns sends first requests without Meta bids.
 *
 * Bounded: a lost listener must not block ads forever; after a timeout the
 * attempt is cleared so a later caller retries (the SDK tolerates re-init).
 */
object MobileAdsInit {
    private const val TAG = "MobileAdsInit"
    private const val INIT_TIMEOUT_MS = 15_000L
    private val lock = Any()
    private var attempt: CompletableDeferred<Boolean>? = null
    @Volatile private var ready = false

    /** Non-suspending check for older call sites. Prefer awaitReady() before a load. */
    fun isInitialized(): Boolean = ready

    suspend fun awaitReady(context: Context): Boolean {
        val pending = withContext(Dispatchers.IO) {
            synchronized(lock) {
                attempt ?: CompletableDeferred<Boolean>().also { deferred ->
                    attempt = deferred
                    try {
                        // Test device IDs belong in dev builds only.
                        MobileAds.setRequestConfiguration(RequestConfiguration.Builder().build())
                        // Background thread per Google's optimize-initialization guide.
                        MobileAds.initialize(context.applicationContext) { status ->
                            status.adapterStatusMap.forEach { (name, s) ->
                                Log.i(TAG, "Adapter $name: ${s.initializationState} ${s.description}")
                            }
                            ready = true
                            deferred.complete(true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "MobileAds.initialize threw", e)
                        deferred.complete(false)
                    }
                }
            }
        }
        val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) { pending.await() } ?: false
        if (!ready) synchronized(lock) { if (attempt === pending) attempt = null }
        return ready
    }
}
