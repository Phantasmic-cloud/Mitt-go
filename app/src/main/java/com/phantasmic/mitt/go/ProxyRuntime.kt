package com.phantasmic.mitt.go

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProxyRuntimeStatus(
    val starting: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
)

object ProxyRuntime {
    private val _status = MutableStateFlow(ProxyRuntimeStatus())
    val status = _status.asStateFlow()

    fun markStarting() {
        _status.value = ProxyRuntimeStatus(starting = true)
    }

    fun markRunning() {
        _status.value = ProxyRuntimeStatus(running = true)
    }

    fun markStopped() {
        _status.value = ProxyRuntimeStatus()
    }

    fun markError(message: String) {
        _status.value = ProxyRuntimeStatus(error = message)
    }
}