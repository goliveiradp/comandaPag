package br.com.comandapag.ui.usuarios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.modelo.Perfil
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.seguranca.Pin
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.ui.acesso.CampoPin
import br.com.comandapag.ui.viewModelCom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UsuariosViewModel(private val deps: Dependencias) : ViewModel() {
    val usuarios: StateFlow<List<Usuario>> =
        deps.usuarios.observarTodos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val erro = MutableStateFlow<String?>(null)

    fun salvar(usuario: Usuario, pin: String, aoSalvar: () -> Unit) {
        val operador = deps.sessao.usuario.value ?: return
        viewModelScope.launch {
            try {
                deps.usuarioService.salvar(usuario, pin.ifEmpty { null }, operador)
                erro.value = null
                aoSalvar()
            } catch (e: RegraDeNegocioException) {
                erro.value = e.message
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsuariosScreen(aoVoltar: () -> Unit) {
    val vm = viewModelCom { UsuariosViewModel(it) }
    val usuarios by vm.usuarios.collectAsStateWithLifecycle()
    val erro by vm.erro.collectAsStateWithLifecycle()
    var editando by remember { mutableStateOf<Usuario?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usuários e permissões") },
                navigationIcon = {
                    IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                vm.erro.value = null
                editando = Usuario(nome = "", permissoes = Perfil.GARCOM.permissoes)
            }) { Icon(Icons.Filled.Add, "Novo usuário") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(usuarios, key = { it.id }) { u ->
                ListItem(
                    headlineContent = { Text(u.nome) },
                    supportingContent = {
                        Text(if (u.ativo) u.descricaoPerfil else "${u.descricaoPerfil} · inativo")
                    },
                    modifier = Modifier.clickable {
                        vm.erro.value = null
                        editando = u
                    },
                )
                HorizontalDivider()
            }
        }
    }

    editando?.let { u ->
        DialogoUsuario(
            usuario = u,
            erro = erro,
            aoCancelar = { editando = null },
            aoSalvar = { alterado, pin -> vm.salvar(alterado, pin) { editando = null } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogoUsuario(
    usuario: Usuario,
    erro: String?,
    aoCancelar: () -> Unit,
    aoSalvar: (Usuario, String) -> Unit,
) {
    val novo = usuario.id == 0L
    var nome by remember { mutableStateOf(usuario.nome) }
    var permissoes by remember { mutableStateOf(usuario.permissoes) }
    var ativo by remember { mutableStateOf(usuario.ativo) }
    var pin by remember { mutableStateOf("") }
    val pinOk = if (novo) Pin.valido(pin) else pin.isEmpty() || Pin.valido(pin)

    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text(if (novo) "Novo usuário" else "Editar usuário") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it.take(30) },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CampoPin(
                    pin,
                    { pin = it },
                    if (novo) "PIN (${Pin.TAMANHO_MINIMO} a ${Pin.TAMANHO_MAXIMO} números)" else "Novo PIN (vazio = manter)",
                )

                Text("Perfil", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Perfil.entries.forEach { perfil ->
                        FilterChip(
                            selected = permissoes == perfil.permissoes,
                            onClick = { permissoes = perfil.permissoes },
                            label = { Text(perfil.descricao) },
                        )
                    }
                }

                Text("Permissões", style = MaterialTheme.typography.labelLarge)
                Permissao.entries.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            permissoes = if (p in permissoes) permissoes - p else permissoes + p
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = p in permissoes, onCheckedChange = null)
                        Text(p.descricao, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }

                if (!novo) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ativo (pode entrar no app)", Modifier.weight(1f))
                        Switch(checked = ativo, onCheckedChange = { ativo = it })
                    }
                }
                erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nome.isNotBlank() && pinOk,
                onClick = { aoSalvar(usuario.copy(nome = nome, permissoes = permissoes, ativo = ativo), pin) },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar") } },
    )
}
