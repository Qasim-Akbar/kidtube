package com.bbro.kidtube.data

import androidx.room.Room
import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.data.remote.SnapshotDto
import com.bbro.kidtube.testing.SessionHarness
import com.bbro.kidtube.testing.ContractFixtures
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

    /** The backend-generated pull response (contract fixture 73): five children, Ali has a 4-item plan, Pip has none. */
    protected fun loadSnapshot(): SnapshotDto = ContractFixtures.load(73).decodeBody()

    companion object {
        /** Ali: plan with 4 items, 1 server view of ITEM_1. */
        const val CHILD_A = "00000000-0000-4000-8000-000000000003"

        /** Pip: no plan, no history. */
        const val CHILD_B = "00000000-0000-4000-8000-000000000007"

        /** Ali's required item (position 0). */
        const val ITEM_1 = "00000000-0000-4000-8000-000000000017"

        /** Ali's optional item (position 1). */
        const val ITEM_2 = "00000000-0000-4000-8000-000000000020"
    }
}
