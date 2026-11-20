package br.com.comandapag.terminal

import android.content.Context
import android.util.Log
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.pagamento.Impressora
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.pagamento.SolicitacaoPagamento
import br.com.comandapag.core.pagamento.TerminalPagamento
import kotlinx.coroutines.delay

fun criarMaquininha(@Suppress("UNUSED_PARAMETER") context: Context): Maquininha =
    Maquininha(terminal = TerminalSimulado(), impressora = ImpressoraSimulada(), simulada = true)

/**
 * Maquininha falsa para testes. Aprova qualquer valor, exceto valores
 * terminados em ,99 (ex.: R$ 10,99), que são recusados para testar o fluxo de erro.
 */
class TerminalSimulado : TerminalPagamento {
    @Volatile private var cancelado = false

    override suspend fun estaAtivado() = true
    override suspend fun ativar(codigoAtivacao: String) = ResultadoOperacao.Sucesso

    override suspend fun cobrar(solicitacao: SolicitacaoPagamento, aoMudarStatus: (String) -> Unit): ResultadoPagamento {
        cancelado = false
        val etapas = if (solicitacao.forma == FormaPagamento.PIX) {
            listOf("PAGAMENTO VIA QRCODE", "AGUARDANDO PAGAMENTO", "PROCESSANDO")
        } else {
            listOf("APROXIME, INSIRA OU PASSE O CARTÃO", "PROCESSANDO", "RETIRE O CARTÃO")
        }
        for (etapa in etapas) {
            if (cancelado) return ResultadoPagamento.Recusado("OPERAÇÃO CANCELADA")
            aoMudarStatus(etapa)
            delay(1200)
        }
        if (cancelado) return ResultadoPagamento.Recusado("OPERAÇÃO CANCELADA")
        if (solicitacao.valorCentavos % 100 == 99L) return ResultadoPagamento.Recusado("TRANSAÇÃO NÃO AUTORIZADA", "51")
        return ResultadoPagamento.Aprovado(
            codigoTransacao = "SIM${System.currentTimeMillis() % 1_000_000}",
            idTransacao = "SIMULADO",
            bandeira = if (solicitacao.forma == FormaPagamento.PIX) null else "VISA",
            mensagem = "TRANSAÇÃO AUTORIZADA",
        )
    }

    override suspend fun cancelarOperacao() {
        cancelado = true
    }
}

class ImpressoraSimulada : Impressora {
    override suspend fun imprimir(linhas: List<String>): ResultadoOperacao {
        Log.i("ImpressoraSimulada", "\n" + linhas.joinToString("\n"))
        delay(500)
        return ResultadoOperacao.Sucesso
    }
}
