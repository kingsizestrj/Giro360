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
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
