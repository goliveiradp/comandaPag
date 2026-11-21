package br.com.comandapag.rede

import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.repositorio.CaixaRepository
import br.com.comandapag.core.repositorio.ComandaRepository
import br.com.comandapag.core.repositorio.ProdutoRepository
import br.com.comandapag.core.repositorio.UsuarioRepository
import kotlinx.coroutines.flow.Flow

/* Repositórios usados pelos terminais: tudo é lido e gravado na maquininha servidor. */

class ProdutoRepositoryRemoto(private val c: ClienteComandaPag) : ProdutoRepository {
    override fun observarAtivos(): Flow<List<Produto>> = c.observar { c.get<List<Produto>>("/produtos").orEmpty() }
    override suspend fun buscar(id: Long): Produto? = c.get("/produtos/$id")
    override suspend fun salvar(produto: Produto): Long =
        c.post<Produto, RespostaId>("/produtos", produto).id.also { c.avisarAlteracao() }
    override suspend fun desativar(id: Long) {
        c.post<String, RespostaId>("/produtos/$id/desativar", "")
        c.avisarAlteracao()
    }
}

class ComandaRepositoryRemoto(private val c: ClienteComandaPag) : ComandaRepository {
    override fun observarAbertas(): Flow<List<Comanda>> = c.observar { c.get<List<Comanda>>("/comandas/abertas").orEmpty() }
    override fun observarFechadas(): Flow<List<Comanda>> = c.observar { c.get<List<Comanda>>("/comandas/fechadas").orEmpty() }
    override fun observar(id: Long): Flow<Comanda?> = c.observar { c.get<Comanda>("/comandas/$id") }
    override suspend fun buscar(id: Long): Comanda? = c.get("/comandas/$id")
    override suspend fun buscarAbertaPorIdentificacao(identificacao: String): Comanda? =
        c.get("/comandas/aberta", mapOf("identificacao" to identificacao))
    override suspend fun salvar(comanda: Comanda): Long =
        c.post<Comanda, RespostaId>("/comandas", comanda).id.also { c.avisarAlteracao() }
    override suspend fun pagamentosDoCaixa(caixaId: Long): List<Pagamento> =
        c.get<List<Pagamento>>("/caixas/$caixaId/pagamentos").orEmpty()
}

class UsuarioRepositoryRemoto(private val c: ClienteComandaPag) : UsuarioRepository {
    override fun observarTodos(): Flow<List<Usuario>> = c.observar { listarTodos() }
    override suspend fun listarTodos(): List<Usuario> = c.get<List<Usuario>>("/usuarios").orEmpty()
    override suspend fun buscar(id: Long): Usuario? = c.get("/usuarios/$id")
    override suspend fun salvar(usuario: Usuario): Long =
        c.post<Usuario, RespostaId>("/usuarios", usuario).id.also { c.avisarAlteracao() }
}

class CaixaRepositoryRemoto(private val c: ClienteComandaPag) : CaixaRepository {
    override fun observarAberto(): Flow<Caixa?> = c.observar { buscarAberto() }
    override fun observarFechados(): Flow<List<Caixa>> = c.observar { c.get<List<Caixa>>("/caixas/fechados").orEmpty() }
    override suspend fun buscarAberto(): Caixa? = c.get("/caixas/aberto")
    override suspend fun buscar(id: Long): Caixa? = c.get("/caixas/$id")
    override suspend fun salvar(caixa: Caixa): Long =
        c.post<Caixa, RespostaId>("/caixas", caixa).id.also { c.avisarAlteracao() }
}
