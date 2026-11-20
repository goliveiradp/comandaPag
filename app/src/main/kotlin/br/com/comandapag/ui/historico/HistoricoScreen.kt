package br.com.comandapag.ui.historico

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.ui.MensagemVazia
import br.com.comandapag.ui.viewModelCom
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class HistoricoViewModel(deps: Dependencias) : ViewModel() {
    val fechadas: StateFlow<List<Comanda>> =
        deps.comandas.observarFechadas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun HistoricoScreen(modifier: Modifier = Modifier, aoAbrirComanda: (Long) -> Unit) {
    val vm = viewModelCom { HistoricoViewModel(it) }
    val fechadas by vm.fechadas.collectAsStateWithLifecycle()

    if (fechadas.isEmpty()) {
        MensagemVazia("Nenhuma comanda fechada", "As comandas pagas e canceladas aparecem aqui.", modifier)
        return
    }

    val inicioDoDia = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val pagasHoje = fechadas.filter { it.status == StatusComanda.PAGA && (it.fechadaEm ?: 0) >= inicioDoDia }

    LazyColumn(modifier.fillMaxSize()) {
        item { ResumoDoDia(pagasHoje) }
        items(fechadas, key = { it.id }) { c ->
            val cancelada = c.status == StatusComanda.CANCELADA
            ListItem(
                headlineContent = { Text(c.identificacao) },
                supportingContent = {
                    Column {
                        Text(
                            (c.fechadaEm?.let(ExtratoComanda::dataPadrao) ?: "") + " · " +
                                if (cancelada) "Cancelada" else c.pagamentos.filter { !it.estornado }.map { it.forma.descricao }.distinct().joinToString(" + "),
                        )
                        if (c.garcomNome.isNotBlank()) Text("Garçom: ${c.garcomNome}")
                    }
                },
                trailingContent = {
                    Text(
                        Dinheiro.formatar(c.totalCentavos),
                        textDecoration = if (cancelada) TextDecoration.LineThrough else null,
                        color = if (cancelada) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                },
                modifier = Modifier.clickable { aoAbrirComanda(c.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ResumoDoDia(pagas: List<Comanda>) {
    Card(Modifier.padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Vendas de hoje", style = MaterialTheme.typography.labelLarge)
            Text(
                Dinheiro.formatar(pagas.sumOf { it.totalPagoCentavos }),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("${pagas.size} comanda(s) paga(s)", style = MaterialTheme.typography.bodySmall)
            FormaPagamento.entries.forEach { f ->
                val total = pagas.flatMap { it.pagamentos }.filter { it.forma == f && !it.estornado }.sumOf { it.valorCentavos }
                if (total > 0) Text("${f.descricao}: ${Dinheiro.formatar(total)}", style = MaterialTheme.typography.bodyMedium)
            }
            val porGarcom = pagas.groupBy { it.garcomNome.ifBlank { "Sem garçom" } }
                .mapValues { (_, l) -> l.sumOf { it.totalPagoCentavos } to l.size }
                .toList().sortedByDescending { it.second.first }
            if (porGarcom.isNotEmpty()) {
                Text("Por garçom", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                porGarcom.forEach { (nome, valores) ->
                    Text(
                        "$nome: ${Dinheiro.formatar(valores.first)} (${valores.second})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
