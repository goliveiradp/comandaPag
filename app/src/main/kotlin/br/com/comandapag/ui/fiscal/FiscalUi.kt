package br.com.comandapag.ui.fiscal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.comandapag.core.fiscal.AmbienteFiscal
import br.com.comandapag.core.fiscal.StatusEmissor
import br.com.comandapag.rede.ModoRede
import br.com.comandapag.ui.dependencias

/** Ajustes > Nota fiscal (NFC-e). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecaoNotaFiscal() {
    val deps = dependencias()
    Text("Nota fiscal (NFC-e)", style = MaterialTheme.typography.titleMedium)

    if (deps.modo == ModoRede.TERMINAL) {
        // Nos terminais, a nota é emitida pelo servidor, com a configuração de lá
        var status by remember { mutableStateOf<StatusEmissor?>(null) }
        LaunchedEffect(Unit) { status = runCatching { deps.emissorNfce.status() }.getOrNull() }
        Text(
            when {
                status == null -> "Consultando o servidor..."
                status!!.ativo -> "Emitida pela maquininha servidor (${status!!.ambiente.descricao})."
                else -> "A NFC-e não está ativada no servidor. Configure lá, em Ajustes > Nota fiscal."
            },
        )
        return
    }

    val salvo by deps.fiscal.dados.collectAsStateWithLifecycle()
    var d by remember(salvo) { mutableStateOf(salvo) }
    var mensagem by remember { mutableStateOf<String?>(null) }

    Text(
        "Emissão pela Focus NFe. Você precisa de: certificado digital A1 e CSC cadastrados na Focus, " +
            "credenciamento de NFC-e na SEFAZ-MA e o token da API (no painel da Focus). Comece pelo Simulado " +
            "ou Homologação para testar.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = d.ambiente == null, onClick = { d = d.copy(ambiente = null) }, label = { Text("Desativada") })
        AmbienteFiscal.entries.forEach { a ->
            FilterChip(
                selected = d.ambiente == a,
                onClick = { d = d.copy(ambiente = a) },
                label = { Text(a.descricao.substringBefore(" (")) },
            )
        }
    }
    d.ambiente?.let { Text(it.descricao, style = MaterialTheme.typography.bodySmall) }

    if (d.ambiente != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Campo(d.cnpj, "CNPJ", KeyboardType.Number) { d = d.copy(cnpj = it.filter(Char::isDigit).take(14)) }
            Campo(d.razaoSocial, "Razão social") { d = d.copy(razaoSocial = it.take(60)) }
            Campo(d.inscricaoEstadual, "Inscrição estadual", KeyboardType.Number) { d = d.copy(inscricaoEstadual = it.filter(Char::isDigit).take(14)) }
            Campo(d.endereco, "Endereço (sai no cupom)") { d = d.copy(endereco = it.take(120)) }
            when (d.ambiente) {
                AmbienteFiscal.HOMOLOGACAO -> Campo(d.tokenHomologacao, "Token de homologação (Focus NFe)", senha = true) {
                    d = d.copy(tokenHomologacao = it.trim())
                }
                AmbienteFiscal.PRODUCAO -> Campo(d.tokenProducao, "Token de produção (Focus NFe)", senha = true) {
                    d = d.copy(tokenProducao = it.trim())
                }
                else -> Unit
            }
        }
    }
    val faltando = when {
        d.ambiente == null -> null
        d.cnpj.length != 14 -> "Informe o CNPJ (14 dígitos)."
        d.razaoSocial.isBlank() -> "Informe a razão social."
        d.ambiente != AmbienteFiscal.SIMULADO && d.token.isBlank() -> "Informe o token da Focus NFe."
        else -> null
    }
    faltando?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Button(
        onClick = {
            deps.fiscal.salvar(d)
            mensagem = if (d.ambiente == null) "NFC-e desativada." else "Configuração da NFC-e salva."
        },
        enabled = d != salvo && faltando == null,
    ) { Text("Salvar nota fiscal") }
    mensagem?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Campo(
    valor: String,
    rotulo: String,
    teclado: KeyboardType = KeyboardType.Text,
    senha: Boolean = false,
    aoMudar: (String) -> Unit,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        singleLine = true,
        visualTransformation = if (senha) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (senha) KeyboardType.Password else teclado),
        modifier = Modifier.fillMaxWidth(),
    )
}


/** Estado do fluxo de emissão na tela da comanda. */
sealed interface EstadoNfce {
    data object Inativo : EstadoNfce
    data object PedindoCpf : EstadoNfce
    data object Emitindo : EstadoNfce
    data class Erro(val mensagem: String) : EstadoNfce
}

