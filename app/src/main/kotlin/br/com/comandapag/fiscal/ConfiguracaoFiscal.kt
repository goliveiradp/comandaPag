package br.com.comandapag.fiscal

import android.content.Context
import br.com.comandapag.core.fiscal.AmbienteFiscal
import br.com.comandapag.core.fiscal.EmissorNfce
import br.com.comandapag.core.fiscal.EmissorNfceSimulado
import br.com.comandapag.core.fiscal.Emitente
import br.com.comandapag.core.fiscal.NotaNfce
import br.com.comandapag.core.fiscal.RespostaCancelamento
import br.com.comandapag.core.fiscal.RespostaNfce
import br.com.comandapag.core.fiscal.StatusEmissor
import br.com.comandapag.rede.ConfiguracaoFocus
import br.com.comandapag.rede.FocusNfeEmissor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Dados da NFC-e guardados neste aparelho (o token da Focus fica só aqui). */
data class DadosFiscaisEmpresa(
    /** Null = NFC-e desativada. */
    val ambiente: AmbienteFiscal? = null,
    val cnpj: String = "",
    val razaoSocial: String = "",
    val inscricaoEstadual: String = "",
    val endereco: String = "",
    val tokenHomologacao: String = "",
    val tokenProducao: String = "",
) {
    val emitente get() = Emitente(cnpj.filter(Char::isDigit), razaoSocial, inscricaoEstadual, endereco)
    val token get() = if (ambiente == AmbienteFiscal.PRODUCAO) tokenProducao else tokenHomologacao
}

class ConfiguracaoFiscal(context: Context) {
    private val prefs = context.getSharedPreferences("comandapag_fiscal", Context.MODE_PRIVATE)

    private val _dados = MutableStateFlow(ler())
    val dados: StateFlow<DadosFiscaisEmpresa> = _dados

    private fun ler() = DadosFiscaisEmpresa(
        ambiente = prefs.getString("ambiente", null)?.let { runCatching { AmbienteFiscal.valueOf(it) }.getOrNull() },
        cnpj = prefs.getString("cnpj", "") ?: "",
        razaoSocial = prefs.getString("razao_social", "") ?: "",
        inscricaoEstadual = prefs.getString("ie", "") ?: "",
        endereco = prefs.getString("endereco", "") ?: "",
        tokenHomologacao = prefs.getString("token_homologacao", "") ?: "",
        tokenProducao = prefs.getString("token_producao", "") ?: "",
    )

    fun salvar(d: DadosFiscaisEmpresa) {
        prefs.edit()
            .putString("ambiente", d.ambiente?.name)
            .putString("cnpj", d.cnpj.filter(Char::isDigit))
            .putString("razao_social", d.razaoSocial.trim())
            .putString("ie", d.inscricaoEstadual.trim())
            .putString("endereco", d.endereco.trim())
            .putString("token_homologacao", d.tokenHomologacao.trim())
            .putString("token_producao", d.tokenProducao.trim())
            .apply()
        _dados.value = ler()
    }
}

/**
 * Emissor deste aparelho conforme a configuração atual: desativado, simulado ou Focus NFe
 * (homologação/produção). Recria o cliente da Focus quando a configuração muda.
 */
class EmissorLocal(private val config: ConfiguracaoFiscal) : EmissorNfce {
    private val simulado = EmissorNfceSimulado { config.dados.value.emitente }
    private var focus: Pair<DadosFiscaisEmpresa, FocusNfeEmissor>? = null

    private fun atual(): EmissorNfce? {
        val d = config.dados.value
        return when (d.ambiente) {
            null -> null
            AmbienteFiscal.SIMULADO -> simulado
            else -> synchronized(this) {
                focus?.takeIf { it.first == d }?.second ?: run {
                    focus?.second?.fechar()
                    FocusNfeEmissor(ConfiguracaoFocus(d.token, d.ambiente, d.emitente)).also { focus = d to it }
                }
            }
        }
    }

    private fun exigir() = atual() ?: throw br.com.comandapag.core.servico.RegraDeNegocioException(
        "A NFC-e não está ativada. Veja em Ajustes > Nota fiscal.",
    )

    override suspend fun status(): StatusEmissor {
        val d = config.dados.value
        val e = atual() ?: return StatusEmissor(ativo = false, ambiente = AmbienteFiscal.SIMULADO)
        val completo = d.cnpj.length == 14 && d.razaoSocial.isNotBlank()
        return e.status().let { it.copy(ativo = it.ativo && completo) }
    }

    override suspend fun emitir(ref: String, nota: NotaNfce): RespostaNfce = exigir().emitir(ref, nota)
    override suspend fun consultar(ref: String): RespostaNfce? = exigir().consultar(ref)
    override suspend fun cancelar(ref: String, justificativa: String): RespostaCancelamento = exigir().cancelar(ref, justificativa)
}
