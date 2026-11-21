package br.com.comandapag.core.fiscal

import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.servico.RegraDeNegocioException

/** Transforma uma comanda paga nos dados da NFC-e. */
object MontagemNfce {

    fun montar(comanda: Comanda, produtos: Map<Long, Produto>, dataEmissao: String, cpf: String? = null): NotaNfce {
        if (comanda.itens.isEmpty()) erro("A comanda não tem itens.")
        val semFiscal = comanda.itens.mapNotNull { item ->
            val p = produtos[item.produtoId]
            if (p == null || !p.fiscal.completos) item.nome else null
        }.distinct()
        if (semFiscal.isNotEmpty()) {
            erro("Cadastre o NCM (8 dígitos) e a tributação em Produtos: ${semFiscal.joinToString(", ")}.")
        }

        // A taxa de serviço entra como "outras despesas", rateada pelos itens na proporção do valor
        val taxa = comanda.taxaServicoCentavos
        val subtotal = comanda.subtotalCentavos
        val rateio = comanda.itens.map { if (subtotal == 0L) 0L else taxa * it.subtotalCentavos / subtotal }.toMutableList()
        val sobra = taxa - rateio.sum()
        if (sobra != 0L) {
            val maior = comanda.itens.indices.maxBy { comanda.itens[it].subtotalCentavos }
            rateio[maior] += sobra
        }

        val itens = comanda.itens.mapIndexed { i, item ->
            val p = produtos.getValue(item.produtoId)
            ItemNota(
                numero = i + 1,
                codigo = item.produtoId.toString(),
                descricao = (item.nome + if (item.observacao.isNotBlank()) " (${item.observacao})" else "").take(120),
                quantidade = item.quantidade,
                valorUnitarioCentavos = item.precoUnitarioCentavos,
                valorBrutoCentavos = item.subtotalCentavos,
                outrasDespesasCentavos = rateio[i],
                fiscal = p.fiscal,
            )
        }

        val validos = comanda.pagamentos.filter { !it.estornado }
        val pagamentos = validos.map { p ->
            when (p.forma) {
                // No dinheiro vai o que o cliente entregou; o troco vai à parte
                FormaPagamento.DINHEIRO -> PagamentoNota("01", p.valorRecebidoCentavos ?: p.valorCentavos)
                FormaPagamento.CREDITO -> PagamentoNota("03", p.valorCentavos, bandeira(p.bandeira))
                FormaPagamento.DEBITO -> PagamentoNota("04", p.valorCentavos, bandeira(p.bandeira))
                FormaPagamento.PIX -> PagamentoNota("17", p.valorCentavos)
            }
        }
        val troco = validos.sumOf { it.trocoCentavos }
        val nota = NotaNfce(
            dataEmissao = dataEmissao,
            itens = itens,
            pagamentos = pagamentos,
            trocoCentavos = troco,
            cpf = cpf?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() },
            informacoesAdicionais = "Comanda ${comanda.identificacao}" +
                (if (comanda.garcomNome.isNotBlank()) " - Garcom: ${comanda.garcomNome}" else ""),
        )
        // A soma dos pagamentos menos o troco tem que bater com o total da nota
        if (nota.pagamentos.sumOf { it.valorCentavos } - troco != nota.totalCentavos) {
            erro("Os pagamentos não fecham com o total da comanda.")
        }
        return nota
    }

    /** Código da bandeira na NFC-e (tBand). */
    fun bandeira(nome: String?): String? {
        val n = nome?.uppercase()?.replace(" ", "") ?: return null
        return when {
            n.isBlank() -> null
            "VISA" in n -> "01"
            "MASTER" in n -> "02"
            "AMEX" in n || "AMERICAN" in n -> "03"
            "SOROCRED" in n -> "04"
            "DINERS" in n -> "05"
            "ELO" in n -> "06"
            "HIPER" in n -> "07"
            "AURA" in n -> "08"
            "CABAL" in n -> "09"
            else -> "99"
        }
    }

    /** CPF com dígitos verificadores corretos. */
    fun cpfValido(cpf: String): Boolean {
        val d = cpf.filter(Char::isDigit)
        if (d.length != 11 || d.all { it == d[0] }) return false
        fun digito(n: Int): Int {
            val soma = (0 until n).sumOf { (d[it] - '0') * (n + 1 - it) }
            return (soma * 10 % 11).let { if (it == 10) 0 else it }
        }
        return digito(9) == d[9] - '0' && digito(10) == d[10] - '0'
    }

    private fun erro(m: String): Nothing = throw RegraDeNegocioException(m)
}
