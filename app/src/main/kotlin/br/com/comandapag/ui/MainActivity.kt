package br.com.comandapag.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import br.com.comandapag.ui.comanda.ComandaScreen
import br.com.comandapag.ui.comandas.ComandasScreen
import br.com.comandapag.ui.configuracoes.ConfiguracoesScreen
import br.com.comandapag.ui.historico.HistoricoScreen
import br.com.comandapag.ui.produtos.ProdutosScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ComandaPagTema { App() } }
    }
}

sealed interface Tela {
    data object Comandas : Tela
    data object Produtos : Tela
    data object Historico : Tela
    data object Configuracoes : Tela
    data class Comanda(val id: Long) : Tela
}

private data class Aba(val tela: Tela, val titulo: String, val icone: ImageVector)

private val abas = listOf(
    Aba(Tela.Comandas, "Comandas", Icons.AutoMirrored.Filled.ReceiptLong),
    Aba(Tela.Produtos, "Produtos", Icons.Filled.Inventory2),
    Aba(Tela.Historico, "Histórico", Icons.Filled.History),
    Aba(Tela.Configuracoes, "Ajustes", Icons.Filled.Settings),
)

@Composable
fun App() {
    val pilha = remember { mutableStateListOf<Tela>(Tela.Comandas) }
    val atual = pilha.last()
    val abrir: (Tela) -> Unit = { pilha.add(it) }
    val voltar: () -> Unit = { if (pilha.size > 1) pilha.removeAt(pilha.lastIndex) }

    BackHandler(enabled = pilha.size > 1, onBack = voltar)

    when (atual) {
        is Tela.Comanda -> ComandaScreen(comandaId = atual.id, aoVoltar = voltar)
        else -> Scaffold(
            bottomBar = {
                NavigationBar {
                    abas.forEach { aba ->
                        NavigationBarItem(
                            selected = atual == aba.tela,
                            onClick = { pilha.clear(); pilha.add(Tela.Comandas); if (aba.tela != Tela.Comandas) pilha.add(aba.tela) },
                            icon = { Icon(aba.icone, contentDescription = null) },
                            label = { Text(aba.titulo) },
                        )
                    }
                }
            },
        ) { padding ->
            val mod = Modifier.padding(padding)
            when (atual) {
                Tela.Comandas -> ComandasScreen(mod, aoAbrirComanda = { abrir(Tela.Comanda(it)) })
                Tela.Produtos -> ProdutosScreen(mod)
                Tela.Historico -> HistoricoScreen(mod, aoAbrirComanda = { abrir(Tela.Comanda(it)) })
                Tela.Configuracoes -> ConfiguracoesScreen(mod)
                is Tela.Comanda -> Unit
            }
        }
    }
}
