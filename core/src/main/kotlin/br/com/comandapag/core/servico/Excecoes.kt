package br.com.comandapag.core.servico

import br.com.comandapag.core.modelo.Permissao

/** Erro de regra de negócio: a mensagem é mostrada para o usuário. */
open class RegraDeNegocioException(mensagem: String) : Exception(mensagem)

/** O usuário não tem a [permissao]; a interface pode pedir a autorização de outra pessoa. */
class SemPermissaoException(val permissao: Permissao) :
    RegraDeNegocioException("Sem permissão para: ${permissao.descricao.lowercase()}.")

/** Não foi possível falar com o servidor (maquininha principal). */
class SemConexaoException(mensagem: String = "Sem conexão com o servidor. Verifique o Wi-Fi.") :
    RegraDeNegocioException(mensagem)

/**
 * A comanda ou o caixa foi alterado em outro aparelho entre a leitura e a gravação.
 * Os serviços repetem a operação sobre os dados novos; se não conseguir, avisa o usuário.
 */
class ConflitoDeVersaoException :
    RegraDeNegocioException("Esta comanda foi alterada em outro aparelho. Tente de novo.")

/** Repete [bloco] quando outra maquininha gravou no meio (conflito de versão). */
internal suspend fun <T> repetindoEmConflito(tentativas: Int = 10, bloco: suspend () -> T): T {
    repeat(tentativas - 1) {
        try {
            return bloco()
        } catch (_: ConflitoDeVersaoException) {
            // Outro aparelho gravou antes: lê de novo e refaz
        }
    }
    return bloco()
}
