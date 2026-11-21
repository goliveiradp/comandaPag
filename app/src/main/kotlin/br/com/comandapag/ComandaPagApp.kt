package br.com.comandapag

import android.app.Application
import br.com.comandapag.core.servico.CaixaService
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.UsuarioService
import br.com.comandapag.dados.BancoDeDados
import br.com.comandapag.dados.CaixaRepositoryRoom
import br.com.comandapag.dados.ComandaRepositoryRoom
import br.com.comandapag.dados.Preferencias
import br.com.comandapag.dados.ProdutoRepositoryRoom
import br.com.comandapag.dados.UsuarioRepositoryRoom
import br.com.comandapag.terminal.Maquininha
import br.com.comandapag.terminal.criarMaquininha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

class ComandaPagApp : Application() {
    lateinit var dependencias: Dependencias
        private set

    override fun onCreate() {
        super.onCreate()
        dependencias = Dependencias(this)
    }
}

/** Injeção de dependências manual, simples o bastante para o tamanho do app. */
class Dependencias(app: Application) {
    private val banco = BancoDeDados.criar(app)
    val produtos = ProdutoRepositoryRoom(banco.produtoDao())
    val comandas = ComandaRepositoryRoom(banco.comandaDao())
    val usuarios = UsuarioRepositoryRoom(banco.usuarioDao())
    val preferencias = Preferencias(app)
    val maquininha: Maquininha = criarMaquininha(app)
    val caixas = CaixaRepositoryRoom(banco.caixaDao())

    /** Mesma trava para comandas e caixa: estornos mexem nos dois. */
    private val trava = Mutex()
    val comandaService = ComandaService(
        comandas = comandas,
        terminal = maquininha.terminal,
        caixas = caixas,
        impressoraProducao = maquininha.impressora,
        trava = trava,
    )
    val caixaService = CaixaService(caixas, comandas, trava)
    val usuarioService = UsuarioService(usuarios)
    val sessao = Sessao(usuarios, CoroutineScope(SupervisorJob() + Dispatchers.Main))
}
