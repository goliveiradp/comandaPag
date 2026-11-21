package br.com.comandapag.rede

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import br.com.comandapag.Dependencias
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.SetorProducao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * App de verdade nos dois papéis: um Dependencias em modo servidor (com o servidor HTTP
 * ligado) e outro em modo terminal falando com ele pela rede.
 */
@RunWith(RobolectricTestRunner::class)
class RedeAppTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val rede = ConfiguracaoRede(app)
    private val abertas = mutableListOf<Dependencias>()

    @After
    fun encerrar() {
        abertas.forEach { it.encerrar() }
        rede.virarIndependente()
    }

    private fun dependencias() = Dependencias(app).also { abertas += it }

    @Test
    fun terminalUsaOsDadosDoServidor() = runBlocking {
        rede.virarServidor()
        val servidor = dependencias()
        assertEquals(ModoRede.SERVIDOR, servidor.modo)
        servidor.usuarioService.criarPrimeiroAdministrador("Dono", "1234")
        servidor.produtos.salvar(Produto(nome = "Chopp", precoCentavos = 990, setor = SetorProducao.BAR))

        // Pareamento com o código mostrado no servidor
        val endereco = ClienteComandaPag.endereco("127.0.0.1", servidor.portaServidor)
        val r = ClienteComandaPag.parear(endereco, servidor.pareamento.codigo, "Maquininha 2")
        assertEquals(1, servidor.pareamento.aparelhosPareados)

        rede.virarTerminal(endereco, r.token, r.servidor)
        val terminal = dependencias()
        assertEquals(ModoRede.TERMINAL, terminal.modo)

        // Login no terminal com o usuário criado no servidor
        val dono = terminal.usuarioService.autenticar(terminal.usuarios.listarTodos().single().id, "1234")
        assertEquals("Dono", dono.nome)

        // Produtos do servidor aparecem no terminal
        val produtos = withTimeout(10_000) { terminal.produtos.observarAtivos().first { it.isNotEmpty() } }
        assertEquals("Chopp", produtos.single().nome)

        // Comanda aberta no terminal fica gravada no servidor
        val c = terminal.comandaService.abrir("Mesa 9", dono)
        terminal.comandaService.adicionarItem(c.id, produtos.single(), 2)
        val noServidor = servidor.comandas.buscar(c.id)!!
        assertEquals("Mesa 9", noServidor.identificacao)
        assertEquals(2, noServidor.itens.single().quantidade)
        assertEquals("Dono", noServidor.garcomNome)

        // Comanda aberta no servidor aparece no terminal
        servidor.comandaService.abrir("Balcão", dono)
        val vistas = withTimeout(10_000) {
            terminal.comandas.observarAbertas().first { lista -> lista.any { it.identificacao == "Balcão" } }
        }
        assertEquals(2, vistas.size)
    }

    @Test
    fun codigoNovoDesconectaOsTerminais() = runBlocking {
        rede.virarServidor()
        val servidor = dependencias()
        val endereco = ClienteComandaPag.endereco("127.0.0.1", servidor.portaServidor)
        val r = ClienteComandaPag.parear(endereco, servidor.pareamento.codigo, "Maquininha 2")
        assertTrue(servidor.pareamento.tokenValido(r.token))
        servidor.pareamento.renovar()
        assertTrue(!servidor.pareamento.tokenValido(r.token))
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
    }
}
