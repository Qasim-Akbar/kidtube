package com.bbro.kidtube.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Endpoints that never carry a bearer token. Served by a client WITHOUT the auth interceptor/authenticator, so a
 * wrong password or an invalid pairing code is a plain 4xx and never triggers a token refresh.
 */
interface AuthApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<RegisterResponse>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<TokenResponse>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<AccessTokenResponse>

    @POST("devices/pair")
    suspend fun pair(@Body body: PairRequest): Response<PairResponse>

    @GET("health")
    suspend fun health(): Response<HealthDto>
}

/** Endpoints that need a bearer token (parent JWT, or a kid device token where the backend allows it). */
interface KidTubeApi {
    // children
    @POST("children")
    suspend fun createChild(@Body body: ChildCreate): Response<ChildDto>

    @GET("children")
    suspend fun listChildren(): Response<List<ChildDto>>

    @PATCH("children/{id}")
    suspend fun patchChild(@Path("id") id: String, @Body body: JsonObject): Response<ChildDto>

    // devices (parent only)
    @POST("devices/pair-code")
    suspend fun createPairCode(): Response<PairCodeResponse>

    @GET("devices")
    suspend fun listDevices(): Response<List<DeviceDto>>

    @POST("devices/{id}/revoke")
    suspend fun revokeDevice(@Path("id") id: String): Response<DeviceDto>

    // videos
    @POST("videos")
    suspend fun createVideo(@Body body: VideoCreate): Response<VideoDto>

    @GET("videos")
    suspend fun listVideos(): Response<List<VideoDto>>

    @DELETE("videos/{id}")
    suspend fun deleteVideo(@Path("id") id: String): Response<Unit>

    @POST("videos/{id}/reverify")
    suspend fun reverifyVideo(@Path("id") id: String): Response<VideoDto>

    // plans
    @GET("children/{childId}/plan")
    suspend fun getPlan(@Path("childId") childId: String): Response<PlanDto>

    @PUT("children/{childId}/plan")
    suspend fun putPlan(@Path("childId") childId: String, @Body body: PlanWrite): Response<PlanDto>

    @POST("children/{childId}/plan/items")
    suspend fun addPlanItem(@Path("childId") childId: String, @Body body: PlanItemCreate): Response<PlanItemDto>

    @PATCH("plan-items/{id}")
    suspend fun patchPlanItem(@Path("id") id: String, @Body body: JsonObject): Response<PlanItemDto>

    @DELETE("plan-items/{id}")
    suspend fun deletePlanItem(@Path("id") id: String): Response<Unit>

    @POST("children/{childId}/plan/reorder")
    suspend fun reorderPlan(@Path("childId") childId: String, @Body body: ReorderRequest): Response<PlanDto>

    // reports (parent only)
    @GET("reports/children/{childId}")
    suspend fun getReport(@Path("childId") childId: String, @Query("range") range: String, @Query("date") date: String): Response<ReportDto>

    // events
    @POST("events")
    suspend fun postEvents(@Body body: EventBatch): Response<EventBatchResult>

    // sync
    @GET("sync/pull")
    suspend fun pull(@Header("If-None-Match") ifNoneMatch: String?): Response<SnapshotDto>

    @POST("sync/push")
    suspend fun push(@Body body: PushBody): Response<PushResult>
}
