package br.com.comandapag.core.modelo

import kotlinx.serialization.Serializable

/** O que cada usuário pode fazer no app. */
enum class Permissao(val descricao: String) {
    COBRAR("Cobrar comandas na maquininha"),
    REMOVER_ITENS("Diminuir ou remover itens já lançados"),
    CANCELAR_COMANDA("Cancelar comandas"),
    ALTERAR_TAXA_SERVICO("Ligar ou desligar os 10% de serviço"),
    VER_RELATORIOS("Ver histórico e vendas"),
    ESTORNAR("Estornar pagamentos"),
    OPERAR_CAIXA("Abrir e fechar o caixa, sangria e suprimento"),
    GERENCIAR_PRODUTOS("Cadastrar e editar produtos"),
    GERENCIAR_USUARIOS("Cadastrar usuários e permissões"),
    CONFIGURAR("Ajustes do estabelecimento e da maquininha"),
}

/** Conjuntos de permissões prontos, para facilitar o cadastro. */
enum class Perfil(val descricao: String, val permissoes: Set<Permissao>) {
    GARCOM("Garçom", setOf(Permissao.COBRAR)),
    CAIXA("Caixa", setOf(Permissao.COBRAR, Permissao.VER_RELATORIOS, Permissao.OPERAR_CAIXA)),
    GERENTE(
        "Gerente",
        setOf(
            Permissao.COBRAR,
            Permissao.REMOVER_ITENS,
            Permissao.CANCELAR_COMANDA,
            Permissao.ALTERAR_TAXA_SERVICO,
            Permissao.VER_RELATORIOS,
            Permissao.ESTORNAR,
            Permissao.OPERAR_CAIXA,
            Permissao.GERENCIAR_PRODUTOS,
        ),
    ),
    ADMINISTRADOR("Administrador", Permissao.entries.toSet()),
    ;

    companion object {
        /** Perfil que corresponde exatamente às permissões, ou null se for personalizado. */
        fun de(permissoes: Set<Permissao>): Perfil? = entries.firstOrNull { it.permissoes == permissoes }
    }
}

@Serializable
data class Usuario(
    val id: Long = 0,
    val nome: String,
    val permissoes: Set<Permissao>,
    val ativo: Boolean = true,
    /** Hash do PIN (nunca guardamos o PIN em texto). */
    val pinHash: String = "",
    val pinSalt: String = "",
) {
    fun pode(permissao: Permissao): Boolean = permissao in permissoes

    val descricaoPerfil: String get() = Perfil.de(permissoes)?.descricao ?: "Personalizado"
}
