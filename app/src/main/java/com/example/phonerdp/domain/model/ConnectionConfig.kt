package com.example.phonerdp.domain.model

data class ConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val domain: String? = null,
) {
    companion object {
        fun empty(): ConnectionConfig = ConnectionConfig(
            host = "",
            port = 3389,
            username = "",
            password = "",
            domain = null
        )
    }
}