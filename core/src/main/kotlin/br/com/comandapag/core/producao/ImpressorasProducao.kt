package br.com.comandapag.core.producao

import br.com.comandapag.core.modelo.SetorProducao
import br.com.comandapag.core.pagamento.Impressora

/** Diz em qual impressora sai o ticket de cada setor (a da maquininha ou uma de rede). */
fun interface ImpressorasProducao {
    fun para(setor: SetorProducao): Impressora
}
