package com.bbro.kidtube.share

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Only a parent phone offers "Share to KidTube". A kid-only device (or one not set up yet) must not. */
object ShareTargetPolicy {
    fun enabledFor(role: DeviceRole?): Boolean = role == DeviceRole.PARENT
}

/** Turns the share-target Activity on or off in the system's share sheet. */
fun interface ShareTargetApplier {
    fun setEnabled(enabled: Boolean)
}

class AndroidShareTargetApplier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ShareTargetApplier {
    override fun setEnabled(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(ComponentName(context, ShareActivity::class.java), state, PackageManager.DONT_KILL_APP)
    }
}

/**
 * Keeps the share target in step with the stored device role. The manifest ships it disabled, so a fresh install or a
 * kid-only device never lists it; it is enabled once the role is PARENT and disabled again if that ever changes.
 */
@Singleton
class ShareTargetManager @Inject constructor(
    private val session: SessionStore,
    private val applier: ShareTargetApplier,
) {
    /** Never returns: collects the role for as long as the caller's scope lives. */
    suspend fun keepInSync() {
        session.info.map { ShareTargetPolicy.enabledFor(it.deviceRole) }.distinctUntilChanged().collect(applier::setEnabled)
    }
}
