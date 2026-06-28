package com.voltec.giro360

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    CameraScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

private const val TAG = "Giro360"

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermissions by remember { mutableStateOf(allPermissionsGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasPermissions = granted.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    if (!hasPermissions) {
        PermissionRequest { permissionLauncher.launch(REQUIRED_PERMISSIONS) }
        return
    }

    // Estado do app
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var slowMotion by remember { mutableStateOf(false) }
    var slowMoSupported by remember { mutableStateOf(true) }
    var frameUri by remember { mutableStateOf<Uri?>(null) }
    val previewView = remember { PreviewView(context) }

    // Seletor de moldura da galeria
    val frameLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) frameUri = uri }

    // Liga a câmera. Refaz quando o modo slow motion muda (qualidade diferente).
    LaunchedEffect(slowMotion) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        slowMoSupported = bindCamera(
            context, provider, lifecycleOwner, previewView, slowMotion
        ) { vc -> videoCapture = vc }
        if (slowMotion && !slowMoSupported) {
            Toast.makeText(
                context,
                "Este aparelho não suporta câmera lenta em alta taxa. Usando velocidade padrão.",
                Toast.LENGTH_LONG
            ).show()
            slowMotion = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Preview da câmera
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Moldura sobreposta (se escolhida)
        frameUri?.let {
            AsyncImage(
                model = it,
                contentDescription = "Moldura",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Controles
        ControlsOverlay(
            isRecording = isRecording,
            slowMotion = slowMotion,
            hasFrame = frameUri != null,
            onToggleRecord = {
                val vc = videoCapture ?: return@ControlsOverlay
                if (isRecording) {
                    recording?.stop()
                    recording = null
                } else {
                    recording = startRecording(context, vc) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> isRecording = true
                            is VideoRecordEvent.Finalize -> {
                                isRecording = false
                                if (!event.hasError()) {
                                    Toast.makeText(
                                        context,
                                        "Vídeo salvo na galeria",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Log.e(TAG, "Erro ao gravar: ${event.error}")
                                }
                            }
                        }
                    }
                }
            },
            onToggleSlowMo = { if (!isRecording) slowMotion = !slowMotion },
            onPickFrame = { frameLauncher.launch("image/*") },
            onClearFrame = { frameUri = null }
        )
    }
}

@Composable
fun ControlsOverlay(
    isRecording: Boolean,
    slowMotion: Boolean,
    hasFrame: Boolean,
    onToggleRecord: () -> Unit,
    onToggleSlowMo: () -> Unit,
    onPickFrame: () -> Unit,
    onClearFrame: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Barra superior: opções de moldura e slow motion
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Slow motion toggle
            FilterChip(
                selected = slowMotion,
                onClick = onToggleSlowMo,
                enabled = !isRecording,
                label = { Text("Câmera lenta") },
                leadingIcon = {
                    Icon(Icons.Filled.SlowMotionVideo, contentDescription = null)
                }
            )
            Row {
                IconButton(onClick = onPickFrame) {
                    Icon(
                        Icons.Filled.AddPhotoAlternate,
                        contentDescription = "Adicionar moldura",
                        tint = Color.White
                    )
                }
                if (hasFrame) {
                    IconButton(onClick = onClearFrame) {
                        Icon(
                            Icons.Filled.HideImage,
                            contentDescription = "Remover moldura",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Botão de gravação central
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            RecordButton(isRecording = isRecording, onClick = onToggleRecord)
        }
    }
}

@Composable
fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(4.dp, Color.White),
        modifier = Modifier.size(84.dp)
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

@Composable
fun PermissionRequest(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CameraAlt,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "O app precisa de acesso à câmera e ao microfone para gravar.",
                color = Color.White
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Permitir acesso") }
        }
    }
}

// ---------- Lógica de câmera ----------

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

private fun allPermissionsGranted(context: Context) = REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Liga a câmera. Em modo slow motion pede qualidade alta (FHD/UHD) que tipicamente
 * permite taxas de quadro maiores; com FallbackStrategy a câmera não quebra se o
 * aparelho não suportar — ela cai para a qualidade mais próxima disponível.
 * Retorna false se o aparelho não tem a qualidade alta exigida pelo slow motion.
 */
private fun bindCamera(
    context: Context,
    provider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    slowMotion: Boolean,
    onReady: (VideoCapture<Recorder>) -> Unit
): Boolean {
    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }

    // Para slow motion priorizamos qualidade alta (mais fps possível).
    val qualitySelector = if (slowMotion) {
        QualitySelector.fromOrderedList(
            listOf(Quality.UHD, Quality.FHD),
            FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
        )
    } else {
        QualitySelector.from(
            Quality.HD,
            FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
        )
    }

    val recorder = Recorder.Builder()
        .setQualitySelector(qualitySelector)
        .build()
    val videoCapture = VideoCapture.withOutput(recorder)

    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    return try {
        provider.unbindAll()
        val camera = provider.bindToLifecycle(
            lifecycleOwner, cameraSelector, preview, videoCapture
        )
        onReady(videoCapture)

        // Checa se a câmera consegue rodar em alta taxa (>= 90fps) para slow motion real.
        if (slowMotion) {
            val ranges = camera.cameraInfo.supportedFrameRateRanges
            val maxFps = ranges.maxOfOrNull { it.upper } ?: 30
            Log.d(TAG, "FPS máx suportado: $maxFps")
            maxFps >= 90
        } else {
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao ligar câmera", e)
        false
    }
}

private fun startRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    onEvent: (VideoRecordEvent) -> Unit
): Recording {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        .format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Giro360_$name")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Giro360")
        }
    }
    val outputOptions = MediaStoreOutputOptions
        .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        .setContentValues(contentValues)
        .build()

    val recordingControl = videoCapture.output.prepareRecording(context, outputOptions)
    val audioGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    return (if (audioGranted) recordingControl.withAudioEnabled() else recordingControl)
        .start(ContextCompat.getMainExecutor(context), onEvent)
}
