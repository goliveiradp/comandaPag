package br.com.comandapag.ui.configuracoes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.pagamento.ResultadoOperacao
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

    fun verificarAtivacao() {
        viewModelScope.launch { ativada.value = deps.maquininha.terminal.estaAtivado() }
    }

    fun definirNome(nome: String) = deps.preferencias.definirNomeEstabelecimento(nome)
    fun definirTaxaPadrao(ativa: Boolean) = deps.preferencias.definirTaxaServicoPadrao(ativa)

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
}

@Composable
fun ConfiguracoesScreen(modifier: Modifier = Modifier) {
    val vm = viewModelCom { ConfiguracoesViewModel(it) }
    val nomeSalvo by vm.nome.collectAsStateWithLifecycle()
    val taxaPadrao by vm.taxaPadrao.collectAsStateWithLifecycle()
    val ativada by vm.ativada.collectAsStateWithLifecycle()
    val mensagem by vm.mensagemAtivacao.collectAsStateWithLifecycle()
    val ativando by vm.ativando.collectAsStateWithLifecycle()
    var nome by remember(nomeSalvo) { mutableStateOf(nomeSalvo) }
    var codigo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.verificarAtivacao() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Estabelecimento", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = nome,
            onValueChange = { nome = it.take(32) },
            label = { Text("Nome impresso no extrato") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.definirNome(nome) }, enabled = nome != nomeSalvo) { Text("Salvar nome") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Novas comandas com 10% de serviço", Modifier.weight(1f))
            Switch(checked = taxaPadrao, onCheckedChange = vm::definirTaxaPadrao)
        }

        HorizontalDivider()
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
}
