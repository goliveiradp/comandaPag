package br.com.comandapag.core

import br.com.comandapag.core.modelo.Dinheiro
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DinheiroTest {
    @Test
    fun formata() {
        assertEquals("R$ 0,00", Dinheiro.formatar(0))
        assertEquals("R$ 0,05", Dinheiro.formatar(5))
        assertEquals("R$ 12,50", Dinheiro.formatar(1250))
        assertEquals("R$ 1.234,56", Dinheiro.formatar(123456))
        assertEquals("R$ 1.000.000,00", Dinheiro.formatar(100000000))
        assertEquals("-R$ 3,00", Dinheiro.formatar(-300))
    }

    @Test
    fun interpretaTexto() {
        assertEquals(1200, Dinheiro.parse("12"))
        assertEquals(1250, Dinheiro.parse("12,5"))
        assertEquals(1250, Dinheiro.parse("12,50"))
        assertEquals(1250, Dinheiro.parse("12.50"))
        assertEquals(123456, Dinheiro.parse("1.234,56"))
        assertEquals(123400, Dinheiro.parse("1.234"))
        assertEquals(300, Dinheiro.parse("R$ 3,00"))
        assertNull(Dinheiro.parse(""))
        assertNull(Dinheiro.parse("abc"))
        assertNull(Dinheiro.parse("1,234"))
        assertNull(Dinheiro.parse("-5"))
    }
}
