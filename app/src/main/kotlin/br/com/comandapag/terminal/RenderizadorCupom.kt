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

    fun salvarPng(bitmap: Bitmap, arquivo: File): File {
        FileOutputStream(arquivo).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return arquivo
    }
}
