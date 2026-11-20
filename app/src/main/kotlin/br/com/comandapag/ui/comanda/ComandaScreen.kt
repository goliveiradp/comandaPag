package br.com.comandapag.ui.comanda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.ui.MensagemVazia
import br.com.comandapag.ui.acesso.rememberAutorizador
import br.com.comandapag.ui.dependencias
import br.com.comandapag.ui.viewModelCom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComandaScreen(comandaId: Long, aoVoltar: () -> Unit) {
    val vm = viewModelCom(chave = "comanda-$comandaId") { ComandaViewModel(comandaId, it) }
    val comanda by vm.comanda.collectAsStateWithLifecycle()
    val produtos by vm.produtos.collectAsStateWithLifecycle()
    val cobranca by vm.cobranca.collectAsStateWithLifecycle()
    val aviso by vm.aviso.collectAsStateWithLifecycle()
    val usuario by dependencias().sessao.usuario.collectAsStateWithLifecycle()
    val autorizador = rememberAutorizador()
    // Quem vai cobrar (o próprio usuário ou quem autorizou)
    var cobrador by remember { mutableStateOf<Usuario?>(null) }

    var mostrarProdutos by remember { mutableStateOf(false) }
    var confirmarCancelamento by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(aviso) {
        aviso?.let {
            vm.aviso.value = null
            snackbar.showSnackbar(it)
        }
    }

    val c = comanda
    val aberta = c?.estaAberta == true

    if (mostrarProdutos) {
        SeletorProdutos(
            produtos = produtos,
            quantidadesNaComanda = c?.itens.orEmpty().groupBy { it.produtoId }.mapValues { (_, l) -> l.sumOf { it.quantidade } },
            aoFechar = { mostrarProdutos = false },
            aoAdicionar = { produto, qtd, obs -> vm.adicionar(produto, qtd, obs) },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(c?.identificacao ?: "", maxLines = 1)
                            if (c != null) {
                                val situacao = when (c.status) {
                                    StatusComanda.ABERTA -> null
                                    StatusComanda.PAGA -> "Paga"
                                    StatusComanda.CANCELADA -> "Cancelada"
                                }
                                val linha = listOfNotNull(
                                    c.garcomNome.takeIf { it.isNotBlank() }?.let { "Garçom: $it" },
                                    situacao?.let { st -> st + (c.fechadaPor?.let { " por $it" } ?: "") },
                                ).joinToString(" · ")
                                if (linha.isNotEmpty()) {
                                    Text(linha, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                    },
                    actions = {
                        IconButton(onClick = vm::imprimirExtrato, enabled = c != null && c.itens.isNotEmpty()) {
                            Icon(Icons.Filled.Print, "Imprimir extrato")
                        }
                        if (aberta) {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mais opções") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Cancelar comanda") },
                                    onClick = { menu = false; confirmarCancelamento = true },
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (c != null) {
                    Resumo(
                        comanda = c,
                        aoAlterarTaxa = { ativa ->
                            autorizador.exigir(Permissao.ALTERAR_TAXA_SERVICO) { vm.definirTaxaServico(ativa, it) }
                        },
                        aoAdicionar = { mostrarProdutos = true },
                        aoCobrar = { autorizador.exigir(Permissao.COBRAR) { cobrador = it } },
                    )
                }
            },
        ) { padding ->
            when {
                c == null -> Spacer(Modifier.padding(padding))
                c.itens.isEmpty() -> MensagemVazia(
                    "Comanda vazia",
                    if (aberta) "Toque em \"Adicionar\" para lançar os itens." else "",
                    Modifier.padding(padding),
                )
                else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    itemsIndexed(c.itens) { indice, item ->
                        LinhaItem(
                            item = item,
                            editavel = aberta,
                            aoAlterar = { nova ->
                                val u = usuario
                                if (nova > item.quantidade && u != null) {
                                    // Aumentar a quantidade é livre
                                    vm.alterarQuantidade(indice, nova, u)
                                } else {
                                    autorizador.exigir(Permissao.REMOVER_ITENS) { vm.alterarQuantidade(indice, nova, it) }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val quemCobra = cobrador
    if (quemCobra != null && c != null) {
        DialogoCobrar(
            totalCentavos = c.totalCentavos,
            simulada = vm.maquininhaSimulada,
            aoCancelar = { cobrador = null },
            aoConfirmar = { forma, parcelas ->
                cobrador = null
                vm.cobrar(forma, parcelas, quemCobra)
            },
        )
    }

    DialogoEstadoCobranca(
        estado = cobranca,
        aoCancelarOperacao = vm::cancelarCobranca,
        aoImprimirExtrato = vm::imprimirExtrato,
        aoFechar = {
            val aprovada = cobranca is EstadoCobranca.Aprovada
            vm.fecharResultadoCobranca()
            if (aprovada) aoVoltar()
        },
    )

    if (confirmarCancelamento) {
        AlertDialog(
            onDismissRequest = { confirmarCancelamento = false },
            title = { Text("Cancelar comanda?") },
            text = { Text("A comanda será fechada sem cobrança. Essa ação não pode ser desfeita.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarCancelamento = false
                    autorizador.exigir(Permissao.CANCELAR_COMANDA) { vm.cancelarComanda(it, aoVoltar) }
                }) { Text("Cancelar comanda") }
            },
            dismissButton = { TextButton(onClick = { confirmarCancelamento = false }) { Text("Voltar") } },
        )
    }
}

@Composable
private fun LinhaItem(item: ItemComanda, editavel: Boolean, aoAlterar: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.nome, style = MaterialTheme.typography.bodyLarge)
            if (item.observacao.isNotBlank()) {
                Text(
                    item.observacao,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${Dinheiro.formatar(item.precoUnitarioCentavos)} · ${Dinheiro.formatar(item.subtotalCentavos)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (editavel) {
            IconButton(onClick = { aoAlterar(item.quantidade - 1) }) { Icon(Icons.Filled.Remove, "Diminuir") }
            Text("${item.quantidade}", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { aoAlterar(item.quantidade + 1) }) { Icon(Icons.Filled.Add, "Aumentar") }
        } else {
            Text("${item.quantidade}x", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Resumo(
    comanda: Comanda,
    aoAlterarTaxa: (Boolean) -> Unit,
    aoAdicionar: () -> Unit,
    aoCobrar: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            LinhaValor("Subtotal", comanda.subtotalCentavos)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Serviço (${Comanda.PERCENTUAL_SERVICO}%)", Modifier.weight(1f))
                if (comanda.estaAberta) {
                    Switch(checked = comanda.taxaServico, onCheckedChange = aoAlterarTaxa)
                    Spacer(Modifier.width(12.dp))
                }
                Text(Dinheiro.formatar(comanda.taxaServicoCentavos))
            }
            Row(Modifier.padding(top = 4.dp)) {
                Text("Total", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    Dinheiro.formatar(comanda.totalCentavos),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            comanda.pagamento?.let { p ->
                val parcelas = if (p.parcelas > 1) " em ${p.parcelas}x" else ""
                Text(
                    "Pago no ${p.forma.descricao}$parcelas · ${ExtratoComanda.dataPadrao(p.pagoEm)}" +
                        (p.bandeira?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (comanda.estaAberta) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = aoAdicionar, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Adicionar")
                    }
                    Button(
                        onClick = aoCobrar,
                        enabled = comanda.totalCentavos > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cobrar") }
                }
            }
        }
    }
}

@Composable
private fun LinhaValor(rotulo: String, centavos: Long) {
    Row {
        Text(rotulo, Modifier.weight(1f))
        Text(Dinheiro.formatar(centavos))
    }
}
