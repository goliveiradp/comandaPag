package br.com.comandapag.dados

import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.repositorio.ComandaRepository
import br.com.comandapag.core.repositorio.ProdutoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProdutoRepositoryRoom(private val dao: ProdutoDao) : ProdutoRepository {
    override fun observarAtivos(): Flow<List<Produto>> = dao.observarAtivos().map { l -> l.map { it.paraModelo() } }
    override suspend fun buscar(id: Long): Produto? = dao.buscar(id)?.paraModelo()
    override suspend fun salvar(produto: Produto): Long = dao.salvar(ProdutoEntity.de(produto))
    override suspend fun desativar(id: Long) = dao.desativar(id)
}

class ComandaRepositoryRoom(private val dao: ComandaDao) : ComandaRepository {
    override fun observarAbertas(): Flow<List<Comanda>> = dao.observarAbertas().map { l -> l.map { it.paraModelo() } }
    override fun observarFechadas(): Flow<List<Comanda>> = dao.observarFechadas().map { l -> l.map { it.paraModelo() } }
    override fun observar(id: Long): Flow<Comanda?> = dao.observar(id).map { it?.paraModelo() }
    override suspend fun buscar(id: Long): Comanda? = dao.buscar(id)?.paraModelo()
    override suspend fun buscarAbertaPorIdentificacao(identificacao: String): Comanda? =
        dao.buscarAbertaPorIdentificacao(identificacao)?.paraModelo()
    override suspend fun salvar(comanda: Comanda): Long = dao.salvar(comanda)
}
