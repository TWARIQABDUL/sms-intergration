package com.smsgateway.app.network

import com.smsgateway.app.network.dto.PendingResponseDto
import com.smsgateway.app.network.dto.StatusRequestDto
import com.smsgateway.app.network.dto.StatusResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @GET("api/sms/pending")
    suspend fun getPending(): Response<PendingResponseDto>

    @POST("api/sms/status")
    suspend fun postStatus(@Body body: StatusRequestDto): Response<StatusResponseDto>
}
