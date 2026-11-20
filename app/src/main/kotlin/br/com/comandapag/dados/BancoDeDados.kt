package br.com.comandapag.dados

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProdutoEntity::class, ComandaEntity::class, ItemComandaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BancoDeDados : RoomDatabase() {
    abstract fun produtoDao(): ProdutoDao
    abstract fun comandaDao(): ComandaDao

    companion object {
        fun criar(context: Context): BancoDeDados =
            Room.databaseBuilder(context, BancoDeDados::class.java, "comandapag.db").build()
    }
}
