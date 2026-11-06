package com.bbro.kidtube.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Base64

/**
 * Claims read from a backend JWT WITHOUT verifying the signature (the app has no key). Only for scheduling
 * ("is it about to expire?") and for reading the family id; the server is what actually validates the token.
 */
data class JwtClaims(val subject: String?, val familyId: String?, val type: String?, val expiresAt: Instant?) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(token: String): JwtClaims? = try {
            val parts = token.split('.')
            if (parts.size != 3) {
                null
            } else {
                val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                val obj = json.parseToJsonElement(payload) as? JsonObject
                obj?.let {
                    JwtClaims(
                        subject = it["sub"]?.jsonPrimitive?.contentOrNull,
                        familyId = it["family_id"]?.jsonPrimitive?.contentOrNull,
                        type = it["type"]?.jsonPrimitive?.contentOrNull,
                        expiresAt = it["exp"]?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochSecond),
                    )
                }
            }
        } catch (_: IllegalArgumentException) { // bad base64 or bad JSON
            null
        }
    }
}
