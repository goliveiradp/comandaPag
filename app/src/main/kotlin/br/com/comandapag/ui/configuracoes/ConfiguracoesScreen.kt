package br.com.comandapag.ui.configuracoes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.seguranca.Pin
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.ui.acesso.CampoPin
import br.com.comandapag.ui.fiscal.SecaoNotaFiscal
import br.com.comandapag.ui.rede.SecaoImpressoras
import br.com.comandapag.ui.rede.SecaoRede
import br.com.comandapag.ui.viewModelCom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ConfiguracoesViewModel(private val deps: Dependencias) : ViewModel() {
    val nome = deps.preferencias.nomeEstabelecimento
    val taxaPadrao = deps.preferencias.taxaServicoPadrao
    val simulada = deps.maquininha.simulada
    val ativada = MutableStateFlow<Boolean?>(null)
    val mensagemAtivacao = MutableStateFlow<String?>(null)
    val ativando = MutableStateFlow(false)
    val mensagemPin = MutableStateFlow<String?>(null)

    fun verificarAtivacao() {
        viewModelScope.launch { ativada.value = deps.maquininha.terminal.estaAtivado() }
    }

    fun definirNome(nome: String) = deps.preferencias.definirNomeEstabelecimento(nome)
    fun definirTaxaPadrao(ativa: Boolean) = deps.preferencias.definirTaxaServicoPadrao(ativa)
    val envioAutomatico = deps.preferencias.envioAutomatico
    fun definirEnvioAutomatico(ativo: Boolean) = deps.preferencias.definirEnvioAutomatico(ativo)

    fun ativar(codigo: String) {
        viewModelScope.launch {
            ativando.value = true
            mensagemAtivacao.value = when (val r = deps.maquininha.terminal.ativar(codigo)) {
                ResultadoOperacao.Sucesso -> "Maquininha ativada!"
                is ResultadoOperacao.Falha -> r.mensagem
            }
            ativando.value = false
            verificarAtivacao()
        }
    }

    fun trocarMeuPin(usuario: Usuario, atual: String, novo: String, aoTrocar: () -> Unit) {
        viewModelScope.launch {
            mensagemPin.value = try {
                deps.usuarioService.alterarProprioPin(usuario.id, atual, novo)
                aoTrocar()
                "PIN alterado."
            } catch (e: RegraDeNegocioException) {
                e.message
            }
        }
    }
}

@Composable
fun ConfiguracoesScreen(modifier: Modifier = Modifier, usuario: Usuario, aoGerenciarUsuarios: () -> Unit) {
    val vm = viewModelCom { ConfiguracoesViewModel(it) }
    val podeConfigurar = usuario.pode(Permissao.CONFIGURAR)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (usuario.pode(Permissao.GERENCIAR_USUARIOS)) {
            Text("Equipe", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = aoGerenciarUsuarios, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Group, contentDescription = null)
                Text("Usuários e permissões", modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider()
        }

        MeuPin(vm, usuario)

        if (podeConfigurar) {
            HorizontalDivider()
            Estabelecimento(vm)
            HorizontalDivider()
            SecaoImpressoras()
            HorizontalDivider()
            SecaoNotaFiscal()
            HorizontalDivider()
            SecaoRede()
            HorizontalDivider()
            Maquininha(vm)
        }
    }
}

@Composable
private fun MeuPin(vm: ConfiguracoesViewModel, usuario: Usuario) {
    val mensagem by vm.mensagemPin.collectAsStateWithLifecycle()
    var atual by remember { mutableStateOf("") }
    var novo by remember { mutableStateOf("") }
    var confirmacao by remember { mutableStateOf("") }

    Text("Meu PIN", style = MaterialTheme.typography.titleMedium)
    CampoPin(atual, { atual = it }, "PIN atual")
    CampoPin(novo, { novo = it }, "Novo PIN")
    CampoPin(confirmacao, { confirmacao = it }, "Repita o novo PIN", erro = confirmacao.isNotEmpty() && confirmacao != novo)
    Button(
        onClick = { vm.trocarMeuPin(usuario, atual, novo) { atual = ""; novo = ""; confirmacao = "" } },
        enabled = atual.isNotEmpty() && Pin.valido(novo) && novo == confirmacao,
    ) { Text("Trocar PIN") }
    mensagem?.let { Text(it) }
}

@Composable
private fun Estabelecimento(vm: ConfiguracoesViewModel) {
    val nomeSalvo by vm.nome.collectAsStateWithLifecycle()
    val taxaPadrao by vm.taxaPadrao.collectAsStateWithLifecycle()
    var nome by remember(nomeSalvo) { mutableStateOf(nomeSalvo) }

    Text("Estabelecimento", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = nome,
        onValueChange = { nome = it.take(32) },
        label = { Text("Nome impresso no extrato") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { vm.definirNome(nome) }, enabled = nome != nomeSalvo) { Text("Salvar nome") }
    Row(
        Modifier.fillMaxWidth().toggleable(value = taxaPadrao, role = Role.Switch, onValueChange = vm::definirTaxaPadrao),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Novas comandas com 10% de serviço", Modifier.weight(1f))
        Switch(checked = taxaPadrao, onCheckedChange = null)
    }
    val envio by vm.envioAutomatico.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().toggleable(value = envio, role = Role.Switch, onValueChange = vm::definirEnvioAutomatico),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Imprimir pedido ao fechar a lista de produtos")
            Text(
                "Envia os itens novos para a cozinha/bar sem precisar tocar em \"Enviar pedido\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = envio, onCheckedChange = null)
    }
}

@Composable
private fun Maquininha(vm: ConfiguracoesViewModel) {
    val ativada by vm.ativada.collectAsStateWithLifecycle()
    val mensagem by vm.mensagemAtivacao.collectAsStateWithLifecycle()
    val ativando by vm.ativando.collectAsStateWithLifecycle()
    var codigo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.verificarAtivacao() }

    Text("Maquininha", style = MaterialTheme.typography.titleMedium)
    Text(
        when {
            vm.simulada -> "Modo simulador (sem cobranças reais)"
            ativada == null -> "Verificando..."
            ativada == true -> "PagBank Smart ativada e pronta para cobrar"
            else -> "Maquininha não ativada"
        },
        color = if (ativada == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (ativada == false && !vm.simulada) {
        OutlinedTextField(
            value = codigo,
            onValueChange = { codigo = it.filter(Char::isDigit).take(10) },
            label = { Text("Código de ativação PagBank") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.ativar(codigo) }, enabled = codigo.isNotBlank() && !ativando) {
            Text(if (ativando) "Ativando..." else "Ativar")
        }
    }
    mensagem?.let { Text(it) }
}
