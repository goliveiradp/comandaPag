package br.com.comandapag.dados

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import br.com.comandapag.core.modelo.Caixa
import br.com.comandapag.core.modelo.Comanda
import br.com.comandapag.core.modelo.DadosFiscais
import br.com.comandapag.core.modelo.EventoComanda
import kotlinx.serialization.builtins.ListSerializer
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.ItemComanda
import br.com.comandapag.core.modelo.MovimentoCaixa
import br.com.comandapag.core.modelo.NotaFiscalConsumidor
import br.com.comandapag.core.modelo.Pagamento
import br.com.comandapag.core.modelo.Permissao
import br.com.comandapag.core.modelo.Produto
import br.com.comandapag.core.modelo.SetorProducao
import br.com.comandapag.core.modelo.StatusComanda
import br.com.comandapag.core.modelo.TipoMovimento
import br.com.comandapag.core.modelo.Usuario

@Entity(tableName = "produtos")
data class ProdutoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    val precoCentavos: Long,
    val categoria: String,
    val ativo: Boolean,
    // Versão 5 do banco
    @ColumnInfo(defaultValue = "COZINHA") val setor: String = SetorProducao.COZINHA.name,
    // Versão 7: dados fiscais para a NFC-e
    @ColumnInfo(defaultValue = "") val ncm: String = "",
    @ColumnInfo(defaultValue = "5102") val cfop: String = "5102",
    @ColumnInfo(defaultValue = "102") val icmsSituacao: String = "102",
    @ColumnInfo(defaultValue = "0") val origem: String = "0",
    @ColumnInfo(defaultValue = "UN") val unidade: String = "UN",
    @ColumnInfo(defaultValue = "49") val pisCofinsSituacao: String = "49",
) {
    fun paraModelo() = Produto(
        id, nome, precoCentavos, categoria, ativo, setorDe(setor),
        DadosFiscais(ncm, cfop, icmsSituacao, origem, unidade, pisCofinsSituacao),
    )

    companion object {
        fun de(p: Produto) = ProdutoEntity(
            p.id, p.nome, p.precoCentavos, p.categoria, p.ativo, p.setor.name,
            p.fiscal.ncm, p.fiscal.cfop, p.fiscal.icmsSituacao, p.fiscal.origem, p.fiscal.unidade, p.fiscal.pisCofinsSituacao,
        )
    }
}

private fun setorDe(nome: String) = SetorProducao.entries.firstOrNull { it.name == nome } ?: SetorProducao.COZINHA

@Entity(tableName = "comandas", indices = [Index("status")])
data class ComandaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identificacao: String,
    val abertaEm: Long,
    val status: String,
    val taxaServico: Boolean,
    val fechadaEm: Long?,
    // Colunas da versão 1-2, quando a comanda tinha um só pagamento. Desde a v3 os
    // pagamentos ficam na tabela `pagamentos`; a migração copia os antigos para lá.
    val pagamentoForma: String? = null,
    val pagamentoValorCentavos: Long? = null,
    val pagamentoParcelas: Int? = null,
    val pagamentoCodigoTransacao: String? = null,
    val pagamentoIdTransacao: String? = null,
    val pagamentoBandeira: String? = null,
    val pagamentoEm: Long? = null,
    // Versão 2 do banco
    @ColumnInfo(defaultValue = "0") val garcomId: Long = 0,
    @ColumnInfo(defaultValue = "") val garcomNome: String = "",
    val fechadaPor: String? = null,
    // Versão 5 do banco
    @ColumnInfo(defaultValue = "0") val pedidosEnviados: Int = 0,
    // Versão 6: controle de gravação entre aparelhos
    @ColumnInfo(defaultValue = "0") val versao: Long = 0,
    // Versão 7: NFC-e (JSON) e número de tentativas
    val nfce: String? = null,
    @ColumnInfo(defaultValue = "0") val tentativasNfce: Int = 0,
    // Versão 8: histórico de quem fez o quê (JSON)
    val historico: String? = null,
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
    // Versão 5 do banco
    @ColumnInfo(defaultValue = "COZINHA") val setor: String = SetorProducao.COZINHA.name,
    @ColumnInfo(defaultValue = "0") val quantidadeEnviada: Int = 0,
)

@Entity(
    tableName = "pagamentos",
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
data class PagamentoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comandaId: Long,
    val posicao: Int,
    val forma: String,
    val valorCentavos: Long,
    val parcelas: Int,
    val codigoTransacao: String?,
    val idTransacao: String?,
    val bandeira: String?,
    val pagoEm: Long,
    val recebidoPor: String,
    val valorRecebidoCentavos: Long?,
    val trocoCentavos: Long,
    // Versão 4 do banco
    val caixaId: Long? = null,
    val estornadoEm: Long? = null,
    val estornadoPor: String? = null,
) {
    fun paraModelo() = Pagamento(
        forma = FormaPagamento.valueOf(forma),
        valorCentavos = valorCentavos,
        parcelas = parcelas,
        codigoTransacao = codigoTransacao,
        idTransacao = idTransacao,
        bandeira = bandeira,
        pagoEm = pagoEm,
        recebidoPor = recebidoPor,
        valorRecebidoCentavos = valorRecebidoCentavos,
        trocoCentavos = trocoCentavos,
        caixaId = caixaId,
        estornadoEm = estornadoEm,
        estornadoPor = estornadoPor,
    )

    companion object {
        fun de(comandaId: Long, posicao: Int, p: Pagamento) = PagamentoEntity(
            comandaId = comandaId,
            posicao = posicao,
            forma = p.forma.name,
            valorCentavos = p.valorCentavos,
            parcelas = p.parcelas,
            codigoTransacao = p.codigoTransacao,
            idTransacao = p.idTransacao,
            bandeira = p.bandeira,
            pagoEm = p.pagoEm,
            recebidoPor = p.recebidoPor,
            valorRecebidoCentavos = p.valorRecebidoCentavos,
            trocoCentavos = p.trocoCentavos,
            caixaId = p.caixaId,
            estornadoEm = p.estornadoEm,
            estornadoPor = p.estornadoPor,
        )
    }
}

