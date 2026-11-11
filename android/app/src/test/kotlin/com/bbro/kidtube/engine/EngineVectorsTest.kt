package com.bbro.kidtube.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Parity with the Python engine: every JSON file in backend/tests/engine_vectors (system property `engine.vectors.dir`,
 * resolved at test time, nothing copied) must produce EXACTLY its `expected` output. One test case per file, named
 * `run[feed/NN_name.json]`. The vectors are the contract; never edit them to make a test pass.
 */
@RunWith(Parameterized::class)
class EngineVectorsTest(private val vector: EngineVector) {
    @Test
    fun run() {
        val actual = when (vector.kind) {
            "feed" -> EngineVectors.runFeed(vector.input)
            "projection" -> EngineVectors.runProjection(vector.input)
            else -> error("unknown vector kind ${vector.kind}")
        }
        // JsonObject equality is strict: same keys, same values, same array order, JsonNull vs missing.
        assertEquals("vector ${vector.id} (${vector.name})", vector.expected, actual)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<Any>> = EngineVectors.loadAll().map { arrayOf<Any>(it) }
    }
}

/** Fails loudly when the vectors are not wired in, so a green run can never mean "zero vectors ran". */
class EngineVectorsGuardTest {
    @Test
    fun systemPropertyIsSetAndPointsAtADirectory() {
        val prop = System.getProperty(EngineVectors.PROPERTY)
        assertTrue("system property ${EngineVectors.PROPERTY} is not set (see app/build.gradle.kts)", prop != null)
        assertTrue("engine vectors directory not found: $prop", EngineVectors.dirOrNull() != null)
    }

    @Test
    fun vectorsExistAndMeetTheBackendMinimums() {
        val all = EngineVectors.loadAll()
        assertTrue("no engine vectors found in ${System.getProperty(EngineVectors.PROPERTY)}", all.isNotEmpty())
        // Same minimums as backend/tests/test_engine_vectors.py.
        assertTrue("expected >= 40 feed vectors, found ${all.count { it.kind == "feed" }}", all.count { it.kind == "feed" } >= 40)
        assertTrue("expected >= 15 projection vectors, found ${all.count { it.kind == "projection" }}", all.count { it.kind == "projection" } >= 15)
    }

    @Test
    fun vectorFilesAreWellFormed() {
        val all = EngineVectors.loadAll()
        for (kind in EngineVectors.KINDS) {
            val names = all.filter { it.kind == kind }.map { it.name }
            assertEquals("duplicate vector names in $kind", names.size, names.toSet().size)
        }
        for (v in all) assertEquals("file name must be NN_<name>.json", v.file.substringAfter('_').removeSuffix(".json"), v.name)
    }

    @Test
    fun parameterizedRunnerSeesEveryFile() {
        val onDisk = EngineVectors.KINDS.sumOf { kind ->
            java.io.File(EngineVectors.dirOrNull()!!, kind).listFiles { f -> f.extension == "json" }.orEmpty().size
        }
        assertEquals(onDisk, EngineVectors.loadAll().size)
    }
}
