package br.com.comandapag.terminal

import br.com.comandapag.core.pagamento.Impressora
import br.com.comandapag.core.pagamento.TerminalPagamento

/**
 * Agrupa o terminal de pagamento e a impressora.
 * Cada flavor (smart / simulador) fornece sua própria função `criarMaquininha(context)`.
 */
class Maquininha(
    val terminal: TerminalPagamento,
    val impressora: Impressora,
    val simulada: Boolean,
)
