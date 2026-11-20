package br.com.comandapag.ui.comandas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.ui.MensagemVazia
import br.com.comandapag.ui.viewModelCom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ComandasViewModel(private val deps: Dependencias) : ViewModel() {
    val abertas: StateFlow<List<Comanda>> =
        deps.comandas.observarAbertas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val taxaServicoPadrao: StateFlow<Boolean> = deps.preferencias.taxaServicoPadrao

    val erro = MutableStateFlow<String?>(null)

    fun abrir(identificacao: String, taxaServico: Boolean, aoAbrir: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                aoAbrir(deps.comandaService.abrir(identificacao, taxaServico).id)
            } catch (e: RegraDeNegocioException) {
                // Se já existe aberta, leva direto para ela
                val existente = deps.comandas.buscarAbertaPorIdentificacao(identificacao.trim())
                if (existente != null) aoAbrir(existente.id) else erro.value = e.message
            }
        }
    }
}

@Composable
fun ComandasScreen(modifier: Modifier = Modifier, aoAbrirComanda: (Long) -> Unit) {
    val vm = viewModelCom { ComandasViewModel(it) }
    val abertas by vm.abertas.collectAsStateWithLifecycle()
    val erro by vm.erro.collectAsStateWithLifecycle()
    val taxaPadrao by vm.taxaServicoPadrao.collectAsStateWithLifecycle()
    var mostrarNova by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        if (abertas.isEmpty()) {
            MensagemVazia("Nenhuma comanda aberta", "Toque em \"Nova comanda\" para começar.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(abertas, key = { it.id }) { comanda ->
                    CartaoComanda(comanda) { aoAbrirComanda(comanda.id) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { mostrarNova = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Nova comanda") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (mostrarNova) {
        DialogoNovaComanda(
            taxaPadrao = taxaPadrao,
            aoCancelar = { mostrarNova = false },
            aoConfirmar = { id, taxa ->
                mostrarNova = false
                vm.abrir(id, taxa, aoAbrirComanda)
            },
        )
    }
    erro?.let {
        AlertDialog(
            onDismissRequest = { vm.erro.value = null },
            confirmButton = { TextButton(onClick = { vm.erro.value = null }) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

@Composable
private fun CartaoComanda(comanda: Comanda, aoClicar: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = aoClicar)) {
        Column(Modifier.padding(16.dp)) {
            Text(comanda.identificacao, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                "${comanda.quantidadeItens} ${if (comanda.quantidadeItens == 1) "item" else "itens"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Dinheiro.formatar(comanda.totalCentavos),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DialogoNovaComanda(
    taxaPadrao: Boolean,
    aoCancelar: () -> Unit,
    aoConfirmar: (String, Boolean) -> Unit,
) {
    var texto by remember { mutableStateOf("") }
    var taxa by remember { mutableStateOf(taxaPadrao) }
    val foco = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text("Nova comanda") },
        text = {
            Column {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it.take(20) },
                    label = { Text("Número, mesa ou cliente") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (texto.isNotBlank()) aoConfirmar(texto, taxa) }),
                    modifier = Modifier.focusRequester(foco),
                )
                // Precisa ficar dentro do conteúdo do diálogo: só aqui o campo já está na tela.
                LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = taxa, onCheckedChange = { taxa = it })
                    Text("Cobrar 10% de serviço")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { aoConfirmar(texto, taxa) }, enabled = texto.isNotBlank()) { Text("Abrir") }
        },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar") } },
    )
}
