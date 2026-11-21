package br.com.comandapag.rede

import br.com.comandapag.core.fiscal.AmbienteFiscal
import br.com.comandapag.core.fiscal.EmissorNfce
import br.com.comandapag.core.fiscal.Emitente
import br.com.comandapag.core.fiscal.NotaJaEmitidaException
import br.com.comandapag.core.fiscal.NotaNfce
import br.com.comandapag.core.fiscal.RespostaCancelamento
import br.com.comandapag.core.fiscal.RespostaNfce
import br.com.comandapag.core.fiscal.StatusEmissor
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.core.servico.SemConexaoException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

/** Configuração da conta na Focus NFe (fica só na maquininha que emite). */
data class ConfiguracaoFocus(
    val token: String,
    val ambiente: AmbienteFiscal,
    val emitente: Emitente,
    /** Para testes; o padrão vem do ambiente. */
    val enderecoBase: String? = null,
) {
    val base: String
        get() = enderecoBase ?: when (ambiente) {
            AmbienteFiscal.PRODUCAO -> "https://api.focusnfe.com.br"
            else -> "https://homologacao.focusnfe.com.br"
        }
}

/**
 * Emissão de NFC-e pela API da Focus NFe (https://doc.focusnfe.com.br/reference/nfce).
 * A emissão é síncrona; a contingência offline (Sefaz fora do ar) é feita pela própria Focus.
 */
