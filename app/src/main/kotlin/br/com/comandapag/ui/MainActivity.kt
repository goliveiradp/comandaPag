package br.com.comandapag.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.ui.acesso.LoginScreen
import br.com.comandapag.ui.comanda.ComandaScreen
import br.com.comandapag.ui.comandas.ComandasScreen
import br.com.comandapag.ui.configuracoes.ConfiguracoesScreen
import br.com.comandapag.ui.historico.HistoricoScreen
import br.com.comandapag.ui.produtos.ProdutosScreen
import br.com.comandapag.ui.usuarios.UsuariosScreen

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
    data object Usuarios : Tela
    data class Comanda(val id: Long) : Tela
}

private data class Aba(val tela: Tela, val titulo: String, val icone: ImageVector, val permissao: Permissao?)

private val abas = listOf(
    Aba(Tela.Comandas, "Comandas", Icons.AutoMirrored.Filled.ReceiptLong, null),
    Aba(Tela.Produtos, "Produtos", Icons.Filled.Inventory2, Permissao.GERENCIAR_PRODUTOS),
    Aba(Tela.Historico, "Histórico", Icons.Filled.History, Permissao.VER_RELATORIOS),
    Aba(Tela.Configuracoes, "Ajustes", Icons.Filled.Settings, null),
)

@Composable
fun App() {
    val sessao = dependencias().sessao
    val usuario by sessao.usuario.collectAsStateWithLifecycle()
    val u = usuario
    if (u == null) {
        LoginScreen()
    } else {
        // Cada login começa com a navegação zerada
        key(u.id) { Principal(u, aoSair = sessao::sair) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Principal(usuario: Usuario, aoSair: () -> Unit) {
    val pilha = remember { mutableStateListOf<Tela>(Tela.Comandas) }
    val atual = pilha.last()
    val abrir: (Tela) -> Unit = { pilha.add(it) }
    val voltar: () -> Unit = { if (pilha.size > 1) pilha.removeAt(pilha.lastIndex) }
    val abasVisiveis = abas.filter { it.permissao == null || usuario.pode(it.permissao) }

    BackHandler(enabled = pilha.size > 1, onBack = voltar)

    when (atual) {
        is Tela.Comanda -> ComandaScreen(comandaId = atual.id, aoVoltar = voltar)
        Tela.Usuarios -> UsuariosScreen(aoVoltar = voltar)
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(usuario.nome, maxLines = 1)
                            Text(usuario.descricaoPerfil, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = {
                        TextButton(onClick = aoSair) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            Text("Sair", modifier = Modifier.padding(start = 4.dp))
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    abasVisiveis.forEach { aba ->
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
                Tela.Comandas -> ComandasScreen(mod, usuario, aoAbrirComanda = { abrir(Tela.Comanda(it)) })
                Tela.Produtos -> ProdutosScreen(mod)
                Tela.Historico -> HistoricoScreen(mod, aoAbrirComanda = { abrir(Tela.Comanda(it)) })
                Tela.Configuracoes -> ConfiguracoesScreen(mod, usuario, aoGerenciarUsuarios = { abrir(Tela.Usuarios) })
                is Tela.Comanda, Tela.Usuarios -> Unit
            }
        }
    }
}
