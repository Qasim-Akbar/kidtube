package com.bbro.kidtube.data.remote

/** Result of GET /sync/pull. */
sealed interface PullOutcome {
    /** 304: nothing relevant changed since the snapshot with the stored ETag. */
    data object NotModified : PullOutcome

    data class Updated(val snapshot: SnapshotDto, val etag: String?) : PullOutcome
}

/**
 * The app-facing remote API. Every method returns an [ApiResult]; callers never see an exception.
 * Auth is handled underneath (interceptor + authenticator), see [ApiWiring].
 */
class KidTubeRemote(private val authApi: AuthApi, private val api: KidTubeApi) {
    // auth (anonymous)
    suspend fun register(request: RegisterRequest): ApiResult<RegisterResponse> = apiCall { authApi.register(request) }

    suspend fun login(email: String, password: String): ApiResult<TokenResponse> =
        apiCall { authApi.login(LoginRequest(email, password)) }

    suspend fun pair(code: String, deviceName: String): ApiResult<PairResponse> =
        apiCall { authApi.pair(PairRequest(code, deviceName)) }

    suspend fun health(): ApiResult<HealthDto> = apiCall { authApi.health() }

    // children
    suspend fun createChild(body: ChildCreate): ApiResult<ChildDto> = apiCall { api.createChild(body) }

    suspend fun listChildren(): ApiResult<List<ChildDto>> = apiCall { api.listChildren() }

    suspend fun patchChild(id: String, patch: ChildPatch): ApiResult<ChildDto> =
        apiCall { api.patchChild(id, patch.toJson()) }

    // devices (parent only)
    suspend fun createPairCode(): ApiResult<PairCodeResponse> = apiCall { api.createPairCode() }

    suspend fun listDevices(): ApiResult<List<DeviceDto>> = apiCall { api.listDevices() }

    suspend fun revokeDevice(id: String): ApiResult<DeviceDto> = apiCall { api.revokeDevice(id) }

    // videos
    suspend fun createVideo(url: String): ApiResult<VideoDto> = apiCall { api.createVideo(VideoCreate(url)) }

    suspend fun listVideos(): ApiResult<List<VideoDto>> = apiCall { api.listVideos() }

    suspend fun deleteVideo(id: String): ApiResult<Unit> = apiCall { api.deleteVideo(id) }

    suspend fun reverifyVideo(id: String): ApiResult<VideoDto> = apiCall { api.reverifyVideo(id) }

    // plans
    suspend fun getPlan(childId: String): ApiResult<PlanDto> = apiCall { api.getPlan(childId) }

    suspend fun putPlan(childId: String, body: PlanWrite): ApiResult<PlanDto> = apiCall { api.putPlan(childId, body) }

    suspend fun addPlanItem(childId: String, body: PlanItemCreate): ApiResult<PlanItemDto> =
        apiCall { api.addPlanItem(childId, body) }

    suspend fun patchPlanItem(id: String, patch: PlanItemPatch): ApiResult<PlanItemDto> =
        apiCall { api.patchPlanItem(id, patch.toJson()) }

    suspend fun deletePlanItem(id: String): ApiResult<Unit> = apiCall { api.deletePlanItem(id) }

    suspend fun reorderPlan(childId: String, itemIds: List<String>): ApiResult<PlanDto> =
        apiCall { api.reorderPlan(childId, ReorderRequest(itemIds)) }

    // reports (parent only)
    suspend fun getReport(childId: String, range: String, date: java.time.LocalDate): ApiResult<ReportDto> =
        apiCall { api.getReport(childId, range, date.toString()) }

    // events and sync
    suspend fun postEvents(events: List<EventDto>): ApiResult<EventBatchResult> =
        apiCall { api.postEvents(EventBatch(events)) }

    suspend fun push(body: PushBody): ApiResult<PushResult> = apiCall { api.push(body) }

    /** [ifNoneMatch] is the ETag header value stored from the previous snapshot (quotes included), or null. */
    suspend fun pull(ifNoneMatch: String?): ApiResult<PullOutcome> = guarded {
        val response = api.pull(ifNoneMatch)
        if (response.code() == 304) {
            ApiResult.Success(PullOutcome.NotModified)
        } else {
            mapResponse(response).map { PullOutcome.Updated(it, response.headers()["ETag"]) }
        }
    }
}
