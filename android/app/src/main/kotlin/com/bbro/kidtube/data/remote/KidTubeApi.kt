package com.bbro.kidtube.data.remote

import retrofit2.http.GET

/** Backend API. Only /health for now; the rest of SPEC section 11 is added task by task. */
interface KidTubeApi {
    @GET("health")
    suspend fun health(): HealthDto
}
