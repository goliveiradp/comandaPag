package br.com.comandapag.core.servico

import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.pagamento.SolicitacaoPagamento
import br.com.comandapag.core.pagamento.TerminalPagamento
import br.com.comandapag.core.repositorio.ComandaRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RegraDeNegocioException(mensagem: String) : Exception(mensagem)

class ComandaService(
    private val comandas: ComandaRepository,
    private val terminal: TerminalPagamento,
    private val agora: () -> Long = System::currentTimeMillis,
) {
    /**
     * Toda alteração lê a comanda, modifica e grava. Sem essa trava, dois toques
     * rápidos em "adicionar" leriam a mesma versão e um item se perderia.
     */
    private val trava = Mutex()

    suspend fun abrir(identificacao: String, taxaServico: Boolean = false): Comanda = trava.withLock {
        val id = identificacao.trim()
        if (id.isEmpty()) erro("Informe o número da comanda, mesa ou nome do cliente.")
        if (comandas.buscarAbertaPorIdentificacao(id) != null) {
            erro("Já existe uma comanda aberta para \"$id\".")
        }
        val nova = Comanda(identificacao = id, abertaEm = agora(), taxaServico = taxaServico)
        nova.copy(id = comandas.salvar(nova))
    }

    suspend fun adicionarItem(
        comandaId: Long,
        produto: Produto,
        quantidade: Int = 1,
        observacao: String = "",
    ): Comanda = trava.withLock {
        if (quantidade <= 0) erro("A quantidade deve ser maior que zero.")
        val comanda = comandaAberta(comandaId)
        val obs = observacao.trim()
        val indice = comanda.itens.indexOfFirst {
            it.produtoId == produto.id && it.observacao == obs &&
                it.precoUnitarioCentavos == produto.precoCentavos
        }
        val itens = if (indice >= 0) {
            comanda.itens.toMutableList().also {
                it[indice] = it[indice].copy(quantidade = it[indice].quantidade + quantidade)
            }
        } else {
            comanda.itens + ItemComanda(
                produtoId = produto.id,
                nome = produto.nome,
                precoUnitarioCentavos = produto.precoCentavos,
                quantidade = quantidade,
                observacao = obs,
            )
        }
        salvar(comanda.copy(itens = itens))
    }

    /** Altera a quantidade do item na posição [indice]. Quantidade 0 remove o item. */
    suspend fun alterarQuantidade(comandaId: Long, indice: Int, quantidade: Int): Comanda = trava.withLock {
        if (quantidade < 0) erro("A quantidade não pode ser negativa.")
        val comanda = comandaAberta(comandaId)
        if (indice !in comanda.itens.indices) erro("Item não encontrado na comanda.")
        val itens = comanda.itens.toMutableList()
        if (quantidade == 0) itens.removeAt(indice) else itens[indice] = itens[indice].copy(quantidade = quantidade)
        salvar(comanda.copy(itens = itens))
    }

    suspend fun removerItem(comandaId: Long, indice: Int): Comanda = alterarQuantidade(comandaId, indice, 0)

    suspend fun definirTaxaServico(comandaId: Long, ativa: Boolean): Comanda = trava.withLock {
        salvar(comandaAberta(comandaId).copy(taxaServico = ativa))
    }

    suspend fun cancelar(comandaId: Long): Comanda = trava.withLock {
        salvar(comandaAberta(comandaId).copy(status = StatusComanda.CANCELADA, fechadaEm = agora()))
    }

    /**
     * Cobra o total da comanda na maquininha. Se aprovado, a comanda é marcada como paga.
     * Retorna o resultado da transação (aprovada ou recusada).
     * A comanda fica travada durante a cobrança, para que o valor não mude no meio.
     */
    suspend fun cobrar(
        comandaId: Long,
        forma: FormaPagamento,
        parcelas: Int = 1,
        aoMudarStatus: (String) -> Unit = {},
    ): ResultadoPagamento = trava.withLock {
        val comanda = comandaAberta(comandaId)
        val total = comanda.totalCentavos
        if (total <= 0) erro("A comanda está vazia.")
        if (total > Int.MAX_VALUE) erro("Valor acima do limite da maquininha.")
        if (parcelas < 1 || parcelas > MAX_PARCELAS) erro("Número de parcelas inválido.")
        if (parcelas > 1 && forma != FormaPagamento.CREDITO) erro("Parcelamento só é possível no crédito.")

        val resultado = terminal.cobrar(
            SolicitacaoPagamento(
                forma = forma,
                valorCentavos = total,
                parcelas = parcelas,
                referencia = referencia(comanda.id),
            ),
            aoMudarStatus,
        )

        if (resultado is ResultadoPagamento.Aprovado) {
            val momento = agora()
            salvar(
                comanda.copy(
                    status = StatusComanda.PAGA,
                    fechadaEm = momento,
                    pagamento = Pagamento(
                        forma = forma,
                        valorCentavos = total,
                        parcelas = parcelas,
                        codigoTransacao = resultado.codigoTransacao,
                        idTransacao = resultado.idTransacao,
                        bandeira = resultado.bandeira,
                        pagoEm = momento,
                    ),
                ),
            )
        }
        resultado
    }

    private suspend fun comandaAberta(id: Long): Comanda {
        val comanda = comandas.buscar(id) ?: erro("Comanda não encontrada.")
        if (!comanda.estaAberta) erro("A comanda \"${comanda.identificacao}\" já foi fechada.")
        return comanda
    }

    private suspend fun salvar(comanda: Comanda): Comanda {
        comandas.salvar(comanda)
        return comanda
    }

    private fun erro(mensagem: String): Nothing = throw RegraDeNegocioException(mensagem)

    companion object {
        const val MAX_PARCELAS = 12

        /** A PagBank aceita até 10 caracteres alfanuméricos na referência da venda. */
        fun referencia(comandaId: Long): String = "CMD$comandaId".take(10)
    }
}
