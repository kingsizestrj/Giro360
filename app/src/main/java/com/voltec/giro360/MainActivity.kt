package com.voltec.giro360

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Prime360Colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = Prime.Bg) {
                    Giro360App()
                }
            }
        }
    }
}

private sealed interface Screen {
    object Events : Screen
    data class Camera(val eventId: String) : Screen
    data class Gallery(val eventId: String) : Screen
}

@Composable
private fun Giro360App() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Events) }

    // Ao abrir o app, tenta reenviar o que ficou pendente (Wi-Fi que caiu, etc.).
    LaunchedEffect(Unit) {
        GiroScope.io.launch { UploadQueue.process(context.applicationContext) }
    }

    BackHandler(enabled = screen !is Screen.Events) { screen = Screen.Events }

    when (val s = screen) {
        is Screen.Events -> EventsScreen(
            onOpenEvent = { screen = Screen.Camera(it) },
            onOpenGallery = { screen = Screen.Gallery(it) }
        )
        is Screen.Camera -> CameraGate {
            CameraScreen(
                eventId = s.eventId,
                onBack = { screen = Screen.Events },
                onOpenGallery = { screen = Screen.Gallery(s.eventId) }
            )
        }
        is Screen.Gallery -> GalleryScreen(
            eventId = s.eventId,
            onBack = { screen = Screen.Events }
        )
    }
}

/** Garante a permissão de câmera/microfone antes de mostrar a tela de gravação. */
@Composable
private fun CameraGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(allPermissionsGranted(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.all { it } }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(REQUIRED_PERMISSIONS) }

    if (granted) content()
    else PermissionRequest { launcher.launch(REQUIRED_PERMISSIONS) }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CameraAlt, null, tint = Color.White, modifier = Modifier.size(64.dp))
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

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

private fun allPermissionsGranted(context: Context) = REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
