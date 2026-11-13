# Nekkora Agent (Client)

Aplicativo desktop do **cliente** Nekkora. Na tela aparece como "Nekkora Agent".

Este repositório é separado do `Nekkora-Agent` (administrativo) de propósito: aqui **não há** telas, comandos ou módulos
de importação, empacotamento, execução, catálogo, pareamento, operação global ou gestão de outros clientes. O código do
outro repositório não foi copiado.

Separar o binário delimita o produto, mas **não é a fronteira de autorização**: quem nega funções administrativas é a API,
mesmo a um Client alterado.

## O que existe hoje

- Uma janela com a tela de entrada: logo, **Entrar** e, abaixo, o andamento ("Aguardando login no navegador...",
  **Cancelar**) ou a mensagem da última tentativa.
- Dois temas, **Sakura Dark** (padrão) e **Nekkora Light**, com os tokens e logos da Nekkora-lib
  ([origem e mapeamento](docs/design/tokens-e-ativos.md)). A troca é o botão de meio círculo na barra de título.
- Depois da entrada, uma tela inicial **vazia**.
- Em disco, só a preferência de tema. Nenhum login é guardado: ao fechar e abrir de novo, **Entrar** volta a ser
  necessário.

Senha e código de verificação nunca são digitados no aplicativo: a entrada acontece no navegador, na conta Nekkora.

## Estrutura

| Pacote | Conteúdo | Pode usar |
|---|---|---|
| `com.nekkora.client.theme` | os dois temas e os tokens | nada de interface |
| `com.nekkora.client.storage` | pasta de dados própria, preferência de tema, instância única | nada de interface |
| `com.nekkora.client.signin` | estados e regras da entrada (uma tentativa por vez, resposta atrasada não entra) | nada de interface nem de rede |
| `com.nekkora.client.auth` | entrada real pelo navegador (retorno local, PKCE, troca com a Platform) | rede; nada de interface |
| `com.nekkora.client.ui` | janela, tela de entrada, tela inicial, componentes | Swing e FlatLaf |

O teste `ArchitectureTest` confere essas fronteiras, a dependência única de execução (FlatLaf) e a ausência de código
administrativo, de login simulado e de qualquer endereço ou certificado de laboratório.

## Isolamento do Nekkora Agent

- Pasta de dados: `%LOCALAPPDATA%\Nekkora\AgentClient`. A pasta do Nekkora Agent (`...\Nekkora\Agent`) é recusada mesmo se
  for pedida por configuração.
- Instância única por arquivo próprio (`client.lock`). Os dois aplicativos podem ficar abertos ao mesmo tempo.
- Abrir o Client não inicia o Nekkora Agent, nem tarefa, automação ou pareamento.

## Construir e testar

Requer JDK 21. O Maven vem pelo wrapper.

```powershell
.\mvnw.cmd verify             # testes sem janela
.\mvnw.cmd verify -Pdesktop   # inclui os testes de janela
```

Os testes de janela abrem a janela do aplicativo e entregam os eventos **a ela** (`doClick`, `dispatchEvent`). Não usam
`Robot` nem enviam teclas ao sistema. As imagens das telas saem em `target\evidencias\`, pintadas em memória nas escalas
100%, 150% e 200%.

A entrada simulada usada nesses testes (`FakeSignIn`) fica em `src/test` e não entra no aplicativo.

## Executar em desenvolvimento

```powershell
.\mvnw.cmd -q package -DskipTests
java -cp "target\nekkora-agent-client.jar;target\lib\*" com.nekkora.client.ClientMain
```

Para não usar a pasta de dados padrão: `$env:NEKKORA_AGENT_CLIENT_DATA_DIR = 'C:\caminho\de\teste'`.

## Empacotar para Windows

```powershell
powershell -ExecutionPolicy Bypass -File tools\package-windows.ps1
```

Gera `target\dist\nekkora-agent-client-<versão>-windows-x64-teste.zip`, o `.sha256` e `VERSOES.txt` (commit, bibliotecas
e runtime). É um pacote de **teste**, sem assinatura digital. Nada é instalado: é uma pasta com o executável e o runtime.

## Fluxo de trabalho

Branches de tarefa a partir de `dev`, com PR para `dev`. A `main` recebe só promoções.
