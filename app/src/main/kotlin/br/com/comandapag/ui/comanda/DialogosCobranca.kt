package br.com.comandapag.ui.comanda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.servico.ComandaService

@Composable
fun DialogoCobrar(
    totalCentavos: Long,
    simulada: Boolean,
    aoCancelar: () -> Unit,
    aoConfirmar: (FormaPagamento, Int) -> Unit,
) {
    var forma by remember { mutableStateOf(FormaPagamento.CREDITO) }
    var parcelas by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text("Cobrar ${Dinheiro.formatar(totalCentavos)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormaPagamento.entries.forEach { f ->
                        FilterChip(
                            selected = forma == f,
                            onClick = { forma = f; if (f != FormaPagamento.CREDITO) parcelas = 1 },
                            label = { Text(f.descricao) },
                        )
                    }
                }
                if (forma == FormaPagamento.CREDITO) {
                    Text("Parcelas", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    // Grade simples 1x a 12x
                    (1..ComandaService.MAX_PARCELAS).chunked(4).forEach { linha ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            linha.forEach { n ->
                                FilterChip(
                                    selected = parcelas == n,
                                    onClick = { parcelas = n },
                                    label = { Text("${n}x") },
                                )
                            }
                        }
                    }
                    if (parcelas > 1) {
                        Text(
                            "${parcelas}x de ${Dinheiro.formatar(totalCentavos / parcelas)} (parcelado sem juros pelo vendedor)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (simulada) {
                    Text(
                        "Modo simulador: nenhuma cobrança real será feita. Valores terminados em ,99 são recusados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { aoConfirmar(forma, parcelas) }) { Text("Cobrar") } },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Voltar") } },
    )
}

@Composable
fun DialogoEstadoCobranca(
    estado: EstadoCobranca,
    aoCancelarOperacao: () -> Unit,
    aoImprimirExtrato: () -> Unit,
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
            onDismissRequest = aoFechar,
            icon = { Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Pagamento aprovado") },
            text = { Text(estado.mensagem) },
            confirmButton = { TextButton(onClick = aoFechar) { Text("Concluir") } },
            dismissButton = { TextButton(onClick = aoImprimirExtrato) { Text("Imprimir extrato") } },
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
