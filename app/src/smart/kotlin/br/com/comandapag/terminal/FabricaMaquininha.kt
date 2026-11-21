package br.com.comandapag.terminal

import android.content.Context

fun criarMaquininha(context: Context): Maquininha {
    val plugPag = PlugPagTerminal(context.applicationContext)
    return Maquininha(terminal = plugPag, impressora = plugPag, simulada = false)
}
