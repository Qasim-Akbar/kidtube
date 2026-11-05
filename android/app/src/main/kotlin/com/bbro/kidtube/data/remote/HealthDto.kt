package com.bbro.kidtube.data.remote

import kotlinx.serialization.Serializable

/** Response of GET /health. */
@Serializable
data class HealthDto(val status: String)
