package br.com.comandapag.ui.caixa

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import br.com.comandapag.Dependencias
import br.com.comandapag.core.impressao.ExtratoComanda
import br.com.comandapag.core.impressao.RelatorioCaixa
import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ResumoCaixa
import br.com.comandapag.core.modelo.TipoMovimento
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.ui.viewModelCom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CaixaViewModel(private val deps: Dependencias) : ViewModel() {
    private val service get() = deps.caixaService

    /** Resumo do caixa aberto, recalculado a cada pagamento ou movimento. Null = caixa fechado. */
    val resumo: StateFlow<ResumoCaixa?> =
        combine(deps.caixas.observarAberto(), deps.comandas.observarAbertas(), deps.comandas.observarFechadas()) { c, _, _ -> c }
            .mapLatest { caixa -> caixa?.let { service.resumo(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val carregado: StateFlow<Boolean> = deps.caixas.observarAberto()
        .mapLatest { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val fechados: StateFlow<List<Caixa>> =
        deps.caixas.observarFechados().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val aviso = MutableStateFlow<String?>(null)

    /** Relatório aberto na tela (fechamento recém-feito ou um fechamento antigo). */
    val relatorio = MutableStateFlow<ResumoCaixa?>(null)

    fun abrir(fundo: Long, operador: Usuario) = executar { service.abrir(fundo, operador); aviso.value = "Caixa aberto" }

    fun suprimento(valor: Long, motivo: String, operador: Usuario) =
        executar { service.suprimento(valor, motivo, operador); aviso.value = "Suprimento registrado" }

    fun sangria(valor: Long, motivo: String, operador: Usuario) =
        executar { service.sangria(valor, motivo, operador); aviso.value = "Sangria registrada" }

    fun fechar(contado: Long, operador: Usuario) = executar { relatorio.value = service.fechar(contado, operador) }

    fun verFechamento(caixa: Caixa) = executar { relatorio.value = service.resumo(caixa) }

    fun imprimir(resumo: ResumoCaixa) {
        viewModelScope.launch {
            val linhas = RelatorioCaixa.gerar(resumo, deps.preferencias.nomeEstabelecimento.value)
            aviso.value = when (val r = deps.maquininha.impressora.imprimir(linhas)) {
                ResultadoOperacao.Sucesso -> "Relatório impresso"
                is ResultadoOperacao.Falha -> "Erro ao imprimir: ${r.mensagem}"
            }
        }
    }

    private fun executar(acao: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                acao()
            } catch (e: RegraDeNegocioException) {
                aviso.value = e.message
            }
        }
    }
}

private enum class Dialogo { ABRIR, SUPRIMENTO, SANGRIA, FECHAR }

@Composable
fun CaixaScreen(modifier: Modifier = Modifier, usuario: Usuario) {
    val vm = viewModelCom { CaixaViewModel(it) }
    val resumo by vm.resumo.collectAsStateWithLifecycle()
    val carregado by vm.carregado.collectAsStateWithLifecycle()
    val fechados by vm.fechados.collectAsStateWithLifecycle()
    val aviso by vm.aviso.collectAsStateWithLifecycle()
    val relatorio by vm.relatorio.collectAsStateWithLifecycle()
    var dialogo by remember { mutableStateOf<Dialogo?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(aviso) {
        aviso?.let {
            vm.aviso.value = null
            snackbar.showSnackbar(it)
        }
    }

    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val r = resumo
            when {
                !carregado -> Unit
                r == null -> {
                    Text("Caixa fechado", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Abra o caixa com o fundo de troco para começar a receber.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { dialogo = Dialogo.ABRIR }, modifier = Modifier.fillMaxWidth()) { Text("Abrir caixa") }
                }
                else -> CaixaAberto(
                    resumo = r,
                    aoSuprimento = { dialogo = Dialogo.SUPRIMENTO },
                    aoSangria = { dialogo = Dialogo.SANGRIA },
                    aoImprimir = { vm.imprimir(r) },
                    aoFechar = { dialogo = Dialogo.FECHAR },
                )
            }

            if (fechados.isNotEmpty()) {
                HorizontalDivider()
                Text("Fechamentos anteriores", style = MaterialTheme.typography.titleMedium)
                fechados.forEach { c ->
                    ListItem(
                        headlineContent = { Text(ExtratoComanda.dataPadrao(c.fechadoEm ?: c.abertoEm)) },
                        supportingContent = { Text("Fechado por ${c.fechadoPor ?: ""}") },
                        modifier = Modifier.clickable { vm.verFechamento(c) },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }

    when (dialogo) {
        Dialogo.ABRIR -> DialogoValor(
            titulo = "Abrir caixa",
            rotulo = "Fundo de troco (R$)",
            permiteZero = true,
            aoCancelar = { dialogo = null },
            aoConfirmar = { valor, _ -> dialogo = null; vm.abrir(valor, usuario) },
        )
        Dialogo.SUPRIMENTO -> DialogoValor(
            titulo = "Suprimento",
            rotulo = "Valor colocado no caixa (R$)",
            comMotivo = true,
            aoCancelar = { dialogo = null },
            aoConfirmar = { valor, motivo -> dialogo = null; vm.suprimento(valor, motivo, usuario) },
        )
        Dialogo.SANGRIA -> DialogoValor(
            titulo = "Sangria",
            rotulo = "Valor retirado do caixa (R$)",
            comMotivo = true,
            aoCancelar = { dialogo = null },
            aoConfirmar = { valor, motivo -> dialogo = null; vm.sangria(valor, motivo, usuario) },
        )
        Dialogo.FECHAR -> resumo?.let { r ->
            DialogoFechar(
                esperado = r.dinheiroEsperadoCentavos,
                aoCancelar = { dialogo = null },
                aoConfirmar = { contado -> dialogo = null; vm.fechar(contado, usuario) },
            )
        }
        null -> Unit
    }

    relatorio?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.relatorio.value = null },
            title = { Text(if (r.caixa.aberto) "Caixa (parcial)" else "Fechamento de caixa") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RelatorioCaixa.gerar(r, "").forEach {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.relatorio.value = null }) { Text("Fechar") } },
            dismissButton = { TextButton(onClick = { vm.imprimir(r) }) { Text("Imprimir") } },
        )
    }
}

@Composable
private fun CaixaAberto(
    resumo: ResumoCaixa,
    aoSuprimento: () -> Unit,
    aoSangria: () -> Unit,
    aoImprimir: () -> Unit,
    aoFechar: () -> Unit,
) {
    val c = resumo.caixa
    Text("Caixa aberto", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Desde ${ExtratoComanda.dataPadrao(c.abertoEm)} por ${c.abertoPor}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Dinheiro esperado na gaveta", style = MaterialTheme.typography.labelLarge)
            Text(
                Dinheiro.formatar(resumo.dinheiroEsperadoCentavos),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Linha("Fundo de troco", c.valorInicialCentavos)
            Linha("Vendas em dinheiro", resumo.vendasPorForma[FormaPagamento.DINHEIRO] ?: 0)
            if (resumo.suprimentosCentavos > 0) Linha("Suprimentos", resumo.suprimentosCentavos)
            if (resumo.sangriasCentavos > 0) Linha("Sangrias", -resumo.sangriasCentavos)
            resumo.estornosPorForma[FormaPagamento.DINHEIRO]?.let { Linha("Estornos em dinheiro", -it) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Vendas neste caixa (${resumo.quantidadePagamentos})", style = MaterialTheme.typography.labelLarge)
            FormaPagamento.entries.forEach { f -> resumo.vendasPorForma[f]?.let { Linha(f.descricao, it) } }
            Linha("Total", resumo.totalVendasCentavos, negrito = true)
            if (resumo.totalEstornosCentavos > 0) Linha("Estornos", -resumo.totalEstornosCentavos)
        }
    }
    val movimentos = c.movimentos.filter { it.tipo != TipoMovimento.ESTORNO }
    if (movimentos.isNotEmpty()) {
        Text("Movimentos", style = MaterialTheme.typography.titleSmall)
        movimentos.forEach { m ->
            val sinal = if (m.tipo == TipoMovimento.SANGRIA) -1 else 1
            Linha("${m.tipo.descricao}${if (m.motivo.isNotBlank()) " · ${m.motivo}" else ""}", sinal * m.valorCentavos)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = aoSuprimento, modifier = Modifier.weight(1f)) { Text("Suprimento") }
        OutlinedButton(onClick = aoSangria, modifier = Modifier.weight(1f)) { Text("Sangria") }
    }
    OutlinedButton(onClick = aoImprimir, modifier = Modifier.fillMaxWidth()) { Text("Imprimir parcial") }
    Button(onClick = aoFechar, modifier = Modifier.fillMaxWidth()) { Text("Fechar caixa") }
}

@Composable
private fun Linha(rotulo: String, centavos: Long, negrito: Boolean = false) {
    Row {
        Text(rotulo, Modifier.weight(1f), fontWeight = if (negrito) FontWeight.Bold else null)
        Text(Dinheiro.formatar(centavos), fontWeight = if (negrito) FontWeight.Bold else null)
    }
}

@Composable
private fun DialogoValor(
    titulo: String,
    rotulo: String,
    permiteZero: Boolean = false,
    comMotivo: Boolean = false,
    aoCancelar: () -> Unit,
    aoConfirmar: (Long, String) -> Unit,
) {
    var texto by remember { mutableStateOf("") }
    var motivo by remember { mutableStateOf("") }
    val valor = if (texto.isBlank() && permiteZero) 0L else Dinheiro.parse(texto)
    val ok = valor != null && (valor > 0 || permiteZero)
    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text(titulo) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CampoDinheiro(texto, { texto = it }, rotulo)
                if (comMotivo) {
                    OutlinedTextField(
                        value = motivo,
                        onValueChange = { motivo = it.take(40) },
                        label = { Text("Motivo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { aoConfirmar(valor ?: 0, motivo) }, enabled = ok) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar") } },
    )
}

@Composable
private fun DialogoFechar(esperado: Long, aoCancelar: () -> Unit, aoConfirmar: (Long) -> Unit) {
    var texto by remember { mutableStateOf("") }
    val contado = Dinheiro.parse(texto)
    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text("Fechar caixa") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Conte o dinheiro da gaveta e informe o valor.")
                CampoDinheiro(texto, { texto = it }, "Dinheiro contado (R$)")
                if (contado != null) {
                    val diferenca = contado - esperado
                    Row {
                        Text("Esperado", Modifier.weight(1f))
                        Text(Dinheiro.formatar(esperado))
                    }
                    Row {
                        Text(
                            when {
                                diferenca > 0 -> "Sobra"
                                diferenca < 0 -> "Falta"
                                else -> "Confere"
                            },
                            Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            Dinheiro.formatar(diferenca),
                            fontWeight = FontWeight.Bold,
                            color = if (diferenca < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { aoConfirmar(contado ?: 0) }, enabled = contado != null) { Text("Fechar caixa") } },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar") } },
    )
}

@Composable
private fun CampoDinheiro(valor: String, aoMudar: (String) -> Unit, rotulo: String) {
    OutlinedTextField(
        value = valor,
        onValueChange = { aoMudar(it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(10)) },
        label = { Text(rotulo) },
        placeholder = { Text("0,00") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
