package com.batchfee.edu.domain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.batchfee.edu.BuildConfig
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.tasks.await

object ReviewFlowLauncher {

    /** Opens the Play Store review flow. On debug builds (or any failure) it falls back
     * to the Play Store listing page so the user can still leave a review. */
    suspend fun openPlayReview(activity: Activity) {
        if (BuildConfig.DEBUG) {
            openPlayStoreListing(activity)
            return
        }
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            val reviewInfo = manager.requestReviewFlow().await()
            manager.launchReviewFlow(activity, reviewInfo).await()
        }.onFailure {
            openPlayStoreListing(activity)
        }
    }

    fun openPlayStoreListing(context: Context) {
        val packageName = context.packageName
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://details?id=$packageName")
            setPackage("com.android.vending")
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            }
            context.startActivity(fallback)
        }
    }
}
