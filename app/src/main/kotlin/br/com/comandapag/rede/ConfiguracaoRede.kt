package br.com.comandapag.rede

import android.content.Context
import br.com.comandapag.core.modelo.SetorProducao
import br.com.comandapag.core.servico.PagamentoPendente
import br.com.comandapag.core.servico.PagamentosPendentes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer

/** Como esta maquininha participa da rede do estabelecimento. */
enum class ModoRede(val descricao: String) {
    INDEPENDENTE("Independente"),
    SERVIDOR("Servidor"),
    TERMINAL("Terminal"),
}

/** Onde sai o ticket de um setor. */
data class DestinoImpressao(val rede: Boolean, val host: String = "", val porta: Int = ImpressoraRede.PORTA_PADRAO)

/** Configurações de rede e impressoras guardadas neste aparelho. */
class ConfiguracaoRede(context: Context) {
    private val prefs = context.getSharedPreferences("comandapag_rede", Context.MODE_PRIVATE)

    val modo: ModoRede get() = runCatching { ModoRede.valueOf(prefs.getString("modo", null)!!) }.getOrDefault(ModoRede.INDEPENDENTE)

    // Terminal
    val enderecoServidor: String get() = prefs.getString("endereco_servidor", "") ?: ""
    val tokenServidor: String get() = prefs.getString("token_servidor", "") ?: ""
    val nomeServidor: String get() = prefs.getString("nome_servidor", "") ?: ""

    fun virarTerminal(endereco: String, token: String, nomeServidor: String) {
        prefs.edit()
            .putString("modo", ModoRede.TERMINAL.name)
            .putString("endereco_servidor", endereco)
            .putString("token_servidor", token)
            .putString("nome_servidor", nomeServidor)
            .apply()
    }

    fun virarServidor() = prefs.edit().putString("modo", ModoRede.SERVIDOR.name).apply()

    fun virarIndependente() = prefs.edit()
        .putString("modo", ModoRede.INDEPENDENTE.name)
        .remove("endereco_servidor")
        .remove("token_servidor")
        .apply()

    // Impressoras por setor
    private val _impressoras = MutableStateFlow(lerImpressoras())
    val impressoras: StateFlow<Map<SetorProducao, DestinoImpressao>> = _impressoras

    private fun lerImpressoras() = SetorProducao.entries.filter { it != SetorProducao.NENHUM }.associateWith { s ->
        DestinoImpressao(
            rede = prefs.getBoolean("impressora_${s.name}_rede", false),
            host = prefs.getString("impressora_${s.name}_host", "") ?: "",
            porta = prefs.getInt("impressora_${s.name}_porta", ImpressoraRede.PORTA_PADRAO),
        )
    }

    fun definirImpressora(setor: SetorProducao, destino: DestinoImpressao) {
        prefs.edit()
            .putBoolean("impressora_${setor.name}_rede", destino.rede)
            .putString("impressora_${setor.name}_host", destino.host.trim())
            .putInt("impressora_${setor.name}_porta", destino.porta)
            .apply()
        _impressoras.value = lerImpressoras()
    }
}

/** Pareamento do servidor, guardado nas preferências para valer depois de reiniciar. */
class PareamentoPreferencias(context: Context) : Pareamento {
    private val prefs = context.getSharedPreferences("comandapag_pareamento", Context.MODE_PRIVATE)

    override val codigo: String
        get() = prefs.getString("codigo", null) ?: Pareamento.novoCodigo().also { prefs.edit().putString("codigo", it).apply() }

    override fun tokenValido(token: String?): Boolean =
        token != null && token in (prefs.getStringSet("tokens", emptySet()) ?: emptySet())

    override fun parear(codigo: String, aparelho: String): String? {
        if (codigo != this.codigo) return null
        val token = Pareamento.novoToken()
        synchronized(this) {
            val tokens = (prefs.getStringSet("tokens", emptySet()) ?: emptySet()) + token
            prefs.edit().putStringSet("tokens", tokens).apply()
        }
        return token
    }

    val aparelhosPareados: Int get() = prefs.getStringSet("tokens", emptySet())?.size ?: 0

    /** Gera um código novo e desconecta todos os terminais (eles precisam parear de novo). */
    fun renovar() {
        prefs.edit().putString("codigo", Pareamento.novoCodigo()).putStringSet("tokens", emptySet()).apply()
    }
}

/**
 * Pagamentos aprovados que não chegaram ao servidor, guardados no aparelho para
 * sobreviver mesmo se o app for fechado antes de reenviar.
 */
class PagamentosPendentesPreferencias(context: Context) : PagamentosPendentes {
    private val prefs = context.getSharedPreferences("comandapag_pendentes", Context.MODE_PRIVATE)
    private val serializador = ListSerializer(PagamentoPendente.serializer())

    private fun ler(): List<PagamentoPendente> =
        prefs.getString("lista", null)?.let { runCatching { Protocolo.json.decodeFromString(serializador, it) }.getOrNull() }
            ?: emptyList()

    private fun gravar(lista: List<PagamentoPendente>) {
        prefs.edit().putString("lista", Protocolo.json.encodeToString(serializador, lista)).commit()
    }

    override suspend fun adicionar(pendente: PagamentoPendente) = synchronized(this) { gravar(ler() + pendente) }
    override suspend fun listar(): List<PagamentoPendente> = synchronized(this) { ler() }
    override suspend fun remover(pendente: PagamentoPendente) = synchronized(this) { gravar(ler() - pendente) }
}
