package br.com.comandapag.rede

import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.Usuario
import br.com.comandapag.core.repositorio.CaixaRepository
import br.com.comandapag.core.repositorio.ComandaRepository
import br.com.comandapag.core.repositorio.ProdutoRepository
import br.com.comandapag.core.repositorio.UsuarioRepository

/*
 * No modo servidor, o próprio servidor também grava (é uma maquininha em uso).
 * Estes repositórios avisam os terminais a cada gravação local.
 */

class ComandaRepositoryNotificando(private val r: ComandaRepository, private val avisar: () -> Unit) : ComandaRepository by r {
    override suspend fun salvar(comanda: Comanda): Long = r.salvar(comanda).also { avisar() }
}

class CaixaRepositoryNotificando(private val r: CaixaRepository, private val avisar: () -> Unit) : CaixaRepository by r {
    override suspend fun salvar(caixa: Caixa): Long = r.salvar(caixa).also { avisar() }
}

class ProdutoRepositoryNotificando(private val r: ProdutoRepository, private val avisar: () -> Unit) : ProdutoRepository by r {
    override suspend fun salvar(produto: Produto): Long = r.salvar(produto).also { avisar() }
    override suspend fun desativar(id: Long) = r.desativar(id).also { avisar() }
}

class UsuarioRepositoryNotificando(private val r: UsuarioRepository, private val avisar: () -> Unit) : UsuarioRepository by r {
    override suspend fun salvar(usuario: Usuario): Long = r.salvar(usuario).also { avisar() }
}
