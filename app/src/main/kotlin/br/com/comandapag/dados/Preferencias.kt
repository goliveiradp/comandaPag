package br.com.comandapag.dados

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Configurações simples do estabelecimento. */
class Preferencias(context: Context) {
    private val prefs = context.getSharedPreferences("comandapag", Context.MODE_PRIVATE)

    private val _nomeEstabelecimento = MutableStateFlow(prefs.getString(CHAVE_NOME, "") ?: "")
    val nomeEstabelecimento: StateFlow<String> = _nomeEstabelecimento

    private val _taxaServicoPadrao = MutableStateFlow(prefs.getBoolean(CHAVE_TAXA, false))
    /** Se novas comandas já abrem com os 10% de serviço. */
    val taxaServicoPadrao: StateFlow<Boolean> = _taxaServicoPadrao

    fun definirNomeEstabelecimento(nome: String) {
        prefs.edit().putString(CHAVE_NOME, nome.trim()).apply()
        _nomeEstabelecimento.value = nome.trim()
    }

    fun definirTaxaServicoPadrao(ativa: Boolean) {
        prefs.edit().putBoolean(CHAVE_TAXA, ativa).apply()
        _taxaServicoPadrao.value = ativa
    }

    private companion object {
        const val CHAVE_NOME = "nome_estabelecimento"
        const val CHAVE_TAXA = "taxa_servico_padrao"
    }
}
