package br.com.comandapag.core.repositorio

import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.Usuario
import kotlinx.coroutines.flow.Flow

interface ProdutoRepository {
    fun observarAtivos(): Flow<List<Produto>>
    suspend fun buscar(id: Long): Produto?
    suspend fun salvar(produto: Produto): Long
    /** Produtos não são apagados (comandas antigas os referenciam), apenas desativados. */
    suspend fun desativar(id: Long)
}

interface ComandaRepository {
    fun observarAbertas(): Flow<List<Comanda>>
    fun observarFechadas(): Flow<List<Comanda>>
    fun observar(id: Long): Flow<Comanda?>
    suspend fun buscar(id: Long): Comanda?
    suspend fun buscarAbertaPorIdentificacao(identificacao: String): Comanda?
    /**
     * Insere (id = 0) ou atualiza a comanda e seus itens. Retorna o id.
     * Na atualização, [Comanda.versao] precisa ser a versão gravada; senão lança
     * ConflitoDeVersaoException (outro aparelho gravou antes). A versão gravada passa a ser +1.
     */
    suspend fun salvar(comanda: Comanda): Long

    /** Todos os pagamentos (inclusive estornados) recebidos no caixa [caixaId]. */
    suspend fun pagamentosDoCaixa(caixaId: Long): List<Pagamento>
}

interface CaixaRepository {
    fun observarAberto(): Flow<Caixa?>
    fun observarFechados(): Flow<List<Caixa>>
    suspend fun buscarAberto(): Caixa?
    suspend fun buscar(id: Long): Caixa?
    /** Insere (id = 0) ou atualiza o caixa e seus movimentos (com controle de versão). Retorna o id. */
    suspend fun salvar(caixa: Caixa): Long
}

interface UsuarioRepository {
    fun observarTodos(): Flow<List<Usuario>>
    suspend fun listarTodos(): List<Usuario>
    suspend fun buscar(id: Long): Usuario?
    /** Insere (id = 0) ou atualiza. Retorna o id. */
    suspend fun salvar(usuario: Usuario): Long
}
