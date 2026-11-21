package br.com.comandapag.rede

import br.com.comandapag.core.CaixaRepositoryEmMemoria
import br.com.comandapag.core.ComandaRepositoryEmMemoria
import br.com.comandapag.core.EmissorNfceFalso
import br.com.comandapag.core.ProdutoRepositoryEmMemoria
import br.com.comandapag.core.TerminalFalso
import br.com.comandapag.core.UsuarioRepositoryEmMemoria
import br.com.comandapag.core.fiscal.AmbienteFiscal
import br.com.comandapag.core.fiscal.Emitente
import br.com.comandapag.core.fiscal.ItemNota
import br.com.comandapag.core.fiscal.NfceService
import br.com.comandapag.core.fiscal.NotaJaEmitidaException
import br.com.comandapag.core.fiscal.NotaNfce
import br.com.comandapag.core.fiscal.PagamentoNota
import br.com.comandapag.core.modelo.DadosFiscais
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusNfce
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.servico.CaixaService
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.RegraDeNegocioException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Focus NFe "de mentira": servidor HTTP local que responde como a documentação oficial. */
class FocusNfeTest {
    private class Requisicao(val metodo: String, val caminho: String, val ref: String?, val autorizacao: String?, val corpo: String)

    private val recebidas = mutableListOf<Requisicao>()
    private var respostaEmissao: Pair<HttpStatusCode, String> = HttpStatusCode.Created to """
        {"cnpj_emitente":"12345678000195","ref":"cmd1","status":"autorizado","status_sefaz":"100",
         "mensagem_sefaz":"Autorizado o uso da NF-e","chave_nfe":"NFe21261112345678000195650010000000121743484310",
         "numero":"12","serie":"1","caminho_danfe":"/x.html",
         "qrcode_url":"http://www.nfce.sefaz.ma.gov.br/portal/consultarNFCe.jsp?p=21261112345678000195650010000000121743484310|2|2|1|ABC",
         "url_consulta_nf":"http://www.nfce.sefaz.ma.gov.br/portal/consultaNFe.do"}
    """.trimIndent()

