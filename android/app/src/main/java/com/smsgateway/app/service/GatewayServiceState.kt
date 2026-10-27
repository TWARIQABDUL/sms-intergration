package com.smsgateway.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ServiceStatus(
    val isRunning: Boolean = false,
    val sentToday: Int = 0,
    val lastPollAtMillis: Long = 0L,
    val signalStrengthLabel: String = "unknown"
)

/** In-process shared state so the status screen can reflect the running service. */
object GatewayServiceState {
    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status

    fun update(transform: (ServiceStatus) -> ServiceStatus) {
        _status.value = transform(_status.value)
    }
}
