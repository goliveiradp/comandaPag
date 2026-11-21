package br.com.comandapag.core.fiscal

import kotlinx.serialization.Serializable

enum class AmbienteFiscal(val descricao: String) {
    /** Sem Sefaz: notas de mentira para testar o fluxo. */
    SIMULADO("Simulado (sem valor fiscal)"),
    HOMOLOGACAO("Homologação (testes na Sefaz, sem valor fiscal)"),
    PRODUCAO("Produção"),
}

/** Dados do emitente impressos no DANFE NFC-e. */
@Serializable
data class Emitente(
    val cnpj: String,
    val razaoSocial: String,
    val inscricaoEstadual: String = "",
    val endereco: String = "",
)

@Serializable
data class StatusEmissor(val ativo: Boolean, val ambiente: AmbienteFiscal, val emitente: Emitente? = null)

/** Item como vai na NFC-e. Valores em centavos. */
@Serializable
data class ItemNota(
    val numero: Int,
    val codigo: String,
    val descricao: String,
    val quantidade: Int,
    val valorUnitarioCentavos: Long,
    val valorBrutoCentavos: Long,
    /** Parte da taxa de serviço (10%) rateada neste item. */
    val outrasDespesasCentavos: Long,
    val fiscal: br.com.comandapag.core.modelo.DadosFiscais,
)

/** Forma de pagamento na NFC-e (códigos tPag: 01 dinheiro, 03 crédito, 04 débito, 17 PIX). */
@Serializable
data class PagamentoNota(
    val codigo: String,
    val valorCentavos: Long,
    /** Bandeira do cartão (tBand), quando conhecida. */
    val bandeira: String? = null,
)

@Serializable
data class NotaNfce(
    /** Data e hora com fuso, ex.: 2026-11-21T20:15:00-03:00. */
    val dataEmissao: String,
    val itens: List<ItemNota>,
    val pagamentos: List<PagamentoNota>,
    val trocoCentavos: Long,
    val cpf: String? = null,
    val informacoesAdicionais: String = "",
) {
    val totalCentavos: Long get() = itens.sumOf { it.valorBrutoCentavos + it.outrasDespesasCentavos }
}

@Serializable
data class RespostaNfce(
    val autorizada: Boolean,
    val mensagem: String? = null,
    val numero: String? = null,
    val serie: String? = null,
    val chave: String? = null,
    val qrCodeUrl: String? = null,
    val urlConsulta: String? = null,
    val protocolo: String? = null,
    val contingencia: Boolean = false,
    val ambiente: AmbienteFiscal,
    val emitente: Emitente? = null,
)

@Serializable
data class RespostaCancelamento(val cancelada: Boolean, val mensagem: String? = null)

/**
 * Quem emite a NFC-e: a Focus NFe (direto ou pelo servidor da rede local) ou o
 * emissor simulado. Erros de validação vêm como RegraDeNegocioException.
 */
interface EmissorNfce {
    suspend fun status(): StatusEmissor
    suspend fun emitir(ref: String, nota: NotaNfce): RespostaNfce
    /** Consulta uma nota já enviada (ex.: a resposta da emissão se perdeu). Null se não existir. */
    suspend fun consultar(ref: String): RespostaNfce?
    suspend fun cancelar(ref: String, justificativa: String): RespostaCancelamento
}

/** Nota já existe no emissor com essa referência (a emissão anterior chegou lá). */
class NotaJaEmitidaException : Exception()
