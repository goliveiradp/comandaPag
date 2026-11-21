package br.com.comandapag.rede

import br.com.comandapag.core.servico.ConflitoDeVersaoException
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.core.servico.SemConexaoException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Conexão de um terminal com a maquininha servidor. Consulta o servidor a cada
 * [intervaloMs] para saber se algo mudou e, nesse caso, atualiza as telas abertas.
 */
class ClienteComandaPag(
    /** Ex.: "http://192.168.0.10:8765". */
    val enderecoBase: String,
    private val token: String,
    escopo: CoroutineScope,
    private val intervaloMs: Long = 1_500,
) {
    @PublishedApi
    internal val http = criarHttp()

    private val sinal = MutableStateFlow(0L)
    private val _conectado = MutableStateFlow(true)

    /** Se a última consulta ao servidor funcionou. */
    val conectado: StateFlow<Boolean> = _conectado

    private val vigia: Job = escopo.launch {
        var ultimaVersao = Long.MIN_VALUE
        while (isActive) {
            try {
                val m = get<Mudancas>("/mudancas") ?: Mudancas(0)
                if (!_conectado.value) sinal.update { it + 1 } // voltou a conexão: atualiza tudo
                _conectado.value = true
                if (m.versao != ultimaVersao) {
                    ultimaVersao = m.versao
                    sinal.update { it + 1 }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _conectado.value = false
            }
            delay(intervaloMs)
        }
    }

    /** Força as telas a buscarem de novo (chamado logo depois de gravar). */
    fun avisarAlteracao() = sinal.update { it + 1 }

    /** Fluxo que busca de novo sempre que algo muda no servidor. Sem conexão, mantém o último valor. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observar(buscar: suspend () -> T): Flow<T> =
        sinal.transformLatest {
            try {
                emit(buscar())
            } catch (_: SemConexaoException) {
                // Sem rede: as telas continuam mostrando o que já tinham
            }
        }.distinctUntilChanged()

    suspend inline fun <reified T> get(caminho: String, parametros: Map<String, String> = emptyMap()): T? {
        val resposta = executar {
            http.get(enderecoBase + caminho) {
                cabecalhoToken()
                parametros.forEach { (k, v) -> parameter(k, v) }
            }
        }
        return if (resposta.status == HttpStatusCode.NotFound) null else verificar(resposta).body<T>()
    }

    suspend inline fun <reified B, reified R> post(caminho: String, corpo: B): R {
        val resposta = executar {
            http.post(enderecoBase + caminho) {
                cabecalhoToken()
                contentType(ContentType.Application.Json)
                setBody(corpo)
            }
        }
        return verificar(resposta).body<R>()
    }

    fun fechar() {
        vigia.cancel()
        http.close()
    }

    @PublishedApi
    internal fun io.ktor.client.request.HttpRequestBuilder.cabecalhoToken() = header(Protocolo.CABECALHO_TOKEN, token)

    @PublishedApi
    internal suspend fun executar(chamada: suspend () -> HttpResponse): HttpResponse =
        try {
            chamada()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _conectado.value = false
            throw SemConexaoException("Sem conexão com a maquininha servidor. Verifique o Wi-Fi.")
        }

    @PublishedApi
    internal suspend fun verificar(resposta: HttpResponse): HttpResponse {
        when (resposta.status) {
            HttpStatusCode.OK -> return resposta
            HttpStatusCode.Conflict -> throw ConflitoDeVersaoException()
            HttpStatusCode.Unauthorized -> throw RegraDeNegocioException(
                "Este aparelho não está pareado com o servidor. Pareie de novo em Ajustes > Rede.",
            )
            else -> {
                val texto = runCatching { resposta.body<RespostaErro>().mensagem }.getOrNull()
                throw RegraDeNegocioException(texto ?: "Erro no servidor (${resposta.status.value}).")
            }
        }
    }

    companion object {
        internal fun criarHttp() = HttpClient(CIO) {
            install(ContentNegotiation) { json(Protocolo.json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 10_000
            }
            expectSuccess = false
        }

        fun endereco(host: String, porta: Int = Protocolo.PORTA_PADRAO) = "http://${host.trim()}:$porta"

        /** Pareia este aparelho com o servidor usando o código mostrado na tela dele. */
        suspend fun parear(enderecoBase: String, codigo: String, aparelho: String): RespostaPareamento {
            val http = criarHttp()
            try {
                val resposta = try {
                    http.post("$enderecoBase/parear") {
                        contentType(ContentType.Application.Json)
                        setBody(PedidoPareamento(codigo, aparelho))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw SemConexaoException("Não encontrei o servidor em $enderecoBase. Confira o IP e o Wi-Fi.")
                }
                return when (resposta.status) {
                    HttpStatusCode.OK -> resposta.body()
                    HttpStatusCode.Forbidden -> throw RegraDeNegocioException("Código de pareamento incorreto.")
                    else -> throw RegraDeNegocioException("Esse endereço não é um servidor ComandaPag.")
                }
            } finally {
                http.close()
            }
        }
    }
}
