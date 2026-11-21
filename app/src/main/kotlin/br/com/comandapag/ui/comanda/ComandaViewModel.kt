package br.com.comandapag.ui.comanda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.servico.RegraDeNegocioException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EstadoCobranca {
    data object Inativa : EstadoCobranca
    data class EmAndamento(val status: String) : EstadoCobranca
    /** Pagamento registrado. [quitada] = a comanda fechou; senão ainda falta [faltaCentavos]. */
    data class Aprovada(
        val titulo: String,
        val mensagem: String,
        val quitada: Boolean,
        val faltaCentavos: Long,
        val trocoCentavos: Long = 0,
    ) : EstadoCobranca
    data class Recusada(val mensagem: String) : EstadoCobranca

    /** Aprovado na maquininha, mas o servidor não respondeu: fica guardado e é reenviado sozinho. */
    data object AprovadoSemServidor : EstadoCobranca
}

class ComandaViewModel(private val comandaId: Long, private val deps: Dependencias) : ViewModel() {

    val comanda: StateFlow<Comanda?> =
        deps.comandas.observar(comandaId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val produtos: StateFlow<List<Produto>> =
        deps.produtos.observarAtivos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cobranca = MutableStateFlow<EstadoCobranca>(EstadoCobranca.Inativa)

    val envioAutomatico: StateFlow<Boolean> = deps.preferencias.envioAutomatico

    val caixaAberto: StateFlow<Boolean> = deps.caixas.observarAberto().map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Em quantas pessoas a conta ainda vai ser dividida (1 = sem divisão). Diminui a cada pagamento. */
    val pessoasRestantes = MutableStateFlow(1)
    val aviso = MutableStateFlow<String?>(null)

    val maquininhaSimulada: Boolean get() = deps.maquininha.simulada

    private val service get() = deps.comandaService

    fun adicionar(produto: Produto, quantidade: Int = 1, observacao: String = "") = executar {
        service.adicionarItem(comandaId, produto, quantidade, observacao)
    }

    /** [operador] é quem fez a alteração (ou quem autorizou, no caso de diminuir/remover). */
    fun alterarQuantidade(indice: Int, quantidade: Int, operador: Usuario) = executar {
        service.alterarQuantidade(comandaId, indice, quantidade, operador)
    }

    fun definirTaxaServico(ativa: Boolean, operador: Usuario) = executar {
        service.definirTaxaServico(comandaId, ativa, operador)
    }

    fun cancelarComanda(operador: Usuario, aoCancelar: () -> Unit) = executar {
        service.cancelar(comandaId, operador)
        aoCancelar()
    }

    /** Cobra [valorCentavos] na maquininha (crédito, débito ou PIX). */
    fun cobrar(forma: FormaPagamento, valorCentavos: Long, parcelas: Int, operador: Usuario) {
        if (cobranca.value is EstadoCobranca.EmAndamento) return
        cobranca.value = EstadoCobranca.EmAndamento("Iniciando ${forma.descricao.lowercase()}...")
        viewModelScope.launch {
            cobranca.value = try {
                val r = service.cobrar(comandaId, forma, parcelas, operador, valorCentavos) { status ->
                    if (cobranca.value is EstadoCobranca.EmAndamento) cobranca.value = EstadoCobranca.EmAndamento(status)
                }
                when (r) {
                    is ResultadoPagamento.Aprovado -> {
                        deps.atualizarPendentes()
                        if (r.registroPendente) {
                            EstadoCobranca.AprovadoSemServidor
                        } else {
                            aposPagar("Pagamento aprovado", r.mensagem ?: "", trocoCentavos = 0)
                        }
                    }
                    is ResultadoPagamento.Recusado -> EstadoCobranca.Recusada(
                        r.mensagem + (r.codigoErro?.takeIf { it.isNotBlank() }?.let { " (código $it)" } ?: ""),
                    )
                }
            } catch (e: RegraDeNegocioException) {
                EstadoCobranca.Recusada(e.message ?: "Não foi possível cobrar")
            }
        }
    }

    /** Recebe em dinheiro a parte [valorCentavos]; o cliente entregou [recebidoCentavos]. */
    fun receberDinheiro(valorCentavos: Long, recebidoCentavos: Long, operador: Usuario) {
        viewModelScope.launch {
            cobranca.value = try {
                val r = service.receberDinheiro(comandaId, recebidoCentavos, operador, valorCentavos)
                aposPagar("Dinheiro recebido", "", r.trocoCentavos)
            } catch (e: RegraDeNegocioException) {
                EstadoCobranca.Recusada(e.message ?: "Não foi possível receber")
            }
        }
    }

    /**
     * Imprime os tickets da cozinha/bar com os itens novos.
     * [seHouver] = não reclamar quando não há nada novo (envio automático).
     */
    fun enviarPedido(operador: Usuario, seHouver: Boolean = false) {
        viewModelScope.launch {
            if (seHouver && (comanda.value?.quantidadePendente ?: 0) == 0) return@launch
            try {
                val r = service.enviarPedido(comandaId, operador)
                aviso.value = buildString {
                    if (r.enviados.isNotEmpty()) append("Pedido enviado: ${r.enviados.joinToString { it.descricao }}")
                    r.falhas.forEach { (setor, motivo) ->
                        if (isNotEmpty()) append(". ")
                        append("Falha ao imprimir ${setor.descricao.lowercase()}: $motivo")
                    }
                }
            } catch (e: RegraDeNegocioException) {
                if (!seHouver) aviso.value = e.message
            }
        }
    }

    /** Estorna o pagamento na posição [indice]. Cartão/PIX passam pela maquininha. */
    fun estornar(indice: Int, operador: Usuario) {
        if (cobranca.value is EstadoCobranca.EmAndamento) return
        cobranca.value = EstadoCobranca.EmAndamento("Estornando...")
        viewModelScope.launch {
            cobranca.value = try {
                when (val r = service.estornar(comandaId, indice, operador) { status ->
                    if (cobranca.value is EstadoCobranca.EmAndamento) cobranca.value = EstadoCobranca.EmAndamento(status)
                }) {
                    is ResultadoPagamento.Aprovado -> {
                        aviso.value = r.mensagem ?: "Estorno realizado"
                        EstadoCobranca.Inativa
                    }
                    is ResultadoPagamento.Recusado -> EstadoCobranca.Recusada(
                        "Estorno não realizado: " + r.mensagem +
                            (r.codigoErro?.takeIf { it.isNotBlank() }?.let { " (código $it)" } ?: ""),
                    )
                }
            } catch (e: RegraDeNegocioException) {
                EstadoCobranca.Recusada(e.message ?: "Não foi possível estornar")
            }
        }
    }

    private suspend fun aposPagar(titulo: String, mensagem: String, trocoCentavos: Long): EstadoCobranca.Aprovada {
        val atual = deps.comandas.buscar(comandaId)
        val falta = atual?.restanteCentavos ?: 0
        if (pessoasRestantes.value > 1) pessoasRestantes.value -= 1
        if (falta == 0L) pessoasRestantes.value = 1
        return EstadoCobranca.Aprovada(
            titulo = titulo,
            mensagem = mensagem,
            quitada = atual?.estaAberta == false,
            faltaCentavos = falta,
            trocoCentavos = trocoCentavos,
        )
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
