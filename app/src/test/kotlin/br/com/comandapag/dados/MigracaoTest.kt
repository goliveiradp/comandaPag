package br.com.comandapag.dados

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import br.com.comandapag.core.modelo.FormaPagamento
import br.com.comandapag.core.modelo.StatusComanda
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Garante que quem já usava uma versão anterior do app não perde as comandas ao atualizar. */
@RunWith(RobolectricTestRunner::class)
class MigracaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun migraDaVersao1AteAAtualMantendoOsDados() = runBlocking {
        val arquivo = context.getDatabasePath("migracao-teste.db").apply { parentFile?.mkdirs(); delete() }

        // Banco exatamente como a versão 1 do app criava
        SQLiteDatabase.openOrCreateDatabase(arquivo, null).use { db ->
            V1.forEach(db::execSQL)
            db.execSQL("INSERT INTO comandas (id, identificacao, abertaEm, status, taxaServico) VALUES (1, 'Mesa 3', 100, 'ABERTA', 1)")
            // Comanda paga na v1 (pagamento único nas colunas da própria comanda)
            db.execSQL(
                "INSERT INTO comandas (id, identificacao, abertaEm, status, taxaServico, fechadaEm, pagamentoForma, " +
                    "pagamentoValorCentavos, pagamentoParcelas, pagamentoCodigoTransacao, pagamentoIdTransacao, " +
                    "pagamentoBandeira, pagamentoEm) VALUES (2, 'Mesa 5', 100, 'PAGA', 0, 200, 'CREDITO', 1500, 3, " +
                    "'TX9', 'ID9', 'VISA', 200)",
            )
            db.execSQL(
                "INSERT INTO itens_comanda (comandaId, posicao, produtoId, nome, precoUnitarioCentavos, quantidade, observacao) " +
                    "VALUES (1, 0, 7, 'Cerveja', 1200, 2, '')",
            )
            db.version = 1
        }

        val banco = Room.databaseBuilder(context, BancoDeDados::class.java, arquivo.absolutePath)
            .addMigrations(BancoDeDados.MIGRACAO_1_2, BancoDeDados.MIGRACAO_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val comanda = ComandaRepositoryRoom(banco.comandaDao()).buscar(1)!!
            assertEquals("Mesa 3", comanda.identificacao)
            assertEquals(StatusComanda.ABERTA, comanda.status)
            assertEquals(2640, comanda.totalCentavos)
            assertEquals("", comanda.garcomNome)
            assertNull(comanda.fechadaPor)
            assertTrue(comanda.pagamentos.isEmpty())
            assertTrue(UsuarioRepositoryRoom(banco.usuarioDao()).listarTodos().isEmpty())

            // O pagamento antigo foi para a tabela nova
            val paga = ComandaRepositoryRoom(banco.comandaDao()).buscar(2)!!
            val p = paga.pagamentos.single()
            assertEquals(FormaPagamento.CREDITO, p.forma)
            assertEquals(1500, p.valorCentavos)
            assertEquals(3, p.parcelas)
            assertEquals("TX9", p.codigoTransacao)
            assertEquals("VISA", p.bandeira)
            assertEquals(200, p.pagoEm)
            assertEquals(0, paga.restanteCentavos)
        } finally {
            banco.close()
        }
    }

    @Test
    fun migracao2Para3BateComOEsquemaExportado() {
        // Cria o banco a partir do esquema v2 exportado e valida a migração contra o esquema v3
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BancoDeDados::class.java)
        val nome = "migracao-2-3.db"
        helper.createDatabase(nome, 2).use { db ->
            db.execSQL(
                "INSERT INTO comandas (id, identificacao, abertaEm, status, taxaServico, pagamentoForma, " +
                    "pagamentoValorCentavos, pagamentoEm, garcomId, garcomNome, fechadaPor) " +
                    "VALUES (7, 'Mesa 7', 1, 'PAGA', 0, 'PIX', 990, 2, 3, 'Ana', 'Ana')",
            )
        }
        helper.runMigrationsAndValidate(nome, 3, true, BancoDeDados.MIGRACAO_2_3).use { db ->
            db.query("SELECT forma, valorCentavos, recebidoPor FROM pagamentos WHERE comandaId = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("PIX", c.getString(0))
                assertEquals(990, c.getLong(1))
                assertEquals("Ana", c.getString(2))
            }
            db.query("SELECT pagamentoForma FROM comandas WHERE id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
        }
    }

    private companion object {
        val V1 = listOf(
            "CREATE TABLE IF NOT EXISTS `produtos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nome` TEXT NOT NULL, `precoCentavos` INTEGER NOT NULL, `categoria` TEXT NOT NULL, `ativo` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `comandas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `identificacao` TEXT NOT NULL, `abertaEm` INTEGER NOT NULL, `status` TEXT NOT NULL, `taxaServico` INTEGER NOT NULL, `fechadaEm` INTEGER, `pagamentoForma` TEXT, `pagamentoValorCentavos` INTEGER, `pagamentoParcelas` INTEGER, `pagamentoCodigoTransacao` TEXT, `pagamentoIdTransacao` TEXT, `pagamentoBandeira` TEXT, `pagamentoEm` INTEGER)",
            "CREATE TABLE IF NOT EXISTS `itens_comanda` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `comandaId` INTEGER NOT NULL, `posicao` INTEGER NOT NULL, `produtoId` INTEGER NOT NULL, `nome` TEXT NOT NULL, `precoUnitarioCentavos` INTEGER NOT NULL, `quantidade` INTEGER NOT NULL, `observacao` TEXT NOT NULL, FOREIGN KEY(`comandaId`) REFERENCES `comandas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_comandas_status` ON `comandas` (`status`)",
            "CREATE INDEX IF NOT EXISTS `index_itens_comanda_comandaId` ON `itens_comanda` (`comandaId`)",
        )
    }
}
