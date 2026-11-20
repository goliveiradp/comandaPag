package br.com.comandapag.ui.comanda

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.ui.MensagemVazia

/** Tela cheia para escolher produtos. Toque adiciona 1 unidade; o lápis permite quantidade e observação. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeletorProdutos(
    produtos: List<Produto>,
    /** Quantidade de cada produto (por id) já lançada na comanda. */
    quantidadesNaComanda: Map<Long, Int>,
    aoFechar: () -> Unit,
    aoAdicionar: (Produto, Int, String) -> Unit,
) {
    var busca by remember { mutableStateOf("") }
    var detalhando by remember { mutableStateOf<Produto?>(null) }
    val filtrados = remember(produtos, busca) {
        produtos.filter { busca.isBlank() || it.nome.contains(busca.trim(), ignoreCase = true) }
    }

    BackHandler(onBack = aoFechar)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adicionar itens") },
                navigationIcon = { IconButton(onClick = aoFechar) { Icon(Icons.Filled.Close, "Fechar") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = busca,
                onValueChange = { busca = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Buscar produto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (produtos.isEmpty()) {
                MensagemVazia("Nenhum produto cadastrado", "Cadastre os produtos na aba \"Produtos\".")
            } else {
                LazyColumn {
                    var categoriaAnterior: String? = null
                    filtrados.forEach { produto ->
                        if (produto.categoria != categoriaAnterior && produto.categoria.isNotBlank()) {
                            item(key = "cat-${produto.categoria}") {
                                Text(
                                    produto.categoria.uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                        }
                        categoriaAnterior = produto.categoria
                        item(key = produto.id) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { aoAdicionar(produto, 1, "") }
                                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(produto.nome, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        Dinheiro.formatar(produto.precoCentavos),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                quantidadesNaComanda[produto.id]?.let { qtd ->
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text("$qtd", modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                }
                                IconButton(onClick = { detalhando = produto }) {
                                    Icon(Icons.Filled.Edit, "Quantidade e observação")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    detalhando?.let { produto ->
        DialogoQuantidade(
            produto = produto,
            aoCancelar = { detalhando = null },
            aoConfirmar = { qtd, obs ->
                detalhando = null
                aoAdicionar(produto, qtd, obs)
            },
        )
    }
}

@Composable
private fun DialogoQuantidade(produto: Produto, aoCancelar: () -> Unit, aoConfirmar: (Int, String) -> Unit) {
    var quantidade by remember { mutableIntStateOf(1) }
    var observacao by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text(produto.nome) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (quantidade > 1) quantidade-- }) { Icon(Icons.Filled.Remove, "Diminuir") }
                    Text("$quantidade", style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { quantidade++ }) { Icon(Icons.Filled.Add, "Aumentar") }
                    Text(
                        Dinheiro.formatar(produto.precoCentavos * quantidade),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                OutlinedTextField(
                    value = observacao,
                    onValueChange = { observacao = it.take(60) },
                    label = { Text("Observação (opcional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { aoConfirmar(quantidade, observacao) }) { Text("Adicionar") } },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar") } },
    )
}
