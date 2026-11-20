# comandaPag

App de **comandas** para bares, lanchonetes e restaurantes que roda direto na
maquininha **PagBank Smart 2** (Android) e cobra pela própria maquininha usando a
biblioteca oficial [PlugPagServiceWrapper](https://github.com/pagseguro/pagseguro-sdk-plugpagservicewrapper).

![Telas do app](docs/telas.png)

*Capturas geradas pelo teste de interface com a maquininha simulada.*

## Funcionalidades

- **Produtos**: cadastro do cardápio (nome, preço e categoria).
- **Comandas**: abrir por número, mesa ou nome do cliente; lançar itens com
  quantidade e observação; alterar/remover itens; taxa de serviço de 10% opcional.
- **Cobrança na maquininha**: crédito (à vista ou parcelado em até 12x), débito e PIX.
  Se aprovado, a comanda é fechada automaticamente.
- **Impressão**: extrato da comanda na impressora térmica da Smart 2.
- **Histórico**: comandas pagas/canceladas e resumo das vendas do dia por forma de pagamento.
- **Ajustes**: nome do estabelecimento (sai no extrato), taxa de serviço padrão e
  ativação da maquininha.
- Funciona **offline**: os dados ficam no banco local (Room/SQLite) da maquininha.

## Estrutura do projeto

```
core/   Regras de negócio em Kotlin puro (sem Android) + testes
        ├─ modelo/       Produto, Comanda, ItemComanda, Pagamento, Dinheiro
        ├─ servico/      ComandaService (abrir, lançar itens, cobrar, cancelar)
        ├─ pagamento/    Interfaces TerminalPagamento e Impressora
        ├─ repositorio/  Interfaces de persistência
        └─ impressao/    ExtratoComanda (texto do cupom em 32 colunas)
app/    App Android (Jetpack Compose + Room)
        ├─ src/main       Telas, banco de dados, renderização do cupom
        ├─ src/smart      Integração real com o PlugPag (PagBank)
        ├─ src/simulador  Maquininha simulada para emulador/celular
        └─ src/testSimulador  Teste de interface do fluxo completo (Robolectric)
```

O app tem dois *flavors*:

| Flavor      | Onde roda               | Cobrança                                    |
|-------------|-------------------------|---------------------------------------------|
| `smart`     | PagBank Smart 2         | Real, via PlugPag                           |
| `simulador` | Emulador ou celular     | Simulada (valores terminados em ,99 são recusados, para testar o erro) |

## Como compilar

Requisitos: Android Studio (ou JDK 17+ e Android SDK 35).

```bash
# Testes das regras de negócio
./gradlew :core:test

# APK para testar no emulador/celular (sem cobrança real)
./gradlew :app:assembleSimuladorDebug

# APK para a maquininha PagBank Smart 2
./gradlew :app:assembleSmartDebug

# Teste de interface do fluxo completo (gera capturas de tela em app/build/capturas)
./gradlew :app:testSimuladorDebugUnitTest
```

Os APKs ficam em `app/build/outputs/apk/<flavor>/debug/`.

## Instalando na Smart 2

A PagBank só permite apps de terceiros nas maquininhas depois de **homologação**:

1. Desenvolva e teste com o flavor `simulador` no emulador/celular.
2. Peça à PagBank, pelo portal de desenvolvedores, um terminal de
   **desenvolvimento** (ou a liberação do seu para depuração).
3. Instale o APK `smart` com `adb install` nesse terminal e teste pagamentos reais.
4. Envie o APK `smart` (release, assinado) para homologação. A PagBank distribui
   o app para as maquininhas.

## Detalhes da integração PagBank

- A biblioteca vem do repositório Maven da PagBank, configurado em
  `settings.gradle.kts` (versão `1.35.0`).
- A permissão `br.com.uol.pagseguro.permission.MANAGE_PAYMENTS` é adicionada
  automaticamente pelo manifesto da biblioteca.
- Os valores vão em centavos; a referência da venda é `CMD<id da comanda>`
  (a PagBank limita a 10 caracteres).
- A via do estabelecimento é impressa pela própria PagBank (`printReceipt = true`);
  o extrato da comanda é impresso pelo app como imagem (`printFromFile`).
- Toda a integração está em `app/src/smart/kotlin/br/com/comandapag/terminal/PlugPagTerminal.kt`.
