package br.com.comandapag.ui.acesso

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.seguranca.Pin
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.ui.rede.DialogoConectarServidor
import br.com.comandapag.ui.viewModelCom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LoginViewModel(private val deps: Dependencias) : ViewModel() {
    /** null enquanto carrega; lista vazia = primeiro acesso. */
    val usuarios: StateFlow<List<Usuario>?> = deps.usuarios.observarTodos()
        .map { l -> l.filter { it.ativo } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val existeAlgumUsuario: StateFlow<Boolean?> = deps.usuarios.observarTodos()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val erro = MutableStateFlow<String?>(null)
    val ocupado = MutableStateFlow(false)

    fun entrar(usuario: Usuario, pin: String) = executar {
        deps.sessao.entrar(deps.usuarioService.autenticar(usuario.id, pin))
    }

    fun criarAdministrador(nome: String, pin: String) = executar {
        deps.sessao.entrar(deps.usuarioService.criarPrimeiroAdministrador(nome, pin))
    }

    private fun executar(acao: suspend () -> Unit) {
        viewModelScope.launch {
            ocupado.value = true
            erro.value = null
            try {
                acao()
            } catch (e: RegraDeNegocioException) {
                erro.value = e.message
            } finally {
                ocupado.value = false
            }
        }
    }
}

@Composable
fun LoginScreen() {
    val vm = viewModelCom { LoginViewModel(it) }
    val usuarios by vm.usuarios.collectAsStateWithLifecycle()
    val existe by vm.existeAlgumUsuario.collectAsStateWithLifecycle()
    val erro by vm.erro.collectAsStateWithLifecycle()
    val ocupado by vm.ocupado.collectAsStateWithLifecycle()
    var selecionado by remember { mutableStateOf<Usuario?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ComandaPag", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            AvisoTerminalSemConexao()
            when {
                existe == null || usuarios == null -> Unit
                existe == false -> PrimeiroAcesso(erro, ocupado, vm::criarAdministrador)
                selecionado == null -> EscolherUsuario(usuarios!!) { vm.erro.value = null; selecionado = it }
                else -> {
                    val u = selecionado!!
                    BackHandler { selecionado = null }
                    Text(u.nome, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
                    Text("Digite seu PIN", style = MaterialTheme.typography.bodyMedium)
                    TecladoPin(
                        aoConfirmar = { vm.entrar(u, it) },
                        erro = erro,
                        ocupado = ocupado,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    TextButton(onClick = { selecionado = null }) { Text("Trocar usuário") }
                }
            }
        }
    }
}

@Composable
private fun EscolherUsuario(usuarios: List<Usuario>, aoEscolher: (Usuario) -> Unit) {
    Text("Quem está usando?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(usuarios, key = { it.id }) { u ->
            Card(Modifier.fillMaxWidth().clickable { aoEscolher(u) }) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(u.nome, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                    Text(u.descricaoPerfil, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PrimeiroAcesso(erro: String?, ocupado: Boolean, aoCriar: (String, String) -> Unit) {
    var nome by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmacao by remember { mutableStateOf("") }
    val pinsIguais = pin == confirmacao
    val valido = nome.isNotBlank() && Pin.valido(pin) && pinsIguais

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Primeiro acesso", style = MaterialTheme.typography.titleLarge)
        Text(
            "Crie o usuário administrador. Ele pode cadastrar garçons, caixas e gerentes e definir o que cada um pode fazer.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = nome,
            onValueChange = { nome = it.take(30) },
            label = { Text("Seu nome") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        CampoPin(pin, { pin = it }, "PIN (${Pin.TAMANHO_MINIMO} a ${Pin.TAMANHO_MAXIMO} números)")
        CampoPin(confirmacao, { confirmacao = it }, "Repita o PIN", erro = confirmacao.isNotEmpty() && !pinsIguais)
        if (confirmacao.isNotEmpty() && !pinsIguais) {
            Text("Os PINs não são iguais.", color = MaterialTheme.colorScheme.error)
        }
        erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { aoCriar(nome, pin) }, enabled = valido && !ocupado, modifier = Modifier.fillMaxWidth()) {
            Text("Criar administrador e entrar")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Já tem outra maquininha com o ComandaPag no estabelecimento?",
            style = MaterialTheme.typography.bodyMedium,
        )
        var conectar by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { conectar = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Conectar a um servidor")
        }
        if (conectar) DialogoConectarServidor(aoFechar = { conectar = false })
    }
}

@Composable
fun CampoPin(valor: String, aoMudar: (String) -> Unit, rotulo: String, erro: Boolean = false, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = valor,
        onValueChange = { novo -> aoMudar(novo.filter(Char::isDigit).take(Pin.TAMANHO_MAXIMO)) },
        label = { Text(rotulo) },
        singleLine = true,
        isError = erro,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.fillMaxWidth(),
    )
}

/** No modo terminal sem conexão, explica o problema e permite desfazer o pareamento. */
@Composable
private fun AvisoTerminalSemConexao() {
    val deps = br.com.comandapag.ui.dependencias()
    if (deps.modo != br.com.comandapag.rede.ModoRede.TERMINAL) return
    val conectado by deps.conectado.collectAsStateWithLifecycle()
    if (conectado) return
    val contexto = androidx.compose.ui.platform.LocalContext.current
    var confirmar by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Sem conexão com o servidor", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Text(
                "Verifique se a maquininha servidor está ligada e no mesmo Wi-Fi (${deps.enderecoServidor}).",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { confirmar = true }) { Text("Desconectar deste servidor") }
        }
    }
    if (confirmar) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmar = false },
            title = { Text("Desconectar do servidor?") },
            text = { Text("Esta maquininha volta a funcionar sozinha. Para voltar à rede, será preciso parear de novo.") },
            confirmButton = {
                TextButton(onClick = {
                    deps.rede.virarIndependente()
                    br.com.comandapag.ui.rede.reiniciarApp(contexto)
                }) { Text("Desconectar") }
            },
            dismissButton = { TextButton(onClick = { confirmar = false }) { Text("Cancelar") } },
        )
    }
}
