package br.com.comandapag

import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.repositorio.UsuarioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Quem está usando a maquininha agora. O usuário é lido do banco a cada mudança,
 * então se um administrador alterar as permissões ou desativar a pessoa,
 * isso vale na hora (e um usuário desativado é desconectado).
 */
class Sessao(usuarios: UsuarioRepository, escopo: CoroutineScope) {
    private val usuarioId = MutableStateFlow<Long?>(null)

    val usuario: StateFlow<Usuario?> =
        combine(usuarioId, usuarios.observarTodos()) { id, lista -> lista.firstOrNull { it.id == id && it.ativo } }
            .stateIn(escopo, SharingStarted.Eagerly, null)

    fun entrar(usuario: Usuario) {
        usuarioId.value = usuario.id
    }

    fun sair() {
        usuarioId.value = null
    }
}