@Entity(tableName = "caixas")
data class CaixaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val abertoEm: Long,
    val abertoPor: String,
    val valorInicialCentavos: Long,
    val fechadoEm: Long?,
    val fechadoPor: String?,
    val valorContadoCentavos: Long?,
    // Versão 6: controle de gravação entre aparelhos
    @ColumnInfo(defaultValue = "0") val versao: Long = 0,
)

@Entity(
    tableName = "movimentos_caixa",
    foreignKeys = [
        ForeignKey(
            entity = CaixaEntity::class,
            parentColumns = ["id"],
            childColumns = ["caixaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("caixaId")],
)
data class MovimentoCaixaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caixaId: Long,
    val posicao: Int,
    val tipo: String,
    val valorCentavos: Long,
    val motivo: String,
    val em: Long,
    val por: String,
    val forma: String?,
)

data class CaixaComMovimentos(
    @Embedded val caixa: CaixaEntity,
    @Relation(parentColumn = "id", entityColumn = "caixaId")
    val movimentos: List<MovimentoCaixaEntity>,
) {
    fun paraModelo() = Caixa(
        id = caixa.id,
        abertoEm = caixa.abertoEm,
        abertoPor = caixa.abertoPor,
        valorInicialCentavos = caixa.valorInicialCentavos,
        movimentos = movimentos.sortedBy { it.posicao }.map {
            MovimentoCaixa(
                tipo = TipoMovimento.valueOf(it.tipo),
                valorCentavos = it.valorCentavos,
                motivo = it.motivo,
                em = it.em,
                por = it.por,
                forma = it.forma?.let(FormaPagamento::valueOf),
            )
        },
        fechadoEm = caixa.fechadoEm,
        fechadoPor = caixa.fechadoPor,
        valorContadoCentavos = caixa.valorContadoCentavos,
        versao = caixa.versao,
    )
}

fun Caixa.paraEntidade() = CaixaEntity(
    id = id,
    abertoEm = abertoEm,
    abertoPor = abertoPor,
    valorInicialCentavos = valorInicialCentavos,
    fechadoEm = fechadoEm,
    fechadoPor = fechadoPor,
    valorContadoCentavos = valorContadoCentavos,
    versao = versao,
)

data class ComandaComItens(
    @Embedded val comanda: ComandaEntity,
    @Relation(parentColumn = "id", entityColumn = "comandaId")
    val itens: List<ItemComandaEntity>,
    @Relation(parentColumn = "id", entityColumn = "comandaId")
    val pagamentos: List<PagamentoEntity>,
) {
    fun paraModelo(): Comanda {
        val c = comanda
        return Comanda(
            id = c.id,
            identificacao = c.identificacao,
            abertaEm = c.abertaEm,
            itens = itens.sortedBy { it.posicao }.map {
                ItemComanda(
                    produtoId = it.produtoId,
                    nome = it.nome,
                    precoUnitarioCentavos = it.precoUnitarioCentavos,
                    quantidade = it.quantidade,
                    observacao = it.observacao,
                    setor = setorDe(it.setor),
                    quantidadeEnviada = it.quantidadeEnviada,
                )
            },
            status = StatusComanda.valueOf(c.status),
            taxaServico = c.taxaServico,
            fechadaEm = c.fechadaEm,
            pagamentos = pagamentos.sortedBy { it.posicao }.map { it.paraModelo() },
            garcomId = c.garcomId,
            garcomNome = c.garcomNome,
            fechadaPor = c.fechadaPor,
            pedidosEnviados = c.pedidosEnviados,
            versao = c.versao,
            nfce = c.nfce?.let { runCatching { json.decodeFromString(NotaFiscalConsumidor.serializer(), it) }.getOrNull() },
            tentativasNfce = c.tentativasNfce,
            historico = c.historico?.let {
                runCatching { json.decodeFromString(ListSerializer(EventoComanda.serializer()), it) }.getOrNull()
            }.orEmpty(),
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
    garcomId = garcomId,
    garcomNome = garcomNome,
    fechadaPor = fechadaPor,
    pedidosEnviados = pedidosEnviados,
    versao = versao,
    nfce = nfce?.let { json.encodeToString(NotaFiscalConsumidor.serializer(), it) },
    tentativasNfce = tentativasNfce,
    historico = historico.takeIf { it.isNotEmpty() }?.let { json.encodeToString(ListSerializer(EventoComanda.serializer()), it) },
)

private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@Entity(tableName = "usuarios")
data class UsuarioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    /** Nomes das permissões separados por vírgula. */
    val permissoes: String,
    val ativo: Boolean,
    val pinHash: String,
    val pinSalt: String,
) {
    fun paraModelo() = Usuario(
        id = id,
        nome = nome,
        permissoes = permissoes.split(',').filter { it.isNotBlank() }
            .mapNotNull { nome -> Permissao.entries.firstOrNull { it.name == nome } }.toSet(),
        ativo = ativo,
        pinHash = pinHash,
        pinSalt = pinSalt,
    )

    companion object {
        fun de(u: Usuario) = UsuarioEntity(
            id = u.id,
            nome = u.nome,
            permissoes = u.permissoes.sortedBy { it.ordinal }.joinToString(",") { it.name },
            ativo = u.ativo,
            pinHash = u.pinHash,
            pinSalt = u.pinSalt,
        )
    }
}
