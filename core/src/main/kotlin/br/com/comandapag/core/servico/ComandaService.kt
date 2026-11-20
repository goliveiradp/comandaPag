package br.com.comandapag.core.servico

import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.MovimentoCaixa
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.TipoMovimento
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.pagamento.SolicitacaoEstorno
import br.com.comandapag.core.pagamento.SolicitacaoPagamento
import br.com.comandapag.core.pagamento.TerminalPagamento
import br.com.comandapag.core.repositorio.CaixaRepository
import br.com.comandapag.core.repositorio.ComandaRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resultado de um recebimento em dinheiro. */
data class RecebimentoDinheiro(val trocoCentavos: Long, val comanda: Comanda) {
    val quitada: Boolean get() = comanda.status == StatusComanda.PAGA
}

open class RegraDeNegocioException(mensagem: String) : Exception(mensagem)

/** O usuário não tem a [permissao]; a interface pode pedir a autorização de outra pessoa. */
class SemPermissaoException(val permissao: Permissao) :
    RegraDeNegocioException("Sem permissão para: ${permissao.descricao.lowercase()}.")

class ComandaService(
    private val comandas: ComandaRepository,
    private val terminal: TerminalPagamento,
    private val caixas: CaixaRepository,
    /**
     * Toda alteração lê a comanda, modifica e grava. Sem essa trava, dois toques
     * rápidos em "adicionar" leriam a mesma versão e um item se perderia.
     * É compartilhada com o [CaixaService], porque estornos também mexem no caixa.
     */
    private val trava: Mutex = Mutex(),
    private val agora: () -> Long = System::currentTimeMillis,
) {

    /** Abre uma comanda tendo [responsavel] como garçom responsável. */
    suspend fun abrir(identificacao: String, responsavel: Usuario, taxaServico: Boolean = false): Comanda = trava.withLock {
        val id = identificacao.trim()
        if (id.isEmpty()) erro("Informe o número da comanda, mesa ou nome do cliente.")
        if (comandas.buscarAbertaPorIdentificacao(id) != null) {
            erro("Já existe uma comanda aberta para \"$id\".")
        }
        val nova = Comanda(
            identificacao = id,
            abertaEm = agora(),
            taxaServico = taxaServico,
            garcomId = responsavel.id,
            garcomNome = responsavel.nome,
        )
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

    /**
     * Altera a quantidade do item na posição [indice]. Quantidade 0 remove o item.
     * Diminuir ou remover exige [Permissao.REMOVER_ITENS].
     */
    suspend fun alterarQuantidade(comandaId: Long, indice: Int, quantidade: Int, operador: Usuario): Comanda = trava.withLock {
        if (quantidade < 0) erro("A quantidade não pode ser negativa.")
        val comanda = comandaAberta(comandaId)
        if (indice !in comanda.itens.indices) erro("Item não encontrado na comanda.")
        if (quantidade < comanda.itens[indice].quantidade) exigir(operador, Permissao.REMOVER_ITENS)
        val itens = comanda.itens.toMutableList()
        if (quantidade == 0) itens.removeAt(indice) else itens[indice] = itens[indice].copy(quantidade = quantidade)
        salvar(semFicarAbaixoDoPago(comanda.copy(itens = itens)))
    }

    suspend fun removerItem(comandaId: Long, indice: Int, operador: Usuario): Comanda =
        alterarQuantidade(comandaId, indice, 0, operador)

    suspend fun definirTaxaServico(comandaId: Long, ativa: Boolean, operador: Usuario): Comanda = trava.withLock {
        val comanda = comandaAberta(comandaId)
        if (comanda.taxaServico == ativa) return@withLock comanda
        exigir(operador, Permissao.ALTERAR_TAXA_SERVICO)
        salvar(semFicarAbaixoDoPago(comanda.copy(taxaServico = ativa)))
    }

    suspend fun cancelar(comandaId: Long, operador: Usuario): Comanda = trava.withLock {
        exigir(operador, Permissao.CANCELAR_COMANDA)
        val comanda = comandaAberta(comandaId)
        if (comanda.pagamentos.any { !it.estornado }) {
            erro("A comanda já tem pagamentos. Estorne-os antes de cancelar.")
        }
        salvar(
            comanda.copy(
                status = StatusComanda.CANCELADA,
                fechadaEm = agora(),
                fechadaPor = operador.nome,
            ),
        )
    }

    /**
     * Cobra [valorCentavos] na maquininha (crédito, débito ou PIX). Sem valor, cobra tudo o que falta.
     * Se aprovado, registra o pagamento; a comanda fecha quando não falta mais nada.
     * A comanda fica travada durante a cobrança, para que o valor não mude no meio.
     */
    suspend fun cobrar(
        comandaId: Long,
        forma: FormaPagamento,
        parcelas: Int = 1,
        operador: Usuario,
        valorCentavos: Long? = null,
        aoMudarStatus: (String) -> Unit = {},
    ): ResultadoPagamento = trava.withLock {
        exigir(operador, Permissao.COBRAR)
        if (!forma.naMaquininha) erro("Use o recebimento em dinheiro para essa forma de pagamento.")
        val comanda = comandaAberta(comandaId)
        val caixa = caixaAberto()
        val restante = comanda.restanteCentavos
        if (comanda.totalCentavos <= 0) erro("A comanda está vazia.")
        val valor = valorCentavos ?: restante
        if (valor <= 0) erro("Informe um valor maior que zero.")
        if (valor > restante) erro("O valor é maior do que falta pagar (${Dinheiro.formatar(restante)}).")
        if (valor > Int.MAX_VALUE) erro("Valor acima do limite da maquininha.")
        if (parcelas < 1 || parcelas > MAX_PARCELAS) erro("Número de parcelas inválido.")
        if (parcelas > 1 && forma != FormaPagamento.CREDITO) erro("Parcelamento só é possível no crédito.")

        val resultado = terminal.cobrar(
            SolicitacaoPagamento(
                forma = forma,
                valorCentavos = valor,
                parcelas = parcelas,
                referencia = referencia(comanda.id, comanda.pagamentos.size + 1),
            ),
            aoMudarStatus,
        )

        if (resultado is ResultadoPagamento.Aprovado) {
            registrar(
                comanda,
                Pagamento(
                    forma = forma,
                    valorCentavos = valor,
                    parcelas = parcelas,
                    codigoTransacao = resultado.codigoTransacao,
                    idTransacao = resultado.idTransacao,
                    bandeira = resultado.bandeira,
                    pagoEm = agora(),
                    recebidoPor = operador.nome,
                    caixaId = caixa.id,
                ),
                operador,
            )
        }
        resultado
    }

    /**
     * Recebe dinheiro. [valorCentavos] é a parte a pagar (sem valor = tudo o que falta) e
     * [recebidoCentavos] é o que o cliente entregou. Se entregou mais, a diferença é troco;
     * se entregou menos, abate só o que foi entregue.
     */
    suspend fun receberDinheiro(
        comandaId: Long,
        recebidoCentavos: Long,
        operador: Usuario,
        valorCentavos: Long? = null,
    ): RecebimentoDinheiro = trava.withLock {
        exigir(operador, Permissao.COBRAR)
        val comanda = comandaAberta(comandaId)
        val caixa = caixaAberto()
        val restante = comanda.restanteCentavos
        if (comanda.totalCentavos <= 0) erro("A comanda está vazia.")
        val parte = valorCentavos ?: restante
        if (parte <= 0) erro("Informe um valor maior que zero.")
        if (parte > restante) erro("O valor é maior do que falta pagar (${Dinheiro.formatar(restante)}).")
        if (recebidoCentavos <= 0) erro("Informe quanto o cliente entregou.")

        val abatido = minOf(parte, recebidoCentavos)
        val troco = recebidoCentavos - abatido
        val atualizada = registrar(
            comanda,
            Pagamento(
                forma = FormaPagamento.DINHEIRO,
                valorCentavos = abatido,
                pagoEm = agora(),
                recebidoPor = operador.nome,
                valorRecebidoCentavos = recebidoCentavos,
                trocoCentavos = troco,
                caixaId = caixa.id,
            ),
            operador,
        )
        RecebimentoDinheiro(trocoCentavos = troco, comanda = atualizada)
    }

    /**
     * Estorna o pagamento na posição [indicePagamento]. Cartão e PIX são estornados na
     * maquininha; dinheiro é devolvido do caixa. Se a comanda estava paga, ela volta a
     * ficar aberta com o valor estornado em aberto.
     */
    suspend fun estornar(
        comandaId: Long,
        indicePagamento: Int,
        operador: Usuario,
        aoMudarStatus: (String) -> Unit = {},
    ): ResultadoPagamento = trava.withLock {
        exigir(operador, Permissao.ESTORNAR)
        val comanda = comandas.buscar(comandaId) ?: erro("Comanda não encontrada.")
        if (comanda.status == StatusComanda.CANCELADA) erro("A comanda foi cancelada.")
        val pagamento = comanda.pagamentos.getOrNull(indicePagamento) ?: erro("Pagamento não encontrado.")
        if (pagamento.estornado) erro("Esse pagamento já foi estornado.")
        val caixa = caixas.buscarAberto() ?: erro("Abra o caixa para fazer estornos.")

        if (pagamento.forma.naMaquininha) {
            val codigo = pagamento.codigoTransacao
            val idTransacao = pagamento.idTransacao
            if (codigo.isNullOrBlank() || idTransacao.isNullOrBlank()) {
                erro("Pagamento sem código da transação; faça o estorno pelo app da PagBank.")
            }
            val r = terminal.estornar(
                SolicitacaoEstorno(pagamento.forma, pagamento.valorCentavos, codigo, idTransacao),
                aoMudarStatus,
            )
            if (r is ResultadoPagamento.Recusado) return@withLock r
        }

        val momento = agora()
        val pagamentos = comanda.pagamentos.toMutableList().also {
            it[indicePagamento] = pagamento.copy(estornadoEm = momento, estornadoPor = operador.nome)
        }
        var atualizada = comanda.copy(pagamentos = pagamentos)
        if (atualizada.status == StatusComanda.PAGA && atualizada.restanteCentavos > 0) {
            atualizada = atualizada.copy(status = StatusComanda.ABERTA, fechadaEm = null, fechadaPor = null)
        }
        salvar(atualizada)
        caixas.salvar(
            caixa.copy(
                movimentos = caixa.movimentos + MovimentoCaixa(
                    tipo = TipoMovimento.ESTORNO,
                    valorCentavos = pagamento.valorCentavos,
                    motivo = "Comanda ${comanda.identificacao}",
                    em = momento,
                    por = operador.nome,
                    forma = pagamento.forma,
                ),
            ),
        )
        ResultadoPagamento.Aprovado(
            codigoTransacao = pagamento.codigoTransacao,
            idTransacao = pagamento.idTransacao,
            bandeira = pagamento.bandeira,
            mensagem = "Estorno de ${Dinheiro.formatar(pagamento.valorCentavos)} realizado",
        )
    }

    private suspend fun caixaAberto(): Caixa =
        caixas.buscarAberto() ?: erro("O caixa está fechado. Abra o caixa para receber.")

    /** Acrescenta o pagamento e fecha a comanda se ela ficou quitada. */
    private suspend fun registrar(comanda: Comanda, pagamento: Pagamento, operador: Usuario): Comanda {
        val comPagamento = comanda.copy(pagamentos = comanda.pagamentos + pagamento)
        val final = if (comPagamento.restanteCentavos == 0L) {
            comPagamento.copy(status = StatusComanda.PAGA, fechadaEm = pagamento.pagoEm, fechadaPor = operador.nome)
        } else {
            comPagamento
        }
        return salvar(final)
    }

    /** Depois de receber parte da conta, o total não pode ficar menor do que já foi pago. */
    private fun semFicarAbaixoDoPago(comanda: Comanda): Comanda {
        if (comanda.totalCentavos < comanda.totalPagoCentavos) {
            erro(
                "Já foram pagos ${Dinheiro.formatar(comanda.totalPagoCentavos)} desta comanda; " +
                    "o total não pode ficar menor que isso.",
            )
        }
        return comanda
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

    private fun exigir(usuario: Usuario, permissao: Permissao) {
        if (!usuario.pode(permissao)) throw SemPermissaoException(permissao)
    }

    private fun erro(mensagem: String): Nothing = throw RegraDeNegocioException(mensagem)

    companion object {
        const val MAX_PARCELAS = 12

        /**
         * Referência da venda na PagBank: até 10 caracteres alfanuméricos.
         * Ex.: comanda 42, 2º pagamento = "C42P2".
         */
        fun referencia(comandaId: Long, numeroPagamento: Int = 1): String =
            "C${comandaId}P$numeroPagamento".take(10)

        /**
         * Valor sugerido para cada pessoa ao dividir [restanteCentavos] entre [pessoas].
         * Arredonda para cima, então a última pessoa paga o que sobrar e a soma fecha exata
         * (ex.: R$ 100,00 / 3 = 33,34 + 33,33 + 33,33).
         */
        fun valorPorPessoa(restanteCentavos: Long, pessoas: Int): Long {
            require(pessoas >= 1) { "pessoas deve ser >= 1" }
            return (restanteCentavos + pessoas - 1) / pessoas
        }
    }
}
