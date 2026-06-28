package com.voltec.giro360

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
    var newName by remember { mutableStateOf("") }

    fun refresh() { events = EventStore.loadEvents(context) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Giro360", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF101010), titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newName = ""; showCreate = true },
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
                    Icon(Icons.Filled.Celebration, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Crie seu primeiro evento para começar a gravar vídeos 360.",
                        color = Color.Gray
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
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C1C1E),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(event.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "${df.format(Date(event.createdAt))} • $count vídeo(s)",
                        color = Color.Gray, fontSize = 13.sp
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, "Excluir", tint = Color(0xFFEF5350))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRecord, modifier = Modifier.weight(1f)) {
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
