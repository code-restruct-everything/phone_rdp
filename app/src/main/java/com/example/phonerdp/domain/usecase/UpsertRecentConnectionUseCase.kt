package com.example.phonerdp.domain.usecase

import com.example.phonerdp.domain.model.ConnectionConfig

class UpsertRecentConnectionUseCase(
    private val limitUseCase: RecentConnectionLimitUseCase = RecentConnectionLimitUseCase(),
) {
    operator fun invoke(
        existing: List<ConnectionConfig>,
        incoming: ConnectionConfig,
        maxCount: Int = 5,
    ): List<ConnectionConfig> {
        val deduplicated = existing.filterNot { current ->
            current.host == incoming.host &&
                current.port == incoming.port &&
                current.username == incoming.username &&
                current.domain == incoming.domain
        }

        return limitUseCase.applyLimit(
            items = listOf(incoming) + deduplicated,
            maxCount = maxCount
        )
    }
}
