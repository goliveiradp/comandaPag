package br.com.comandapag

import android.app.Application
import android.content.Context
import android.os.Build
import br.com.comandapag.core.modelo.SetorProducao
import br.com.comandapag.core.pagamento.Impressora
import br.com.comandapag.core.fiscal.EmissorNfce
import br.com.comandapag.core.fiscal.NfceService
import br.com.comandapag.core.producao.ImpressorasProducao
import br.com.comandapag.core.repositorio.CaixaRepository
import br.com.comandapag.core.repositorio.ComandaRepository
import br.com.comandapag.core.repositorio.ProdutoRepository
import br.com.comandapag.core.repositorio.UsuarioRepository
import br.com.comandapag.core.servico.CaixaService
import br.com.comandapag.core.servico.ComandaService
import br.com.comandapag.core.servico.UsuarioService
import br.com.comandapag.dados.BancoDeDados
import br.com.comandapag.dados.CaixaRepositoryRoom
import br.com.comandapag.dados.ComandaRepositoryRoom
import br.com.comandapag.dados.Preferencias
import br.com.comandapag.dados.ProdutoRepositoryRoom
import br.com.comandapag.dados.UsuarioRepositoryRoom
import br.com.comandapag.fiscal.ConfiguracaoFiscal
import br.com.comandapag.fiscal.EmissorLocal
import br.com.comandapag.rede.CaixaRepositoryNotificando
import br.com.comandapag.rede.CaixaRepositoryRemoto
import br.com.comandapag.rede.ClienteComandaPag
import br.com.comandapag.rede.ComandaRepositoryNotificando
import br.com.comandapag.rede.ComandaRepositoryRemoto
import br.com.comandapag.rede.ConfiguracaoRede
import br.com.comandapag.rede.EmissorNfceRemoto
import br.com.comandapag.rede.ImpressoraRede
import br.com.comandapag.rede.ModoRede
import br.com.comandapag.rede.PagamentosPendentesPreferencias
import br.com.comandapag.rede.PareamentoPreferencias
import br.com.comandapag.rede.ProdutoRepositoryNotificando
import br.com.comandapag.rede.ProdutoRepositoryRemoto
import br.com.comandapag.rede.ServicoServidor
import br.com.comandapag.rede.ServidorComandaPag
import br.com.comandapag.rede.UsuarioRepositoryNotificando
import br.com.comandapag.rede.UsuarioRepositoryRemoto
import br.com.comandapag.terminal.Maquininha
import br.com.comandapag.terminal.criarMaquininha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class ComandaPagApp : Application() {
    lateinit var dependencias: Dependencias
        private set

    override fun onCreate() {
        super.onCreate()
        dependencias = Dependencias(this)
    }

    /** Recria tudo (ex.: depois de mudar o modo de rede). A tela precisa ser reaberta em seguida. */
    fun reiniciarDependencias() {
        dependencias.encerrar()
        dependencias = Dependencias(this)
    }
}

/**
 * Injeção de dependências manual. Conforme o modo de rede, os dados ficam neste aparelho
 * (independente/servidor) ou na maquininha servidor (terminal).
 */
class Dependencias(private val app: Application) {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val banco = BancoDeDados.criar(app)

    val preferencias = Preferencias(app)
    val rede = ConfiguracaoRede(app)
    val modo: ModoRede = rede.modo
    val maquininha: Maquininha = criarMaquininha(app)
    val pareamento = PareamentoPreferencias(app)

    /** NFC-e: configuração e emissor deste aparelho (no servidor, também atende os terminais). */
    val fiscal = ConfiguracaoFiscal(app)
    private val emissorLocal = EmissorLocal(fiscal)

