package com.voltec.giro360

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Estilo de moldura desenhada por código (sem precisar de imagem). */
data class FrameStyle(
    val id: String,
    val name: String,
    val draw: DrawScope.() -> Unit
)

private fun DrawScope.border(color: Color, widthDp: Float, insetDp: Float) {
    val w = widthDp.dp.toPx()
    val inset = insetDp.dp.toPx()
    drawRect(
        color = color,
        topLeft = Offset(inset + w / 2, inset + w / 2),
        size = androidx.compose.ui.geometry.Size(
            size.width - 2 * inset - w,
            size.height - 2 * inset - w
        ),
        style = Stroke(width = w)
    )
}

/** Lista de molduras disponíveis no app. */
val BUILT_IN_FRAMES: List<FrameStyle> = listOf(
    FrameStyle("dourado", "Dourado") {
        border(Color(0xFFFFD700), 10f, 10f)
        border(Color(0xFFB8860B), 3f, 22f)
    },
    FrameStyle("branco", "Clássico") {
        border(Color.White, 6f, 14f)
    },
    FrameStyle("neon", "Néon") {
        border(Color(0xFF00E5FF), 8f, 12f)
        border(Color(0xFFFF00E5), 3f, 22f)
    },
    FrameStyle("festa", "Festa") {
        val cores = listOf(
            Color(0xFFFF1744), Color(0xFFFFEA00),
            Color(0xFF00E676), Color(0xFF2979FF), Color(0xFFD500F9)
        )
        val w = 9f.dp.toPx()
        val inset = 12f.dp.toPx()
        val segW = (size.width - 2 * inset) / cores.size
        cores.forEachIndexed { i, c ->
            // topo
            drawRect(c, Offset(inset + i * segW, inset), androidx.compose.ui.geometry.Size(segW, w))
            // base
            drawRect(c, Offset(inset + i * segW, size.height - inset - w), androidx.compose.ui.geometry.Size(segW, w))
        }
        val segH = (size.height - 2 * inset) / cores.size
        cores.forEachIndexed { i, c ->
            drawRect(c, Offset(inset, inset + i * segH), androidx.compose.ui.geometry.Size(w, segH))
            drawRect(c, Offset(size.width - inset - w, inset + i * segH), androidx.compose.ui.geometry.Size(w, segH))
        }
    },
    FrameStyle("casamento", "Elegante") {
        border(Color(0xFFFFFFFF), 2f, 16f)
        border(Color(0xFFFFFFFF), 2f, 24f)
    }
)

fun frameById(id: String?): FrameStyle? = BUILT_IN_FRAMES.firstOrNull { it.id == id }

/** Desenha a moldura embutida ocupando a tela toda. */
@Composable
fun BuiltInFrameOverlay(frameId: String?, modifier: Modifier = Modifier) {
    val style = frameById(frameId) ?: return
    Canvas(modifier = modifier) { style.draw(this) }
}

/** Miniatura quadrada da moldura, para o seletor. */
@Composable
fun FrameThumbnail(style: FrameStyle, sizeDp: Int = 56) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) { style.draw(this) }
}
