package br.com.comandapag.core

import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.StatusComanda
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtratoComandaTest {
    @Test
    fun geraExtratoQueCabeNaBobina() {
        val comanda = Comanda(
            id = 1, identificacao = "Mesa 3", abertaEm = 0, taxaServico = true,
            status = StatusComanda.PAGA, fechadaEm = 0,
            itens = listOf(
                ItemComanda(1, "Cerveja", 1200, 2),
                ItemComanda(2, "Porção de batata frita com cheddar e bacon", 4590, 1, "bem passada"),
            ),
            pagamento = Pagamento(FormaPagamento.CREDITO, 7689, 1, "ABC123", "ID", "MASTERCARD", 0),
        )
        val linhas = ExtratoComanda.gerar(comanda, "Bar do Zé", formatarData = { "01/01/2026 20:00" })
        println(linhas.joinToString("\n"))

        assertTrue(linhas.all { it.length <= ExtratoComanda.COLUNAS }, "linha maior que a bobina")
        assertTrue(linhas.any { it.startsWith("TOTAL") && it.endsWith("R$ 76,89") })
        assertTrue(linhas.any { it.startsWith("Serviço (10%)") && it.endsWith("R$ 6,99") })
        assertTrue(linhas.any { it.startsWith("Pago (Crédito)") })
        assertEquals("Comanda: Mesa 3", linhas.first { it.startsWith("Comanda") })
        assertTrue("1x Porção de batata frita com" in linhas && "cheddar e bacon" in linhas)
    }

    @Test
    fun quebraPalavrasMaioresQueALinha() {
        assertEquals(listOf("abcde", "fghij", "k"), ExtratoComanda.quebrar("abcdefghijk", 5))
        assertEquals(listOf("ab cd", "ef"), ExtratoComanda.quebrar("ab cd ef", 5))
        assertEquals(listOf("  ab", "  cd"), ExtratoComanda.quebrar("  ab cd", 5))
    }
}
