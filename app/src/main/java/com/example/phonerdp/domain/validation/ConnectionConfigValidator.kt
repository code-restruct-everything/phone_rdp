package com.example.phonerdp.domain.validation

import com.example.phonerdp.domain.model.ConnectionConfig

class ConnectionConfigValidator {
    fun validate(config: ConnectionConfig): ValidationResult {
        if (config.host.isBlank()) {
            return ValidationResult(false, "Host/IP is required.")
        }
        if (config.port !in 1..65535) {
            return ValidationResult(false, "Port must be between 1 and 65535.")
        }
        if (config.username.isBlank()) {
            return ValidationResult(false, "Username is required.")
        }
        if (config.password.isBlank()) {
            return ValidationResult(false, "Password is required.")
        }
        return ValidationResult(true, "OK")
    }
}

data class ValidationResult(
    val isValid: Boolean,
    val message: String,
)