class FocusNfeEmissor(private val config: ConfiguracaoFocus) : EmissorNfce {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000 // a Sefaz pode demorar
        }
        expectSuccess = false
    }

    override suspend fun status() =
        StatusEmissor(ativo = config.token.isNotBlank() && config.emitente.cnpj.isNotBlank(), ambiente = config.ambiente, emitente = config.emitente)

    override suspend fun emitir(ref: String, nota: NotaNfce): RespostaNfce {
        val r = chamar {
            http.post("${config.base}/v2/nfce") {
                basicAuth(config.token, "")
                parameter("ref", ref)
                contentType(ContentType.Application.Json)
                setBody(corpo(nota).toString())
            }
        }
        val texto = r.bodyAsText()
        if (r.status == HttpStatusCode.UnprocessableEntity && codigoErro(texto) == "already_processed") {
            throw NotaJaEmitidaException()
        }
        verificar(r, texto)
        return resposta(Protocolo.json.parseToJsonElement(texto).jsonObject)
    }

    override suspend fun consultar(ref: String): RespostaNfce? {
        val r = chamar {
            http.get("${config.base}/v2/nfce/$ref") { basicAuth(config.token, "") }
        }
        if (r.status == HttpStatusCode.NotFound) return null
        val texto = r.bodyAsText()
        verificar(r, texto)
        return resposta(Protocolo.json.parseToJsonElement(texto).jsonObject)
    }

    override suspend fun cancelar(ref: String, justificativa: String): RespostaCancelamento {
        val r = chamar {
            http.delete("${config.base}/v2/nfce/$ref") {
                basicAuth(config.token, "")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("justificativa", justificativa) }.toString())
            }
        }
        val texto = r.bodyAsText()
        verificar(r, texto)
        val json = Protocolo.json.parseToJsonElement(texto).jsonObject
        return RespostaCancelamento(
            cancelada = json.texto("status") == "cancelado",
            mensagem = json.texto("mensagem_sefaz"),
        )
    }

    fun fechar() = http.close()

    /** Corpo JSON com os nomes de campo da Focus NFe. */
    fun corpo(nota: NotaNfce): JsonObject = buildJsonObject {
        put("cnpj_emitente", config.emitente.cnpj.filter(Char::isDigit))
        put("data_emissao", nota.dataEmissao)
        put("natureza_operacao", "VENDA AO CONSUMIDOR")
        put("presenca_comprador", "1") // presencial
        put("consumidor_final", "1")
        put("modalidade_frete", "9") // sem frete
        put("local_destino", "1") // operação interna
        nota.cpf?.let { put("cpf_destinatario", it) }
        if (nota.informacoesAdicionais.isNotBlank()) put("informacoes_adicionais_contribuinte", nota.informacoesAdicionais)
        put(
            "items",
            buildJsonArray {
                nota.itens.forEach { i ->
                    add(
                        buildJsonObject {
                            put("numero_item", i.numero.toString())
                            put("codigo_produto", i.codigo)
                            put("descricao", i.descricao)
                            put("codigo_ncm", i.fiscal.ncm)
                            put("cfop", i.fiscal.cfop)
                            put("unidade_comercial", i.fiscal.unidade)
                            put("quantidade_comercial", i.quantidade)
                            put("valor_unitario_comercial", reais(i.valorUnitarioCentavos))
                            put("unidade_tributavel", i.fiscal.unidade)
                            put("quantidade_tributavel", i.quantidade)
                            put("valor_unitario_tributavel", reais(i.valorUnitarioCentavos))
                            put("valor_bruto", reais(i.valorBrutoCentavos))
                            if (i.outrasDespesasCentavos > 0) put("valor_outras_despesas", reais(i.outrasDespesasCentavos))
                            put("icms_origem", i.fiscal.origem)
                            put("icms_situacao_tributaria", i.fiscal.icmsSituacao)
                            put("pis_situacao_tributaria", i.fiscal.pisCofinsSituacao)
                            put("cofins_situacao_tributaria", i.fiscal.pisCofinsSituacao)
                        },
                    )
                }
            },
        )
        put(
            "formas_pagamento",
            buildJsonArray {
                nota.pagamentos.forEach { p ->
                    add(
                        buildJsonObject {
                            put("forma_pagamento", p.codigo)
                            put("valor_pagamento", reais(p.valorCentavos))
                            if (p.codigo in setOf("03", "04", "17")) {
                                // Maquininha POS: pagamento "não integrado" com a automação (tpIntegra = 2)
                                put("tipo_integracao", "2")
                                p.bandeira?.let { put("bandeira_operadora", it) }
                            }
                        },
                    )
                }
            },
        )
        if (nota.trocoCentavos > 0) put("valor_troco", reais(nota.trocoCentavos))
    }

    private fun resposta(json: JsonObject) = RespostaNfce(
        autorizada = json.texto("status") == "autorizado",
        mensagem = json.texto("mensagem_sefaz") ?: json.texto("mensagem"),
        numero = json.texto("numero"),
        serie = json.texto("serie"),
        chave = json.texto("chave_nfe")?.removePrefix("NFe"),
        qrCodeUrl = json.texto("qrcode_url"),
        urlConsulta = json.texto("url_consulta_nf"),
        protocolo = json.texto("protocolo") ?: json.texto("numero_protocolo"),
        contingencia = (json["contingencia_offline"] as? JsonPrimitive)?.booleanOrNull == true,
        ambiente = config.ambiente,
        emitente = config.emitente,
    )

    private suspend fun chamar(chamada: suspend () -> HttpResponse): HttpResponse =
        try {
            chamada()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SemConexaoException("Sem conexão com a Focus NFe. Verifique a internet.")
        }

    private fun verificar(r: HttpResponse, texto: String) {
        if (r.status.value in 200..299) return
        if (r.status == HttpStatusCode.Unauthorized) throw RegraDeNegocioException("Token da Focus NFe inválido. Confira em Ajustes > Nota fiscal.")
        val json = runCatching { Protocolo.json.parseToJsonElement(texto).jsonObject }.getOrNull()
        val detalhes = (json?.get("erros") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.texto("mensagem") }.orEmpty()
        val mensagem = listOfNotNull(json?.texto("mensagem")).plus(detalhes).joinToString(" ").ifBlank { "Erro ${r.status.value} na Focus NFe." }
        throw RegraDeNegocioException("Focus NFe: $mensagem")
    }

    private fun codigoErro(texto: String): String? =
        runCatching { Protocolo.json.parseToJsonElement(texto).jsonObject.texto("codigo") }.getOrNull()

    private fun JsonObject.texto(campo: String): String? = (this[campo] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** 1250 centavos -> 12.50 (número decimal no JSON). */
        fun reais(centavos: Long): BigDecimal = BigDecimal.valueOf(centavos, 2)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(chave: String, valor: BigDecimal) = put(chave, JsonPrimitive(valor))
private fun kotlinx.serialization.json.JsonObjectBuilder.put(chave: String, valor: Int) = put(chave, JsonPrimitive(valor))
