package br.com.comandapag.ui.comanda

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.SetorProducao
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
    val pessoas by vm.pessoasRestantes.collectAsStateWithLifecycle()
    val caixaAberto by vm.caixaAberto.collectAsStateWithLifecycle()
    val envioAutomatico by vm.envioAutomatico.collectAsStateWithLifecycle()
    var estornando by remember { mutableStateOf<Int?>(null) }
    val usuario by dependencias().sessao.usuario.collectAsStateWithLifecycle()
    val autorizador = rememberAutorizador()
    // Quem vai cobrar (o próprio usuário ou quem autorizou)
    var cobrador by remember { mutableStateOf<Usuario?>(null) }

    var mostrarProdutos by remember { mutableStateOf(false) }
    var confirmarCancelamento by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Mostra cada aviso uma vez. A chave fixa evita que limpar o aviso cancele a exibição.
    LaunchedEffect(Unit) {
        vm.aviso.filterNotNull().collect {
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
            aoFechar = {
                mostrarProdutos = false
                val u = usuario
                if (envioAutomatico && u != null) vm.enviarPedido(u, seHouver = true)
            },
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
                        aoEstornar = { estornando = it },
                        aoEnviarPedido = { usuario?.let { vm.enviarPedido(it) } },
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
        DialogoReceber(
            comanda = c,
            caixaAberto = caixaAberto,
            pessoas = pessoas,
            aoMudarPessoas = { vm.pessoasRestantes.value = it },
            simulada = vm.maquininhaSimulada,
            aoCancelar = { cobrador = null },
            aoCobrar = { forma, valor, parcelas ->
                cobrador = null
                vm.cobrar(forma, valor, parcelas, quemCobra)
            },
            aoReceberDinheiro = { valor, recebido ->
                cobrador = null
                vm.receberDinheiro(valor, recebido, quemCobra)
            },
        )
    }

    DialogoEstadoCobranca(
        estado = cobranca,
        aoCancelarOperacao = vm::cancelarCobranca,
        aoImprimirExtrato = vm::imprimirExtrato,
        aoProximoPagamento = {
            vm.fecharResultadoCobranca()
            autorizador.exigir(Permissao.COBRAR) { cobrador = it }
        },
        aoFechar = {
            val quitada = (cobranca as? EstadoCobranca.Aprovada)?.quitada == true
            vm.fecharResultadoCobranca()
            if (quitada) aoVoltar()
        },
    )

    val indiceEstorno = estornando
    val pagamentoEstorno = indiceEstorno?.let { c?.pagamentos?.getOrNull(it) }
    if (indiceEstorno != null && pagamentoEstorno != null) {
        AlertDialog(
            onDismissRequest = { estornando = null },
            title = { Text("Estornar pagamento?") },
            text = {
                Text(
                    "${pagamentoEstorno.forma.descricao} de ${Dinheiro.formatar(pagamentoEstorno.valorCentavos)}. " +
                        if (pagamentoEstorno.forma.naMaquininha) {
                            "O estorno será feito na maquininha."
                        } else {
                            "Devolva o dinheiro ao cliente; o valor sai do caixa."
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    estornando = null
                    autorizador.exigir(Permissao.ESTORNAR) { vm.estornar(indiceEstorno, it) }
                }) { Text("Estornar") }
            },
            dismissButton = { TextButton(onClick = { estornando = null }) { Text("Voltar") } },
        )
    }

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
            if (editavel && item.setor != SetorProducao.NENHUM) {
                val pendente = item.quantidadePendente
                Text(
                    when {
                        pendente == 0 -> "Enviado (${item.setor.descricao.lowercase()})"
                        item.quantidadeEnviada == 0 -> "Novo · não enviado"
                        else -> "$pendente novo(s) · ${item.quantidadeEnviada} enviado(s)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pendente > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                )
            }
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
    aoEstornar: (Int) -> Unit,
    aoEnviarPedido: () -> Unit,
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
            val podeEstornar = comanda.status != StatusComanda.CANCELADA
            comanda.pagamentos.forEachIndexed { i, p ->
                val parcelas = if (p.parcelas > 1) " ${p.parcelas}x" else ""
                val troco = if (p.trocoCentavos > 0) " (troco ${Dinheiro.formatar(p.trocoCentavos)})" else ""
                val situacao = if (p.estornado) " · ESTORNADO" else ""
                val estilo = MaterialTheme.typography.bodySmall.copy(
                    textDecoration = if (p.estornado) TextDecoration.LineThrough else null,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (podeEstornar && !p.estornado) Modifier.clickable { aoEstornar(i) } else Modifier)
                        .padding(top = 2.dp),
                ) {
                    Text(
                        "${p.forma.descricao}$parcelas · ${ExtratoComanda.dataPadrao(p.pagoEm)}$troco$situacao",
                        Modifier.weight(1f),
                        style = estilo,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(Dinheiro.formatar(p.valorCentavos), style = estilo, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (podeEstornar && comanda.pagamentos.any { !it.estornado }) {
                Text(
                    "Toque num pagamento para estornar",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (comanda.estaAberta && comanda.totalPagoCentavos > 0) {
                Row {
                    Text("Falta", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        Dinheiro.formatar(comanda.restanteCentavos),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (comanda.estaAberta && comanda.quantidadePendente > 0) {
                FilledTonalButton(onClick = aoEnviarPedido, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Icon(Icons.Filled.Restaurant, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enviar pedido (${comanda.quantidadePendente})")
                }
            }
            if (comanda.estaAberta) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = aoAdicionar, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Adicionar")
                    }
                    Button(
                        onClick = aoCobrar,
                        enabled = comanda.restanteCentavos > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (comanda.totalPagoCentavos > 0) "Receber resto" else "Receber") }
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
