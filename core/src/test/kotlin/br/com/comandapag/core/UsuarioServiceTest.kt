package br.com.comandapag.core

import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.seguranca.Pin
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.core.servico.SemPermissaoException
import br.com.comandapag.core.servico.UsuarioService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UsuarioServiceTest {
    private val repo = UsuarioRepositoryEmMemoria()
    private var relogio = 0L
    private val service = UsuarioService(repo, agora = { relogio })

    @Test
    fun pinNaoFicaGuardadoEmTexto() {
        val salt = Pin.gerarSalt()
        val hash = Pin.hash("1234", salt)
        assertFalse(hash.contains("1234"))
        assertTrue(Pin.confere("1234", salt, hash))
        assertFalse(Pin.confere("1235", salt, hash))
        assertNotEquals(hash, Pin.hash("1234", Pin.gerarSalt()), "salts diferentes geram hashes diferentes")
        assertFalse(Pin.valido("123"))
        assertFalse(Pin.valido("1234567"))
        assertFalse(Pin.valido("12a4"))
        assertTrue(Pin.valido("123456"))
    }

    @Test
    fun primeiroAcessoCriaAdministradorUmaVezSo() = runTest {
        assertFalse(service.existeUsuario())
        val admin = service.criarPrimeiroAdministrador(" Dono ", "4321")
        assertEquals("Dono", admin.nome)
        assertEquals(Permissao.entries.toSet(), admin.permissoes)
        assertEquals(admin.id, service.autenticar(admin.id, "4321").id)
        assertFailsWith<RegraDeNegocioException> { service.criarPrimeiroAdministrador("Outro", "1111") }
    }

    @Test
    fun cadastroDeUsuariosExigePermissaoEValida() = runTest {
        val admin = service.criarPrimeiroAdministrador("Dono", "4321")
        val garcom = service.salvar(Usuario(nome = "Ana", permissoes = Perfil.GARCOM.permissoes), "1111", admin)

        assertFailsWith<SemPermissaoException> {
            service.salvar(Usuario(nome = "Zé", permissoes = Perfil.GARCOM.permissoes), "2222", garcom)
        }
        assertFailsWith<RegraDeNegocioException> { // nome repetido
            service.salvar(Usuario(nome = "ana", permissoes = emptySet()), "2222", admin)
        }
        assertFailsWith<RegraDeNegocioException> { // usuário novo sem PIN
            service.salvar(Usuario(nome = "Zé", permissoes = emptySet()), null, admin)
        }
        assertFailsWith<RegraDeNegocioException> { // PIN curto
            service.salvar(Usuario(nome = "Zé", permissoes = emptySet()), "12", admin)
        }

        // Editar sem informar PIN mantém o PIN antigo
        service.salvar(garcom.copy(nome = "Ana Paula"), null, admin)
        assertEquals("Ana Paula", service.autenticar(garcom.id, "1111").nome)
    }

    @Test
    fun naoDeixaFicarSemAdministrador() = runTest {
        val admin = service.criarPrimeiroAdministrador("Dono", "4321")
        assertFailsWith<RegraDeNegocioException> {
            service.salvar(admin.copy(permissoes = Perfil.GERENTE.permissoes), null, admin)
        }
        assertFailsWith<RegraDeNegocioException> { service.salvar(admin.copy(ativo = false), null, admin) }

        // Com um segundo administrador, pode
        service.salvar(Usuario(nome = "Sócia", permissoes = Perfil.ADMINISTRADOR.permissoes), "9999", admin)
        service.salvar(admin.copy(permissoes = Perfil.GERENTE.permissoes), null, admin)
    }

    @Test
    fun usuarioInativoNaoEntra() = runTest {
        val admin = service.criarPrimeiroAdministrador("Dono", "4321")
        val garcom = service.salvar(Usuario(nome = "Ana", permissoes = Perfil.GARCOM.permissoes), "1111", admin)
        service.salvar(garcom.copy(ativo = false), null, admin)
        assertFailsWith<RegraDeNegocioException> { service.autenticar(garcom.id, "1111") }
    }

    @Test
    fun bloqueiaDepoisDeCincoErros() = runTest {
        val admin = service.criarPrimeiroAdministrador("Dono", "4321")
        repeat(UsuarioService.MAX_FALHAS) {
            assertFailsWith<RegraDeNegocioException> { service.autenticar(admin.id, "0000") }
        }
        // Bloqueado: nem o PIN certo funciona
        val e = assertFailsWith<RegraDeNegocioException> { service.autenticar(admin.id, "4321") }
        assertTrue(e.message!!.contains("Aguarde"))
        relogio += UsuarioService.BLOQUEIO_MS
        assertEquals(admin.id, service.autenticar(admin.id, "4321").id)
    }

    @Test
    fun autorizacaoExigeAPermissao() = runTest {
        val admin = service.criarPrimeiroAdministrador("Dono", "4321")
        val garcom = service.salvar(Usuario(nome = "Ana", permissoes = Perfil.GARCOM.permissoes), "1111", admin)
        assertFailsWith<SemPermissaoException> { service.autorizar(garcom.id, "1111", Permissao.CANCELAR_COMANDA) }
        assertEquals(admin.id, service.autorizar(admin.id, "4321", Permissao.CANCELAR_COMANDA).id)
    }

    @Test
    fun garcomTrocaOProprioPin() = runTest {
        val admin = service.criarPrimeiroAdministrador("Dono", "4321")
        val garcom = service.salvar(Usuario(nome = "Ana", permissoes = Perfil.GARCOM.permissoes), "1111", admin)
        assertFailsWith<RegraDeNegocioException> { service.alterarProprioPin(garcom.id, "9999", "2222") }
        service.alterarProprioPin(garcom.id, "1111", "2222")
        assertFailsWith<RegraDeNegocioException> { service.autenticar(garcom.id, "1111") }
        assertEquals(garcom.id, service.autenticar(garcom.id, "2222").id)
        assertEquals(Perfil.GARCOM.permissoes, repo.buscar(garcom.id)!!.permissoes)
    }

    @Test
    fun perfisSaoReconhecidos() {
        assertEquals(Perfil.GARCOM, Perfil.de(setOf(Permissao.COBRAR)))
        assertEquals(null, Perfil.de(setOf(Permissao.COBRAR, Permissao.CONFIGURAR)))
    }
}
