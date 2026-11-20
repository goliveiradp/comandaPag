package br.com.comandapag.dados

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import br.com.comandapag.core.modelo.Comanda
import kotlinx.coroutines.flow.Flow

@Dao
interface ProdutoDao {
    @Query("SELECT * FROM produtos WHERE ativo = 1 ORDER BY categoria, nome")
    fun observarAtivos(): Flow<List<ProdutoEntity>>

    @Query("SELECT * FROM produtos WHERE id = :id")
    suspend fun buscar(id: Long): ProdutoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun salvar(produto: ProdutoEntity): Long

    @Query("UPDATE produtos SET ativo = 0 WHERE id = :id")
    suspend fun desativar(id: Long)
}

@Dao
abstract class ComandaDao {
    @Transaction
    @Query("SELECT * FROM comandas WHERE status = 'ABERTA' ORDER BY abertaEm")
    abstract fun observarAbertas(): Flow<List<ComandaComItens>>

    @Transaction
    @Query("SELECT * FROM comandas WHERE status != 'ABERTA' ORDER BY fechadaEm DESC LIMIT 500")
    abstract fun observarFechadas(): Flow<List<ComandaComItens>>

    @Transaction
    @Query("SELECT * FROM comandas WHERE id = :id")
    abstract fun observar(id: Long): Flow<ComandaComItens?>

    @Transaction
    @Query("SELECT * FROM comandas WHERE id = :id")
    abstract suspend fun buscar(id: Long): ComandaComItens?

    @Transaction
    @Query("SELECT * FROM comandas WHERE status = 'ABERTA' AND identificacao = :identificacao COLLATE NOCASE LIMIT 1")
    abstract suspend fun buscarAbertaPorIdentificacao(identificacao: String): ComandaComItens?

    @Insert
    protected abstract suspend fun inserir(comanda: ComandaEntity): Long

    @Update
    protected abstract suspend fun atualizar(comanda: ComandaEntity)

    @Insert
    protected abstract suspend fun inserirItens(itens: List<ItemComandaEntity>)

    @Query("DELETE FROM itens_comanda WHERE comandaId = :comandaId")
    protected abstract suspend fun apagarItens(comandaId: Long)

    /** Salva a comanda e substitui todos os seus itens numa única transação. */
    @Transaction
    open suspend fun salvar(comanda: Comanda): Long {
        val entidade = comanda.paraEntidade()
        val id = if (comanda.id == 0L) inserir(entidade) else entidade.id.also { atualizar(entidade) }
        apagarItens(id)
        inserirItens(
            comanda.itens.mapIndexed { i, item ->
                ItemComandaEntity(
                    comandaId = id,
                    posicao = i,
                    produtoId = item.produtoId,
                    nome = item.nome,
                    precoUnitarioCentavos = item.precoUnitarioCentavos,
                    quantidade = item.quantidade,
                    observacao = item.observacao,
                )
            },
        )
        return id
    }
}

@Dao
interface UsuarioDao {
    @Query("SELECT * FROM usuarios ORDER BY ativo DESC, nome COLLATE NOCASE")
    fun observarTodos(): Flow<List<UsuarioEntity>>

    @Query("SELECT * FROM usuarios")
    suspend fun listarTodos(): List<UsuarioEntity>

    @Query("SELECT * FROM usuarios WHERE id = :id")
    suspend fun buscar(id: Long): UsuarioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun salvar(usuario: UsuarioEntity): Long
}
