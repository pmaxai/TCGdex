package io.github.pmaxai.cardscanner.store

import android.content.Context

/** The direct edition sells on the website: no store billing. */
object Stores {
    fun billing(ctx: Context): StoreBilling = NoBilling
}
