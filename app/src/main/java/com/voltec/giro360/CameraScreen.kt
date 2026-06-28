package com.voltec.giro360

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

private const val TAG = "Giro360"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    eventId: String,
    onBack: () -> Unit,
    onOpenGallery: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Evento + configurações editáveis (salvas de volta no evento)
    var event by remember { mutableStateOf(EventStore.getEvent(context, eventId)) }
    val ev = event ?: return

    var effect by remember { mutableStateOf(ev.effect) }
    var duration by remember { mutableStateOf(ev.durationSeconds) }
    var countdownSeconds by remember { mutableStateOf(ev.countdownSeconds) }
    var frameId by remember { mutableStateOf(ev.frameId) }
    var customFrameUri by remember { mutableStateOf(ev.customFrameUri?.let { Uri.parse(it) }) }
    var musicUri by remember { mutableStateOf(ev.musicUri?.let { Uri.parse(it) }) }
    var musicName by remember { mutableStateOf(ev.musicName) }
    var boomFps by remember { mutableStateOf(ev.boomerangFps) }
    var boomWidth by remember { mutableStateOf(ev.boomerangWidth) }

    // Salva as configurações no evento sempre que algo muda
    fun persist() {
        val updated = ev.copy(
            effect = effect,
            durationSeconds = duration,
            countdownSeconds = countdownSeconds,
            boomerangFps = boomFps,
            boomerangWidth = boomWidth,
            frameId = frameId,
            customFrameUri = customFrameUri?.toString(),
            musicUri = musicUri?.toString(),
            musicName = musicName
        )
        EventStore.saveEvent(context, updated)
        event = updated
    }

    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(0f) }
    var showSettings by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var recordProgress by remember { mutableStateOf(0f) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var qrUrl by remember { mutableStateOf<String?>(null) }
    val previewView = remember { PreviewView(context) }

    val isSlow = effect == Effect.SLOW

    // Pickers
    val frameLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) { customFrameUri = uri; frameId = null; persist() } }
    val musicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            musicUri = uri
            musicName = "Música escolhida"
            persist()
        }
    }

    // Liga a câmera (recria quando a qualidade muda por causa do slow motion)
    LaunchedEffect(isSlow) {
        // getInstance().get() é bloqueante -> fora da main; bind volta para a main.
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        val result = bindCamera(context, provider, lifecycleOwner, previewView, isSlow)
        videoCapture = result.first
        camera = result.second
        camera?.cameraControl?.setLinearZoom(zoom)
    }

    // Aplica o zoom
    LaunchedEffect(zoom) { camera?.cameraControl?.setLinearZoom(zoom) }

    // Ao sair da tela: encerra a gravação ativa (será finalizada e salva) e cancela a contagem.
    DisposableEffect(Unit) {
        onDispose {
            try { recording?.stop() } catch (_: Exception) {}
            recording = null
            countdownJob?.cancel()
        }
    }

    fun finalizeRecording(sourcePath: String) {
        val appCtx = context.applicationContext
        val curEffect = effect
        val curMusic = musicUri
        val curFps = boomFps
        val curW = boomWidth
        val curFrameId = frameId
        val curCustomFrame = customFrameUri
        // Roda num escopo de aplicação (GiroScope): processamento, gravação e upload
        // terminam mesmo se o usuário sair da tela — nada de vídeo perdido.
        GiroScope.io.launch {
            suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
            try {
                if (AppConfig.isConfigured(appCtx)) {
                    // MODO SERVIDOR: envia o vídeo BRUTO + efeito; o servidor processa
                    // (FFmpeg) e o QR aparece na hora. Mantém uma cópia local.
                    val saved = com.voltec.giro360.Recording(
                        id = UUID.randomUUID().toString(),
                        eventId = eventId,
                        filePath = sourcePath,
                        createdAt = System.currentTimeMillis(),
                        effect = curEffect
                    )
                    EventStore.addRecording(appCtx, saved)
                    // Moldura: embutida -> PNG; da galeria -> bytes da imagem.
                    val frameBytes: ByteArray? = when {
                        curFrameId != null -> frameById(curFrameId)?.let {
                            runCatching { renderFrameToPng(it) }.getOrNull()
                        }
                        curCustomFrame != null -> runCatching {
                            appCtx.contentResolver.openInputStream(curCustomFrame)?.use { it.readBytes() }
                        }.getOrNull()
                        else -> null
                    }
                    ui { busyMessage = "Enviando ao servidor… 0%" }
                    when (val result = CloudUploader.uploadJob(
                        appCtx, sourcePath, curEffect.name.lowercase(), curFps,
                        frameBytes, curMusic, eventId
                    ) { pct -> busyMessage = "Enviando ao servidor… $pct%" }) {
                        is CloudUploader.Result.Success -> {
                            EventStore.updateRecording(appCtx, saved.copy(shareUrl = result.url))
                            ui { qrUrl = result.url } // QR imediato; servidor processa em segundo plano
                        }
                        is CloudUploader.Result.Error -> {
                            // Falhou (ex.: Wi-Fi caiu) -> entra na fila de reenvio.
                            UploadQueue.add(
                                appCtx,
                                UploadQueue.Job(
                                    recId = saved.id, eventId = eventId, videoPath = sourcePath,
                                    effect = curEffect.name.lowercase(), fps = curFps,
                                    frameId = curFrameId,
                                    customFrameUri = curCustomFrame?.toString(),
                                    musicUri = curMusic?.toString()
                                )
                            )
                            ui {
                                Toast.makeText(
                                    appCtx,
                                    "Sem conexão com o servidor. Será reenviado automaticamente.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } else {
                    // MODO LOCAL (sem servidor): processa o efeito no aparelho.
                    ui { busyMessage = "Processando vídeo…" }
                    val finalPath = VideoProcessor.process(
                        appCtx, sourcePath, curEffect, curMusic, curFps, curW
                    ) { s -> busyMessage = s }
                    EventStore.addRecording(
                        appCtx,
                        com.voltec.giro360.Recording(
                            id = UUID.randomUUID().toString(),
                            eventId = eventId,
                            filePath = finalPath,
                            createdAt = System.currentTimeMillis(),
                            effect = curEffect
                        )
                    )
                    ui { Toast.makeText(appCtx, "Vídeo salvo!", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao finalizar o vídeo", e)
                ui { Toast.makeText(appCtx, "Erro ao finalizar o vídeo", Toast.LENGTH_LONG).show() }
            } finally {
                ui { busyMessage = null }
            }
        }
    }

    fun beginRecording() {
        val vc = videoCapture ?: return
        if (isRecording) return
        recordProgress = 0f
        recording = startRecordingToFile(context, vc) { rec, finalEvent ->
            when (finalEvent) {
                is VideoRecordEvent.Start -> isRecording = true
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    recordProgress = 0f
                    if (!finalEvent.hasError()) {
                        finalizeRecording(rec.absolutePath)
                    } else {
                        Log.e(TAG, "Erro ao gravar: ${finalEvent.error}")
                        File(rec.absolutePath).delete()
                    }
                }
            }
        }
        // Progresso (anel ao redor do botão) + parada automática após a duração.
        scope.launch {
            val total = (duration * 1000L).coerceAtLeast(1L)
            var elapsed = 0L
            while (elapsed < total) {
                delay(50)
                if (recording == null) { recordProgress = 0f; return@launch } // parado manualmente
                elapsed += 50
                recordProgress = (elapsed.toFloat() / total).coerceIn(0f, 1f)
            }
            recording?.stop()
            recording = null
        }
    }

    fun toggleRecording() {
        when {
            isRecording -> { recording?.stop(); recording = null }
            countdown > 0 -> { countdownJob?.cancel(); countdown = 0 } // cancela a contagem
            countdownSeconds <= 0 -> beginRecording()
            else -> {
                countdownJob = scope.launch {
                    for (i in countdownSeconds downTo 1) { countdown = i; delay(1000) }
                    countdown = 0
                    beginRecording()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Moldura sobreposta
        customFrameUri?.let {
            AsyncImage(
                model = it, contentDescription = "Moldura",
                contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()
            )
        } ?: BuiltInFrameOverlay(frameId, Modifier.fillMaxSize())

        // Contagem regressiva
        if (countdown > 0) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$countdown", color = Color.White,
                    fontSize = 140.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // ----- Barra superior -----
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Voltar", tint = Color.White)
            }
            Text(ev.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row {
                IconButton(onClick = onOpenGallery) {
                    Icon(Icons.Filled.PhotoLibrary, "Galeria", tint = Color.White)
                }
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(Icons.Filled.Tune, "Ajustes", tint = Color.White)
                }
            }
        }

        // ----- Controles inferiores -----
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.ZoomOut, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Slider(
                    value = zoom, onValueChange = { zoom = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Icon(Icons.Filled.ZoomIn, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            // Chips de efeito (sempre visíveis)
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Effect.values().forEach { e ->
                    EffectChip(label = e.label, selected = effect == e, enabled = !isRecording) {
                        effect = e; persist()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${duration}s" + if (countdownSeconds > 0) " • ${countdownSeconds}s p/ iniciar" else "",
                color = Prime.TextDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Box(contentAlignment = Alignment.Center) {
                if (isRecording) {
                    CircularProgressIndicator(
                        progress = { recordProgress },
                        modifier = Modifier.size(98.dp),
                        color = Prime.Record,
                        trackColor = Color(0x55FFFFFF),
                        strokeWidth = 5.dp
                    )
                }
                RecordButton(isRecording = isRecording, onClick = ::toggleRecording)
            }
        }
    }

    // ----- Ajustes (bottom sheet) -----
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = Prime.Surface
        ) {
            SettingsSheet(
                effect = effect, onEffect = { effect = it; persist() },
                duration = duration, onDuration = { duration = it; persist() },
                countdown = countdownSeconds, onCountdown = { countdownSeconds = it; persist() },
                boomFps = boomFps, onBoomFps = { boomFps = it; persist() },
                boomWidth = boomWidth, onBoomWidth = { boomWidth = it; persist() },
                frameId = frameId,
                onFrame = { customFrameUri = null; frameId = it; persist() },
                onPickFrame = { frameLauncher.launch("image/*") },
                onNoFrame = { frameId = null; customFrameUri = null; persist() },
                musicName = musicName,
                onPickMusic = { musicLauncher.launch("audio/*") },
                onClearMusic = { musicUri = null; musicName = null; persist() }
            )
        }
    }

    // Overlay de processamento/envio
    busyMessage?.let { msg ->
        Box(
            Modifier.fillMaxSize().background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(msg, color = Color.White)
            }
        }
    }

    // QR Code automático após o envio ao servidor
    qrUrl?.let { url ->
        val qr = remember(url) { ShareUtil.generateQr(url) }
        AlertDialog(
            onDismissRequest = { qrUrl = null },
            confirmButton = { TextButton(onClick = { qrUrl = null }) { Text("Fechar") } },
            title = { Text("QR Code do vídeo") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    qr?.let { Image(it.asImageBitmap(), "QR", Modifier.size(220.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Text("O cliente aponta a câmera para baixar o vídeo.", fontSize = 13.sp)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    effect: Effect, onEffect: (Effect) -> Unit,
    duration: Int, onDuration: (Int) -> Unit,
    countdown: Int, onCountdown: (Int) -> Unit,
    boomFps: Int, onBoomFps: (Int) -> Unit,
    boomWidth: Int, onBoomWidth: (Int) -> Unit,
    frameId: String?, onFrame: (String) -> Unit, onPickFrame: () -> Unit, onNoFrame: () -> Unit,
    musicName: String?, onPickMusic: () -> Unit, onClearMusic: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp).padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState())
    ) {
            Text("Ajustes", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(14.dp))
            Text("Efeito", color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Effect.values().forEach { e ->
                    FilterChip(
                        selected = effect == e, onClick = { onEffect(e) },
                        label = { Text(e.label) }
                    )
                }
            }
            if (effect == Effect.BOOMERANG) {
                Text("Boomerang: vídeo vai-e-volta (sem áudio)", color = Color(0xFFFFC107), fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text("Velocidade: ${boomFps} fps", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = boomFps.toFloat(), onValueChange = { onBoomFps(it.toInt()) },
                    valueRange = 12f..30f, steps = 17
                )
                Text("Qualidade", color = Color.White, fontSize = 13.sp)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(360 to "Rápida", 480 to "Média", 720 to "Alta", 1080 to "Máxima")
                        .forEach { (w, lbl) ->
                            FilterChip(
                                selected = boomWidth == w, onClick = { onBoomWidth(w) },
                                label = { Text("$lbl (${w}p)") }
                            )
                        }
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("Duração: ${duration}s", color = Color.White, fontWeight = FontWeight.Bold)
            Slider(
                value = duration.toFloat(), onValueChange = { onDuration(it.toInt()) },
                valueRange = 1f..20f, steps = 18
            )

            Text("Contagem antes de gravar: ${countdown}s", color = Color.White, fontWeight = FontWeight.Bold)
            Slider(
                value = countdown.toFloat(), onValueChange = { onCountdown(it.toInt()) },
                valueRange = 0f..10f, steps = 9
            )
            Spacer(Modifier.height(12.dp))

            Text("Moldura", color = Color.White, fontWeight = FontWeight.Bold)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(onClick = onNoFrame, label = { Text("Nenhuma") })
                BUILT_IN_FRAMES.forEach { f ->
                    Surface(
                        onClick = { onFrame(f.id) },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (frameId == f.id) 2.dp else 0.dp, Color.White),
                        color = Color.DarkGray
                    ) {
                        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            FrameThumbnail(f, 48)
                            Text(f.name, color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
                AssistChip(onClick = onPickFrame, label = { Text("Da galeria") })
            }
            Spacer(Modifier.height(12.dp))

            Text("Música de fundo", color = Color.White, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPickMusic) { Text(musicName ?: "Escolher música") }
                if (musicName != null) {
                    IconButton(onClick = onClearMusic) {
                        Icon(Icons.Filled.Clear, "Remover música", tint = Color.White)
                    }
                }
            }
        }
}

@Composable
private fun EffectChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (selected) Prime.Violet else Color(0x33FFFFFF)
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape, color = Color.Transparent,
        border = BorderStroke(4.dp, Color.White), modifier = Modifier.size(84.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Surface(
                shape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape,
                color = Color.Red,
                modifier = Modifier.size(if (isRecording) 36.dp else 64.dp)
            ) {}
        }
    }
}

// ---------- Lógica de câmera ----------

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
    }
} catch (e: Exception) {
    Surface.ROTATION_0
}

private fun bindCamera(
    context: Context,
    provider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    slowMotion: Boolean
): Pair<VideoCapture<Recorder>?, Camera?> {
    // Orientação correta do vídeo/preview (corrige vídeo saindo deitado).
    val targetRotation = displayRotation(context)

    val preview = Preview.Builder().setTargetRotation(targetRotation).build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }
    // Sempre a melhor qualidade que o aparelho oferecer (UHD > FHD > HD > SD).
    val qualitySelector = QualitySelector.fromOrderedList(
        listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
    )
    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    fun buildVideoCapture(stab: Boolean): VideoCapture<Recorder> {
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        val builder = VideoCapture.Builder(recorder).setTargetRotation(targetRotation)
        if (stab) builder.setVideoStabilizationEnabled(true)
        return builder.build()
    }

    // Tenta com estabilização de vídeo; se o aparelho não suportar, o bind falha
    // e caímos no fallback sem estabilização.
    return try {
        provider.unbindAll()
        val videoCapture = buildVideoCapture(true)
        val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
        videoCapture to camera
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao ligar câmera; tentando sem estabilização", e)
        try {
            provider.unbindAll()
            val videoCapture = buildVideoCapture(false)
            val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
            videoCapture to camera
        } catch (e2: Exception) {
            Log.e(TAG, "Falha ao ligar câmera", e2)
            null to null
        }
    }
}

private fun startRecordingToFile(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    onEvent: (File, VideoRecordEvent) -> Unit
): Recording {
    val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val outFile = File(dir, "Giro360_$name.mp4")
    val outputOptions = FileOutputOptions.Builder(outFile).build()

    val pending = videoCapture.output.prepareRecording(context, outputOptions)
    val audioGranted = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    return (if (audioGranted) pending.withAudioEnabled() else pending)
        .start(ContextCompat.getMainExecutor(context)) { event ->
            onEvent(outFile, event)
        }
}
