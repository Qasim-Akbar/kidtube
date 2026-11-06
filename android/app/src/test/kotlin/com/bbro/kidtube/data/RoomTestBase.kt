package com.bbro.kidtube.data

import androidx.room.Room
import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.SnapshotDto
import com.bbro.kidtube.testing.SessionHarness
import com.bbro.kidtube.testing.resourceText
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

/** In-memory Room (via Robolectric's SQLite) + a real SessionStore on a temp file + the fake clock. */
@RunWith(RobolectricTestRunner::class)
abstract class RoomTestBase {
    protected lateinit var db: KidTubeDatabase
    protected lateinit var session: SessionHarness
    protected val clock = FakeClock(Instant.parse("2026-11-05T10:00:00Z"))

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), KidTubeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = SessionHarness()
    }

    @After
    fun closeDatabase() {
        db.close()
        session.close()
    }

    protected fun loadSnapshot(name: String = "snapshot"): SnapshotDto =
        KidTubeJson.instance.decodeFromString(resourceText("/fixtures/$name.json"))

    companion object {
        const val CHILD_A = "33333333-3333-4333-8333-333333333333"
        const val CHILD_B = "33333333-3333-4333-8333-333333333334"
        const val ITEM_1 = "77777777-7777-4777-8777-777777777771"
        const val ITEM_2 = "77777777-7777-4777-8777-777777777772"
    }
}
