package br.com.comandapag.dados

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.StatusComanda

@Entity(tableName = "produtos")
data class ProdutoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    val precoCentavos: Long,
    val categoria: String,
    val ativo: Boolean,
) {
    fun paraModelo() = Produto(id, nome, precoCentavos, categoria, ativo)

    companion object {
        fun de(p: Produto) = ProdutoEntity(p.id, p.nome, p.precoCentavos, p.categoria, p.ativo)
    }
}

@Entity(tableName = "comandas", indices = [Index("status")])
data class ComandaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identificacao: String,
    val abertaEm: Long,
    val status: String,
    val taxaServico: Boolean,
    val fechadaEm: Long?,
    // Pagamento (nulo enquanto a comanda não for paga)
    val pagamentoForma: String?,
    val pagamentoValorCentavos: Long?,
    val pagamentoParcelas: Int?,
    val pagamentoCodigoTransacao: String?,
    val pagamentoIdTransacao: String?,
    val pagamentoBandeira: String?,
    val pagamentoEm: Long?,
)

@Entity(
    tableName = "itens_comanda",
    foreignKeys = [
        ForeignKey(
            entity = ComandaEntity::class,
            parentColumns = ["id"],
            childColumns = ["comandaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("comandaId")],
)
data class ItemComandaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comandaId: Long,
    /** Posição do item na comanda, para manter a ordem de lançamento. */
    val posicao: Int,
    val produtoId: Long,
    val nome: String,
    val precoUnitarioCentavos: Long,
    val quantidade: Int,
    val observacao: String,
)

data class ComandaComItens(
    @Embedded val comanda: ComandaEntity,
    @Relation(parentColumn = "id", entityColumn = "comandaId")
    val itens: List<ItemComandaEntity>,
) {
    fun paraModelo(): Comanda {
        val c = comanda
        val pagamento = c.pagamentoForma?.let { forma ->
            Pagamento(
                forma = FormaPagamento.valueOf(forma),
                valorCentavos = c.pagamentoValorCentavos ?: 0,
                parcelas = c.pagamentoParcelas ?: 1,
                codigoTransacao = c.pagamentoCodigoTransacao,
                idTransacao = c.pagamentoIdTransacao,
                bandeira = c.pagamentoBandeira,
                pagoEm = c.pagamentoEm ?: 0,
            )
        }
        return Comanda(
            id = c.id,
            identificacao = c.identificacao,
            abertaEm = c.abertaEm,
            itens = itens.sortedBy { it.posicao }.map {
                ItemComanda(it.produtoId, it.nome, it.precoUnitarioCentavos, it.quantidade, it.observacao)
            },
            status = StatusComanda.valueOf(c.status),
            taxaServico = c.taxaServico,
            fechadaEm = c.fechadaEm,
            pagamento = pagamento,
        )
    }
}

fun Comanda.paraEntidade() = ComandaEntity(
    id = id,
    identificacao = identificacao,
    abertaEm = abertaEm,
    status = status.name,
    taxaServico = taxaServico,
    fechadaEm = fechadaEm,
    pagamentoForma = pagamento?.forma?.name,
    pagamentoValorCentavos = pagamento?.valorCentavos,
    pagamentoParcelas = pagamento?.parcelas,
    pagamentoCodigoTransacao = pagamento?.codigoTransacao,
    pagamentoIdTransacao = pagamento?.idTransacao,
    pagamentoBandeira = pagamento?.bandeira,
    pagamentoEm = pagamento?.pagoEm,
)
