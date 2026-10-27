package com.smsgateway.app.network.dto

data class PendingMessageDto(
    val id: String,
    val to: String,
    val body: String
)

data class PendingResponseDto(
    val messages: List<PendingMessageDto>
)

data class StatusRequestDto(
    val id: String,
    val status: String, // "delivered" | "failed"
    val error: String? = null
)

data class StatusResponseDto(
    val ok: Boolean
)
