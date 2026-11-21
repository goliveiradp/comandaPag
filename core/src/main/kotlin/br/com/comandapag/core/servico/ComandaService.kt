package br.com.comandapag.core.servico

import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.EventoComanda
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.MovimentoCaixa
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.SetorProducao
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.TipoEvento
import br.com.comandapag.core.modelo.TipoMovimento
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.pagamento.ResultadoPagamento
import br.com.comandapag.core.pagamento.SolicitacaoEstorno
import br.com.comandapag.core.pagamento.SolicitacaoPagamento
import br.com.comandapag.core.pagamento.TerminalPagamento
import br.com.comandapag.core.producao.ImpressorasProducao
import br.com.comandapag.core.producao.ItemPedido
import br.com.comandapag.core.producao.PedidoProducao
import br.com.comandapag.core.producao.TicketProducao
import br.com.comandapag.core.repositorio.CaixaRepository
import br.com.comandapag.core.repositorio.ComandaRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resultado do envio de um pedido: setores impressos e os que falharam (com o motivo). */
data class EnvioPedido(val enviados: List<SetorProducao>, val falhas: Map<SetorProducao, String>)

/** Resultado de um recebimento em dinheiro. */
data class RecebimentoDinheiro(val trocoCentavos: Long, val comanda: Comanda) {
    val quitada: Boolean get() = comanda.status == StatusComanda.PAGA
}