/** Situação da NFC-e na comanda paga, com os botões de emitir, reimprimir e cancelar. */
@Composable
fun BlocoNfce(
    comanda: br.com.comandapag.core.modelo.Comanda,
    nfceAtiva: Boolean,
    aoEmitir: () -> Unit,
    aoReimprimir: () -> Unit,
    aoCancelar: () -> Unit,
) {
    if (comanda.status != br.com.comandapag.core.modelo.StatusComanda.PAGA) return
    val n = comanda.nfce
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
    when {
        n != null && n.autorizada -> {
            Text(
                "NFC-e nº ${n.numero} série ${n.serie} autorizada" + if (n.contingencia) " (contingência)" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (n.ambiente != AmbienteFiscal.PRODUCAO.name) {
                Text("Sem valor fiscal (${n.ambiente.lowercase()})", style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = aoReimprimir) { Text("Reimprimir NFC-e") }
                androidx.compose.material3.TextButton(onClick = aoCancelar) { Text("Cancelar NFC-e") }
            }
        }
        nfceAtiva -> {
            if (n != null) {
                Text(
                    if (n.status == br.com.comandapag.core.modelo.StatusNfce.CANCELADA) "NFC-e nº ${n.numero} cancelada"
                    else "Última NFC-e rejeitada: ${n.mensagem ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = aoEmitir, modifier = Modifier.fillMaxWidth()) { Text("Emitir NFC-e") }
        }
    }
}

@Composable
fun DialogosNfce(estado: EstadoNfce, aoEmitir: (cpf: String?) -> Unit, aoTentarDeNovo: () -> Unit, aoFechar: () -> Unit) {
    when (estado) {
        EstadoNfce.Inativo -> Unit
        EstadoNfce.PedindoCpf -> {
            var cpf by remember { mutableStateOf("") }
            val digitos = cpf.filter(Char::isDigit)
            val valido = digitos.isEmpty() || br.com.comandapag.core.fiscal.MontagemNfce.cpfValido(digitos)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = aoFechar,
                title = { Text("Emitir NFC-e") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CPF na nota? (opcional)")
                        OutlinedTextField(
                            value = cpf,
                            onValueChange = { cpf = it.filter(Char::isDigit).take(11) },
                            label = { Text("CPF do consumidor") },
                            singleLine = true,
                            isError = !valido && digitos.length == 11,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (digitos.length == 11 && !valido) Text("CPF inválido", color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        enabled = valido && (digitos.isEmpty() || digitos.length == 11),
                        onClick = { aoEmitir(digitos.ifEmpty { null }) },
                    ) { Text(if (digitos.isEmpty()) "Emitir sem CPF" else "Emitir") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = aoFechar) { Text("Agora não") } },
            )
        }
        EstadoNfce.Emitindo -> androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            text = {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Text("Emitindo NFC-e na Sefaz...")
                }
            },
            confirmButton = {},
        )
        is EstadoNfce.Erro -> androidx.compose.material3.AlertDialog(
            onDismissRequest = aoFechar,
            title = { Text("NFC-e não emitida") },
            text = { Text(estado.mensagem) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = aoTentarDeNovo) { Text("Tentar de novo") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = aoFechar) { Text("Fechar") } },
        )
    }
}
