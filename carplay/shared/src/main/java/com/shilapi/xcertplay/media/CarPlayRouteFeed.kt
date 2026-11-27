package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Route

/**
 * The route the iPhone is guiding to, for a host app (Binnacle, through CarPlayEmbedService;
 * issue #180). Null while no route is active or no session runs. Only sent when it changes.
 */
object CarPlayRouteFeed {
    private var observer: ((Iap2Route?) -> Unit)? = null
    private var current: Iap2Route? = null

    /**
     * One observer at a time; null stops it. It gets the current route at once, then each change,
     * called under the lock so deliveries keep the order the routes were set in: it must only post
     * (CarPlayEmbedService hands each one to its main thread).
     */
    @Synchronized
    fun observe(next: ((Iap2Route?) -> Unit)?) {
        observer = next
        next?.invoke(current)
    }

    /** Called on the iAP2 thread, and with null when a session ends. */
    @Synchronized
    fun publish(route: Iap2Route?) {
        if (route == current) return
        current = route
        observer?.invoke(route)
    }
}
