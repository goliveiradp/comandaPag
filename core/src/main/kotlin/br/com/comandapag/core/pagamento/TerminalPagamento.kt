package br.com.comandapag.core.pagamento

import br.com.comandapag.core.modelo.FormaPagamento

data class SolicitacaoPagamento(
    val forma: FormaPagamento,
    val valorCentavos: Long,
    val parcelas: Int = 1,
    /** Referência da venda: até 10 caracteres, apenas letras sem acento e números. */
    val referencia: String,
)

sealed interface ResultadoPagamento {
    data class Aprovado(
        val codigoTransacao: String?,
        val idTransacao: String?,
        val bandeira: String?,
        val mensagem: String?,
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

    /** Pede para a maquininha cancelar a operação em andamento. */
    suspend fun cancelarOperacao()
}

interface Impressora {
    suspend fun imprimir(linhas: List<String>): ResultadoOperacao
}
