package br.com.comandapag.core.impressao

import br.com.comandapag.core.impressao.ExtratoComanda.centralizar
import br.com.comandapag.core.impressao.ExtratoComanda.colunasEsqDir
import br.com.comandapag.core.impressao.ExtratoComanda.quebrar
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ResumoCaixa
import br.com.comandapag.core.modelo.TipoMovimento

/** Relatório de fechamento (ou parcial) do caixa para a impressora térmica. */
object RelatorioCaixa {

    fun gerar(
        resumo: ResumoCaixa,
        nomeEstabelecimento: String,
        colunas: Int = ExtratoComanda.COLUNAS,
        formatarData: (Long) -> String = ExtratoComanda::dataPadrao,
    ): List<String> {
        val c = resumo.caixa
        val sep = "-".repeat(colunas)
        val l = mutableListOf<String>()
        fun valor(rotulo: String, centavos: Long) { l += colunasEsqDir(rotulo, Dinheiro.formatar(centavos), colunas) }

        if (nomeEstabelecimento.isNotBlank()) l += centralizar(nomeEstabelecimento.uppercase(), colunas)
        l += centralizar(if (c.aberto) "CAIXA (PARCIAL)" else "FECHAMENTO DE CAIXA", colunas)
        l += sep
        l += quebrar("Aberto: ${formatarData(c.abertoEm)} ${c.abertoPor}", colunas)
        c.fechadoEm?.let { l += quebrar("Fechado: ${formatarData(it)} ${c.fechadoPor ?: ""}", colunas) }
        l += sep

        l += "VENDAS (${resumo.quantidadePagamentos})"
        FormaPagamento.entries.forEach { f -> resumo.vendasPorForma[f]?.let { valor("  ${f.descricao}", it) } }
        valor("  Total", resumo.totalVendasCentavos)
        if (resumo.estornosPorForma.isNotEmpty()) {
            l += "ESTORNOS"
            FormaPagamento.entries.forEach { f -> resumo.estornosPorForma[f]?.let { valor("  ${f.descricao}", -it) } }
            valor("  Líquido", resumo.totalVendasCentavos - resumo.totalEstornosCentavos)
        }
        l += sep

        l += "DINHEIRO NA GAVETA"
        valor("  Fundo de troco", c.valorInicialCentavos)
        valor("  Vendas em dinheiro", resumo.vendasPorForma[FormaPagamento.DINHEIRO] ?: 0)
        if (resumo.suprimentosCentavos > 0) valor("  Suprimentos", resumo.suprimentosCentavos)
        if (resumo.sangriasCentavos > 0) valor("  Sangrias", -resumo.sangriasCentavos)
        resumo.estornosPorForma[FormaPagamento.DINHEIRO]?.let { valor("  Estornos", -it) }
        valor("  Esperado", resumo.dinheiroEsperadoCentavos)
        c.valorContadoCentavos?.let { valor("  Contado", it) }
        resumo.diferencaCentavos?.let { d ->
            valor(
                when {
                    d > 0 -> "  SOBRA"
                    d < 0 -> "  FALTA"
                    else -> "  Diferença"
                },
                d,
            )
        }

        val movs = c.movimentos.filter { it.tipo != TipoMovimento.ESTORNO }
        if (movs.isNotEmpty()) {
            l += sep
            l += "MOVIMENTOS"
            movs.forEach { m ->
                val sinal = if (m.tipo == TipoMovimento.SANGRIA) -1 else 1
                valor("  ${m.tipo.descricao}", sinal * m.valorCentavos)
                if (m.motivo.isNotBlank()) l += quebrar("    ${m.motivo} (${m.por})", colunas)
            }
        }
        l += sep
        return l
    }
}