    private val porta = ServerSocket(0).use { it.localPort }
    private val focus = embeddedServer(CIO, port = porta) {
        routing {
            post("/v2/nfce") {
                recebidas += Requisicao("POST", "/v2/nfce", call.request.queryParameters["ref"], call.request.headers["Authorization"], call.receiveText())
                call.respondText(respostaEmissao.second, ContentType.Application.Json, respostaEmissao.first)
            }
            get("/v2/nfce/{ref}") {
                val ref = call.parameters["ref"]
                recebidas += Requisicao("GET", "/v2/nfce/$ref", ref, call.request.headers["Authorization"], "")
                if (ref == "cmd1") call.respondText(respostaEmissao.second, ContentType.Application.Json)
                else call.respondText("""{"codigo":"nao_encontrado","mensagem":"Nota fiscal não encontrada"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
            delete("/v2/nfce/{ref}") {
                recebidas += Requisicao("DELETE", "/v2/nfce/${call.parameters["ref"]}", call.parameters["ref"], call.request.headers["Authorization"], call.receiveText())
                call.respondText("""{"status_sefaz":"135","mensagem_sefaz":"Evento registrado e vinculado a NFC-e","status":"cancelado"}""", ContentType.Application.Json)
            }
        }
    }.start(wait = false)

    private val emitente = Emitente("12.345.678/0001-95", "Bar do Zé Ltda", "123456789", "Rua A, 1 - São Luís/MA")
    private val emissor = FocusNfeEmissor(
        ConfiguracaoFocus("TOKEN123", AmbienteFiscal.HOMOLOGACAO, emitente, enderecoBase = "http://127.0.0.1:$porta"),
    )

    private val nota = NotaNfce(
        dataEmissao = "2026-11-21T20:00:00-03:00",
        itens = listOf(
            ItemNota(1, "7", "Cerveja", 2, 1200, 2400, 240, DadosFiscais(ncm = "22030000", cfop = "5405", icmsSituacao = "500")),
        ),
        pagamentos = listOf(PagamentoNota("01", 5000), PagamentoNota("03", 1000, "02"), PagamentoNota("17", 640)),
        trocoCentavos = 3000,
        cpf = "52998224725",
        informacoesAdicionais = "Comanda Mesa 3",
    )

    @AfterTest
    fun parar() {
        emissor.fechar()
        focus.stop(100, 500)
    }

    @Test
    fun emiteComOsCamposDaDocumentacao() = runBlocking {
        val r = emissor.emitir("cmd1", nota)
        assertTrue(r.autorizada)
        assertEquals("12", r.numero)
        assertEquals("21261112345678000195650010000000121743484310", r.chave, "sem o prefixo NFe")
        assertTrue(r.qrCodeUrl!!.startsWith("http://www.nfce.sefaz.ma.gov.br"))

        val req = recebidas.single()
        assertEquals("cmd1", req.ref)
        // HTTP Basic: usuário = token, senha em branco
        assertEquals("Basic " + Base64.getEncoder().encodeToString("TOKEN123:".toByteArray()), req.autorizacao)

        val json = Protocolo.json.parseToJsonElement(req.corpo).jsonObject
        fun JsonObject.t(c: String) = this[c]!!.jsonPrimitive.content
        assertEquals("12345678000195", json.t("cnpj_emitente"))
        assertEquals("2026-11-21T20:00:00-03:00", json.t("data_emissao"))
        assertEquals("1", json.t("presenca_comprador"))
        assertEquals("1", json.t("consumidor_final"))
        assertEquals("9", json.t("modalidade_frete"))
        assertEquals("1", json.t("local_destino"))
        assertEquals("52998224725", json.t("cpf_destinatario"))
        assertEquals("30.00", json.t("valor_troco"))

        val item = json["items"]!!.jsonArray.single().jsonObject
        assertEquals("22030000", item.t("codigo_ncm"))
        assertEquals("5405", item.t("cfop"))
        assertEquals("500", item.t("icms_situacao_tributaria"))
        assertEquals("0", item.t("icms_origem"))
        assertEquals("49", item.t("pis_situacao_tributaria"))
        assertEquals("49", item.t("cofins_situacao_tributaria"))
        assertEquals("12.00", item.t("valor_unitario_comercial"))
        assertEquals("24.00", item.t("valor_bruto"))
        assertEquals("2.40", item.t("valor_outras_despesas"))
        assertEquals("2", item.t("quantidade_comercial"))

        val pags = json["formas_pagamento"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("01", "03", "17"), pags.map { it.t("forma_pagamento") })
        assertEquals("50.00", pags[0].t("valor_pagamento"))
        assertNull(pags[0]["tipo_integracao"], "dinheiro não leva grupo de cartão")
        assertEquals("2", pags[1].t("tipo_integracao"), "maquininha POS = não integrado")
        assertEquals("02", pags[1].t("bandeira_operadora"))
        assertEquals("2", pags[2].t("tipo_integracao"))
    }

    @Test
    fun rejeicaoEErros() = runBlocking {
        respostaEmissao = HttpStatusCode.Created to """{"status":"erro_autorizacao","status_sefaz":"704","mensagem_sefaz":"Rejeição: NFC-e com Data-Hora de emissão atrasada"}"""
        val r = emissor.emitir("cmd1", nota)
        assertTrue(!r.autorizada)
        assertTrue(r.mensagem!!.contains("704") || r.mensagem!!.contains("atrasada"))

        respostaEmissao = HttpStatusCode.UnprocessableEntity to """{"codigo":"already_processed","mensagem":"A nota fiscal já foi autorizada"}"""
        assertFailsWith<NotaJaEmitidaException> { emissor.emitir("cmd1", nota) }

        respostaEmissao = HttpStatusCode.UnprocessableEntity to """{"codigo":"erro_validacao_schema","mensagem":"data_emissao não pode ser vazio"}"""
        val e = assertFailsWith<RegraDeNegocioException> { emissor.emitir("cmd1", nota) }
        assertTrue(e.message!!.contains("data_emissao"))

        respostaEmissao = HttpStatusCode.Unauthorized to "HTTP Basic: Access denied"
        assertTrue(assertFailsWith<RegraDeNegocioException> { emissor.emitir("cmd1", nota) }.message!!.contains("Token"))
    }

    @Test
    fun consultaECancela() = runBlocking {
        assertEquals("12", emissor.consultar("cmd1")!!.numero)
        assertNull(emissor.consultar("cmd99"))
        val c = emissor.cancelar("cmd1", "Cancelamento da venda a pedido do cliente")
        assertTrue(c.cancelada)
        val req = recebidas.last()
        assertEquals("DELETE", req.metodo)
        assertEquals("/v2/nfce/cmd1", req.caminho)
        assertEquals("Cancelamento da venda a pedido do cliente", Protocolo.json.parseToJsonElement(req.corpo).jsonObject["justificativa"]!!.jsonPrimitive.content)
    }

    @Test
    fun terminalEmitePeloServidor() = runBlocking {
        val porta = ServerSocket(0).use { it.localPort }
        val comandas = ComandaRepositoryEmMemoria()
        val produtos = ProdutoRepositoryEmMemoria()
        val caixas = CaixaRepositoryEmMemoria()
        val emissorDoServidor = EmissorNfceFalso()
        val pareamento = PareamentoEmMemoria("123456")
        val servidor = ServidorComandaPag(produtos, comandas, UsuarioRepositoryEmMemoria(), caixas, pareamento, porta, emissorNfce = { emissorDoServidor })
        servidor.iniciar()
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val endereco = ClienteComandaPag.endereco("127.0.0.1", porta)
            val cliente = ClienteComandaPag(endereco, ClienteComandaPag.parear(endereco, "123456", "T").token, escopo)
            val comandasRemotas = ComandaRepositoryRemoto(cliente)
            val produtosRemotos = ProdutoRepositoryRemoto(cliente)
            val admin = Usuario(1, "Dono", Perfil.ADMINISTRADOR.permissoes)
            val prato = Produto(nome = "Prato", precoCentavos = 2500, fiscal = DadosFiscais(ncm = "21069090"))
                .let { it.copy(id = produtosRemotos.salvar(it)) }
            val servico = ComandaService(comandasRemotas, TerminalFalso(), CaixaRepositoryRemoto(cliente))
            CaixaService(CaixaRepositoryRemoto(cliente), comandasRemotas).abrir(0, admin)
            val c = servico.abrir("Mesa 1", admin)
            servico.adicionarItem(c.id, prato)
            servico.cobrar(c.id, FormaPagamento.PIX, operador = admin)

            val nfce = NfceService(comandasRemotas, produtosRemotos, EmissorNfceRemoto(cliente))
            assertTrue(nfce.status().ativo)
            val r = nfce.emitir(c.id, admin)
            assertEquals(StatusNfce.AUTORIZADA, r.nota.status)
            assertEquals("Bar do Zé Ltda", r.emitente!!.razaoSocial)
            assertEquals("cmd${c.id}", emissorDoServidor.emitidas.single().first, "quem falou com a Sefaz foi o servidor")
            assertTrue(comandas.buscar(c.id)!!.nfce!!.autorizada, "a nota ficou gravada no servidor")
        } finally {
            escopo.cancel()
            servidor.parar()
        }
    }

    @Test
    fun qrCodeEscPos() {
        val bytes = ImpressoraRede.escPosComQrCode(listOf("NFC-e"), "http://x?p=1", listOf("fim"))
        val texto = String(bytes, Charsets.ISO_8859_1)
        assertTrue(texto.contains("http://x?p=1"))
        val inicioQr = byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00)
        assertTrue(String(inicioQr, Charsets.ISO_8859_1) in texto, "comando de QR Code modelo 2")
        // Comando "guardar dados": GS ( k pL pH 31 50 30 + dados, com pL/pH = tamanho dos dados + 3
        val qr = ImpressoraRede.qrCodeEscPos("abc")
        val inicio = 9 + 8 + 8 // modelo, tamanho do módulo e correção de erro vêm antes
        assertEquals(listOf(0x1D, 0x28, 0x6B, 6, 0, 0x31, 0x50, 0x30), qr.copyOfRange(inicio, inicio + 8).map { it.toInt() })
        assertEquals("abc", String(qr.copyOfRange(inicio + 8, inicio + 11), Charsets.US_ASCII))
    }
}
