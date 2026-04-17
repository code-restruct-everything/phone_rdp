package com.example.phonerdp.ui.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.phonerdp.domain.model.ConnectionConfig

@Composable
fun ConnectionScreen(
    modifier: Modifier = Modifier,
    initialValue: ConnectionConfig,
    recentConnections: List<ConnectionConfig>,
    onConnectClick: (ConnectionConfig) -> Unit,
    onRecentSelected: (ConnectionConfig) -> Unit,
) {
    var host by remember(initialValue) { mutableStateOf(initialValue.host) }
    var port by remember(initialValue) { mutableIntStateOf(initialValue.port) }
    var username by remember(initialValue) { mutableStateOf(initialValue.username) }
    var password by remember(initialValue) { mutableStateOf(initialValue.password) }
    var domain by remember(initialValue) { mutableStateOf(initialValue.domain.orEmpty()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "Phone RDP", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Host / IP") },
            singleLine = true
        )

        OutlinedTextField(
            value = port.toString(),
            onValueChange = { input -> port = input.toIntOrNull() ?: 0 },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Port") },
            singleLine = true
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Username") },
            singleLine = true
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )

        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Domain (Optional)") },
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onConnectClick(
                        ConnectionConfig(
                            host = host,
                            port = port,
                            username = username,
                            password = password,
                            domain = domain.ifBlank { null }
                        )
                    )
                }
            ) {
                Text("Connect")
            }
        }

        Text(text = "Recent", style = MaterialTheme.typography.titleMedium)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recentConnections) { config ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            host = config.host
                            port = config.port
                            username = config.username
                            password = config.password
                            domain = config.domain.orEmpty()
                            onRecentSelected(config)
                        }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "${config.host}:${config.port}")
                        Text(text = config.username, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