class ComandaService(
    private val comandas: ComandaRepository,
    private val terminal: TerminalPagamento,
    private val caixas: CaixaRepository,
    /** Impressoras dos tickets de cozinha/bar. Sem elas, não é possível enviar pedidos. */
    private val impressoras: ImpressorasProducao? = null,
    /** Onde ficam os pagamentos aprovados que não chegaram ao servidor. */
    private val pendentes: PagamentosPendentes = PagamentosPendentesEmMemoria(),
    /**
     * Toda alteração lê a comanda, modifica e grava. Sem essa trava, dois toques
     * rápidos em "adicionar" leriam a mesma versão e um item se perderia.
     * É compartilhada com o [CaixaService], porque estornos também mexem no caixa.
     * Entre aparelhos diferentes, quem protege é a versão da comanda (ver [repetindoEmConflito]).
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
            historico = listOf(EventoComanda(agora(), TipoEvento.ABRIU, responsavel.nome)),
        )
        nova.copy(id = comandas.salvar(nova))
    }

    /** Lança [quantidade] de [produto]; [operador] fica registrado no histórico da comanda. */
    suspend fun adicionarItem(
        comandaId: Long,
        produto: Produto,
        quantidade: Int = 1,
        observacao: String = "",
        operador: Usuario? = null,
    ): Comanda = trava.withLock {
        if (quantidade <= 0) erro("A quantidade deve ser maior que zero.")
        repetindoEmConflito { adicionarItemSemTrava(comandaId, produto, quantidade, observacao, operador) }
    }

    private suspend fun adicionarItemSemTrava(
        comandaId: Long,
        produto: Produto,
        quantidade: Int,
        observacao: String,
        operador: Usuario?,
    ): Comanda {
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
                setor = produto.setor,
            )
        }
        val evento = EventoComanda(
            em = agora(),
            tipo = TipoEvento.ADICIONOU,
            por = operador?.nome.orEmpty(),
            produtoId = produto.id,
            item = produto.nome,
            observacao = obs,
            quantidade = quantidade,
        )
        return salvar(comanda.copy(itens = itens, historico = comanda.historico + evento))
    }

    /**
     * Altera a quantidade do item na posição [indice]. Quantidade 0 remove o item.
     * Diminuir ou remover exige [Permissao.REMOVER_ITENS]. Se o que foi retirado já
     * estava na cozinha/bar, imprime um ticket de cancelamento.
     * [operador] é quem tem a permissão; se for outra pessoa autorizando, [solicitante] é
     * quem pediu (ex.: o garçom). Os dois ficam no histórico.
     */
    suspend fun alterarQuantidade(
        comandaId: Long,
        indice: Int,
        quantidade: Int,
        operador: Usuario,
        solicitante: Usuario? = null,
    ): Comanda = trava.withLock {
        if (quantidade < 0) erro("A quantidade não pode ser negativa.")
        // O item é identificado pela posição na tela; se outro aparelho mudar a comanda no
        // meio, a retentativa procura o mesmo item (produto, observação e preço).
        val alvo = comandaAberta(comandaId).itens.getOrNull(indice) ?: erro("Item não encontrado na comanda.")
        var item = alvo
        val salva = repetindoEmConflito {
            val comanda = comandaAberta(comandaId)
            val i = comanda.itens.indexOfFirst { it.mesmoItemQue(alvo) }
            if (i < 0) erro("Item não encontrado na comanda.")
            item = comanda.itens[i]
            if (quantidade < item.quantidade) exigir(operador, Permissao.REMOVER_ITENS)
            val itens = comanda.itens.toMutableList()
            if (quantidade == 0) {
                itens.removeAt(i)
            } else {
                itens[i] = item.copy(quantidade = quantidade, quantidadeEnviada = minOf(item.quantidadeEnviada, quantidade))
            }
            val diferenca = quantidade - item.quantidade
            val historico = if (diferenca == 0) {
                comanda.historico
            } else {
                val (por, autorizou) = quem(operador, solicitante)
                comanda.historico + EventoComanda(
                    em = agora(),
                    tipo = if (diferenca > 0) TipoEvento.ADICIONOU else TipoEvento.REMOVEU,
                    por = por,
                    autorizadoPor = autorizou.takeIf { diferenca < 0 },
                    produtoId = item.produtoId,
                    item = item.nome,
                    observacao = item.observacao,
                    quantidade = kotlin.math.abs(diferenca),
                )
            }
            salvar(semFicarAbaixoDoPago(comanda.copy(itens = itens, historico = historico)))
        }

        val cancelados = item.quantidadeEnviada - quantidade
        if (cancelados > 0 && item.setor != SetorProducao.NENHUM) {
            impressoras?.para(item.setor)?.let { impressora ->
                val pedido = PedidoProducao(
                    setor = item.setor,
                    comanda = salva.identificacao,
                    garcom = operador.nome,
                    numero = 0,
                    em = agora(),
                    itens = listOf(ItemPedido(cancelados, item.nome, item.observacao)),
                    cancelamento = true,
                )
                // Se a impressão falhar, a alteração continua valendo
                runCatching { impressora.imprimir(TicketProducao.gerar(pedido), TicketProducao.COLUNAS) }
            }
        }
        salva
    }

    /**
     * Envia para a cozinha/bar os itens ainda não enviados: imprime um ticket por setor.
     * Só os setores impressos com sucesso são marcados como enviados; os outros continuam
     * pendentes para tentar de novo.
     */
    suspend fun enviarPedido(comandaId: Long, operador: Usuario): EnvioPedido = trava.withLock {
        val impressoras = impressoras ?: erro("Impressora não disponível.")
        val comanda = comandaAberta(comandaId)
        val novos = comanda.itens.filter { it.quantidadePendente > 0 }
        if (novos.isEmpty()) erro("Não há itens novos para enviar.")

        val numero = comanda.pedidosEnviados + 1
        val momento = agora()
        val enviados = mutableListOf<SetorProducao>()
        val falhas = mutableMapOf<SetorProducao, String>()
        for ((setor, itensDoSetor) in novos.groupBy { it.setor }.toSortedMap()) {
            val pedido = PedidoProducao(
                setor = setor,
                comanda = comanda.identificacao,
                garcom = comanda.garcomNome.ifBlank { operador.nome },
                numero = numero,
                em = momento,
                itens = itensDoSetor.map { ItemPedido(it.quantidadePendente, it.nome, it.observacao) },
            )
            val r = runCatching { impressoras.para(setor).imprimir(TicketProducao.gerar(pedido), TicketProducao.COLUNAS) }
                .getOrElse { ResultadoOperacao.Falha(it.message ?: "Erro ao imprimir") }
            when (r) {
                ResultadoOperacao.Sucesso -> enviados += setor
                is ResultadoOperacao.Falha -> falhas[setor] = r.mensagem
            }
        }

        if (enviados.isNotEmpty()) {
            // Marca como enviado exatamente o que foi impresso. Se outro aparelho mexeu na
            // comanda depois da leitura, aplica sobre a versão nova sem reimprimir.
            val impressos = novos.filter { it.setor in enviados }
            repetindoEmConflito {
                val atual = comandaAberta(comandaId)
                val itens = atual.itens.map { item ->
                    val impresso = impressos.firstOrNull { it.mesmoItemQue(item) }
                    when {
                        impresso != null -> item.copy(
                            quantidadeEnviada = minOf(item.quantidade, item.quantidadeEnviada + impresso.quantidadePendente),
                        )
                        item.setor == SetorProducao.NENHUM -> item.copy(quantidadeEnviada = item.quantidade)
                        else -> item
                    }
                }
                salvar(atual.copy(itens = itens, pedidosEnviados = maxOf(atual.pedidosEnviados, numero)))
            }
        }
        EnvioPedido(enviados, falhas)
    }

    suspend fun removerItem(comandaId: Long, indice: Int, operador: Usuario, solicitante: Usuario? = null): Comanda =
        alterarQuantidade(comandaId, indice, 0, operador, solicitante)

    suspend fun definirTaxaServico(
        comandaId: Long,
        ativa: Boolean,
        operador: Usuario,
        solicitante: Usuario? = null,
    ): Comanda = trava.withLock {
        repetindoEmConflito {
            val comanda = comandaAberta(comandaId)
            if (comanda.taxaServico == ativa) return@repetindoEmConflito comanda
            exigir(operador, Permissao.ALTERAR_TAXA_SERVICO)
            val (por, autorizou) = quem(operador, solicitante)
            val evento = EventoComanda(agora(), if (ativa) TipoEvento.TAXA_LIGADA else TipoEvento.TAXA_DESLIGADA, por, autorizou)
            salvar(semFicarAbaixoDoPago(comanda.copy(taxaServico = ativa, historico = comanda.historico + evento)))
        }
    }

    suspend fun cancelar(comandaId: Long, operador: Usuario, solicitante: Usuario? = null): Comanda = trava.withLock {
        exigir(operador, Permissao.CANCELAR_COMANDA)
        repetindoEmConflito {
            val comanda = comandaAberta(comandaId)
            if (comanda.pagamentos.any { !it.estornado }) {
                erro("A comanda já tem pagamentos. Estorne-os antes de cancelar.")
            }
            val (por, autorizou) = quem(operador, solicitante)
            salvar(
                comanda.copy(
                    status = StatusComanda.CANCELADA,
                    fechadaEm = agora(),
                    fechadaPor = operador.nome,
                    historico = comanda.historico + EventoComanda(agora(), TipoEvento.CANCELOU, por, autorizou),
                ),
            )
        }
    }

    /** Quem fez (o solicitante, se houver) e quem autorizou (se foi outra pessoa). */
    private fun quem(operador: Usuario, solicitante: Usuario?): Pair<String, String?> =
        if (solicitante != null && solicitante.id != operador.id) {
            solicitante.nome to operador.nome
        } else {
            operador.nome to null
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
            val pagamento = Pagamento(
                    forma = forma,
                    valorCentavos = valor,
                    parcelas = parcelas,
                    codigoTransacao = resultado.codigoTransacao,
                    idTransacao = resultado.idTransacao,
                    bandeira = resultado.bandeira,
                    pagoEm = agora(),
                    recebidoPor = operador.nome,
                    caixaId = caixa.id,
                )
            try {
                registrar(comandaId, pagamento, operador.nome)
            } catch (e: SemConexaoException) {
                // O dinheiro já foi cobrado: guarda e reenvia quando a conexão voltar
                pendentes.adicionar(PagamentoPendente(comandaId, pagamento, operador.nome))
                return@withLock resultado.copy(registroPendente = true)
            }
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
            comandaId,
            Pagamento(
                forma = FormaPagamento.DINHEIRO,
                valorCentavos = abatido,
                pagoEm = agora(),
                recebidoPor = operador.nome,
                valorRecebidoCentavos = recebidoCentavos,
                trocoCentavos = troco,
                caixaId = caixa.id,
            ),
            operador.nome,
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
        if (comanda.nfce?.autorizada == true) {
            erro("Esta comanda tem NFC-e autorizada. Cancele a nota antes de estornar (até 30 minutos depois da emissão).")
        }
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
        repetindoEmConflito {
            val atual = comandas.buscar(comandaId) ?: erro("Comanda não encontrada.")
            // Pagamentos só são acrescentados ao fim, então a posição não muda
            val pagamentos = atual.pagamentos.toMutableList().also {
                it[indicePagamento] = it[indicePagamento].copy(estornadoEm = momento, estornadoPor = operador.nome)
            }
            var atualizada = atual.copy(pagamentos = pagamentos)
            if (atualizada.status == StatusComanda.PAGA && atualizada.restanteCentavos > 0) {
                atualizada = atualizada.copy(status = StatusComanda.ABERTA, fechadaEm = null, fechadaPor = null)
            }
            salvar(atualizada)
        }
        val movimento = MovimentoCaixa(
            tipo = TipoMovimento.ESTORNO,
            valorCentavos = pagamento.valorCentavos,
            motivo = "Comanda ${comanda.identificacao}",
            em = momento,
            por = operador.nome,
            forma = pagamento.forma,
        )
        repetindoEmConflito {
            val atual = caixas.buscar(caixa.id) ?: caixa
            caixas.salvar(atual.copy(movimentos = atual.movimentos + movimento))
        }
        ResultadoPagamento.Aprovado(
            codigoTransacao = pagamento.codigoTransacao,
            idTransacao = pagamento.idTransacao,
            bandeira = pagamento.bandeira,
            mensagem = "Estorno de ${Dinheiro.formatar(pagamento.valorCentavos)} realizado",
        )
    }

    private suspend fun caixaAberto(): Caixa =
        caixas.buscarAberto() ?: erro("O caixa está fechado. Abra o caixa para receber.")

    /**
     * Acrescenta o pagamento e fecha a comanda se ela ficou quitada. Lê a versão mais nova
     * a cada tentativa, porque outra maquininha pode ter mexido na comanda durante a cobrança.
     */
    private suspend fun registrar(comandaId: Long, pagamento: Pagamento, operador: String): Comanda =
        repetindoEmConflito {
            val comanda = comandas.buscar(comandaId) ?: erro("Comanda não encontrada.")
            val comPagamento = comanda.copy(pagamentos = comanda.pagamentos + pagamento)
            val final = if (comPagamento.estaAberta && comPagamento.restanteCentavos == 0L) {
                comPagamento.copy(status = StatusComanda.PAGA, fechadaEm = pagamento.pagoEm, fechadaPor = operador)
            } else {
                comPagamento
            }
            salvar(final)
        }

    /** Pagamentos aprovados que ainda não chegaram ao servidor. */
    suspend fun pagamentosPendentes(): List<PagamentoPendente> = pendentes.listar()

    /**
     * Tenta gravar os pagamentos pendentes. Retorna quantos foram gravados.
     * Chamado periodicamente pelo app enquanto houver pendências.
     */
    suspend fun reenviarPendentes(): Int = trava.withLock {
        var gravados = 0
        for (p in pendentes.listar()) {
            try {
                val comanda = comandas.buscar(p.comandaId)
                // Se já foi gravado (ex.: a resposta se perdeu mas o servidor recebeu), não duplica
                val jaGravado = comanda?.pagamentos?.any { it.codigoTransacao == p.pagamento.codigoTransacao && it.pagoEm == p.pagamento.pagoEm } == true
                if (!jaGravado) registrar(p.comandaId, p.pagamento, p.operador)
                pendentes.remover(p)
                gravados++
            } catch (_: SemConexaoException) {
                break
            }
        }
        gravados
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

    /** Grava e devolve a comanda já com a versão nova. */
    private suspend fun salvar(comanda: Comanda): Comanda {
        val id = comandas.salvar(comanda)
        return comanda.copy(id = id, versao = comanda.versao + 1)
    }

    /** Mesmo produto, observação e preço: é a mesma linha da comanda. */
    private fun ItemComanda.mesmoItemQue(outro: ItemComanda) =
        produtoId == outro.produtoId && observacao == outro.observacao &&
            precoUnitarioCentavos == outro.precoUnitarioCentavos

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
