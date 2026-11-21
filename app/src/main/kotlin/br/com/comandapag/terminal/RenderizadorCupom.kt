package br.com.comandapag.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import br.com.comandapag.core.impressao.ExtratoComanda
import java.io.File
import java.io.FileOutputStream

/**
 * Converte as linhas de texto do extrato em uma imagem para a impressora
 * térmica da maquininha (largura de 384 px na bobina de 58 mm).
 */
object RenderizadorCupom {
    const val LARGURA_PX = 384

    fun renderizar(linhas: List<String>, colunas: Int = ExtratoComanda.COLUNAS): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 20f
        }
        // Ajusta o tamanho da fonte para que `colunas` caracteres ocupem toda a largura.
        val larguraTexto = paint.measureText("M".repeat(colunas))
        paint.textSize = paint.textSize * LARGURA_PX / larguraTexto

        val alturaLinha = (paint.fontSpacing).toInt()
        val margem = alturaLinha
        val bitmap = Bitmap.createBitmap(LARGURA_PX, alturaLinha * linhas.size + margem * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        linhas.forEachIndexed { i, linha ->
            canvas.drawText(linha, 0f, margem + alturaLinha * (i + 1) - paint.descent(), paint)
        }
        return bitmap
    }

    /** Texto, QR Code centralizado (ex.: DANFE NFC-e) e mais texto, numa imagem só. */
    fun renderizarComQrCode(antes: List<String>, qrCode: String, depois: List<String>, colunas: Int = ExtratoComanda.COLUNAS): Bitmap {
        val cima = renderizar(antes, colunas)
        val baixo = renderizar(depois, colunas)
        val lado = LARGURA_PX * 2 / 3
        val matriz = com.google.zxing.qrcode.QRCodeWriter().encode(
            qrCode,
            com.google.zxing.BarcodeFormat.QR_CODE,
            lado,
            lado,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
        )
        val resultado = Bitmap.createBitmap(LARGURA_PX, cima.height + lado + baixo.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultado)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(cima, 0f, 0f, null)
        val preto = Paint().apply { color = Color.BLACK }
        val x0 = (LARGURA_PX - lado) / 2
        for (y in 0 until matriz.height) {
            for (x in 0 until matriz.width) {
                if (matriz[x, y]) canvas.drawPoint((x0 + x).toFloat(), (cima.height + y).toFloat(), preto)
            }
        }
        canvas.drawBitmap(baixo, 0f, (cima.height + lado).toFloat(), null)
        return resultado
    }

    fun salvarPng(bitmap: Bitmap, arquivo: File): File {
        FileOutputStream(arquivo).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return arquivo
    }
}
