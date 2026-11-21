package br.com.comandapag.ui.rede

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.comandapag.ComandaPagApp
import br.com.comandapag.core.pagamento.ResultadoOperacao
import br.com.comandapag.core.servico.RegraDeNegocioException
import br.com.comandapag.rede.ClienteComandaPag
import br.com.comandapag.rede.ModoRede
import br.com.comandapag.rede.Protocolo
import br.com.comandapag.rede.enderecosLocais
import br.com.comandapag.ui.MainActivity
import br.com.comandapag.ui.dependencias
import kotlinx.coroutines.launch

/** Recria as dependências (novo modo de rede) e reabre a tela inicial. */
fun reiniciarApp(context: Context) {
    (context.applicationContext as ComandaPagApp).reiniciarDependencias()
    val activity = context.encontrarActivity()
    activity?.startActivity(Intent(activity, MainActivity::class.java))
    activity?.finish()
}

private tailrec fun Context.encontrarActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.encontrarActivity()
    else -> null
}

/** Seção "Rede (várias maquininhas)" dos Ajustes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecaoRede() {
    val deps = dependencias()
    val contexto = LocalContext.current
    var confirmar by remember { mutableStateOf<ModoRede?>(null) }
    var conectar by remember { mutableStateOf(false) }

    Text("Rede (várias maquininhas)", style = MaterialTheme.typography.titleMedium)
    Text(
        "Para usar várias maquininhas juntas: escolha uma como Servidor e conecte as outras como Terminal. " +
            "Todas precisam estar no mesmo Wi-Fi.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModoRede.entries.forEach { m ->
            FilterChip(
                selected = deps.modo == m,
                onClick = {
                    when {
                        m == deps.modo -> Unit
                        m == ModoRede.TERMINAL -> conectar = true
                        else -> confirmar = m
                    }
                },
                label = { Text(m.descricao) },
            )
        }
    }

    when (deps.modo) {
        ModoRede.INDEPENDENTE -> Text("Esta maquininha funciona sozinha.", style = MaterialTheme.typography.bodyMedium)
        ModoRede.SERVIDOR -> CartaoServidor()
        ModoRede.TERMINAL -> {
            val conectado by deps.conectado.collectAsStateWithLifecycle()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Conectado ao servidor ${deps.rede.nomeServidor}".trim(), fontWeight = FontWeight.Bold)
                    Text(deps.enderecoServidor, fontFamily = FontFamily.Monospace)
                    Text(
                        if (conectado) "Comunicação OK" else "Sem conexão com o servidor",
                        color = if (conectado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    confirmar?.let { novo ->
        AlertDialog(
            onDismissRequest = { confirmar = null },
            title = { Text("Mudar para ${novo.descricao.lowercase()}?") },
            text = {
                Text(
                    when (novo) {
                        ModoRede.SERVIDOR -> "Esta maquininha vai guardar os dados do estabelecimento e as outras vão se " +
                            "conectar a ela. Ela precisa ficar ligada e no Wi-Fi durante o expediente."
                        else -> "Esta maquininha vai voltar a funcionar sozinha, com os dados guardados nela." +
                            if (deps.modo == ModoRede.TERMINAL) " As comandas do servidor não aparecem mais aqui." else ""
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmar = null
                    if (novo == ModoRede.SERVIDOR) deps.rede.virarServidor() else deps.rede.virarIndependente()
                    reiniciarApp(contexto)
                }) { Text("Mudar") }
            },
            dismissButton = { TextButton(onClick = { confirmar = null }) { Text("Cancelar") } },
        )
    }
    if (conectar) DialogoConectarServidor(aoFechar = { conectar = false })
}

@Composable
private fun CartaoServidor() {
    val deps = dependencias()
    var codigo by remember { mutableStateOf(deps.pareamento.codigo) }
    var pareados by remember { mutableStateOf(deps.pareamento.aparelhosPareados) }
    val ips = remember { enderecosLocais() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Nas outras maquininhas, em Ajustes > Rede > Terminal, informe:", style = MaterialTheme.typography.bodySmall)
            Text("Endereço deste servidor", style = MaterialTheme.typography.labelLarge)
            if (ips.isEmpty()) {
                Text("Sem Wi-Fi: conecte esta maquininha à rede do estabelecimento.", color = MaterialTheme.colorScheme.error)
            }
            ips.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Text("Código de pareamento", style = MaterialTheme.typography.labelLarge)
            Text(codigo, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("$pareados aparelho(s) pareado(s)", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                deps.pareamento.renovar()
                codigo = deps.pareamento.codigo
                pareados = 0
            }) { Text("Gerar novo código (desconecta todos)") }
        }
    }
}

/** Pareia esta maquininha com um servidor e passa para o modo terminal. */
@Composable
fun DialogoConectarServidor(aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    val deps = dependencias()
    val escopo = rememberCoroutineScope()
    var ip by remember { mutableStateOf("") }
    var codigo by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var conectando by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!conectando) aoFechar() },
        title = { Text("Conectar a um servidor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Veja o endereço e o código na maquininha servidor, em Ajustes > Rede.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.filter { c -> c.isDigit() || c == '.' || c == ':' }.take(21) },
                    label = { Text("Endereço do servidor (ex.: 192.168.0.10)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = codigo,
                    onValueChange = { codigo = it.filter(Char::isDigit).take(6) },
                    label = { Text("Código de pareamento") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (conectando) Text("Conectando...")
            }
        },
        confirmButton = {
            TextButton(
                enabled = ip.isNotBlank() && codigo.length == 6 && !conectando,
                onClick = {
                    escopo.launch {
                        conectando = true
                        erro = null
                        try {
                            val (host, porta) = ip.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: Protocolo.PORTA_PADRAO) }
                            val endereco = ClienteComandaPag.endereco(host, porta)
                            val r = ClienteComandaPag.parear(endereco, codigo, Build.MODEL ?: "Maquininha")
                            deps.rede.virarTerminal(endereco, r.token, r.servidor)
                            reiniciarApp(contexto)
                        } catch (e: RegraDeNegocioException) {
                            erro = e.message
                        } finally {
                            conectando = false
                        }
                    }
                },
            ) { Text("Conectar") }
        },
        dismissButton = { TextButton(onClick = aoFechar, enabled = !conectando) { Text("Cancelar") } },
    )
}

