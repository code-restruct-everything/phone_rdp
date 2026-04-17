package com.example.phonerdp.data.repository

import com.example.phonerdp.domain.model.ConnectionConfig
import com.example.phonerdp.domain.usecase.UpsertRecentConnectionUseCase

class InMemoryRecentConnectionRepository : RecentConnectionRepository {
    private val storage = mutableListOf<ConnectionConfig>()
    private val upsertUseCase = UpsertRecentConnectionUseCase()

    override fun getRecentConnections(): List<ConnectionConfig> = storage.toList()

    override fun saveConnection(config: ConnectionConfig) {
        val trimmed = upsertUseCase(storage, config, maxCount = 5)
        storage.clear()
        storage.addAll(trimmed)
    }
}
