package br.com.comandapag.rede

import br.com.comandapag.core.fiscal.AmbienteFiscal
import br.com.comandapag.core.fiscal.EmissorNfce
import br.com.comandapag.core.fiscal.NotaJaEmitidaException
import br.com.comandapag.core.fiscal.StatusEmissor
import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.repositorio.CaixaRepository
import br.com.comandapag.core.repositorio.ComandaRepository
import br.com.comandapag.core.repositorio.ProdutoRepository
import br.com.comandapag.core.repositorio.UsuarioRepository
import br.com.comandapag.core.servico.ConflitoDeVersaoException
import br.com.comandapag.core.servico.RegraDeNegocioException
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

/**
 * Servidor que roda na maquininha principal. Expõe os repositórios dela para os
 * terminais da rede local. As regras de negócio continuam rodando em cada aparelho;
 * o servidor só guarda os dados e recusa gravações com versão velha (HTTP 409).
 */
class ServidorComandaPag(
    private val produtos: ProdutoRepository,
    private val comandas: ComandaRepository,
    private val usuarios: UsuarioRepository,
    private val caixas: CaixaRepository,
    private val pareamento: Pareamento,
    val porta: Int = Protocolo.PORTA_PADRAO,
    private val nome: String = "ComandaPag",
    /** Emissor de NFC-e deste aparelho; os terminais emitem por ele. */
    private val emissorNfce: () -> EmissorNfce? = { null },
) {
    /** Aumenta a cada gravação (vinda da rede ou do próprio servidor, via [notificarMudanca]). */
    private val mudancas = AtomicLong(0)
    private var servidor: EmbeddedServer<*, *>? = null

    fun iniciar() {
        if (servidor != null) return
        servidor = embeddedServer(CIO, port = porta, host = "0.0.0.0") { modulo() }.start(wait = false)
    }

    fun parar() {
        servidor?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        servidor = null
    }

    /** Avisa os terminais que algo mudou (chamado quando o próprio servidor grava). */
    fun notificarMudanca() {
        mudancas.incrementAndGet()
    }

    private fun Application.modulo() {
        install(ContentNegotiation) { json(Protocolo.json) }
        routing {
            get("/ping") { call.respond(Ping(servidor = nome)) }
            post("/parear") {
                val pedido = call.receive<PedidoPareamento>()
                val token = pareamento.parear(pedido.codigo.trim(), pedido.aparelho)
                if (token == null) {
                    call.respond(HttpStatusCode.Forbidden, RespostaErro("Código de pareamento incorreto."))
                } else {
                    call.respond(RespostaPareamento(token, nome))
                }
            }

            protegido(HttpMethod.Get, "/mudancas") { call.respond(Mudancas(mudancas.get())) }

            // Produtos
            protegido(HttpMethod.Get, "/produtos") { call.respond(produtos.observarAtivos().first()) }
            protegido(HttpMethod.Get, "/produtos/{id}") { responderOu404(produtos.buscar(id())) }
            protegido(HttpMethod.Post, "/produtos") { gravou(produtos.salvar(call.receive<Produto>())) }
            protegido(HttpMethod.Post, "/produtos/{id}/desativar") { produtos.desativar(id()); gravou(id()) }

            // Comandas
            protegido(HttpMethod.Get, "/comandas/abertas") { call.respond(comandas.observarAbertas().first()) }
            protegido(HttpMethod.Get, "/comandas/fechadas") { call.respond(comandas.observarFechadas().first()) }
            protegido(HttpMethod.Get, "/comandas/aberta") {
                val identificacao = call.request.queryParameters["identificacao"].orEmpty()
                responderOu404(comandas.buscarAbertaPorIdentificacao(identificacao))
            }
            protegido(HttpMethod.Get, "/comandas/{id}") { responderOu404(comandas.buscar(id())) }
            protegido(HttpMethod.Post, "/comandas") { gravou(comandas.salvar(call.receive<Comanda>())) }

            // Usuários
            protegido(HttpMethod.Get, "/usuarios") { call.respond(usuarios.listarTodos()) }
            protegido(HttpMethod.Get, "/usuarios/{id}") { responderOu404(usuarios.buscar(id())) }
            protegido(HttpMethod.Post, "/usuarios") { gravou(usuarios.salvar(call.receive<Usuario>())) }

            // Caixa
            protegido(HttpMethod.Get, "/caixas/aberto") { responderOu404(caixas.buscarAberto()) }
            protegido(HttpMethod.Get, "/caixas/fechados") { call.respond(caixas.observarFechados().first()) }
            protegido(HttpMethod.Get, "/caixas/{id}") { responderOu404(caixas.buscar(id())) }
            protegido(HttpMethod.Get, "/caixas/{id}/pagamentos") { call.respond(comandas.pagamentosDoCaixa(id())) }
            protegido(HttpMethod.Post, "/caixas") { gravou(caixas.salvar(call.receive<Caixa>())) }

            // NFC-e: emitida aqui, com o token da Focus que só o servidor conhece
            protegido(HttpMethod.Get, "/fiscal/status") {
                call.respond(emissorNfce()?.status() ?: StatusEmissor(ativo = false, ambiente = AmbienteFiscal.SIMULADO))
            }
            protegido(HttpMethod.Post, "/fiscal/emitir") {
                val pedido = call.receive<PedidoEmissao>()
                val emissor = emissorNfce() ?: throw RegraDeNegocioException("A NFC-e não está configurada no servidor.")
                val r = try {
                    RespostaEmissaoServidor(resposta = emissor.emitir(pedido.ref, pedido.nota))
                } catch (_: NotaJaEmitidaException) {
                    RespostaEmissaoServidor(jaEmitida = true)
                }
                call.respond(r)
            }
            protegido(HttpMethod.Get, "/fiscal/consultar") {
                val emissor = emissorNfce() ?: throw RegraDeNegocioException("A NFC-e não está configurada no servidor.")
                responderOu404(emissor.consultar(call.request.queryParameters["ref"].orEmpty()))
            }
            protegido(HttpMethod.Post, "/fiscal/cancelar") {
                val pedido = call.receive<PedidoCancelamento>()
                val emissor = emissorNfce() ?: throw RegraDeNegocioException("A NFC-e não está configurada no servidor.")
                call.respond(emissor.cancelar(pedido.ref, pedido.justificativa))
            }
        }
    }

    private suspend fun RoutingContext.gravou(id: Long) {
        mudancas.incrementAndGet()
        call.respond(RespostaId(id))
    }

    private fun RoutingContext.id(): Long =
        call.parameters["id"]?.toLongOrNull() ?: throw RegraDeNegocioException("Identificador inválido.")

    private suspend inline fun <reified T : Any> RoutingContext.responderOu404(valor: T?) {
        if (valor == null) call.respond(HttpStatusCode.NotFound, RespostaErro("Não encontrado.")) else call.respond(valor)
    }

    /** Rota que exige o token do pareamento e converte os erros em códigos HTTP. */
    private fun Route.protegido(metodo: HttpMethod, caminho: String, bloco: suspend RoutingContext.() -> Unit) {
        route(caminho, metodo) {
            handle {
                if (!pareamento.tokenValido(call.request.headers[Protocolo.CABECALHO_TOKEN])) {
                    call.respond(HttpStatusCode.Unauthorized, RespostaErro("Aparelho não pareado."))
                    return@handle
                }
                try {
                    bloco()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConflitoDeVersaoException) {
                    call.respond(HttpStatusCode.Conflict, RespostaErro(e.message ?: "Conflito."))
                } catch (e: RegraDeNegocioException) {
                    call.respond(HttpStatusCode.UnprocessableEntity, RespostaErro(e.message ?: "Erro."))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, RespostaErro(e.message ?: "Requisição inválida."))
                }
            }
        }
    }
}
