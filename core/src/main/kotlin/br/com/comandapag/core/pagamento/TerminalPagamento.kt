package br.com.comandapag.core.pagamento

import br.com.comandapag.core.modelo.FormaPagamento

data class SolicitacaoPagamento(
    val forma: FormaPagamento,
    val valorCentavos: Long,
    val parcelas: Int = 1,
    /** Referência da venda: até 10 caracteres, apenas letras sem acento e números. */
    val referencia: String,
)

data class SolicitacaoEstorno(
    val forma: FormaPagamento,
    val valorCentavos: Long,
    val codigoTransacao: String,
    val idTransacao: String,
)

sealed interface ResultadoPagamento {
    data class Aprovado(
        val codigoTransacao: String?,
        val idTransacao: String?,
        val bandeira: String?,
        val mensagem: String?,
        /** Aprovado na maquininha, mas ainda não gravado no servidor (será reenviado). */
        val registroPendente: Boolean = false,
    ) : ResultadoPagamento

    data class Recusado(val mensagem: String, val codigoErro: String? = null) : ResultadoPagamento
}

sealed interface ResultadoOperacao {
    data object Sucesso : ResultadoOperacao
    data class Falha(val mensagem: String) : ResultadoOperacao
}

/**
 * Abstração da maquininha. A implementação real usa o PlugPag da PagBank
 * (flavor "smart"); o flavor "simulador" usa uma versão falsa para rodar
 * no emulador ou no celular.
 */
interface TerminalPagamento {
    suspend fun estaAtivado(): Boolean
    suspend fun ativar(codigoAtivacao: String): ResultadoOperacao

    /**
     * Executa a cobrança. Bloqueia até o fim da transação.
     * [aoMudarStatus] recebe as mensagens da maquininha ("APROXIME, INSIRA OU PASSE O CARTÃO", ...).
     */
    suspend fun cobrar(
        solicitacao: SolicitacaoPagamento,
        aoMudarStatus: (String) -> Unit = {},
    ): ResultadoPagamento

    /** Estorna (cancela) uma transação aprovada anteriormente. */
    suspend fun estornar(
        solicitacao: SolicitacaoEstorno,
        aoMudarStatus: (String) -> Unit = {},
    ): ResultadoPagamento

    /** Pede para a maquininha cancelar a operação em andamento. */
    suspend fun cancelarOperacao()
}

interface Impressora {
    /**
     * Imprime as [linhas]. [colunas] define o tamanho da letra: a linha inteira ocupa a
     * largura da bobina, então menos colunas = letra maior (ex.: 24 para a cozinha).
     */
    suspend fun imprimir(linhas: List<String>, colunas: Int = COLUNAS_PADRAO): ResultadoOperacao

    /**
     * Imprime [antes], um QR Code com [qrCode] e depois [depois] (usado no DANFE NFC-e).
     * Impressoras que não desenham QR Code imprimem o endereço por extenso.
     */
    suspend fun imprimirComQrCode(
        antes: List<String>,
        qrCode: String,
        depois: List<String>,
        colunas: Int = COLUNAS_PADRAO,
    ): ResultadoOperacao = imprimir(antes + qrCode.chunked(colunas) + depois, colunas)

    companion object {
        const val COLUNAS_PADRAO = 32
    }
}