    /** Conexão com o servidor (só no modo terminal). */
    val cliente: ClienteComandaPag? =
        if (modo == ModoRede.TERMINAL) {
            ClienteComandaPag(rede.enderecoServidor, rede.tokenServidor, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        } else {
            null
        }

    /** Servidor HTTP (só no modo servidor). */
    private var servidor: ServidorComandaPag? = null
    private val avisarTerminais: () -> Unit = { servidor?.notificarMudanca() }

    val produtos: ProdutoRepository
    val comandas: ComandaRepository
    val usuarios: UsuarioRepository
    val caixas: CaixaRepository

    init {
        val produtosLocais = ProdutoRepositoryRoom(banco.produtoDao())
        val comandasLocais = ComandaRepositoryRoom(banco.comandaDao())
        val usuariosLocais = UsuarioRepositoryRoom(banco.usuarioDao())
        val caixasLocais = CaixaRepositoryRoom(banco.caixaDao())
        when {
            cliente != null -> {
                produtos = ProdutoRepositoryRemoto(cliente)
                comandas = ComandaRepositoryRemoto(cliente)
                usuarios = UsuarioRepositoryRemoto(cliente)
                caixas = CaixaRepositoryRemoto(cliente)
            }
            modo == ModoRede.SERVIDOR -> {
                produtos = ProdutoRepositoryNotificando(produtosLocais, avisarTerminais)
                comandas = ComandaRepositoryNotificando(comandasLocais, avisarTerminais)
                usuarios = UsuarioRepositoryNotificando(usuariosLocais, avisarTerminais)
                caixas = CaixaRepositoryNotificando(caixasLocais, avisarTerminais)
                servidor = ServidorComandaPag(
                    produtosLocais, comandasLocais, usuariosLocais, caixasLocais,
                    pareamento = pareamento,
                    nome = preferencias.nomeEstabelecimento.value.ifBlank { Build.MODEL ?: "ComandaPag" },
                    emissorNfce = { emissorLocal },
                ).also {
                    runCatching { it.iniciar() }
                    ServicoServidor.iniciar(app)
                }
            }
            else -> {
                produtos = produtosLocais
                comandas = comandasLocais
                usuarios = usuariosLocais
                caixas = caixasLocais
            }
        }
    }

    /** Se este aparelho (terminal) está falando com o servidor. Sempre true nos outros modos. */
    val conectado: StateFlow<Boolean> = cliente?.conectado ?: MutableStateFlow(true)

    /** Cozinha e bar podem imprimir numa impressora de rede ou na da própria maquininha. */
    private val impressoras = ImpressorasProducao { setor -> impressoraDo(setor) }

    fun impressoraDo(setor: SetorProducao): Impressora {
        val destino = rede.impressoras.value[setor]
        return if (destino != null && destino.rede && destino.host.isNotBlank()) {
            ImpressoraRede(destino.host, destino.porta)
        } else {
            maquininha.impressora
        }
    }

    /** Mesma trava para comandas e caixa: estornos mexem nos dois. */
    private val trava = Mutex()
    private val pendentes = PagamentosPendentesPreferencias(app)
    val comandaService = ComandaService(
        comandas = comandas,
        terminal = maquininha.terminal,
        caixas = caixas,
        impressoras = impressoras,
        pendentes = pendentes,
        trava = trava,
    )
    val caixaService = CaixaService(caixas, comandas, trava)

    /** Nos terminais a NFC-e é emitida pelo servidor (só ele tem o token da Focus NFe). */
    val emissorNfce: EmissorNfce = cliente?.let { EmissorNfceRemoto(it) } ?: emissorLocal
    val nfceService = NfceService(comandas, produtos, emissorNfce)
    val usuarioService = UsuarioService(usuarios)
    val sessao = Sessao(usuarios, escopo)

    /** Quantos pagamentos aprovados ainda não chegaram ao servidor. */
    val pagamentosPendentes = MutableStateFlow(0)

    init {
        // Reenvia pagamentos que ficaram presos sem conexão
        escopo.launch {
            while (isActive) {
                runCatching {
                    if (comandaService.pagamentosPendentes().isNotEmpty()) comandaService.reenviarPendentes()
                    pagamentosPendentes.value = comandaService.pagamentosPendentes().size
                }
                delay(10_000)
            }
        }
    }

    /** Atualiza o contador de pendentes logo depois de uma cobrança. */
    fun atualizarPendentes() {
        escopo.launch { pagamentosPendentes.value = comandaService.pagamentosPendentes().size }
    }

    val enderecoServidor: String get() = rede.enderecoServidor
    val portaServidor: Int get() = servidor?.porta ?: 0

    fun encerrar() {
        escopo.cancel()
        cliente?.fechar()
        servidor?.parar()
        if (modo == ModoRede.SERVIDOR) ServicoServidor.parar(app)
        banco.close()
    }

    companion object {
        fun de(context: Context): Dependencias = (context.applicationContext as ComandaPagApp).dependencias
    }
}
