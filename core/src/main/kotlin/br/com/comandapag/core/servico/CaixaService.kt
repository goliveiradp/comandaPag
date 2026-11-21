package br.com.comandapag.core.servico

import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Dinheiro
import br.com.comandapag.core.modelo.MovimentoCaixa
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.ResumoCaixa
import br.com.comandapag.core.modelo.TipoMovimento
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.repositorio.CaixaRepository
import br.com.comandapag.core.repositorio.ComandaRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Abertura, sangria, suprimento e fechamento do caixa.
 * Use a mesma [trava] do [ComandaService], porque estornos também mexem no caixa.
 */
class CaixaService(
    private val caixas: CaixaRepository,
    private val comandas: ComandaRepository,
    private val trava: Mutex = Mutex(),
    private val agora: () -> Long = System::currentTimeMillis,
) {
    suspend fun caixaAberto(): Caixa? = caixas.buscarAberto()

    suspend fun abrir(fundoDeTrocoCentavos: Long, operador: Usuario): Caixa = trava.withLock {
        exigir(operador)
        if (caixas.buscarAberto() != null) erro("Já existe um caixa aberto.")
        if (fundoDeTrocoCentavos < 0) erro("O fundo de troco não pode ser negativo.")
        val novo = Caixa(abertoEm = agora(), abertoPor = operador.nome, valorInicialCentavos = fundoDeTrocoCentavos)
        novo.copy(id = caixas.salvar(novo))
    }

    suspend fun suprimento(valorCentavos: Long, motivo: String, operador: Usuario): Caixa =
        movimentar(TipoMovimento.SUPRIMENTO, valorCentavos, motivo, operador)

    suspend fun sangria(valorCentavos: Long, motivo: String, operador: Usuario): Caixa =
        movimentar(TipoMovimento.SANGRIA, valorCentavos, motivo, operador)

    suspend fun resumo(caixa: Caixa): ResumoCaixa =
        ResumoCaixa.calcular(caixa, comandas.pagamentosDoCaixa(caixa.id))

    /** Fecha o caixa com o dinheiro contado na gaveta e devolve o resumo com a diferença. */
    suspend fun fechar(valorContadoCentavos: Long, operador: Usuario): ResumoCaixa = trava.withLock {
        exigir(operador)
        if (valorContadoCentavos < 0) erro("O valor contado não pode ser negativo.")
        val fechado = repetindoEmConflito {
            val caixa = caixas.buscarAberto() ?: erro("Não há caixa aberto.")
            val f = caixa.copy(fechadoEm = agora(), fechadoPor = operador.nome, valorContadoCentavos = valorContadoCentavos)
            caixas.salvar(f)
            f.copy(versao = f.versao + 1)
        }
        resumo(fechado)
    }

    private suspend fun movimentar(tipo: TipoMovimento, valor: Long, motivo: String, operador: Usuario): Caixa =
        trava.withLock {
            exigir(operador)
            if (valor <= 0) erro("Informe um valor maior que zero.")
            repetindoEmConflito {
                val caixa = caixas.buscarAberto() ?: erro("Abra o caixa primeiro.")
                if (tipo == TipoMovimento.SANGRIA) {
                    val disponivel = resumo(caixa).dinheiroEsperadoCentavos
                    if (valor > disponivel) erro("Sangria maior que o dinheiro do caixa (${Dinheiro.formatar(disponivel)}).")
                }
                val atualizado = caixa.copy(
                    movimentos = caixa.movimentos + MovimentoCaixa(tipo, valor, motivo.trim(), agora(), operador.nome),
                )
                caixas.salvar(atualizado)
                atualizado.copy(versao = atualizado.versao + 1)
            }
        }

    private fun exigir(operador: Usuario) {
        if (!operador.pode(Permissao.OPERAR_CAIXA)) throw SemPermissaoException(Permissao.OPERAR_CAIXA)
    }

    private fun erro(mensagem: String): Nothing = throw RegraDeNegocioException(mensagem)
}
