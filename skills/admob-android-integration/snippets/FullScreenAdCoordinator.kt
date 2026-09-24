package com.example.app.ads

import android.app.Activity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Exclusive ownership of the fullscreen surface across interstitial, app-open and
 * rewarded (production-proven in a shipped app, 2026-09). Each manager:
 *   token = tryAcquire() ?: return            // before show()
 *   var showInvoked = false; ... showInvoked = true; ad.show(activity)
 *   release(token) in dismiss / failed-to-show
 *   on CancellationException: if (!showInvoked) release(token) + put ad back
 *   on show timeout: releaseWhenResumed(activity.hostLifecycle) { detach callbacks; release(token) }
 */
class FullScreenAdCoordinator {
    private val nextId = AtomicLong()
    private val lock = Any()
    private var active: Long? = null
    private var routeBlocked = false
    private var consentFormBlocked = false

    fun tryAcquire(): Long? = synchronized(lock) {
        if (active != null || routeBlocked || consentFormBlocked) null
        else nextId.incrementAndGet().also { active = it }
    }

    /** Paywall / onboarding / system-picker routes: no fullscreen ad there. */
    fun setRouteBlocked(blocked: Boolean) = synchronized(lock) { routeBlocked = blocked }

    /** Set when a UMP form opens; clear on its callback, on a throw, AND on host destroy. */
    fun setConsentFormBlocked(blocked: Boolean) = synchronized(lock) { consentFormBlocked = blocked }

    fun release(id: Long) = synchronized(lock) { if (active == id) active = null }

    fun isBusy(): Boolean = synchronized(lock) { active != null }

    /** For non-ad fullscreen UI (automatic paywall): an ad OR a consent form owns the screen. */
    fun isScreenTaken(): Boolean = synchronized(lock) { active != null || consentFormBlocked }
}

/**
 * Lost terminal callback recovery. The ad is its own Activity stacked on the host,
 * so a RESUMED host means it is gone. Never release on a bare timer (overlap), never
 * hold forever (every fullscreen format dead for the process). Call on Main.
 */
fun releaseWhenResumed(lifecycle: Lifecycle?, release: () -> Unit) {
    if (lifecycle == null || lifecycle.currentState == Lifecycle.State.DESTROYED) return release()
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) { owner.lifecycle.removeObserver(this); release() }
        override fun onDestroy(owner: LifecycleOwner) { owner.lifecycle.removeObserver(this); release() }
    })
}

/**
 * Bounded wait for RESUMED. Use it (don't test currentState) from
 * ProcessLifecycleOwner.onStart, where the Activity is only STARTED.
 */
suspend fun awaitResumed(lifecycle: Lifecycle?, timeoutMs: Long): Boolean {
    lifecycle ?: return false
    return withTimeoutOrNull(timeoutMs) {
        lifecycle.currentStateFlow.first {
            it.isAtLeast(Lifecycle.State.RESUMED) || it == Lifecycle.State.DESTROYED
        }
    }?.isAtLeast(Lifecycle.State.RESUMED) == true
}

val Activity.hostLifecycle: Lifecycle? get() = (this as? LifecycleOwner)?.lifecycle
val Activity.isHostResumed: Boolean
    get() = hostLifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
