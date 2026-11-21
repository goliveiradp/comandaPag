package br.com.comandapag.ui.produtos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.modelo.DadosFiscais
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.SetorProducao
import br.com.comandapag.ui.MensagemVazia
import br.com.comandapag.ui.viewModelCom
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProdutosViewModel(private val deps: Dependencias) : ViewModel() {
    val produtos: StateFlow<List<Produto>> =
        deps.produtos.observarAtivos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun salvar(produto: Produto) {
        viewModelScope.launch { deps.produtos.salvar(produto) }
    }

    fun excluir(produto: Produto) {
        viewModelScope.launch { deps.produtos.desativar(produto.id) }
    }
}

@Composable
fun ProdutosScreen(modifier: Modifier = Modifier) {
    val vm = viewModelCom { ProdutosViewModel(it) }
    val produtos by vm.produtos.collectAsStateWithLifecycle()
    // null = fechado; Produto com id 0 = novo
    var editando by remember { mutableStateOf<Produto?>(null) }

    Box(modifier.fillMaxSize()) {
        if (produtos.isEmpty()) {
            MensagemVazia("Nenhum produto", "Cadastre o cardápio para lançar itens nas comandas.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(produtos, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.nome) },
                        supportingContent = {
                            Text(listOf(p.categoria, p.setor.descricao).filter { it.isNotBlank() }.joinToString(" · "))
                        },
                        trailingContent = {
                            Text(Dinheiro.formatar(p.precoCentavos), style = MaterialTheme.typography.titleSmall)
                        },
                        modifier = Modifier.clickable { editando = p },
                    )
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { editando = Produto(nome = "", precoCentavos = 0) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Novo produto") }
    }

    editando?.let { p ->
        DialogoProduto(
            produto = p,
            categorias = produtos.map { it.categoria }.filter { it.isNotBlank() }.distinct(),
            aoCancelar = { editando = null },
            aoSalvar = { vm.salvar(it); editando = null },
            aoExcluir = if (p.id != 0L) ({ vm.excluir(p); editando = null }) else null,
        )
    }
}

@Composable
private fun DialogoProduto(
    produto: Produto,
    categorias: List<String>,
    aoCancelar: () -> Unit,
    aoSalvar: (Produto) -> Unit,
    aoExcluir: (() -> Unit)?,
) {
    var nome by remember { mutableStateOf(produto.nome) }
    var preco by remember {
        mutableStateOf(if (produto.id == 0L) "" else Dinheiro.formatar(produto.precoCentavos).removePrefix("R$ "))
    }
    var categoria by remember { mutableStateOf(produto.categoria) }
    var setor by remember { mutableStateOf(produto.setor) }
    var fiscal by remember { mutableStateOf(produto.fiscal) }
    val precoCentavos = Dinheiro.parse(preco)
    val valido = nome.isNotBlank() && precoCentavos != null && precoCentavos > 0

    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text(if (produto.id == 0L) "Novo produto" else "Editar produto") },
        text = {
            Column {
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it.take(40) },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = preco,
                    onValueChange = { preco = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(10) },
                    label = { Text("Preço (R$)") },
                    placeholder = { Text("0,00") },
                    singleLine = true,
                    isError = preco.isNotEmpty() && precoCentavos == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = categoria,
                    onValueChange = { categoria = it.take(30) },
                    label = { Text("Categoria (opcional)") },
                    supportingText = {
                        if (categorias.isNotEmpty()) Text("Existentes: " + categorias.joinToString(", "))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CamposFiscais(fiscal) { fiscal = it }
                Text("Preparo (ticket do pedido)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SetorProducao.entries.forEach { s ->
                        FilterChip(selected = setor == s, onClick = { setor = s }, label = { Text(s.descricao) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valido,
                onClick = {
                    aoSalvar(
                        produto.copy(
                            nome = nome.trim(),
                            precoCentavos = precoCentavos!!,
                            categoria = categoria.trim(),
                            setor = setor,
                            fiscal = fiscal,
                        ),
                    )
                },
            ) { Text("Salvar") }
        },
        dismissButton = {
            if (aoExcluir != null) {
                TextButton(onClick = aoExcluir) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = aoCancelar) { Text("Cancelar") }
            }
        },
    )
}

/** Dados para a NFC-e (o contador informa NCM, CFOP e CSOSN de cada produto). */
@Composable
private fun CamposFiscais(fiscal: DadosFiscais, aoMudar: (DadosFiscais) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    TextButton(onClick = { aberto = !aberto }, contentPadding = PaddingValues(0.dp)) {
        Text(
            if (aberto) "Ocultar dados da nota fiscal" else "Dados da nota fiscal (NFC-e)" + if (fiscal.completos) "" else " · falta NCM",
        )
    }
    if (!aberto) return
    OutlinedTextField(
        value = fiscal.ncm,
        onValueChange = { aoMudar(fiscal.copy(ncm = it.filter(Char::isDigit).take(8))) },
        label = { Text("NCM (8 dígitos)") },
        isError = fiscal.ncm.isNotEmpty() && fiscal.ncm.length != 8,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = fiscal.cfop,
            onValueChange = { aoMudar(fiscal.copy(cfop = it.filter(Char::isDigit).take(4))) },
            label = { Text("CFOP") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = fiscal.icmsSituacao,
            onValueChange = { aoMudar(fiscal.copy(icmsSituacao = it.filter(Char::isDigit).take(3))) },
            label = { Text("CSOSN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = fiscal.unidade,
            onValueChange = { aoMudar(fiscal.copy(unidade = it.uppercase().take(6))) },
            label = { Text("Unidade") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = fiscal.pisCofinsSituacao,
            onValueChange = { aoMudar(fiscal.copy(pisCofinsSituacao = it.filter(Char::isDigit).take(2))) },
            label = { Text("CST PIS/COFINS") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        "Simples Nacional: refeições costumam usar CFOP 5102 / CSOSN 102; bebidas com substituição " +
            "tributária, CFOP 5405 / CSOSN 500. Confirme com o contador.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
