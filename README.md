# Nekkora Agent (Client)

Aplicativo desktop do **cliente** Nekkora. Na tela aparece como "Nekkora Agent".

Este repositório é separado do `Nekkora-Agent` (administrativo) de propósito: aqui **não há** telas, comandos ou módulos
de catálogo da máquina, pareamento, monitoramento, gatilhos remotos, operação global ou gestão de outros clientes.

O que o cliente faz no próprio computador (importar um pacote, preparar um pacote a partir de código) reaproveita só o
núcleo necessário do outro repositório: a validação de ZIP, o validador do pacote e o empacotador. A origem exata, classe
por classe, está em `THIRD-PARTY-NOTICES.md`. O assistente, a integração com a Platform pela credencial de máquina e as
telas do Agent administrativo **não** vieram.

Separar o binário delimita o produto, mas **não é a fronteira de autorização**: quem nega funções administrativas é a API,
mesmo a um Client alterado.

## O que existe hoje

- Uma janela com a tela de entrada: logo, **Entrar** e, abaixo, o andamento ("Aguardando login no navegador...",
  **Cancelar**) ou a mensagem do resultado da última tentativa (tabela em `docs/login-pelo-navegador.md`, seção 11).
- No pé da tela de entrada, a versão do aplicativo ("Versão 0.1.0"). O número vem de uma única fonte, o `<version>` do
  `pom.xml`: o build o grava em `build.properties`, dentro do JAR, e a tela lê dali (`BuildInfo`). Numa execução sem
  esses metadados a linha diz "Versão de desenvolvimento".
- Abaixo da versão, "© <ano> Nekkora. Todos os direitos reservados.", com o ano em que o build foi feito (gravado nos
  mesmos metadados a cada build). É só identificação visual: as licenças dos componentes continuam em
  `THIRD-PARTY-NOTICES.md`.
- Dois temas, **Sakura Dark** (padrão) e **Nekkora Light**, com os tokens e logos da Nekkora-lib
  ([origem e mapeamento](docs/design/tokens-e-ativos.md)). A troca é o botão de meio círculo na barra de título.
- **Entrar** abre o navegador padrão na Platform; o aplicativo espera o retorno local e só abre a tela inicial depois
  que a Platform confirma ([contrato da entrada](docs/login-pelo-navegador.md)).
- Depois da entrada, o **seletor de organizações**: cartões em duas colunas (uma só quando a largura não comporta), na
  ordem da Platform, com rolagem só na área dos cartões. A escolha é sempre explícita, mesmo com uma organização só.
  Carregando, falha (com **Tentar novamente**) e "nenhuma organização" são estados diferentes.
- Escolhida a organização: o seletor **Ambiente** (o workspace da Platform; só o rótulo muda) e a lista de automações
  do ambiente, com a versão publicada (número da Platform) e a versão declarada no pacote. Nada é escolhido sozinho, e
  trocar de contexto apaga o que dependia do anterior. **Nova automação** e **Importar (.zip)** ficam fora da área
  rolável.
- **Importar (.zip)** traz um pacote pronto para a área local do ambiente escolhido; **Nova automação** prepara um pacote
  local a partir de um `.zip` de código. As duas são locais: nada vai para a Platform, nada é executado, nenhuma
  dependência é instalada e não é preciso máquina pareada. Os pacotes ficam separados por organização e ambiente, na
  pasta de dados deste aplicativo.
- **Editar** renomeia. Automação da Platform: o servidor confere a permissão e grava; o pacote e as versões publicadas
  não mudam. Item que só existe neste computador: muda só o nome mostrado aqui. O formulário diz o alcance antes do campo.
- **Executar** é só local e nunca começa sozinho: abre a preparação (pacote conferido, Python escolhido por você e
  conferido contra o pacote, pasta de saída, aprovação explícita daquele conteúdo) e só **Iniciar execução** lança o
  processo. Nada é instalado. Nada vai para a Platform: sem registro de execução, monitoramento ou gatilho remoto.
- **Estado da integração:** este aplicativo não tem vínculo de máquina com a Platform, e a tela diz "Somente local —
  integração com a Platform não configurada". O pareamento fica para uma etapa futura.
- Com uma execução em andamento, trocar de organização ou de ambiente e terminar a sessão ficam bloqueados; sair pede
  confirmação antes de encerrar o processo.
- Cada linha diz se o pacote está neste computador. Listar os metadados na Platform não traz o código.
- No topo, o **avatar** com as iniciais do nome (o contrato não tem foto) e o menu: nome, Configurações e Ajuda
  (desabilitadas nesta etapa), **Terminar sessão** e **Sair**. Terminar sessão descarta o token, pede o encerramento à
  Platform e volta para a entrada; Sair encerra o aplicativo.
- As consultas usam o token da sessão do aplicativo, com o escopo `identity navigation`, nas rotas
  `/api/v1/desktop/me` e `/api/v1/desktop/organizations` (contrato `desktop-navigation/1`, na API). O aplicativo não
  declara organização, papel nem conta: quem decide o que aparece é o servidor, a cada consulta.
- Em disco, só a preferência de tema. Nenhum login é guardado: ao fechar e abrir de novo, **Entrar** volta a ser
  necessário.

Senha e código de verificação nunca são digitados no aplicativo: a entrada acontece no navegador, na conta Nekkora.
A sessão dura 15 minutos, sem renovação, e fica só em memória.

## Estrutura

| Pacote | Conteúdo | Pode usar |
|---|---|---|
| `com.nekkora.client.theme` | os dois temas e os tokens | nada de interface |
| `com.nekkora.client.storage` | pasta de dados própria, preferência de tema, instância única | nada de interface |
| `com.nekkora.client.signin` | estados e regras da entrada (uma tentativa por vez, resposta atrasada não entra) | nada de interface nem de rede |
| `com.nekkora.client.auth` | entrada real pelo navegador (retorno local, PKCE, troca com a Platform) e as consultas da pessoa | rede; nada de interface |
| `com.nekkora.client.nav` | o que a pessoa vê depois de entrar (perfil, organizações, escolha) e a porta das consultas | nada de interface nem de rede |
| `com.nekkora.client.pack` | área local de pacotes: validação de ZIP, validador e impressão digital do pacote, empacotador, guarda por organização e ambiente | arquivos; nada de interface nem de rede |
| `com.nekkora.client.run` | execução local: sondagem do Python, aprovações, lançamento sem shell, cancelamento, registros | processos e arquivos; nada de interface nem de rede |
| `com.nekkora.client.ui` | janela, tela de entrada, seletor de organizações, avatar e menu, componentes | Swing e FlatLaf |

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
