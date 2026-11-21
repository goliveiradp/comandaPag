package br.com.comandapag.dados

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProdutoEntity::class,
        ComandaEntity::class,
        ItemComandaEntity::class,
        UsuarioEntity::class,
        PagamentoEntity::class,
        CaixaEntity::class,
        MovimentoCaixaEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class BancoDeDados : RoomDatabase() {
    abstract fun produtoDao(): ProdutoDao
    abstract fun comandaDao(): ComandaDao
    abstract fun usuarioDao(): UsuarioDao
    abstract fun caixaDao(): CaixaDao

    companion object {
        const val NOME = "comandapag.db"

        /** v2: usuários com PIN e permissões; garçom responsável e quem fechou a comanda. */
        val MIGRACAO_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usuarios` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`nome` TEXT NOT NULL, `permissoes` TEXT NOT NULL, `ativo` INTEGER NOT NULL, " +
                        "`pinHash` TEXT NOT NULL, `pinSalt` TEXT NOT NULL)",
                )
                db.execSQL("ALTER TABLE `comandas` ADD COLUMN `garcomId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `comandas` ADD COLUMN `garcomNome` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `comandas` ADD COLUMN `fechadaPor` TEXT")
            }
        }

        /**
         * v3: vários pagamentos por comanda (divisão de conta, dinheiro com troco).
         * O pagamento único das versões anteriores é copiado para a nova tabela.
         */
        val MIGRACAO_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pagamentos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`comandaId` INTEGER NOT NULL, `posicao` INTEGER NOT NULL, `forma` TEXT NOT NULL, " +
                        "`valorCentavos` INTEGER NOT NULL, `parcelas` INTEGER NOT NULL, `codigoTransacao` TEXT, " +
                        "`idTransacao` TEXT, `bandeira` TEXT, `pagoEm` INTEGER NOT NULL, `recebidoPor` TEXT NOT NULL, " +
                        "`valorRecebidoCentavos` INTEGER, `trocoCentavos` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`comandaId`) REFERENCES `comandas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pagamentos_comandaId` ON `pagamentos` (`comandaId`)")
                db.execSQL(
                    "INSERT INTO `pagamentos` (`comandaId`, `posicao`, `forma`, `valorCentavos`, `parcelas`, " +
                        "`codigoTransacao`, `idTransacao`, `bandeira`, `pagoEm`, `recebidoPor`, " +
                        "`valorRecebidoCentavos`, `trocoCentavos`) " +
                        "SELECT `id`, 0, `pagamentoForma`, COALESCE(`pagamentoValorCentavos`, 0), " +
                        "COALESCE(`pagamentoParcelas`, 1), `pagamentoCodigoTransacao`, `pagamentoIdTransacao`, " +
                        "`pagamentoBandeira`, COALESCE(`pagamentoEm`, `fechadaEm`, `abertaEm`), " +
                        "COALESCE(`fechadaPor`, ''), NULL, 0 " +
                        "FROM `comandas` WHERE `pagamentoForma` IS NOT NULL",
                )
                db.execSQL(
                    "UPDATE `comandas` SET `pagamentoForma` = NULL, `pagamentoValorCentavos` = NULL, " +
                        "`pagamentoParcelas` = NULL, `pagamentoCodigoTransacao` = NULL, `pagamentoIdTransacao` = NULL, " +
                        "`pagamentoBandeira` = NULL, `pagamentoEm` = NULL",
                )
            }
        }

        /**
         * v4: caixa (abertura, sangria, suprimento, fechamento) e estorno de pagamentos.
         * Usuários existentes ganham as permissões novas conforme o que já podiam fazer:
         * quem cancelava comandas (gerente) passa a estornar; quem via relatórios (caixa,
         * gerente) passa a operar o caixa; administradores ganham as duas.
         */
        val MIGRACAO_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pagamentos` ADD COLUMN `caixaId` INTEGER")
                db.execSQL("ALTER TABLE `pagamentos` ADD COLUMN `estornadoEm` INTEGER")
                db.execSQL("ALTER TABLE `pagamentos` ADD COLUMN `estornadoPor` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `caixas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`abertoEm` INTEGER NOT NULL, `abertoPor` TEXT NOT NULL, `valorInicialCentavos` INTEGER NOT NULL, " +
                        "`fechadoEm` INTEGER, `fechadoPor` TEXT, `valorContadoCentavos` INTEGER)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `movimentos_caixa` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`caixaId` INTEGER NOT NULL, `posicao` INTEGER NOT NULL, `tipo` TEXT NOT NULL, " +
                        "`valorCentavos` INTEGER NOT NULL, `motivo` TEXT NOT NULL, `em` INTEGER NOT NULL, " +
                        "`por` TEXT NOT NULL, `forma` TEXT, " +
                        "FOREIGN KEY(`caixaId`) REFERENCES `caixas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movimentos_caixa_caixaId` ON `movimentos_caixa` (`caixaId`)")

                fun tem(permissao: String) = "(',' || `permissoes` || ',') LIKE '%,$permissao,%'"
                db.execSQL(
                    "UPDATE `usuarios` SET `permissoes` = `permissoes` || ',ESTORNAR' " +
                        "WHERE ${tem("CANCELAR_COMANDA")} OR ${tem("GERENCIAR_USUARIOS")}",
                )
                db.execSQL(
                    "UPDATE `usuarios` SET `permissoes` = `permissoes` || ',OPERAR_CAIXA' " +
                        "WHERE ${tem("VER_RELATORIOS")} OR ${tem("GERENCIAR_USUARIOS")}",
                )
            }
        }

        /**
         * v5: setor de produção (cozinha/bar) nos produtos e itens, e controle do que já foi
         * enviado. Itens de comandas existentes contam como já enviados, para não reimprimir.
         */
        val MIGRACAO_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `produtos` ADD COLUMN `setor` TEXT NOT NULL DEFAULT 'COZINHA'")
                db.execSQL("ALTER TABLE `itens_comanda` ADD COLUMN `setor` TEXT NOT NULL DEFAULT 'COZINHA'")
                db.execSQL("ALTER TABLE `itens_comanda` ADD COLUMN `quantidadeEnviada` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `itens_comanda` SET `quantidadeEnviada` = `quantidade`")
                db.execSQL("ALTER TABLE `comandas` ADD COLUMN `pedidosEnviados` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun criar(context: Context): BancoDeDados =
            Room.databaseBuilder(context, BancoDeDados::class.java, NOME)
                .addMigrations(MIGRACAO_1_2, MIGRACAO_2_3, MIGRACAO_3_4, MIGRACAO_4_5)
                .build()
    }
}
