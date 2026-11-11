package com.bbro.kidtube.share

import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.core.PinHasher
import com.bbro.kidtube.core.PinStore
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.PlanWrite
import com.bbro.kidtube.data.repo.ChildSetupRepository
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.DEFAULT_PLAN
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.data.repo.SnapshotRepository
import com.bbro.kidtube.data.repo.VideoRepository
import com.bbro.kidtube.sync.SyncTestBase
import com.bbro.kidtube.sync.SyncTrigger
import com.bbro.kidtube.testing.ContractFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.time.LocalDate

/** The share sheet's logic over the production stack: Room, the real repositories, the outbox and a fake backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest : SyncTestBase() {
    private lateinit var pins: PinStore
    private var online = true

    private val newLink = "https://youtu.be/zzzzzzzzzzz"
    private val aliHasThis = "https://youtu.be/vid00000001" // Ali's plan already contains it (contract fixture 73)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        pins = PinStore(session.backing, session.cipher, clock, PinHasher(SecureRandom(), 1_000))
        route("PUT /children/*") { json(ContractFixtures.load(25).status, ContractFixtures.load(25).bodyText) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): ShareViewModel {
        val connectivity = ConnectivityChecker { online }
        val setup = ChildSetupRepository(
            stack.remote, SnapshotRepository(stack.remote, db.snapshotDao(), session.store, clock), session.store, clock, connectivity, SyncTrigger.None,
        )
        return ShareViewModel(
            session.store, pins, ChildrenRepository(db.childDao()), VideoRepository(db.videoDao()), PlanRepository(db.planDao()),
            outboxRepo, setup, connectivity,
        )
    }

    private suspend fun ShareViewModel.until(predicate: (ShareUiState) -> Boolean) = withTimeout(10_000) { state.first(predicate) }

    private suspend fun readyForm(text: String = "Look! $newLink"): ShareViewModel {
        loginAsParent()
        pins.setPin("1234")
        seedSnapshot()
        return viewModel().also {
            it.start(text)
            it.until { s -> s.phase == SharePhase.FORM }
        }
    }

    private fun ShareViewModel.childId(name: String) = state.value.children.first { it.name == name }.id

    private suspend fun outboxPlacements() = db.outboxDao().pendingVideos().flatMap { db.outboxDao().placementsFor(it.id) }

    private fun putPlans() = requests("PUT", "/children/").map { KidTubeJson.instance.decodeFromString(PlanWrite.serializer(), it.body) }

    // ---- who may use the sheet, and what it can show --------------------------------------------------------------

    @Test
    fun aKidOnlyDevice_getsNoSharing_andNothingIsSaved() = runBlocking<Unit> {
        pairAsKid()
        seedSnapshot()
        val vm = viewModel()

        vm.start(newLink)

        assertEquals(SharePhase.NOT_PARENT, vm.until { it.phase == SharePhase.NOT_PARENT }.phase)
        assertTrue(db.outboxDao().pendingVideos().isEmpty())
    }

    @Test
    fun anUnsupportedLink_showsTheUnsupportedScreen() = runBlocking<Unit> {
        loginAsParent()
        seedSnapshot()
        val vm = viewModel()

        vm.start("https://music.youtube.com/watch?v=dQw4w9WgXcQ")

        vm.until { it.phase == SharePhase.UNSUPPORTED }
    }

    @Test
    fun textWithoutAnyLink_isUnsupportedToo() = runBlocking<Unit> {
        loginAsParent()
        val vm = viewModel()
        vm.start("just some words")
        vm.until { it.phase == SharePhase.UNSUPPORTED }
    }

    @Test
    fun noChildrenYet_asksForAChildFirst() = runBlocking<Unit> {
        loginAsParent()
        val vm = viewModel()

        vm.start(newLink) // nothing pulled yet

        vm.until { it.phase == SharePhase.NO_CHILDREN }
    }

    @Test
    fun aKnownVideo_showsItsTitleAndNoOtherwise() = runBlocking<Unit> {
        val vm = readyForm("watch $aliHasThis")
        assertEquals("Counting to ten", vm.state.value.link?.title)

        val unknown = readyFormFor("https://youtu.be/zzzzzzzzzzz")
        assertNull(unknown.state.value.link?.title)
        assertNull(unknown.state.value.link?.thumbnailUrl)
    }

    private suspend fun readyFormFor(text: String) = viewModel().also {
        it.start(text)
        it.until { s -> s.phase == SharePhase.FORM }
    }

    // ---- saving ---------------------------------------------------------------------------------------------------

    @Test
    fun save_needsAPin_andSomeone() = runBlocking<Unit> {
        val vm = readyForm()
        assertFalse(vm.state.value.canSave)

        vm.onToggleChild(vm.childId("Sara"))
        assertFalse("no PIN yet", vm.state.value.canSave)
        vm.onPinChange("12")
        assertFalse(vm.state.value.canSave)
        vm.onPinChange("1234")
        assertTrue(vm.state.value.canSave)

        vm.onToggleChild(vm.childId("Sara"))
        assertFalse("nobody selected", vm.state.value.canSave)
        vm.onPreset(SharePreset.LIBRARY_ONLY)
        assertTrue("library only needs no child", vm.state.value.canSave)
    }

    @Test
    fun save_writesTheOutbox_withTheChosenPreset_andShowsSaved() = runBlocking<Unit> {
        val vm = readyForm()
        val sara = vm.childId("Sara")
        vm.onToggleChild(sara)
        vm.onPreset(SharePreset.THREE_TIMES)
        vm.onPinChange("1234")

        vm.save()
        val saved = vm.until { it.phase == SharePhase.SAVED }

        assertEquals(SavedInfo(libraryOnly = false), saved.saved)
        val video = db.outboxDao().pendingVideos().single()
        assertEquals(newLink, video.rawUrl)
        val placement = db.outboxDao().placementsFor(video.id).single()
        assertEquals(sara, placement.childId)
        assertEquals(3, placement.viewTarget)
        assertEquals(1, placement.dailyCap)
        assertFalse(placement.required)
    }

    @Test
    fun save_forAllChildren_writesOnePlacementPerChild() = runBlocking<Unit> {
        val vm = readyForm()
        vm.onToggleAll()
        assertTrue(vm.state.value.allSelected)
        vm.onPreset(SharePreset.MUST_WATCH)
        vm.onPinChange("1234")

        vm.save()
        vm.until { it.phase == SharePhase.NO_CHILDREN || it.phase == SharePhase.SAVED || it.planOffer != null }

        // Pip has no plan, so the sheet first asks; choosing "save without a plan" completes the save for everyone.
        vm.answerPlanOffer(createPlans = false)
        vm.until { it.phase == SharePhase.SAVED }
        val placements = outboxPlacements()
        assertEquals(vm.state.value.children.map { it.id }.toSet(), placements.map { it.childId }.toSet())
        assertTrue(placements.all { it.required })
    }

    @Test
    fun save_libraryOnly_addsTheVideoWithoutPlacements() = runBlocking<Unit> {
        val vm = readyForm()
        vm.onPreset(SharePreset.LIBRARY_ONLY)
        vm.onPinChange("1234")

        vm.save()
        assertEquals(true, vm.until { it.phase == SharePhase.SAVED }.saved?.libraryOnly)

        assertEquals(1, db.outboxDao().pendingVideos().size)
        assertTrue(outboxPlacements().isEmpty())
    }

    @Test
    fun save_wrongPin_savesNothing_andShowsTriesLeft() = runBlocking<Unit> {
        val vm = readyForm()
        vm.onToggleChild(vm.childId("Sara"))
        vm.onPinChange("9999")

        vm.save()
        val state = vm.until { it.attemptsLeft != null }

        assertEquals(4, state.attemptsLeft)
        assertEquals("", state.pin)
        assertEquals(SharePhase.FORM, state.phase)
        assertTrue(db.outboxDao().pendingVideos().isEmpty())
    }

    @Test
    fun save_works_withNoInternet() = runBlocking<Unit> {
        online = false
        val vm = readyForm()
        assertTrue(vm.state.value.offline)
        vm.onToggleChild(vm.childId("Sara"))
        vm.onPinChange("1234")

        vm.save()
        vm.until { it.phase == SharePhase.SAVED }

        assertEquals(1, db.outboxDao().pendingVideos().size)
        assertTrue("saving locally sends nothing", requests("POST", "/").isEmpty())
    }

    // ---- duplicates -----------------------------------------------------------------------------------------------

    @Test
    fun aVideoAlreadyInThatChildsPlan_isNotAddedTwice() = runBlocking<Unit> {
        val vm = readyForm("share $aliHasThis")
        vm.onToggleChild(vm.childId("Ali"))
        vm.onPinChange("1234")

        vm.save()
        val state = vm.until { it.note != null }

        assertEquals(ShareNote.AlreadySaved(listOf("Ali")), state.note)
        assertEquals(SharePhase.FORM, state.phase)
        assertTrue(db.outboxDao().pendingVideos().isEmpty())
    }

    @Test
    fun withTwoChildren_theOneWhoHasItIsSkipped_theOtherGetsIt() = runBlocking<Unit> {
        val vm = readyForm("share $aliHasThis")
        val sara = vm.childId("Sara")
        vm.onToggleChild(vm.childId("Ali"))
        vm.onToggleChild(sara)
        vm.onPinChange("1234")

        vm.save()
        val saved = vm.until { it.phase == SharePhase.SAVED }

        assertEquals(listOf("Ali"), saved.saved?.skippedNames)
        assertEquals(listOf(sara), outboxPlacements().map { it.childId })
    }

    @Test
    fun sharingTheSameLinkTwice_secondTimeFindsItInTheOutbox() = runBlocking<Unit> {
        val first = readyForm()
        val sara = first.childId("Sara")
        first.onToggleChild(sara)
        first.onPinChange("1234")
        first.save()
        first.until { it.phase == SharePhase.SAVED }

        val second = readyFormFor(newLink)
        second.onToggleChild(sara)
        second.onPinChange("1234")
        second.save()

        assertEquals(ShareNote.AlreadySaved(listOf("Sara")), second.until { it.note != null }.note)
        assertEquals(1, db.outboxDao().pendingVideos().size)
    }

    @Test
    fun libraryOnly_forAVideoAlreadyInTheLibrary_saysSo() = runBlocking<Unit> {
        val vm = readyForm("share $aliHasThis")
        vm.onPreset(SharePreset.LIBRARY_ONLY)
        vm.onPinChange("1234")

        vm.save()

        assertEquals(ShareNote.AlreadyInLibrary, vm.until { it.note != null }.note)
        assertTrue(db.outboxDao().pendingVideos().isEmpty())
    }

    // ---- a child without a plan -----------------------------------------------------------------------------------

    @Test
    fun aChildWithoutAPlan_isOfferedTheDefaultPlan_insteadOfSilentlyWaiting() = runBlocking<Unit> {
        val vm = readyForm()
        vm.onToggleChild(vm.childId("Pip")) // Pip has no plan in the snapshot
        vm.onPinChange("1234")

        vm.save()
        val state = vm.until { it.planOffer != null }

        assertEquals(PlanOffer(listOf("Pip")), state.planOffer)
        assertTrue("nothing is saved until the parent answers", db.outboxDao().pendingVideos().isEmpty())
        assertTrue(putPlans().isEmpty())
    }

    @Test
    fun noOfferWhenEveryChosenChildHasAPlan_orForLibraryOnly() = runBlocking<Unit> {
        val withPlan = readyForm()
        withPlan.onToggleChild(withPlan.childId("Sara"))
        withPlan.onPinChange("1234")
        withPlan.save()
        assertNull(withPlan.until { it.phase == SharePhase.SAVED }.planOffer)

        val library = readyFormFor("https://youtu.be/yyyyyyyyyyy")
        library.onToggleChild(library.childId("Pip"))
        library.onPreset(SharePreset.LIBRARY_ONLY)
        library.onPinChange("1234")
        library.save()
        assertNull(library.until { it.phase == SharePhase.SAVED }.planOffer)
    }

    @Test
    fun acceptingTheOffer_createsTheDefaultPlan_thenSaves_withoutAskingForThePinAgain() = runBlocking<Unit> {
        val vm = readyForm()
        val pip = vm.childId("Pip")
        vm.onToggleChild(pip)
        vm.onPreset(SharePreset.MUST_WATCH)
        vm.onPinChange("1234")
        vm.save()
        vm.until { it.planOffer != null }

        vm.answerPlanOffer(createPlans = true)
        val saved = vm.until { it.phase == SharePhase.SAVED }

        assertEquals(listOf("/children/$pip/plan"), requests("PUT", "/children/").map { it.path })
        assertEquals(listOf(DEFAULT_PLAN.toWrite(LocalDate.parse("2026-11-05"))), putPlans())
        assertEquals(emptyList<String>(), saved.saved?.waitingForPlanNames)
        val placement = outboxPlacements().single()
        assertEquals(pip, placement.childId)
        assertTrue(placement.required)
    }

    @Test
    fun ifThePlanCannotBeCreated_theLinkIsStillSaved_andWaitsForAPlan() = runBlocking<Unit> {
        route("PUT /children/*") { json(503, """{"detail":"down"}""") }
        val vm = readyForm()
        vm.onToggleChild(vm.childId("Pip"))
        vm.onPinChange("1234")
        vm.save()
        vm.until { it.planOffer != null }

        vm.answerPlanOffer(createPlans = true)
        val saved = vm.until { it.phase == SharePhase.SAVED }

        assertEquals(listOf("Pip"), saved.saved?.waitingForPlanNames)
        assertEquals(1, outboxPlacements().size) // kept pending: the sync engine marks it "no_plan" and retries later
    }

    @Test
    fun saveWithoutAPlan_sendsNoPlanRequest_andSavesPending() = runBlocking<Unit> {
        val vm = readyForm()
        vm.onToggleChild(vm.childId("Pip"))
        vm.onPinChange("1234")
        vm.save()
        vm.until { it.planOffer != null }

        vm.answerPlanOffer(createPlans = false)
        val saved = vm.until { it.phase == SharePhase.SAVED }

        assertTrue(putPlans().isEmpty())
        assertEquals(listOf("Pip"), saved.saved?.waitingForPlanNames)
        assertEquals(1, outboxPlacements().size)
    }

    @Test
    fun cancellingTheOffer_savesNothing() = runBlocking<Unit> {
        val vm = readyForm()
        vm.onToggleChild(vm.childId("Pip"))
        vm.onPinChange("1234")
        vm.save()
        vm.until { it.planOffer != null }

        vm.cancelPlanOffer()

        assertNull(vm.state.value.planOffer)
        assertEquals(SharePhase.FORM, vm.state.value.phase)
        assertTrue(db.outboxDao().pendingVideos().isEmpty())
    }
}
