package br.com.comandapag.core.producao

import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.SetorProducao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ItemPedido(val quantidade: Int, val nome: String, val observacao: String = "")

/** Um ticket para a cozinha ou o bar. */
data class PedidoProducao(
    val setor: SetorProducao,
    val comanda: String,
    val garcom: String,
    /** Número do pedido dentro da comanda (1º, 2º...). Zero em cancelamentos. */
    val numero: Int,
    val em: Long,
    val itens: List<ItemPedido>,
    val cancelamento: Boolean = false,
)

/** Texto do ticket de produção, com letra grande (24 colunas). */
object TicketProducao {
    const val COLUNAS = 24

    fun gerar(
        pedido: PedidoProducao,
        colunas: Int = COLUNAS,
        formatarHora: (Long) -> String = ::horaPadrao,
    ): List<String> {
        val forte = "=".repeat(colunas)
        val l = mutableListOf<String>()
        l += forte
        if (pedido.cancelamento) l += ExtratoComanda.centralizar("*** CANCELAMENTO ***", colunas)
        l += ExtratoComanda.centralizar(pedido.setor.descricao.uppercase(), colunas)
        l += forte
        val numero = if (pedido.numero > 0) "Pedido ${pedido.numero}" else ""
        if (pedido.comanda.length + numero.length + 1 <= colunas) {
            l += ExtratoComanda.colunasEsqDir(pedido.comanda, numero, colunas)
        } else {
            // Nome longo: comanda inteira numa linha e o número do pedido na outra
            l += ExtratoComanda.quebrar(pedido.comanda, colunas)
            if (numero.isNotEmpty()) l += numero
        }
        if (pedido.garcom.isNotBlank()) l += ExtratoComanda.quebrar("Garçom: ${pedido.garcom}", colunas)
        l += formatarHora(pedido.em)
        l += "-".repeat(colunas)
        pedido.itens.forEach { item ->
            val prefixo = if (pedido.cancelamento) "-" else ""
            l += ExtratoComanda.quebrar("$prefixo${item.quantidade}x ${item.nome}", colunas)
            if (item.observacao.isNotBlank()) l += ExtratoComanda.quebrar("  >> ${item.observacao}", colunas)
        }
        l += forte
        return l
    }

    fun horaPadrao(millis: Long): String =
        SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(millis))
}
