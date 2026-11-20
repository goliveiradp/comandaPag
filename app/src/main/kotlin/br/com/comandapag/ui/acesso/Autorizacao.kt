package br.com.comandapag.ui.acesso

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.ui.dependencias
import kotlinx.coroutines.launch

/**
 * Executa ações que exigem uma permissão. Se o usuário logado tiver a permissão,
 * a ação roda na hora; se não tiver, abre o diálogo para alguém autorizado
 * (ex.: o gerente) digitar o PIN. A ação recebe quem a liberou.
 */
class Autorizador internal constructor(private val usuarioAtual: () -> Usuario?) {
    internal var pendente by mutableStateOf<Pair<Permissao, (Usuario) -> Unit>?>(null)

    fun exigir(permissao: Permissao, acao: (Usuario) -> Unit) {
        val usuario = usuarioAtual() ?: return
        if (usuario.pode(permissao)) acao(usuario) else pendente = permissao to acao
    }
}

@Composable
fun rememberAutorizador(): Autorizador {
    val sessao = dependencias().sessao
    val autorizador = remember { Autorizador { sessao.usuario.value } }
    autorizador.pendente?.let { (permissao, acao) ->
        DialogoAutorizacao(
            permissao = permissao,
            aoCancelar = { autorizador.pendente = null },
            aoAutorizar = { quem ->
                autorizador.pendente = null
                acao(quem)
            },
        )
    }
    return autorizador
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogoAutorizacao(permissao: Permissao, aoCancelar: () -> Unit, aoAutorizar: (Usuario) -> Unit) {
    val deps = dependencias()
    val todos by deps.usuarios.observarTodos().collectAsState(initial = emptyList())
    val autorizados = todos.filter { it.ativo && it.pode(permissao) }
    var escolhido by remember { mutableStateOf<Usuario?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()
    val selecionado = escolhido ?: autorizados.singleOrNull()

    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text("Autorização necessária") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Você não tem permissão para: ${permissao.descricao.lowercase()}. " +
                        "Peça para uma pessoa autorizada digitar o PIN.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (autorizados.isEmpty()) {
                    Text("Nenhum usuário tem essa permissão.", color = MaterialTheme.colorScheme.error)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        autorizados.forEach { u ->
                            FilterChip(
                                selected = selecionado?.id == u.id,
                                onClick = { escolhido = u; erro = null },
                                label = { Text(u.nome) },
                            )
                        }
                    }
                    if (selecionado != null) {
                        TecladoPin(
                            erro = erro,
                            ocupado = ocupado,
                            tamanhoTecla = 40.dp,
                            aoConfirmar = { pin ->
                                escopo.launch {
                                    ocupado = true
                                    try {
                                        aoAutorizar(deps.usuarioService.autorizar(selecionado.id, pin, permissao))
                                    } catch (e: RegraDeNegocioException) {
                                        erro = e.message
                                    } finally {
                                        ocupado = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar") } },
    )
}
