package com.bbro.kidtube.ui.kid

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Reference count shared by every kid screen: during a transition two are composed at once. Main thread only. */
private object PortraitLockState {
    var holders = 0
    var original: Int? = null
}

/** Kid mode is portrait only: while any kid screen is composed the activity is locked to portrait; the original setting returns after the last one. */
@Composable
fun LockPortrait() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        if (activity != null) {
            if (PortraitLockState.holders++ == 0) PortraitLockState.original = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose {
            if (activity != null && --PortraitLockState.holders == 0) {
                PortraitLockState.original?.let { activity.requestedOrientation = it }
                PortraitLockState.original = null
            }
        }
    }
}
