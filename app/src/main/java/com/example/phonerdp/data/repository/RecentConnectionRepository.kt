package com.example.phonerdp.data.repository

import com.example.phonerdp.domain.model.ConnectionConfig

interface RecentConnectionRepository {
    fun getRecentConnections(): List<ConnectionConfig>
    fun saveConnection(config: ConnectionConfig)
}