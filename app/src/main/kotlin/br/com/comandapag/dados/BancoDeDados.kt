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
    ],
    version = 3,
    exportSchema = true,
)
abstract class BancoDeDados : RoomDatabase() {
    abstract fun produtoDao(): ProdutoDao
    abstract fun comandaDao(): ComandaDao
    abstract fun usuarioDao(): UsuarioDao

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

        fun criar(context: Context): BancoDeDados =
            Room.databaseBuilder(context, BancoDeDados::class.java, NOME)
                .addMigrations(MIGRACAO_1_2, MIGRACAO_2_3)
                .build()
    }
}
