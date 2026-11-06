package com.bbro.kidtube.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted session. Two kinds of data, both in one Preferences DataStore:
 *  - non-secret info (device role, family id/timezone, device id, active mode, auth-problem flags) stored as plain values;
 *  - secrets (parent access/refresh token, kid device token), each encrypted with [SecretCipher] (Keystore AES-GCM)
 *    and bound to its own name as AAD, so a blob copied between keys does not decrypt. Tokens are never logged.
 *
 * [DeviceRole] is the one-time setup choice. [ActiveMode] is a SEPARATE, persisted flag that only matters when the
 * role is PARENT (a parent phone can be switched into kid mode and must come back up in the same mode after the
 * process dies or the phone reboots). A KID-role device is always in kid mode and ignores [setActiveMode].
 */
@Singleton
class SessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    // ---- observation -----------------------------------------------------------------------

    val sessionState: Flow<SessionState> = dataStore.data.map(::deriveState).distinctUntilChanged()

    val info: Flow<SessionInfo> = dataStore.data.map(::readInfo).distinctUntilChanged()

    suspend fun currentInfo(): SessionInfo = info.first()

    suspend fun currentState(): SessionState = sessionState.first()

    // ---- secrets ---------------------------------------------------------------------------

    suspend fun accessToken(): String? = readSecret(ACCESS)

    suspend fun refreshToken(): String? = readSecret(REFRESH)

    suspend fun deviceToken(): String? = readSecret(DEVICE)

    // ---- writes ----------------------------------------------------------------------------

    /** After a successful login: this phone is a parent device. Keeps a previously persisted active mode. */
    suspend fun saveParentLogin(accessToken: String, refreshToken: String, familyId: String?) {
        dataStore.edit { p ->
            p[ROLE] = DeviceRole.PARENT.name
            familyId?.let { p[FAMILY_ID] = it }
            putSecret(p, ACCESS, accessToken)
            putSecret(p, REFRESH, refreshToken)
            p.remove(DEVICE.key)
            p.remove(DEVICE_ID)
            p[NEEDS_LOGIN] = false
            p[DEVICE_REVOKED] = false
        }
    }

    /** Parent access token renewed by /auth/refresh. */
    suspend fun updateAccessToken(accessToken: String) {
        dataStore.edit { p -> putSecret(p, ACCESS, accessToken) }
    }

    /** After a successful pairing: this phone is a kid-only device. */
    suspend fun saveDevicePairing(deviceToken: String, deviceId: String, familyId: String) {
        dataStore.edit { p ->
            p[ROLE] = DeviceRole.KID.name
            p[FAMILY_ID] = familyId
            p[DEVICE_ID] = deviceId
            putSecret(p, DEVICE, deviceToken)
            p.remove(ACCESS.key)
            p.remove(REFRESH.key)
            p[NEEDS_LOGIN] = false
            p[DEVICE_REVOKED] = false
        }
    }

    /**
     * Switch a parent device between parent and kid mode. Ignored (returns false) on any other role, so a kid-only
     * device can never be talked into parent mode. Leaving kid mode needs the PIN, which the UI layer enforces.
     */
    suspend fun setActiveMode(mode: ActiveMode): Boolean {
        var applied = false
        dataStore.edit { p ->
            if (p[ROLE] == DeviceRole.PARENT.name) {
                p[ACTIVE_MODE] = mode.name
                applied = true
            }
        }
        return applied
    }

    suspend fun setFamilyTimezone(timezone: String) {
        dataStore.edit { it[FAMILY_TZ] = timezone }
    }

    /** The refresh token was rejected (or is gone). Only meaningful on a parent device; data is kept. */
    suspend fun markNeedsLogin() {
        dataStore.edit { p -> if (p[ROLE] == DeviceRole.PARENT.name) p[NEEDS_LOGIN] = true }
    }

    /** The device token was rejected with 401. Only meaningful on a kid device; data is kept. */
    suspend fun markDeviceRevoked() {
        dataStore.edit { p -> if (p[ROLE] == DeviceRole.KID.name) p[DEVICE_REVOKED] = true }
    }

    /** Logout: removes the secrets only. Role, family, timezone, active mode and (elsewhere) all Room data stay. */
    suspend fun clearSecrets() {
        dataStore.edit { p ->
            p.remove(ACCESS.key)
            p.remove(REFRESH.key)
            p.remove(DEVICE.key)
        }
    }

    // ---- internals -------------------------------------------------------------------------

    private class Secret(val name: String) {
        val key = stringPreferencesKey("secret_$name")
        val aad: ByteArray = "kidtube.session.$name".toByteArray(Charsets.UTF_8)
    }

    private fun putSecret(p: androidx.datastore.preferences.core.MutablePreferences, secret: Secret, value: String) {
        val blob = cipher.encrypt(value.toByteArray(Charsets.UTF_8), secret.aad)
        p[secret.key] = Base64.getEncoder().encodeToString(blob)
    }

    private suspend fun readSecret(secret: Secret): String? {
        val stored = dataStore.data.first()[secret.key] ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(stored), secret.aad), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            discard(secret)
        } catch (_: IllegalArgumentException) { // corrupt base64 / blob
            discard(secret)
        }
    }

    /** An undecryptable secret (Keystore key lost, data tampered) is as good as absent; drop it so the state updates. */
    private suspend fun discard(secret: Secret): String? {
        dataStore.edit { it.remove(secret.key) }
        return null
    }

    private fun readInfo(p: Preferences): SessionInfo = SessionInfo(
        deviceRole = p[ROLE]?.let { runCatching { DeviceRole.valueOf(it) }.getOrNull() },
        familyId = p[FAMILY_ID],
        familyTimezone = p[FAMILY_TZ],
        deviceId = p[DEVICE_ID],
        activeMode = p[ACTIVE_MODE]?.let { runCatching { ActiveMode.valueOf(it) }.getOrNull() } ?: ActiveMode.PARENT,
    )

    private fun deriveState(p: Preferences): SessionState {
        val info = readInfo(p)
        return when (info.deviceRole) {
            null -> SessionState.Unauthenticated
            DeviceRole.PARENT ->
                if (p[NEEDS_LOGIN] == true || p[REFRESH.key] == null) SessionState.NeedsLogin
                else SessionState.ParentDevice(info.activeMode)
            DeviceRole.KID ->
                if (p[DEVICE_REVOKED] == true) SessionState.DeviceRevoked
                else if (p[DEVICE.key] == null) SessionState.Unauthenticated
                else SessionState.KidDevice
        }
    }

    private companion object {
        val ROLE = stringPreferencesKey("device_role")
        val FAMILY_ID = stringPreferencesKey("family_id")
        val FAMILY_TZ = stringPreferencesKey("family_timezone")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ACTIVE_MODE = stringPreferencesKey("active_mode")
        val NEEDS_LOGIN = booleanPreferencesKey("needs_login")
        val DEVICE_REVOKED = booleanPreferencesKey("device_revoked")
        val ACCESS = Secret("parent_access")
        val REFRESH = Secret("parent_refresh")
        val DEVICE = Secret("device_token")
    }
}
