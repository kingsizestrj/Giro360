package com.voltec.giro360

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    onOpenEvent: (String) -> Unit,
    onOpenGallery: (String) -> Unit
) {
    val context = LocalContext.current
    var events by remember { mutableStateOf(EventStore.loadEvents(context)) }
    var showCreate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    fun refresh() { events = EventStore.loadEvents(context) }

    Scaffold(
        containerColor = Prime.Bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CameraAlt, null, tint = Prime.Violet, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Prime", fontWeight = FontWeight.Bold)
                        Text("360", fontWeight = FontWeight.Bold, color = Prime.Pink)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, "Configurações", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Prime.Bg, titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newName = ""; showCreate = true },
                containerColor = Prime.Violet,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Novo evento") }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (events.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Celebration, null, tint = Prime.Violet, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Nenhum evento ainda", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Toque em + para criar um evento e começar a gravar seus vídeos 360.",
                        color = Prime.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(events, key = { it.id }) { e ->
                        EventCard(
                            event = e,
                            count = EventStore.recordingCount(context, e.id),
                            onRecord = { onOpenEvent(e.id) },
                            onGallery = { onOpenGallery(e.id) },
                            onDelete = { EventStore.deleteEvent(context, e.id); refresh() }
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Novo evento") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("Nome do evento") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val ev = Event(
                            id = UUID.randomUUID().toString(),
                            name = newName.trim(),
                            createdAt = System.currentTimeMillis()
                        )
                        EventStore.saveEvent(context, ev)
                        showCreate = false
                        refresh()
                        onOpenEvent(ev.id)
                    }
                ) { Text("Criar e gravar") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancelar") } }
        )
    }

    if (showSettings) {
        ServerSettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
private fun ServerSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(AppConfig.getServerUrl(context)) }
    var apiKey by remember { mutableStateOf(AppConfig.getApiKey(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servidor de vídeos") },
        text = {
            Column {
                Text(
                    "Informe o endereço do seu servidor Giro360 (Docker). Usado para gerar " +
                        "o QR Code de download dos vídeos.",
                    fontSize = 13.sp, color = Color.Gray
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("URL do servidor") },
                    placeholder = { Text("http://192.168.0.10:8080") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("Chave (API key)") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppConfig.save(context, url, apiKey)
                onDismiss()
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun EventCard(
    event: Event,
    count: Int,
    onRecord: () -> Unit,
    onGallery: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val df = remember { SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Prime.Surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Prime.SurfaceHi),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Movie, null, tint = Prime.Violet, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "${df.format(Date(event.createdAt))} • $count vídeo(s)",
                        color = Prime.TextDim, fontSize = 13.sp
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, "Excluir", tint = Prime.TextDim)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRecord, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Prime.Violet, contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Gravar")
                }
                OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Galeria")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir evento?") },
            text = { Text("Isso apaga o evento e todos os vídeos dele.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Excluir", color = Color(0xFFEF5350))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}
