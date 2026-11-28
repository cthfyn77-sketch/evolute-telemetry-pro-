package com.evolute.telemetrypro.data

/** Data contracts for the verified EVACONNECT Python client. */
class ApiClient private constructor() {
    data class Telemetry(
        val soc: Double, val odometer: Double, val stockRange: Double?,
        val online: Boolean, val ready: Boolean, val updatedAt: String?,
        val sensorsJson: String = "{}"
    )
    data class Tokens(
        val access: String, val refresh: String, val userId: String?,
        val userToken: String?, val widgetId: String?
    )
}

interface TokenStore {
    fun get(): ApiClient.Tokens?
    fun save(tokens: ApiClient.Tokens)
    fun clear()
}
