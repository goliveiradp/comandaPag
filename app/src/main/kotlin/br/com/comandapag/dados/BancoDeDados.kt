package br.com.comandapag.dados

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProdutoEntity::class, ComandaEntity::class, ItemComandaEntity::class, UsuarioEntity::class],
    version = 2,
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

        fun criar(context: Context): BancoDeDados =
            Room.databaseBuilder(context, BancoDeDados::class.java, NOME)
                .addMigrations(MIGRACAO_1_2)
                .build()
    }
}
