package br.com.comandapag.terminal

import android.content.Context
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.pagamento.Impressora
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.pagamento.SolicitacaoPagamento
import br.com.comandapag.core.pagamento.TerminalPagamento
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPag
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagActivationData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagEventData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagEventListener
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagPaymentData
import br.com.uol.pagseguro.plugpagservice.wrapper.PlugPagPrinterData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Integração com a maquininha PagBank Smart 2 através da biblioteca
 * PlugPagServiceWrapper. Todas as chamadas do PlugPag são bloqueantes,
 * por isso rodam em Dispatchers.IO.
 */
class PlugPagTerminal(private val context: Context) : TerminalPagamento, Impressora {

    private val plugPag by lazy { PlugPag(context) }

    override suspend fun estaAtivado(): Boolean = withContext(Dispatchers.IO) {
        runCatching { plugPag.isAuthenticated() }.getOrDefault(false)
    }

    override suspend fun ativar(codigoAtivacao: String): ResultadoOperacao = withContext(Dispatchers.IO) {
        runCatching {
            val r = plugPag.initializeAndActivatePinpad(PlugPagActivationData(codigoAtivacao.trim()))
            if (r.result == PlugPag.RET_OK) ResultadoOperacao.Sucesso
            else ResultadoOperacao.Falha(r.errorMessage.ifBlank { "Falha na ativação (código ${r.errorCode})" })
        }.getOrElse { ResultadoOperacao.Falha(it.message ?: "Erro ao ativar a maquininha") }
    }

    override suspend fun cobrar(
        solicitacao: SolicitacaoPagamento,
        aoMudarStatus: (String) -> Unit,
    ): ResultadoPagamento = withContext(Dispatchers.IO) {
        runCatching {
            plugPag.setEventListener(object : PlugPagEventListener {
                override fun onEvent(data: PlugPagEventData) {
                    data.customMessage.takeIf { it.isNotBlank() }?.let(aoMudarStatus)
                }
            })

            val parcelado = solicitacao.forma == FormaPagamento.CREDITO && solicitacao.parcelas > 1
            val dados = PlugPagPaymentData(
                tipo(solicitacao.forma),
                solicitacao.valorCentavos.toInt(),
                if (parcelado) PlugPag.INSTALLMENT_TYPE_PARC_VENDEDOR else PlugPag.INSTALLMENT_TYPE_A_VISTA,
                if (parcelado) solicitacao.parcelas else PlugPag.A_VISTA_INSTALLMENT_QUANTITY,
                solicitacao.referencia,
                true, // imprime a via do estabelecimento
            )

            val r = plugPag.doPayment(dados)
            if (r.result == PlugPag.RET_OK) {
                ResultadoPagamento.Aprovado(
                    codigoTransacao = r.transactionCode,
                    idTransacao = r.transactionId,
                    bandeira = r.cardBrand,
                    mensagem = r.message,
                )
            } else {
                ResultadoPagamento.Recusado(
                    mensagem = r.message?.takeIf { it.isNotBlank() } ?: "Transação não autorizada",
                    codigoErro = r.errorCode,
                )
            }
        }.getOrElse { ResultadoPagamento.Recusado(it.message ?: "Erro de comunicação com a maquininha") }
    }

    override suspend fun cancelarOperacao() {
        withContext(Dispatchers.IO) { runCatching { plugPag.abort() } }
    }

    override suspend fun imprimir(linhas: List<String>): ResultadoOperacao = withContext(Dispatchers.IO) {
        runCatching {
            val arquivo = RenderizadorCupom.salvarPng(
                RenderizadorCupom.renderizar(linhas),
                File(context.cacheDir, "extrato.png"),
            )
            val r = plugPag.printFromFile(PlugPagPrinterData(arquivo.absolutePath, QUALIDADE_IMPRESSAO, AVANCO_PAPEL))
            if (r.result == PlugPag.RET_OK) ResultadoOperacao.Sucesso
            else ResultadoOperacao.Falha(r.message ?: "Falha na impressão (código ${r.errorCode})")
        }.getOrElse { ResultadoOperacao.Falha(it.message ?: "Erro ao imprimir") }
    }

    private fun tipo(forma: FormaPagamento): Int = when (forma) {
        FormaPagamento.CREDITO -> PlugPag.TYPE_CREDITO
        FormaPagamento.DEBITO -> PlugPag.TYPE_DEBITO
        FormaPagamento.PIX -> PlugPag.TYPE_PIX
        FormaPagamento.DINHEIRO -> throw IllegalArgumentException("Dinheiro não passa pela maquininha")
    }

    private companion object {
        /** Qualidade de impressão de 1 a 4. */
        const val QUALIDADE_IMPRESSAO = 4
        /** Linhas em branco após a impressão, para destacar o papel. */
        const val AVANCO_PAPEL = 10 * 12
    }
}
