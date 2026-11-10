package com.bbro.kidtube.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Whether the phone has a network at all. Lets the sign-in screens tell "no connection" from "server unreachable". */
fun interface ConnectivityChecker {
    fun isOnline(): Boolean
}

class AndroidConnectivityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityChecker {
    override fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** The name a kid phone shows up with in the parent's device list. */
fun interface DeviceNameSource {
    fun name(): String
}

class BuildDeviceNameSource @Inject constructor() : DeviceNameSource {
    override fun name(): String = Build.MODEL?.takeIf { it.isNotBlank() }?.take(60) ?: "Android phone"
}
