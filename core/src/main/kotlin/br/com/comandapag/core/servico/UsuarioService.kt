package br.com.comandapag.core.servico

import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.repositorio.UsuarioRepository
import br.com.comandapag.core.seguranca.Pin

class UsuarioService(
    private val usuarios: UsuarioRepository,
    private val agora: () -> Long = System::currentTimeMillis,
) {
    private class Tentativas(var falhas: Int = 0, var bloqueadoAte: Long = 0)
    private val tentativas = mutableMapOf<Long, Tentativas>()

    suspend fun existeUsuario(): Boolean = usuarios.listarTodos().isNotEmpty()

    /** Primeiro acesso: cria o administrador. Só funciona se ainda não houver nenhum usuário. */
    suspend fun criarPrimeiroAdministrador(nome: String, pin: String): Usuario {
        if (existeUsuario()) erro("O administrador já foi criado.")
        return gravar(Usuario(nome = nome, permissoes = Permissao.entries.toSet()), pin, existentes = emptyList())
    }

    /**
     * Cria ou altera um usuário. [novoPin] é obrigatório para usuários novos;
     * em edições, null ou vazio mantém o PIN atual.
     */
    suspend fun salvar(usuario: Usuario, novoPin: String?, operador: Usuario): Usuario {
        if (!operador.pode(Permissao.GERENCIAR_USUARIOS)) throw SemPermissaoException(Permissao.GERENCIAR_USUARIOS)
        val existentes = usuarios.listarTodos()

        // Nunca deixar o sistema sem ninguém que consiga gerenciar usuários.
        val adminsDepois = existentes.filter { it.id != usuario.id } + usuario
        if (adminsDepois.none { it.ativo && it.pode(Permissao.GERENCIAR_USUARIOS) }) {
            erro("É preciso manter pelo menos um usuário ativo com permissão para gerenciar usuários.")
        }
        return gravar(usuario, novoPin, existentes)
    }

    /** Qualquer usuário pode trocar o próprio PIN, desde que informe o atual. */
    suspend fun alterarProprioPin(usuarioId: Long, pinAtual: String, novoPin: String): Usuario {
        val usuario = autenticar(usuarioId, pinAtual)
        return gravar(usuario, novoPin.ifEmpty { erro("Informe o novo PIN.") }, usuarios.listarTodos())
    }

    /** Confere o PIN. Após [MAX_FALHAS] erros seguidos, o usuário fica bloqueado por [BLOQUEIO_MS]. */
    suspend fun autenticar(usuarioId: Long, pin: String): Usuario {
        val usuario = usuarios.buscar(usuarioId)?.takeIf { it.ativo } ?: erro("Usuário não encontrado.")
        val t = tentativas.getOrPut(usuarioId) { Tentativas() }
        val restante = t.bloqueadoAte - agora()
        if (restante > 0) erro("Muitas tentativas erradas. Aguarde ${(restante + 999) / 1000} s.")

        if (!Pin.confere(pin, usuario.pinSalt, usuario.pinHash)) {
            t.falhas++
            if (t.falhas >= MAX_FALHAS) {
                t.falhas = 0
                t.bloqueadoAte = agora() + BLOQUEIO_MS
                erro("PIN incorreto. Usuário bloqueado por ${BLOQUEIO_MS / 1000} s.")
            }
            erro("PIN incorreto.")
        }
        tentativas.remove(usuarioId)
        return usuario
    }

    /** Autenticação de quem vai liberar uma ação (ex.: gerente autorizando um garçom). */
    suspend fun autorizar(usuarioId: Long, pin: String, permissao: Permissao): Usuario {
        val usuario = autenticar(usuarioId, pin)
        if (!usuario.pode(permissao)) throw SemPermissaoException(permissao)
        return usuario
    }

    private suspend fun gravar(usuario: Usuario, novoPin: String?, existentes: List<Usuario>): Usuario {
        val nome = usuario.nome.trim()
        if (nome.isEmpty()) erro("Informe o nome.")
        if (existentes.any { it.id != usuario.id && it.nome.equals(nome, ignoreCase = true) }) {
            erro("Já existe um usuário chamado \"$nome\".")
        }
        var final = usuario.copy(nome = nome)
        if (!novoPin.isNullOrEmpty()) {
            if (!Pin.valido(novoPin)) erro("O PIN deve ter de ${Pin.TAMANHO_MINIMO} a ${Pin.TAMANHO_MAXIMO} números.")
            val salt = Pin.gerarSalt()
            final = final.copy(pinSalt = salt, pinHash = Pin.hash(novoPin, salt))
        } else if (final.pinHash.isEmpty()) {
            erro("Defina um PIN para o usuário.")
        }
        return final.copy(id = usuarios.salvar(final))
    }

    private fun erro(mensagem: String): Nothing = throw RegraDeNegocioException(mensagem)

    companion object {
        const val MAX_FALHAS = 5
        const val BLOQUEIO_MS = 30_000L
    }
}
