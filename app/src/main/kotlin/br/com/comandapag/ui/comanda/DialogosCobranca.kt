package br.com.comandapag.ui.comanda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.servico.ComandaService

/** Texto do campo de valor a partir de centavos: 1250 -> "12,50". */
private fun textoValor(centavos: Long) = Dinheiro.formatar(centavos).removePrefix("R$ ")

/**
 * Receber o pagamento (ou parte dele): escolher a forma, dividir a conta
 * entre pessoas e, no dinheiro, calcular o troco.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogoReceber(
    comanda: Comanda,
    caixaAberto: Boolean,
    pessoas: Int,
    aoMudarPessoas: (Int) -> Unit,
    simulada: Boolean,
    aoCancelar: () -> Unit,
    aoCobrar: (forma: FormaPagamento, valorCentavos: Long, parcelas: Int) -> Unit,
    aoReceberDinheiro: (valorCentavos: Long, recebidoCentavos: Long) -> Unit,
) {
    val restante = comanda.restanteCentavos
    val sugerido = ComandaService.valorPorPessoa(restante, pessoas)
    var forma by remember { mutableStateOf(FormaPagamento.CREDITO) }
    var parcelas by remember { mutableIntStateOf(1) }
    var valorTexto by remember(sugerido) { mutableStateOf(textoValor(sugerido)) }
    var recebidoTexto by remember { mutableStateOf("") }

    val valor = Dinheiro.parse(valorTexto)
    val valorOk = valor != null && valor in 1..restante
    val recebido = Dinheiro.parse(recebidoTexto)
    val dinheiro = forma == FormaPagamento.DINHEIRO
    val podeConfirmar = caixaAberto && valorOk && (!dinheiro || (recebido != null && recebido > 0))

    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text("Receber") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!caixaAberto) {
                    Text(
                        "O caixa está fechado. Abra o caixa (aba Caixa) para receber.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Situação da conta
                if (comanda.totalPagoCentavos > 0) {
                    LinhaValor("Total", comanda.totalCentavos)
                    LinhaValor("Já pago", comanda.totalPagoCentavos)
                }
                Row {
                    Text("Falta", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(Dinheiro.formatar(restante), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                // Dividir a conta
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dividir em", Modifier.weight(1f))
                    IconButton(onClick = { if (pessoas > 1) aoMudarPessoas(pessoas - 1) }, enabled = pessoas > 1) {
                        Icon(Icons.Filled.Remove, "Menos pessoas")
                    }
                    Text("$pessoas", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { if (pessoas < 50) aoMudarPessoas(pessoas + 1) }) {
                        Icon(Icons.Filled.Add, "Mais pessoas")
                    }
                    Text(if (pessoas == 1) "pessoa" else "pessoas")
                }
                if (pessoas > 1) {
                    Text(
                        "Faltam $pessoas pessoas: ${Dinheiro.formatar(sugerido)} cada",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                OutlinedTextField(
                    value = valorTexto,
                    onValueChange = { valorTexto = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(10) },
                    label = { Text("Valor deste pagamento (R$)") },
                    singleLine = true,
                    isError = !valorOk,
                    supportingText = {
                        if (valor != null && valor > restante) Text("Maior do que falta pagar")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Forma de pagamento
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormaPagamento.entries.forEach { f ->
                        FilterChip(
                            selected = forma == f,
                            onClick = { forma = f; if (f != FormaPagamento.CREDITO) parcelas = 1 },
                            label = { Text(f.descricao) },
                        )
                    }
                }

                if (forma == FormaPagamento.CREDITO) {
                    Text("Parcelas", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..ComandaService.MAX_PARCELAS).forEach { n ->
                            FilterChip(selected = parcelas == n, onClick = { parcelas = n }, label = { Text("${n}x") })
                        }
                    }
                    if (parcelas > 1 && valor != null) {
                        Text(
                            "${parcelas}x de ${Dinheiro.formatar(valor / parcelas)} (parcelado sem juros pelo vendedor)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (dinheiro) {
                    OutlinedTextField(
                        value = recebidoTexto,
                        onValueChange = { recebidoTexto = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(10) },
                        label = { Text("Valor recebido (R$)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (valor != null && valor > 0) {
                            FilterChip(selected = false, onClick = { recebidoTexto = textoValor(valor) }, label = { Text("Exato") })
                        }
                        listOf(1000L, 2000L, 5000L, 10000L).forEach { nota ->
                            FilterChip(
                                selected = recebido == nota,
                                onClick = { recebidoTexto = textoValor(nota) },
                                label = { Text(Dinheiro.formatar(nota).removeSuffix(",00")) },
                            )
                        }
                    }
                    if (valor != null && recebido != null && recebido > 0) {
                        if (recebido >= valor) {
                            Row {
                                Text("Troco", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                Text(
                                    Dinheiro.formatar(recebido - valor),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            Text(
                                "Recebido menor que o valor: abate ${Dinheiro.formatar(recebido)} e continua faltando o resto.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (simulada && !dinheiro) {
                    Text(
                        "Modo simulador: nenhuma cobrança real será feita. Valores terminados em ,99 são recusados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = podeConfirmar,
                onClick = {
                    val v = valor ?: return@TextButton
                    if (dinheiro) aoReceberDinheiro(v, recebido ?: return@TextButton) else aoCobrar(forma, v, parcelas)
                },
            ) { Text(if (dinheiro) "Receber" else "Cobrar") }
        },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Voltar") } },
    )
}

@Composable
private fun LinhaValor(rotulo: String, centavos: Long) {
    Row {
        Text(rotulo, Modifier.weight(1f))
        Text(Dinheiro.formatar(centavos))
    }
}

@Composable
fun DialogoEstadoCobranca(
    estado: EstadoCobranca,
    aoCancelarOperacao: () -> Unit,
    aoImprimirExtrato: () -> Unit,
    nfceAtiva: Boolean = false,
    aoEmitirNfce: () -> Unit = {},
    aoProximoPagamento: () -> Unit,
    aoFechar: () -> Unit,
) {
    when (estado) {
        EstadoCobranca.Inativa -> Unit
        is EstadoCobranca.EmAndamento -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(48.dp))
                    Text(
                        estado.status,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = aoCancelarOperacao) { Text("Cancelar") } },
        )
        is EstadoCobranca.Aprovada -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            icon = { Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(estado.titulo) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (estado.mensagem.isNotBlank()) Text(estado.mensagem)
                    if (estado.trocoCentavos > 0) {
                        Text(
                            "Troco: ${Dinheiro.formatar(estado.trocoCentavos)}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Text(
                        if (estado.quitada) "Comanda paga" else "Ainda falta ${Dinheiro.formatar(estado.faltaCentavos)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                when {
                    estado.quitada && nfceAtiva -> TextButton(onClick = aoEmitirNfce) { Text("Emitir NFC-e") }
                    estado.quitada -> TextButton(onClick = aoFechar) { Text("Concluir") }
                    else -> TextButton(onClick = aoProximoPagamento) { Text("Próximo pagamento") }
                }
            },
            dismissButton = {
                when {
                    estado.quitada && nfceAtiva -> TextButton(onClick = aoFechar) { Text("Sem nota") }
                    estado.quitada -> TextButton(onClick = aoImprimirExtrato) { Text("Imprimir extrato") }
                    else -> TextButton(onClick = aoFechar) { Text("Depois") }
                }
            },
        )
        EstadoCobranca.AprovadoSemServidor -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            icon = { Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Pagamento aprovado") },
            text = {
                Text(
                    "O pagamento foi APROVADO na maquininha, mas o servidor não respondeu (Wi-Fi). " +
                        "Ele está guardado neste aparelho e será registrado sozinho quando a conexão voltar.\n\n" +
                        "NÃO cobre de novo.",
                )
            },
            confirmButton = { TextButton(onClick = aoFechar) { Text("Entendi") } },
        )
        is EstadoCobranca.Recusada -> AlertDialog(
            onDismissRequest = aoFechar,
            icon = { Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Pagamento não concluído") },
            text = { Text(estado.mensagem) },
            confirmButton = { TextButton(onClick = aoFechar) { Text("OK") } },
        )
    }
}
