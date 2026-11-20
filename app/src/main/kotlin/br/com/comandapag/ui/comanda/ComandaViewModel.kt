package br.com.comandapag.ui.comanda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.servico.RegraDeNegocioException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EstadoCobranca {
    data object Inativa : EstadoCobranca
    data class EmAndamento(val status: String) : EstadoCobranca
    data class Aprovada(val mensagem: String) : EstadoCobranca
    data class Recusada(val mensagem: String) : EstadoCobranca
}

class ComandaViewModel(private val comandaId: Long, private val deps: Dependencias) : ViewModel() {

    val comanda: StateFlow<Comanda?> =
        deps.comandas.observar(comandaId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val produtos: StateFlow<List<Produto>> =
        deps.produtos.observarAtivos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cobranca = MutableStateFlow<EstadoCobranca>(EstadoCobranca.Inativa)
    val aviso = MutableStateFlow<String?>(null)

    val maquininhaSimulada: Boolean get() = deps.maquininha.simulada

    private val service get() = deps.comandaService

    fun adicionar(produto: Produto, quantidade: Int = 1, observacao: String = "") = executar {
        service.adicionarItem(comandaId, produto, quantidade, observacao)
        aviso.value = "${quantidade}x ${produto.nome} adicionado"
    }

    fun alterarQuantidade(indice: Int, quantidade: Int) = executar {
        service.alterarQuantidade(comandaId, indice, quantidade)
    }

    fun definirTaxaServico(ativa: Boolean) = executar { service.definirTaxaServico(comandaId, ativa) }

    fun cancelarComanda(aoCancelar: () -> Unit) = executar {
        service.cancelar(comandaId)
        aoCancelar()
    }

    fun cobrar(forma: FormaPagamento, parcelas: Int) {
        if (cobranca.value is EstadoCobranca.EmAndamento) return
        cobranca.value = EstadoCobranca.EmAndamento("Iniciando ${forma.descricao.lowercase()}...")
        viewModelScope.launch {
            cobranca.value = try {
                val r = service.cobrar(comandaId, forma, parcelas) { status ->
                    if (cobranca.value is EstadoCobranca.EmAndamento) cobranca.value = EstadoCobranca.EmAndamento(status)
                }
                when (r) {
                    is ResultadoPagamento.Aprovado -> EstadoCobranca.Aprovada(r.mensagem ?: "Pagamento aprovado")
                    is ResultadoPagamento.Recusado -> EstadoCobranca.Recusada(
                        r.mensagem + (r.codigoErro?.takeIf { it.isNotBlank() }?.let { " (código $it)" } ?: ""),
                    )
                }
            } catch (e: RegraDeNegocioException) {
                EstadoCobranca.Recusada(e.message ?: "Não foi possível cobrar")
            }
        }
    }

    fun cancelarCobranca() {
        viewModelScope.launch { deps.maquininha.terminal.cancelarOperacao() }
    }

    fun fecharResultadoCobranca() {
        cobranca.value = EstadoCobranca.Inativa
    }

    fun imprimirExtrato() {
        val atual = comanda.value ?: return
        viewModelScope.launch {
            val linhas = ExtratoComanda.gerar(atual, deps.preferencias.nomeEstabelecimento.value)
            aviso.value = when (val r = deps.maquininha.impressora.imprimir(linhas)) {
                ResultadoOperacao.Sucesso -> "Extrato impresso"
                is ResultadoOperacao.Falha -> "Erro ao imprimir: ${r.mensagem}"
            }
        }
    }

    private fun executar(acao: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                acao()
            } catch (e: RegraDeNegocioException) {
                aviso.value = e.message
            }
        }
    }
}
