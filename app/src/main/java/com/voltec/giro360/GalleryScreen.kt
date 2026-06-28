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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(eventId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val event = remember { EventStore.getEvent(context, eventId) }
    var recordings by remember { mutableStateOf(EventStore.loadRecordings(context, eventId)) }
    var selected by remember { mutableStateOf<Recording?>(null) }
    var qrFor by remember { mutableStateOf<Recording?>(null) }
    var waFor by remember { mutableStateOf<Recording?>(null) }

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
                    ActionRow(Icons.Filled.Send, "Enviar vídeo no WhatsApp") {
                        ShareUtil.sendVideoToWhatsApp(context, rec.filePath); selected = null
                    }
                    ActionRow(Icons.Filled.Link, "WhatsApp: link por número") {
                        waFor = rec; selected = null
                    }
                    ActionRow(Icons.Filled.Share, "Compartilhar (outros apps)") {
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
        QrDialog(rec, onUpdated = { refresh() }, onDismiss = { qrFor = null })
    }

    // WhatsApp por número
    waFor?.let { rec ->
        WhatsAppDialog(rec, onUpdated = { refresh() }, onDismiss = { waFor = null })
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
private fun QrDialog(rec: Recording, onUpdated: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shareUrl by remember { mutableStateOf(rec.shareUrl) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val configured = remember { AppConfig.isConfigured(context) }

    val qrBitmap = remember(shareUrl) {
        shareUrl?.let { ShareUtil.generateQr(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = { Text("QR Code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    qrBitmap != null -> {
                        Image(qrBitmap.asImageBitmap(), "QR", Modifier.size(220.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("O cliente aponta a câmera para baixar o vídeo.", fontSize = 13.sp)
                    }
                    !configured -> {
                        Text(
                            "Configure o servidor de vídeos primeiro: volte à tela inicial e " +
                                "toque na engrenagem para informar a URL e a chave do seu servidor.",
                            fontSize = 13.sp
                        )
                    }
                    uploading -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Enviando vídeo para o servidor…", fontSize = 13.sp)
                    }
                    else -> {
                        error?.let {
                            Text("Erro: $it", color = Color(0xFFEF5350), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(onClick = {
                            uploading = true; error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    CloudUploader.upload(context, rec.filePath)
                                }
                                uploading = false
                                when (result) {
                                    is CloudUploader.Result.Success -> {
                                        val updated = rec.copy(shareUrl = result.url)
                                        EventStore.updateRecording(context, updated)
                                        shareUrl = result.url
                                        onUpdated()
                                    }
                                    is CloudUploader.Result.Error -> error = result.message
                                }
                            }
                        }) { Text("Enviar e gerar QR") }
                    }
                }
            }
        }
    )
}

@Composable
private fun WhatsAppDialog(rec: Recording, onUpdated: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("+55 ") }
    var shareUrl by remember { mutableStateOf(rec.shareUrl) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val configured = remember { AppConfig.isConfigured(context) }
    val ready = configured || shareUrl != null

    fun openWhatsApp(url: String) {
        ShareUtil.sendWhatsApp(context, phone, "Seu vídeo Giro360: $url")
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enviar no WhatsApp") },
        text = {
            Column {
                if (!ready) {
                    Text(
                        "Para enviar por número, configure o servidor de vídeos (engrenagem na " +
                            "tela inicial). O WhatsApp envia o link de download do vídeo.",
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        "Digite o número com DDD (e país). O WhatsApp abre na conversa com o " +
                            "link do vídeo pronto para enviar.",
                        fontSize = 13.sp, color = Color.Gray
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it },
                        label = { Text("Número") },
                        placeholder = { Text("+55 11 99999-8888") },
                        singleLine = true
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Erro: $it", color = Color(0xFFEF5350), fontSize = 13.sp)
                    }
                    if (sending) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Enviando vídeo ao servidor…", fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (ready) {
                TextButton(
                    enabled = !sending && phone.any { it.isDigit() },
                    onClick = {
                        val url = shareUrl
                        if (url != null) {
                            openWhatsApp(url)
                        } else {
                            sending = true; error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    CloudUploader.upload(context, rec.filePath)
                                }
                                sending = false
                                when (result) {
                                    is CloudUploader.Result.Success -> {
                                        val updated = rec.copy(shareUrl = result.url)
                                        EventStore.updateRecording(context, updated)
                                        shareUrl = result.url
                                        onUpdated()
                                        openWhatsApp(result.url)
                                    }
                                    is CloudUploader.Result.Error -> error = result.message
                                }
                            }
                        }
                    }
                ) { Text("Enviar") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun VideoThumb(rec: Recording, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(rec.id) { mutableStateOf<Bitmap?>(null) }
    var status by remember(rec.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(rec.id) {
        thumb = withContext(Dispatchers.IO) { loadThumbnail(rec.filePath) }
    }
    LaunchedEffect(rec.id, rec.shareUrl) {
        if (rec.shareUrl != null) {
            status = withContext(Dispatchers.IO) { CloudUploader.fetchStatus(rec.shareUrl) }
        }
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
        // Badge de status (servidor)
        val (txt, col) = when {
            rec.shareUrl == null -> "No celular" to Color(0xFF455A64)
            status == "done" -> "Pronto" to Color(0xFF2E7D32)
            status == "processing" -> "Processando" to Color(0xFFB8860B)
            status == "error" -> "Erro" to Color(0xFFC62828)
            else -> "Enviado" to Color(0xFF455A64)
        }
        Surface(
            color = col, shape = RoundedCornerShape(6.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
        ) {
            Text(txt, color = Color.White, fontSize = 10.sp,
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