/** Escolha de onde sai o ticket da cozinha e do bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecaoImpressoras() {
    val deps = dependencias()
    val destinos by deps.rede.impressoras.collectAsStateWithLifecycle()
    val escopo = rememberCoroutineScope()
    var resultadoTeste by remember { mutableStateOf<String?>(null) }

    Text("Impressoras de produção", style = MaterialTheme.typography.titleMedium)
    Text(
        "Onde saem os tickets de pedido. Impressoras de rede precisam ser ESC/POS (Epson, Elgin, Bematech, Daruma...).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    destinos.forEach { (setor, destino) ->
        var host by remember(setor) { mutableStateOf(destino.host) }
        Text(setor.descricao, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !destino.rede,
                onClick = { deps.rede.definirImpressora(setor, destino.copy(rede = false)) },
                label = { Text("Esta maquininha") },
            )
            FilterChip(
                selected = destino.rede,
                onClick = { deps.rede.definirImpressora(setor, destino.copy(rede = true)) },
                label = { Text("Impressora de rede") },
            )
        }
        if (destino.rede) {
            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it.filter { c -> c.isDigit() || c == '.' }.take(15)
                    deps.rede.definirImpressora(setor, destino.copy(host = host))
                },
                label = { Text("IP da impressora (${setor.descricao.lowercase()})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(onClick = {
            escopo.launch {
                resultadoTeste = "Imprimindo teste ${setor.descricao.lowercase()}..."
                val r = deps.impressoraDo(setor).imprimir(
                    listOf("=".repeat(24), "TESTE ${setor.descricao.uppercase()}", "ComandaPag", "=".repeat(24)),
                    24,
                )
                resultadoTeste = when (r) {
                    ResultadoOperacao.Sucesso -> "Teste da ${setor.descricao.lowercase()} enviado"
                    is ResultadoOperacao.Falha -> r.mensagem
                }
            }
        }) { Text("Testar ${setor.descricao.lowercase()}") }
    }
    resultadoTeste?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

