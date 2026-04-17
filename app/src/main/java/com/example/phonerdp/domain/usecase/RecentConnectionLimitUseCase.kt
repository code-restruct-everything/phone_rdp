package com.example.phonerdp.domain.usecase

import com.example.phonerdp.domain.model.ConnectionConfig

class RecentConnectionLimitUseCase {
    fun applyLimit(items: List<ConnectionConfig>, maxCount: Int = 5): List<ConnectionConfig> {
        if (maxCount <= 0) return emptyList()
        return items.take(maxCount)
    }
}