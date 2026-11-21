package br.com.comandapag.rede

import br.com.comandapag.core.CaixaRepositoryEmMemoria
import br.com.comandapag.core.ComandaRepositoryEmMemoria
import br.com.comandapag.core.ProdutoRepositoryEmMemoria
import br.com.comandapag.core.TerminalFalso
import br.com.comandapag.core.UsuarioRepositoryEmMemoria
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.servico.CaixaService
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.core.servico.SemConexaoException
import br.com.comandapag.core.servico.UsuarioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Servidor de verdade (HTTP na porta local) com dois "aparelhos" conectados ao mesmo tempo. */
class RedeLocalTest {
    private val porta = ServerSocket(0).use { it.localPort }
    private val endereco = ClienteComandaPag.endereco("127.0.0.1", porta)
    private val pareamento = PareamentoEmMemoria("123456")

    // Dados do servidor
    private val produtos = ProdutoRepositoryEmMemoria()
    private val comandas = ComandaRepositoryEmMemoria()
    private val usuarios = UsuarioRepositoryEmMemoria()
    private val caixas = CaixaRepositoryEmMemoria()
    private val servidor = ServidorComandaPag(produtos, comandas, usuarios, caixas, pareamento, porta, "Bar do Zé")
        .also { it.iniciar() }

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admin = Usuario(id = 1, nome = "Dono", permissoes = Perfil.ADMINISTRADOR.permissoes)

    /** Um aparelho terminal: seus próprios serviços, falando com o servidor pela rede. */
    inner class Aparelho(nome: String) {
        val token = runBlocking { ClienteComandaPag.parear(endereco, "123456", nome).token }
        val cliente = ClienteComandaPag(endereco, token, escopo, intervaloMs = 200)
        val comandas = ComandaRepositoryRemoto(cliente)
        val caixas = CaixaRepositoryRemoto(cliente)
        val produtos = ProdutoRepositoryRemoto(cliente)
        val usuarios = UsuarioRepositoryRemoto(cliente)
        val terminal = TerminalFalso()
        val comandaService = ComandaService(this.comandas, terminal, this.caixas)
        val caixaService = CaixaService(this.caixas, this.comandas)
    }

    @AfterTest
    fun encerrar() {
        escopo.cancel()
        servidor.parar()
    }

    @Test
    fun pareamentoExigeOCodigoCerto() = runBlocking {
        val e = assertFailsWith<RegraDeNegocioException> { ClienteComandaPag.parear(endereco, "000000", "Intruso") }
        assertTrue(e.message!!.contains("incorreto"))

        // Sem token válido, nada é acessível
        val intruso = ClienteComandaPag(endereco, "token-falso", escopo)
        val erro = assertFailsWith<RegraDeNegocioException> { ComandaRepositoryRemoto(intruso).buscar(1) }
        assertTrue(erro.message!!.contains("pareado"))

        val r = ClienteComandaPag.parear(endereco, "123456", "Maquininha 2")
        assertEquals("Bar do Zé", r.servidor)
        assertTrue(pareamento.tokenValido(r.token))
    }

    @Test
    fun doisAparelhosLancandoNaMesmaComandaNaoPerdemItens() = runBlocking {
        val a = Aparelho("Maquininha A")
        val b = Aparelho("Maquininha B")
        val cerveja = Produto(id = 1, nome = "Cerveja", precoCentavos = 1200)
        val comanda = a.comandaService.abrir("Mesa 1", admin)

        // 15 cervejas de cada aparelho, ao mesmo tempo
        coroutineScope {
            (1..15).flatMap {
                listOf(
                    async(Dispatchers.IO) { a.comandaService.adicionarItem(comanda.id, cerveja) },
                    async(Dispatchers.IO) { b.comandaService.adicionarItem(comanda.id, cerveja) },
                )
            }.awaitAll()
        }
        val final = comandas.buscar(comanda.id)!!
        assertEquals(30, final.itens.single().quantidade, "nenhum item pode se perder")
    }

    @Test
    fun caixaEPagamentosSaoCompartilhados() = runBlocking {
        val a = Aparelho("Caixa")
        val b = Aparelho("Garçom")
        a.caixaService.abrir(10_000, admin)

        // O garçom (aparelho B) recebe em dinheiro e no PIX
        val cerveja = Produto(id = 1, nome = "Cerveja", precoCentavos = 1200)
        val c = b.comandaService.abrir("Mesa 2", admin)
        b.comandaService.adicionarItem(c.id, cerveja, 3) // 36,00
        b.comandaService.receberDinheiro(c.id, 2_000, admin, valorCentavos = 2_000)
        b.comandaService.cobrar(c.id, FormaPagamento.PIX, operador = admin)
        assertEquals(1, b.terminal.solicitacoes.size, "o PIX foi cobrado na maquininha do garçom")

        // O caixa (aparelho A) vê as vendas no mesmo caixa
        val resumo = a.caixaService.resumo(a.caixaService.caixaAberto()!!)
        assertEquals(2_000, resumo.vendasPorForma[FormaPagamento.DINHEIRO])
        assertEquals(1_600, resumo.vendasPorForma[FormaPagamento.PIX])
        assertEquals(12_000, resumo.dinheiroEsperadoCentavos)
        assertEquals(StatusComanda.PAGA, comandas.buscar(c.id)!!.status)

        // Sangria no aparelho A enquanto B estorna o dinheiro: o caixa fica com os dois movimentos
        coroutineScope {
            listOf(
                async(Dispatchers.IO) { a.caixaService.sangria(1_000, "Cofre", admin) },
                async(Dispatchers.IO) { b.comandaService.estornar(c.id, 0, admin) },
            ).awaitAll()
        }
        assertEquals(2, caixas.buscarAberto()!!.movimentos.size)
    }

    @Test
    fun loginEUsuariosVemDoServidor() = runBlocking {
        UsuarioService(usuarios).criarPrimeiroAdministrador("Dono", "4321")
        val a = Aparelho("Terminal")
        val servico = UsuarioService(a.usuarios)
        val dono = servico.autenticar(a.usuarios.listarTodos().single().id, "4321")
        assertEquals("Dono", dono.nome)
    }

    @Test
    fun telasDoOutroAparelhoAtualizamSozinhas() = runBlocking {
        val a = Aparelho("A")
        val b = Aparelho("B")
        val abertasNoB = b.comandas.observarAbertas()
        assertTrue(abertasNoB.first().isEmpty())
        a.comandaService.abrir("Mesa 7", admin)
        // B fica sabendo pela consulta periódica, sem fazer nada
        val vistas = withTimeout(5_000) { abertasNoB.first { it.isNotEmpty() } }
        assertEquals("Mesa 7", vistas.single().identificacao)

        a.produtos.salvar(Produto(nome = "Chopp", precoCentavos = 990))
        assertEquals("Chopp", withTimeout(5_000) { b.produtos.observarAtivos().first { it.isNotEmpty() } }.single().nome)
    }

    @Test
    fun semServidorAvisaSemConexao() = runBlocking {
        val a = Aparelho("A")
        servidor.parar()
        assertFailsWith<SemConexaoException> { a.comandas.buscar(1) }
        withTimeout(5_000) { a.cliente.conectado.first { !it } }
        Unit
    }
}
