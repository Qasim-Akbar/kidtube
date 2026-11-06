package com.bbro.kidtube.testing

import com.bbro.kidtube.data.remote.KidTubeJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

/**
 * One backend-generated API exchange (backend/tests/contract_fixtures/NN_name.json), read at test time from the
 * directory named by the `contract.fixtures.dir` system property (set in app/build.gradle.kts). Never copied, never
 * hand-edited: refresh them from the backend (see CLAUDE.md) and these tests follow.
 */
class ContractFixture(
    val file: String,
    val name: String,
    val status: Int,
    /** Response headers, lower-case names. */
    val headers: Map<String, String>,
    val requestMethod: String,
    val requestPath: String,
    val requestHeaders: Map<String, String>,
    val requestBody: JsonElement?,
    val body: JsonElement?,
) {
    /** The response body exactly as the server sent it (JSON text), or "" for none. */
    val bodyText: String get() = body?.takeIf { it !is JsonNull }?.toString().orEmpty()

    inline fun <reified T> decodeBody(): T =
        KidTubeJson.instance.decodeFromJsonElement(checkNotNull(body) { "$file has no body" })
}

object ContractFixtures {
    private val dir: File by lazy {
        val path = checkNotNull(System.getProperty("contract.fixtures.dir")) {
            "system property contract.fixtures.dir is not set (see app/build.gradle.kts)"
        }
        File(path).also { check(it.isDirectory) { "contract fixtures directory not found: $path" } }
    }

    /** Looks a fixture up by its numeric prefix, e.g. `load(73)` -> 73_sync_pull.json. */
    fun load(number: Int): ContractFixture {
        val prefix = "%02d_".format(number)
        val file = checkNotNull(dir.listFiles { f -> f.name.startsWith(prefix) && f.extension == "json" }?.singleOrNull()) {
            "no single contract fixture numbered $number in $dir"
        }
        val root = KidTubeJson.instance.parseToJsonElement(file.readText()).jsonObject
        val request = root.getValue("request").jsonObject
        return ContractFixture(
            file = file.name,
            name = root.getValue("name").jsonPrimitive.content,
            status = root.getValue("status").toString().toInt(),
            headers = root.stringMap("headers"),
            requestMethod = request.getValue("method").jsonPrimitive.content,
            requestPath = request.getValue("path").jsonPrimitive.content,
            requestHeaders = request.stringMap("headers"),
            requestBody = request["body"],
            body = root["body"],
        )
    }

    private fun JsonObject.stringMap(key: String): Map<String, String> =
        (this[key] as? JsonObject)?.entries?.associate { (k, v) -> k to v.jsonPrimitive.content }.orEmpty()
}
