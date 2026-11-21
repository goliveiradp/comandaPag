package br.com.comandapag.core.fiscal

import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.NotaFiscalConsumidor
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.StatusNfce
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.repositorio.ComandaRepository
import br.com.comandapag.core.repositorio.ProdutoRepository
import br.com.comandapag.core.servico.ConflitoDeVersaoException
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.core.servico.SemPermissaoException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class ResultadoEmissao(val comanda: Comanda, val nota: NotaFiscalConsumidor, val emitente: Emitente?)

/** Emissão e cancelamento da NFC-e das comandas pagas. */
class NfceService(
    private val comandas: ComandaRepository,
    private val produtos: ProdutoRepository,
    private val emissor: EmissorNfce,
    private val agora: () -> Long = System::currentTimeMillis,
    private val fuso: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun status(): StatusEmissor = emissor.status()

    /** Emite a NFC-e da comanda paga. [cpf] é opcional (CPF na nota). */
    suspend fun emitir(comandaId: Long, operador: Usuario, cpf: String? = null): ResultadoEmissao {
        if (!operador.pode(Permissao.COBRAR)) throw SemPermissaoException(Permissao.COBRAR)
        val status = emissor.status()
        if (!status.ativo) erro("A NFC-e não está configurada. Veja em Ajustes > Nota fiscal.")
        val cpfLimpo = cpf?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }
        if (cpfLimpo != null && !MontagemNfce.cpfValido(cpfLimpo)) erro("CPF inválido.")

        val comanda = comandas.buscar(comandaId) ?: erro("Comanda não encontrada.")
        if (comanda.status != StatusComanda.PAGA) erro("A NFC-e só pode ser emitida depois que a comanda estiver paga.")
        if (comanda.nfce?.autorizada == true) erro("Esta comanda já tem NFC-e autorizada.")

        val catalogo = comanda.itens.map { it.produtoId }.distinct()
            .mapNotNull { id -> produtos.buscar(id)?.let { id to it } }.toMap()
        val momento = agora()
        val nota = MontagemNfce.montar(comanda, catalogo, dataEmissao(momento), cpfLimpo)

        // A referência só muda depois de uma rejeição ou cancelamento. Se a resposta se perder,
        // reenviar com a mesma referência não gera nota em dobro.
        val ref = referencia(comanda)
        val resposta = try {
            emissor.emitir(ref, nota)
        } catch (_: NotaJaEmitidaException) {
            emissor.consultar(ref) ?: erro("O emissor diz que a nota já existe, mas não a encontrou.")
        }
        val registro = NotaFiscalConsumidor(
            ref = ref,
            status = if (resposta.autorizada) StatusNfce.AUTORIZADA else StatusNfce.REJEITADA,
            ambiente = resposta.ambiente.name,
            emitidaEm = momento,
            numero = resposta.numero,
            serie = resposta.serie,
            chave = resposta.chave,
            qrCodeUrl = resposta.qrCodeUrl,
            urlConsulta = resposta.urlConsulta,
            protocolo = resposta.protocolo,
            mensagem = resposta.mensagem,
            cpf = cpfLimpo,
            contingencia = resposta.contingencia,
        )
        val salva = gravar(comandaId) {
            it.copy(nfce = registro, tentativasNfce = it.tentativasNfce + if (resposta.autorizada) 0 else 1)
        }
        if (!resposta.autorizada) erro("NFC-e rejeitada: ${resposta.mensagem ?: "sem detalhes"}")
        return ResultadoEmissao(salva, registro, resposta.emitente ?: status.emitente)
    }

    /** Cancela a NFC-e (a Sefaz permite em até 30 minutos depois da emissão). */
    suspend fun cancelar(comandaId: Long, operador: Usuario, justificativa: String = JUSTIFICATIVA_PADRAO): Comanda {
        if (!operador.pode(Permissao.ESTORNAR)) throw SemPermissaoException(Permissao.ESTORNAR)
        val comanda = comandas.buscar(comandaId) ?: erro("Comanda não encontrada.")
        val nfce = comanda.nfce?.takeIf { it.autorizada } ?: erro("Esta comanda não tem NFC-e autorizada.")
        if (agora() - nfce.emitidaEm > PRAZO_CANCELAMENTO_MS) {
            erro("A NFC-e só pode ser cancelada em até 30 minutos depois da emissão. Procure o contador.")
        }
        val texto = justificativa.trim()
        if (texto.length !in 15..255) erro("A justificativa precisa ter entre 15 e 255 caracteres.")
        val r = emissor.cancelar(nfce.ref, texto)
        if (!r.cancelada) erro("A Sefaz não cancelou a nota: ${r.mensagem ?: "sem detalhes"}")
        return gravar(comandaId) {
            it.copy(
                nfce = nfce.copy(status = StatusNfce.CANCELADA, canceladaEm = agora(), mensagem = r.mensagem),
                tentativasNfce = it.tentativasNfce + 1,
            )
        }
    }

    fun dataEmissao(millis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), fuso)
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private suspend fun gravar(comandaId: Long, mudar: (Comanda) -> Comanda): Comanda {
        repeat(10) {
            val atual = comandas.buscar(comandaId) ?: erro("Comanda não encontrada.")
            val nova = mudar(atual)
            try {
                comandas.salvar(nova)
                return nova.copy(versao = nova.versao + 1)
            } catch (_: ConflitoDeVersaoException) {
                // Outro aparelho gravou: tenta de novo sobre a versão nova
            }
        }
        throw ConflitoDeVersaoException()
    }

    private fun erro(m: String): Nothing = throw RegraDeNegocioException(m)

    companion object {
        const val PRAZO_CANCELAMENTO_MS = 30 * 60 * 1000L
        const val JUSTIFICATIVA_PADRAO = "Cancelamento da venda a pedido do cliente"

        /** Referência da nota no emissor: comanda + tentativa (ex.: "cmd42", "cmd42-1"). */
        fun referencia(comanda: Comanda): String =
            "cmd${comanda.id}" + if (comanda.tentativasNfce > 0) "-${comanda.tentativasNfce}" else ""
    }
}
