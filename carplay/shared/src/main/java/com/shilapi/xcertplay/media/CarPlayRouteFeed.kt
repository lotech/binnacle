package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Route

/**
 * The route the iPhone is guiding to, for a host app (Binnacle, through CarPlayEmbedService;
 * issue #180). Null while no route is active or no session runs. Only sent when it changes.
 */
object CarPlayRouteFeed {
    private var observer: ((Iap2Route?) -> Unit)? = null
    private var current: Iap2Route? = null

    /** One observer at a time; null stops it. It gets the current route at once, then each change. */
    fun observe(next: ((Iap2Route?) -> Unit)?) {
        val initial = synchronized(this) {
            observer = next
            current
        }
        next?.invoke(initial)
    }

    /** Called on the iAP2 thread. */
    fun publish(route: Iap2Route?) {
        val target = synchronized(this) {
            if (route == current) return
            current = route
            observer
        } ?: return
        target(route)
    }
}
