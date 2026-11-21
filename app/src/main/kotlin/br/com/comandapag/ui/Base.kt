package br.com.comandapag.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.comandapag.ComandaPagApp
import br.com.comandapag.Dependencias

// Paleta verde (Material 3) usada no app inteiro
private val CoresClaras = lightColorScheme(
    primary = Color(0xFF006C47),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8DF7C1),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF4E6355),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8D6),
    onSecondaryContainer = Color(0xFF0B1F14),
    background = Color(0xFFF7FBF5),
    onBackground = Color(0xFF181D19),
    surface = Color(0xFFF7FBF5),
    onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF404943),
    surfaceContainer = Color(0xFFEBF0EA),
    surfaceContainerHigh = Color(0xFFE5EAE4),
    surfaceContainerHighest = Color(0xFFDFE4DE),
    surfaceContainerLow = Color(0xFFF1F6F0),
    outline = Color(0xFF707972),
)

private val CoresEscuras = darkColorScheme(
    primary = Color(0xFF70DBA6),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFF8DF7C1),
    secondary = Color(0xFFB5CCBA),
    secondaryContainer = Color(0xFF364B3E),
    onSecondaryContainer = Color(0xFFD0E8D6),
    background = Color(0xFF101511),
    surface = Color(0xFF101511),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
)

@Composable
fun ComandaPagTema(conteudo: @Composable () -> Unit) {
    val cores = if (isSystemInDarkTheme()) CoresEscuras else CoresClaras
    MaterialTheme(colorScheme = cores, content = conteudo)
}

@Composable
fun dependencias(): Dependencias =
    (LocalContext.current.applicationContext as ComandaPagApp).dependencias

/** Cria (ou reaproveita) um ViewModel construído a partir das dependências do app. */
@Composable
inline fun <reified VM : ViewModel> viewModelCom(
    chave: String? = null,
    crossinline criar: (Dependencias) -> VM,
): VM {
    val deps = dependencias()
    return viewModel(key = chave, factory = viewModelFactory { initializer { criar(deps) } })
}
