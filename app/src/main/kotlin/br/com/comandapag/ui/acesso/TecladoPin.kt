package br.com.comandapag.ui.acesso

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.comandapag.core.seguranca.Pin

/**
 * Teclado numérico para digitar o PIN, sem depender do teclado do Android.
 * Os dígitos aparecem como bolinhas.
 */
@Composable
fun TecladoPin(
    aoConfirmar: (String) -> Unit,
    modifier: Modifier = Modifier,
    erro: String? = null,
    ocupado: Boolean = false,
    tamanhoTecla: Dp = 64.dp,
) {
    var pin by remember { mutableStateOf("") }
    // Limpa o que foi digitado quando chega um erro novo
    var ultimoErro by remember { mutableStateOf<String?>(null) }
    if (erro != ultimoErro) {
        ultimoErro = erro
        if (erro != null) pin = ""
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.height(24.dp).semantics { contentDescription = "PIN com ${pin.length} dígitos" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Uma bolinha para cada dígito digitado: o tamanho do PIN não fica à mostra
            if (pin.isEmpty()) {
                Text("Digite o PIN", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            }
            repeat(pin.length) {
                Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        Text(
            erro ?: " ",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        val linhas = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("<", "0", "OK"))
        Column(verticalArrangement = Arrangement.spacedBy(tamanhoTecla / 8)) {
            linhas.forEach { linha ->
                Row(horizontalArrangement = Arrangement.spacedBy(tamanhoTecla / 8)) {
                    linha.forEach { tecla ->
                        val mod = Modifier.size(width = tamanhoTecla * 1.3f, height = tamanhoTecla)
                        when (tecla) {
                            "<" -> TextButton(
                                onClick = { pin = pin.dropLast(1) },
                                enabled = pin.isNotEmpty() && !ocupado,
                                modifier = mod,
                                contentPadding = PaddingValues(0.dp),
                            ) { Icon(Icons.AutoMirrored.Filled.Backspace, "Apagar") }
                            "OK" -> TextButton(
                                onClick = { aoConfirmar(pin) },
                                enabled = Pin.valido(pin) && !ocupado,
                                modifier = mod,
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("OK", style = MaterialTheme.typography.titleLarge) }
                            else -> FilledTonalButton(
                                onClick = { if (pin.length < Pin.TAMANHO_MAXIMO) pin += tecla },
                                enabled = !ocupado,
                                modifier = mod,
                                contentPadding = PaddingValues(0.dp),
                            ) { Text(tecla, style = MaterialTheme.typography.headlineSmall) }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).height(4.dp))
    }
}
