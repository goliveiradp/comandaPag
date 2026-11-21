package br.com.comandapag.core.fiscal

/**
 * Emissor de mentira para testar o fluxo sem conta na Focus NFe nem Sefaz.
 * As notas saem marcadas como sem valor fiscal.
 */
class EmissorNfceSimulado(private val emitente: () -> Emitente) : EmissorNfce {
    private val notas = mutableMapOf<String, RespostaNfce>()
    private var numero = 0

    override suspend fun status() = StatusEmissor(ativo = true, ambiente = AmbienteFiscal.SIMULADO, emitente = emitente())

    override suspend fun emitir(ref: String, nota: NotaNfce): RespostaNfce = synchronized(this) {
        notas[ref]?.let { throw NotaJaEmitidaException() }
        numero++
        val chave = ("21" + "2611" + emitente().cnpj.filter(Char::isDigit).padStart(14, '0') + "65" + "001" +
            numero.toString().padStart(9, '0') + "1" + numero.toString().padStart(8, '0')).take(43).padEnd(43, '0') + "0"
        RespostaNfce(
            autorizada = true,
            mensagem = "Autorizado (simulado, sem valor fiscal)",
            numero = numero.toString(),
            serie = "1",
            chave = chave,
            qrCodeUrl = "http://www.nfce.sefaz.ma.gov.br/portal/consultarNFCe.jsp?p=$chave|2|2|1|SIMULADO",
            urlConsulta = "http://www.nfce.sefaz.ma.gov.br/portal/consultaNFe.do",
            protocolo = "SIMULADO${numero.toString().padStart(7, '0')}",
            ambiente = AmbienteFiscal.SIMULADO,
            emitente = emitente(),
        ).also { notas[ref] = it }
    }

    override suspend fun consultar(ref: String): RespostaNfce? = notas[ref]

    override suspend fun cancelar(ref: String, justificativa: String) =
        RespostaCancelamento(cancelada = notas.containsKey(ref), mensagem = "Cancelamento simulado")
}
