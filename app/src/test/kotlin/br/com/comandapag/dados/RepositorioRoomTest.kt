package br.com.comandapag.dados

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.MovimentoCaixa
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.TipoMovimento
import br.com.comandapag.core.modelo.Usuario
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositorioRoomTest {
    private val banco = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BancoDeDados::class.java)
        .allowMainThreadQueries().build()
    private val comandas = ComandaRepositoryRoom(banco.comandaDao())
    private val produtos = ProdutoRepositoryRoom(banco.produtoDao())
    private val usuarios = UsuarioRepositoryRoom(banco.usuarioDao())
    private val caixas = CaixaRepositoryRoom(banco.caixaDao())

    @After fun fechar() = banco.close()

    @Test
    fun salvaEAtualizaComandaComItens() = runBlocking {
        val id = comandas.salvar(Comanda(identificacao = "Mesa 1", abertaEm = 10, garcomId = 5, garcomNome = "Ana"))
        val itens = listOf(ItemComanda(1, "Cerveja", 1200, 2), ItemComanda(2, "Fritas", 3590, 1, "sem sal"))
        comandas.salvar(comandas.buscar(id)!!.copy(itens = itens))
        assertEquals(itens, comandas.buscar(id)!!.itens)
        assertEquals(id, comandas.buscarAbertaPorIdentificacao("mesa 1")!!.id)
        assertEquals(1, comandas.observarAbertas().first().size)

        val pagamentos = listOf(
            Pagamento(FormaPagamento.PIX, 1000, 1, "C", "I", null, 20, recebidoPor = "Ana"),
            Pagamento(FormaPagamento.DINHEIRO, 200, pagoEm = 21, valorRecebidoCentavos = 500, trocoCentavos = 300),
        )
        comandas.salvar(
            comandas.buscar(id)!!.copy(
                itens = itens.take(1), status = StatusComanda.PAGA, fechadaEm = 20, pagamentos = pagamentos, fechadaPor = "Bruno",
            ),
        )
        val paga = comandas.buscar(id)!!
        assertEquals("Ana", paga.garcomNome)
        assertEquals(5L, paga.garcomId)
        assertEquals("Bruno", paga.fechadaPor)
        assertEquals(pagamentos, paga.pagamentos)
        assertEquals(1, paga.itens.size)
        assertNull(comandas.buscarAbertaPorIdentificacao("Mesa 1"))
        assertEquals(1, comandas.observarFechadas().first().size)
    }

    @Test
    fun salvaUsuarioComPermissoes() = runBlocking {
        val id = usuarios.salvar(
            Usuario(nome = "Ana", permissoes = Perfil.CAIXA.permissoes, pinHash = "h", pinSalt = "s"),
        )
        val lido = usuarios.buscar(id)!!
        assertEquals(Perfil.CAIXA.permissoes, lido.permissoes)
        assertEquals("h", lido.pinHash)
        usuarios.salvar(lido.copy(permissoes = emptySet(), ativo = false))
        assertEquals(emptySet<Permissao>(), usuarios.buscar(id)!!.permissoes)
        assertEquals(1, usuarios.observarTodos().first().size)
    }

    @Test
    fun salvaCaixaComMovimentosEPagamentosVinculados() = runBlocking {
        val id = caixas.salvar(Caixa(abertoEm = 1, abertoPor = "Dani", valorInicialCentavos = 10_000))
        val estorno = MovimentoCaixa(TipoMovimento.ESTORNO, 500, "Comanda 1", 3, "Bruno", FormaPagamento.PIX)
        caixas.salvar(
            caixas.buscarAberto()!!.copy(
                movimentos = listOf(MovimentoCaixa(TipoMovimento.SANGRIA, 3_000, "Cofre", 2, "Dani"), estorno),
            ),
        )
        val lido = caixas.buscar(id)!!
        assertEquals(2, lido.movimentos.size)
        assertEquals(estorno, lido.movimentos[1])
        assertEquals(id, caixas.observarAberto().first()!!.id)

        val comandaId = comandas.salvar(
            Comanda(
                identificacao = "Mesa 1", abertaEm = 1,
                pagamentos = listOf(
                    Pagamento(FormaPagamento.PIX, 500, pagoEm = 2, caixaId = id, estornadoEm = 3, estornadoPor = "Bruno"),
                    Pagamento(FormaPagamento.DINHEIRO, 700, pagoEm = 2, caixaId = 999),
                ),
            ),
        )
        val doCaixa = comandas.pagamentosDoCaixa(id)
        assertEquals(1, doCaixa.size)
        assertTrue(doCaixa.single().estornado)
        assertEquals("Bruno", comandas.buscar(comandaId)!!.pagamentos[0].estornadoPor)

        caixas.salvar(lido.copy(fechadoEm = 9, fechadoPor = "Dani", valorContadoCentavos = 6_500))
        assertNull(caixas.buscarAberto())
        assertEquals(6_500L, caixas.observarFechados().first().single().valorContadoCentavos)
    }

    @Test
    fun produtosDesativadosSomemDaLista() = runBlocking {
        val id = produtos.salvar(Produto(nome = "Água", precoCentavos = 400, categoria = "Bebidas"))
        produtos.salvar(Produto(nome = "Suco", precoCentavos = 800))
        assertEquals(2, produtos.observarAtivos().first().size)
        produtos.desativar(id)
        assertEquals(listOf("Suco"), produtos.observarAtivos().first().map { it.nome })
    }
}
