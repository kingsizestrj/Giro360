package com.voltec.giro360

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(eventId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val event = remember { EventStore.getEvent(context, eventId) }
    var recordings by remember { mutableStateOf(EventStore.loadRecordings(context, eventId)) }
    var selected by remember { mutableStateOf<Recording?>(null) }
    var qrFor by remember { mutableStateOf<Recording?>(null) }

    fun refresh() { recordings = EventStore.loadRecordings(context, eventId) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(event?.name ?: "Galeria") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Voltar") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF101010),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (recordings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nenhum vídeo ainda.", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(recordings, key = { it.id }) { rec ->
                    VideoThumb(rec) { selected = rec }
                }
            }
        }
    }

    // Ações do vídeo selecionado
    selected?.let { rec ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {},
            title = { Text("Vídeo") },
            text = {
                Column {
                    ActionRow(Icons.Filled.PlayArrow, "Reproduzir") {
                        ShareUtil.playVideo(context, rec.filePath); selected = null
                    }
                    ActionRow(Icons.Filled.Share, "Compartilhar (WhatsApp, etc.)") {
                        ShareUtil.shareVideo(context, rec.filePath); selected = null
                    }
                    ActionRow(Icons.Filled.QrCode2, "QR Code") {
                        qrFor = rec; selected = null
                    }
                    ActionRow(Icons.Filled.Delete, "Excluir") {
                        EventStore.deleteRecording(context, rec.id); selected = null; refresh()
                    }
                }
            }
        )
    }

    // QR Code
    qrFor?.let { rec ->
        QrDialog(rec) { qrFor = null }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(16.dp))
        Text(label)
    }
}

@Composable
private fun QrDialog(rec: Recording, onDismiss: () -> Unit) {
    val qrBitmap = remember(rec.id) {
        if (rec.shareUrl != null) ShareUtil.generateQr(rec.shareUrl) else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = { Text("QR Code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (qrBitmap != null) {
                    Image(qrBitmap.asImageBitmap(), "QR", Modifier.size(220.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Aponte a câmera para baixar o vídeo.", fontSize = 13.sp)
                } else {
                    Text(
                        "O QR Code com link funciona depois que ativarmos o envio para a nuvem " +
                            "(próxima etapa). Por enquanto use o botão Compartilhar.",
                        fontSize = 13.sp
                    )
                }
            }
        }
    )
}

@Composable
private fun VideoThumb(rec: Recording, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(rec.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(rec.id) {
        thumb = withContext(Dispatchers.IO) { loadThumbnail(rec.filePath) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val t = thumb
        if (t != null) {
            Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.Movie, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
        }
        Icon(
            Icons.Filled.PlayCircle, null, tint = Color(0xCCFFFFFF),
            modifier = Modifier.size(44.dp)
        )
        Surface(
            color = Color(0xAA000000),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
        ) {
            Text(rec.effect.label, color = Color.White, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

private fun loadThumbnail(path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.getFrameAtTime(0)
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}
