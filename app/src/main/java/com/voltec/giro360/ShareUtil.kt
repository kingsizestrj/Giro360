package com.voltec.giro360

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

object ShareUtil {

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Abre o seletor de compartilhamento do Android (WhatsApp, Instagram, e-mail, etc). */
    fun shareVideo(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar vídeo"))
    }

    /** Abre o vídeo no player do sistema. */
    fun playVideo(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * Envia o ARQUIVO do vídeo direto para o WhatsApp. O WhatsApp abre com o
     * vídeo anexado e o usuário escolhe o contato lá dentro. Se o WhatsApp não
     * estiver instalado, cai no seletor padrão de compartilhamento.
     */
    fun sendVideoToWhatsApp(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = uriFor(context, file)
        fun base() = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // "com.whatsapp" = WhatsApp normal; "com.whatsapp.w4b" = WhatsApp Business
        val installed = listOf("com.whatsapp", "com.whatsapp.w4b").filter { pkg ->
            base().setPackage(pkg).resolveActivity(context.packageManager) != null
        }
        try {
            when (installed.size) {
                1 -> context.startActivity(base().setPackage(installed[0]))
                0 -> context.startActivity(Intent.createChooser(base(), "Enviar vídeo"))
                else -> {
                    // Os dois instalados: seletor só com os WhatsApp
                    val chooser = Intent.createChooser(
                        base().setPackage(installed[0]), "Enviar para"
                    )
                    val extras = installed.drop(1)
                        .map { base().setPackage(it) }
                        .toTypedArray()
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras)
                    context.startActivity(chooser)
                }
            }
        } catch (e: Exception) {
            context.startActivity(Intent.createChooser(base(), "Enviar vídeo"))
        }
    }

    /**
     * Abre o WhatsApp já na conversa do número informado, com a mensagem pronta.
     * Envia o LINK do vídeo (não o arquivo) — é assim que o WhatsApp permite
     * mandar direto para um número.
     */
    fun sendWhatsApp(context: Context, phone: String, text: String) {
        val digits = phone.filter { it.isDigit() }
        val url = "https://wa.me/$digits?text=" + Uri.encode(text)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "Não foi possível abrir o WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    /** Gera um QR Code (bitmap) a partir de um texto/link. */
    fun generateQr(content: String, size: Int = 600): Bitmap? {
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            // IntArray + createBitmap é muito mais rápido que setPixel pixel-a-pixel.
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
        } catch (e: Exception) {
            null
        }
    }
}
