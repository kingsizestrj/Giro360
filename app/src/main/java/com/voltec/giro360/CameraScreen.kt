package com.voltec.giro360

import android.content.Context
import android.net.Uri
import android.util.Log
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    var autoStart by remember { mutableStateOf(ev.autoStart) }
    var frameId by remember { mutableStateOf(ev.frameId) }
    var customFrameUri by remember { mutableStateOf(ev.customFrameUri?.let { Uri.parse(it) }) }
    var musicUri by remember { mutableStateOf(ev.musicUri?.let { Uri.parse(it) }) }
    var musicName by remember { mutableStateOf(ev.musicName) }

    // Salva as configurações no evento sempre que algo muda
    fun persist() {
        val updated = ev.copy(
            effect = effect,
            durationSeconds = duration,
            autoStart = autoStart,
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
        val provider = ProcessCameraProvider.getInstance(context).get()
        val result = bindCamera(context, provider, lifecycleOwner, previewView, isSlow)
        videoCapture = result.first
        camera = result.second
        camera?.cameraControl?.setLinearZoom(zoom)
    }

    // Aplica o zoom
    LaunchedEffect(zoom) { camera?.cameraControl?.setLinearZoom(zoom) }

    fun beginRecording() {
        val vc = videoCapture ?: return
        if (isRecording) return
        recording = startRecordingToFile(context, vc) { rec, finalEvent ->
            when (finalEvent) {
                is VideoRecordEvent.Start -> isRecording = true
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    if (!finalEvent.hasError()) {
                        scope.launch {
                            val finalPath = VideoProcessor.process(
                                context, rec.absolutePath, effect, musicUri
                            )
                            EventStore.addRecording(
                                context,
                                Recording(
                                    id = UUID.randomUUID().toString(),
                                    eventId = eventId,
                                    filePath = finalPath,
                                    createdAt = System.currentTimeMillis(),
                                    effect = effect
                                )
                            )
                            Toast.makeText(context, "Vídeo salvo!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "Erro ao gravar: ${finalEvent.error}")
                        File(rec.absolutePath).delete()
                    }
                }
            }
        }
        // para automaticamente após a duração configurada
        scope.launch {
            delay(duration * 1000L)
            recording?.stop()
            recording = null
        }
    }

    fun toggleRecording() {
        if (isRecording) {
            recording?.stop(); recording = null
        } else beginRecording()
    }

    // Detector de giro para iniciar automaticamente
    DisposableEffect(autoStart) {
        val detector = if (autoStart) SpinDetector(context) {
            // chamado na thread de sensores; volta pra UI
            scope.launch { if (!isRecording) beginRecording() }
        } else null
        detector?.start()
        onDispose { detector?.stop() }
    }
    // Re-arma o detector quando termina de gravar
    LaunchedEffect(isRecording) {
        if (!isRecording && autoStart) {
            delay(1500) // cooldown pra não disparar no fim do giro
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

        // ----- Painel de configurações -----
        if (showSettings) {
            SettingsPanel(
                effect = effect, onEffect = { effect = it; persist() },
                duration = duration, onDuration = { duration = it; persist() },
                autoStart = autoStart, onAutoStart = { autoStart = it; persist() },
                frameId = frameId,
                onFrame = { customFrameUri = null; frameId = it; persist() },
                onPickFrame = { frameLauncher.launch("image/*") },
                onNoFrame = { frameId = null; customFrameUri = null; persist() },
                musicName = musicName,
                onPickMusic = { musicLauncher.launch("audio/*") },
                onClearMusic = { musicUri = null; musicName = null; persist() },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ----- Controles inferiores -----
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.ZoomOut, null, tint = Color.White)
                Slider(
                    value = zoom, onValueChange = { zoom = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Icon(Icons.Filled.ZoomIn, null, tint = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            // Efeito atual + auto
            Text(
                "${effect.label} • ${duration}s" + if (autoStart) " • Auto" else "",
                color = Color.White, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            RecordButton(isRecording = isRecording, onClick = ::toggleRecording)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPanel(
    effect: Effect, onEffect: (Effect) -> Unit,
    duration: Int, onDuration: (Int) -> Unit,
    autoStart: Boolean, onAutoStart: (Boolean) -> Unit,
    frameId: String?, onFrame: (String) -> Unit, onPickFrame: () -> Unit, onNoFrame: () -> Unit,
    musicName: String?, onPickMusic: () -> Unit, onClearMusic: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF21A1A1A)
    ) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
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
            }
            Spacer(Modifier.height(12.dp))

            Text("Duração: ${duration}s", color = Color.White, fontWeight = FontWeight.Bold)
            Slider(
                value = duration.toFloat(), onValueChange = { onDuration(it.toInt()) },
                valueRange = 3f..20f, steps = 16
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoStart, onCheckedChange = onAutoStart)
                Spacer(Modifier.width(8.dp))
                Text("Gravar automático ao girar", color = Color.White)
            }
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

private fun bindCamera(
    context: Context,
    provider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    slowMotion: Boolean
): Pair<VideoCapture<Recorder>?, Camera?> {
    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }
    val qualitySelector = if (slowMotion) {
        QualitySelector.fromOrderedList(
            listOf(Quality.UHD, Quality.FHD),
            FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
        )
    } else {
        QualitySelector.from(
            Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
        )
    }
    val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
    val videoCapture = VideoCapture.withOutput(recorder)
    return try {
        provider.unbindAll()
        val camera = provider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture
        )
        videoCapture to camera
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao ligar câmera", e)
        null to null
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
