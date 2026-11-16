# Execução remota: habilitação deste computador

PLATFORM-CLIENT-EXECUCAO-REMOTA-001. O vínculo deste computador com a Platform (T01a), a continuidade da credencial
(T01b), o inventário de Python (T01c), a entrega de uma tentativa (T02) e a **execução do que a Platform solicitar** (T03):
ver "Entrega e execução de uma tentativa".

Contrato do servidor: `docs/platform-client-execucao-remota-001/t01a-habilitacao-e-elegibilidade.md`, no repositório da API.

## Dois atos distintos

| Ato | O que é | Onde |
|---|---|---|
| **Entrar** | A pessoa, com a conta dela, pelo navegador | camadas `auth` e `signin` |
| **Habilitar este computador** | A máquina, com um código criado na Platform por quem administra as máquinas, e a confirmação explícita aqui | camada `remote` |

Entrar não habilita. Sair não desabilita. Fechar o aplicativo não desabilita (só a presença para). A camada `remote` não
recebe, não lê e não guarda a sessão da pessoa; o token de quem entrou nunca vira credencial de máquina.

## Habilitar

Menu da conta → **Execução remota**. A pessoa informa o código de habilitação, marca a confirmação e clica em
**Habilitar este computador**. Sem as duas coisas, nada sai deste computador.

- O aplicativo declara ao servidor que a confirmação foi feita na máquina (`Nekkora-Agent-Intent: pair-remote`). Um
  código de pareamento comum é recusado pelo servidor, e a tela diz por quê.
- Só vale a resposta que **diz** que a máquina foi habilitada. Qualquer outra coisa é falha, nunca sucesso.
- O código some do campo assim que a tentativa termina, com sucesso ou não. Ele não é gravado.
- Resposta perdida: repetir **o mesmo código na mesma abertura do aplicativo** reaproveita o segredo gerado e conclui.
  Se o aplicativo for fechado antes, é preciso um código novo (e quem administra revoga a máquina que ficou na Platform).

## A credencial da máquina

- O segredo é gerado aqui (32 bytes aleatórios) e viaja uma vez, no resgate.
- Fica em `%LOCALAPPDATA%\Nekkora\AgentClient\remota\maquina.json`, **protegido pela DPAPI do usuário do Windows**
  (`WindowsKeyring`, via JNA). O arquivo traz a identidade (sem segredo) e o segredo cifrado; nunca a credencial em claro.
- Sem a proteção do sistema disponível, o aplicativo **não habilita** e não grava nada.
- Se o servidor habilitar e este computador não conseguir gravar, o aplicativo encerra o vínculo no servidor.
- A credencial só é enviada ao endereço da Platform em que foi emitida. Arquivo de outro endereço, adulterado ou de outro
  usuário do Windows não é usado: a tela oferece só **Remover deste computador**.
- Nada disso aparece em log (o aplicativo não tem log), exceção, argumento de processo ou ZIP.

## Presença, pausar, desabilitar

- **Presença:** uma ao abrir e depois a cada 60 s, enquanto o aplicativo estiver aberto e houver vínculo. Nesta versão
  ela **não declara nenhuma capacidade de execução remota**, então a Platform mostra o computador como *incompatível* e
  não lhe entrega nada.
- **Pausar / Retomar recebimento:** vale no servidor, que é a referência; a presença traz o valor de lá.
- **Desabilitar este computador:** pede um segundo passo de confirmação, encerra o vínculo na Platform e remove a
  credencial daqui. **Sem contato com a Platform, nada é removido** e a tela avisa: a pessoa tenta de novo, ou quem
  administra revoga a máquina na Platform.
- **Vínculo encerrado na Platform** (máquina revogada): a credencial deixa de ser enviada e a tela oferece só remover.

## Renovação da credencial (T01b)

A credencial da máquina vale 90 dias. O aplicativo a renova **sozinho**, sem pessoa e sem código, quando faltarem até 30
dias, a cada presença (portanto só com o aplicativo aberto):

1. gera o segredo novo e o **grava protegido** (`remotaenovacao.json`, DPAPI) antes de pedir qualquer coisa;
2. pede a troca com a credencial atual; a resposta traz só o identificador e a validade da nova;
3. grava a credencial nova e só então apaga o segredo pendente. A presença seguinte já usa a nova.

- **Resposta perdida, ou aplicativo fechado no meio:** a troca é repetida com a credencial anterior e o **mesmo** segredo;
  o servidor devolve a mesma credencial nova. Enquanto houver uma troca em curso, a recusa da credencial antiga **não**
  é tratada como fim do vínculo.
- **Falha passageira** (rede, servidor): nova tentativa com espera crescente (1 min, dobrando, até 60 min). A credencial
  atual continua valendo até vencer.
- **Vencida ou revogada:** nunca é renovada. O aplicativo para de enviar qualquer coisa, a tela diz que a habilitação
  expirou ou foi revogada e oferece só remover; para voltar, é preciso habilitar de novo com um código novo. Não há
  atalho pela conta de quem entrou.
- A tela mostra até quando a credencial vale.

Limite: com o aplicativo **fechado** durante toda a janela de 30 dias, a credencial vence e a habilitação precisa ser
refeita.

## Inventário de Python (T01c, parte 1)

Com o computador habilitado, o aplicativo informa à Platform **quais versões de Python** encontrou aqui, para que se
possa escolher onde executar. A tela diz isso antes de habilitar.

- **O que vai:** por instalação, um identificador opaco, a versão, o tipo (instalação ou ambiente virtual) e o instante.
- **O que não vai:** caminho, nome de usuário ou de pasta, lista de pacotes. O servidor recusa qualquer campo a mais.
- **Identificador opaco:** HMAC-SHA-256 da identidade local do ambiente com um sal aleatório deste computador
  (`remota\inventario.sal`). O mesmo ambiente tem o mesmo identificador nesta máquina; o mesmo caminho em outra máquina
  tem outro. Desabilitar apaga o sal.
- **Quando:** na primeira presença de cada abertura, depois a cada 6 horas, e pelo botão **Atualizar versões de Python**.
  Sem habilitação, o Python nem é procurado para isso.
- **Como é descoberto:** a mesma descoberta limitada e a mesma sondagem da execução local (inicializador do Python,
  registro de instalações, PATH; nada da Microsoft Store; sem varrer discos). A camada `remote` não cria processos: recebe
  de quem monta o aplicativo só a identidade local, o tipo e a versão.
- Capacidade declarada na presença: `PY_INVENTORY_1_0`. Continua sem `REMOTE_RUN_1_0`: a Platform ainda mostra o
  computador como incompatível.

Publicar o inventário não escolhe ambiente nem verifica dependências; isso vem nas próximas partes.

## Entrega e execução de uma tentativa (T02 e T03)

A máquina **puxa**: pergunta à Platform o que há para ela (`remote/next`), a cada 15 s (ponto de partida de laboratório).
O aplicativo declara que executa (`REMOTE_RUN_1_0`) e a tela diz isso. O caminho:

1. a tentativa entregue é **gravada no diário** (`remota/tentativa.json`, token protegido pela DPAPI) antes de qualquer
   confirmação. Se não der para gravar, nada é confirmado e a Platform entrega de novo, com token e época novos;
2. o recebimento é confirmado (`ack`). Repetir (resposta perdida, aplicativo fechado) não muda nada no servidor;
3. **preparando**: o pacote da tentativa é baixado para `remota/tentativas/<tentativa>/pacote.zip`. O arquivo só fica se
   chegou inteiro, dentro do limite (50 MiB) e com o SHA-256 declarado. O Python escolhido na Platform é localizado
   **neste computador** pelo identificador opaco; se não existe mais, a tentativa é recusada. **Nenhum outro é escolhido**;
4. **iniciando**: a Platform reconfere as condições da solicitação. Autorização revogada ou vencida, cancelamento pedido:
   **nada é iniciado**;
5. o motor confere e inicia (`docs/origem-da-execucao.md`): SHA-256 e impressão do pacote, extração segura, as
   dependências indicadas pela Platform no Python escolhido (versão exata), e as travas de sempre (sessão utilizável,
   versão do Python, dependências do pacote). **Nada é instalado.** A intenção de iniciar é gravada antes e o identificador
   da execução logo depois;
6. **em execução**: a cada consulta o estado é repetido (renova o prazo da comunicação) e o pedido de cancelamento é
   obedecido. O estado só vira "cancelada" quando o processo para de fato;
7. o **desfecho** é informado (`SUCCEEDED`, `FAILED`, `TIMED_OUT` com o código de saída quando há; `CANCELLED`;
   `ENDED_UNKNOWN` quando o fim não foi observado);
8. **resultados**: depois de a Platform aceitar o desfecho, os arquivos que a automação gravou na pasta de saída são
   enviados, um a um, e o envio é fechado. Vai o nome **relativo à pasta de saída**, nunca o caminho deste computador.
   Limites (os da Platform): 50 arquivos, 20 MiB cada, 50 MiB no total, nome de até 200 caracteres. **O que passa do limite
   não é enviado e é contado**: o fechamento diz quantos foram e quantos ficaram de fora. **Os arquivos não mudam o
   desfecho.** Só arquivos comuns dentro da pasta: ligações não são seguidas e nada fora dela é alcançado; o arquivo de
   sinal do aplicativo não é enviado;
9. só depois do fechamento o diário é apagado e a limpeza acontece (abaixo).

### Limpeza e recuperação (T04)

| O quê | Quando sai deste computador |
|---|---|
| **Código da tentativa**: o ZIP baixado, a cópia extraída e a cópia que foi executada | sempre, quando a tentativa está resolvida com a Platform (qualquer desfecho). O registro da execução fica |
| **Pasta de saída** (o que a automação gravou) | **só** com a confirmação de recebimento conferida: cada arquivo confirmado pela Platform com o mesmo número, tamanho e SHA-256; fechamento desta tentativa aceito com as mesmas contagens; nenhum arquivo de fora; e a pasta, relida e recalculada antes de apagar, igual ao que foi confirmado |
| Pasta de saída com arquivo fora do limite (`OUT_OF_LIMIT`), envio não confirmado (`NOT_CONFIRMED`) ou alterada depois do envio (`CHANGED_AFTER_SEND`) | **fica guardada**, marcada como pendente (`pendente.txt`: início e motivo). Aparece na tela "Execução remota" com a quantidade, o tamanho, a pasta e a data de remoção. É removida **7 dias** depois da marca (`Runner.MANAGED_OUTPUT_RETENTION`) |
| Sobra de uma queda entre o desfecho e a limpeza | ao abrir o aplicativo e de hora em hora, a varredura tira o **código** das tentativas que não são a pendente. A pasta de saída que sobrou sem marca é marcada nesse momento: o prazo de 7 dias começa aí, nunca antes |
| O que a limpeza **nunca** alcança | só pastas de tentativa (nome no formato de UUID) dentro de `remota/tentativas`. Projeto original, ambiente Python, registros de execuções locais, registro da execução remota e qualquer pasta pessoal ficam fora do alcance |

- **Execução interrompida não é duplicada.** Se o aplicativo fecha com a execução em andamento, ao reabrir o motor
  reconhece o processo pelo registro (identificador, início e executável), o **adota** e ocupa a vaga; o canal continua a
  acompanhar a mesma execução. Nada é lançado de novo, em nenhum caso: nem quando o processo sumiu (termina "sem prova do
  resultado"), nem quando o aplicativo fechou entre pedir o início e gravar a execução.
- **O Python e as dependências são conferidos de novo nesta máquina a cada tentativa**, imediatamente antes de iniciar: o
  Python escolhido é localizado de novo, as dependências indicadas pela Platform e as do pacote são conferidas nele, na
  versão exata. O que valia quando a escolha foi feita na Platform não é presumido.
- Repetir em outra máquina é decidido na Platform; esta máquina não transfere nada.

| Situação | O que acontece |
|---|---|
| Sem rede com a execução em andamento | a execução **não é interrompida**; o desfecho fica no diário e é informado quando a rede voltar |
| Aplicativo fechado com a execução em andamento | a execução é cancelada pelo encerramento (como a local) e informada como cancelada na próxima abertura |
| Aplicativo fechado entre pedir o início e gravar a execução | termina como "sem prova do resultado"; **nada é lançado de novo** |
| Token ou época que não valem mais, tentativa que a Platform não tem | o que esta entrega iniciou aqui é parado, o diário é apagado, nada é alterado na Platform |
| Recebimento pausado | nada novo é entregue; o que está em execução continua |
| Sem rede no meio do envio dos resultados | o envio é retomado na próxima consulta; o desfecho não é informado de novo e nenhum arquivo é duplicado |

Motivos de recusa que esta máquina dá: `BUSY` (há outra execução, local ou não), `PACKAGE_INVALID`, `ENVIRONMENT_GONE`,
`ENVIRONMENT_INCOMPATIBLE`, `DEPENDENCIES_MISSING`, `SESSION_UNAVAILABLE` (tela bloqueada ou sem sessão interativa),
`DISK_PRESSURE` (não conseguiu preparar as pastas ou gravar) e `CLIENT_INCOMPATIBLE` (pacote de um tipo que o aplicativo
não executa, ou entrega sem o ambiente ou o Python).

A camada `remote` continua sem criar processos e sem abrir pacotes: quem executa é entregue por quem monta o aplicativo
(`RemoteWork`, no ponto de montagem), e a execução é uma **execução gerenciada** do motor.

## O que continua local

Executar pelo botão **Executar** é local, com o computador habilitado ou não. `run`, `pack` e `nav` não conhecem
`remote`, e `remote` não conhece a execução local nem as aprovações locais: uma aprovação antiga não vira solicitação
remota, e nenhum registro ou resultado de execução local chega ao canal da máquina. Ver `docs/origem-da-execucao.md`.

## Dependência nova

JNA 5.17.0 (`jna-platform`), só para a DPAPI. É a segunda biblioteca de execução do aplicativo; o inventário está em
`THIRD-PARTY-NOTICES.md`. No pacote, a biblioteca nativa do JNA vem da pasta do aplicativo: nada é descompactado em pasta
temporária.

## Testes

| O que | Teste |
|---|---|
| Habilitar, recusas, resposta perdida, pausar, desabilitar, vínculo encerrado, outro endereço, arquivo adulterado, sem proteção | `RemoteMachineTest` (canal simulado em HTTPS; DPAPI real) |
| Tela: menu, diálogo modal, cliques, confirmação obrigatória, segundo passo, troca de tema | `RemoteDesktopTest` (`-Pdesktop`) |
| Renovação: janela, troca, resposta perdida, fechamento no meio, espera crescente, vencida/revogada, pendente adulterado | `CredentialRenewalTest` (relógio do teste; DPAPI real) |
| Inventário: nada de caminho no pedido, identificador estável e por máquina, filtros, falhas | `PythonInventoryTest` |
| Descoberta sem pacote | `PythonEnvironmentsTest.instalacoesSemPacoteMesmaDescobertaSoComAVersao` (exige `NEKKORA_TEST_PYTHON`) |
| Entrega e condução da tentativa: ordem dos estados, download, Python escolhido, reconferência antes de iniciar, cancelamento, cada desfecho, recusas, pacote que não confere, resposta perdida, fechamento no meio, entrega obsoleta, sem rede, pausa, desabilitar | `RemoteDeliveryTest` (20; canal simulado em HTTPS; DPAPI real; quem executa simulado) |
| Resultados: ordem, nome relativo, limites contados, retomada sem duplicar, fechamento, nada para o que não executou | `RemoteDeliveryTest` (3 dos 19) |
| Limpeza e recuperação: resultado pendente não é apagado antes da confirmação; varredura preserva a pendente e os resultados; processo adotado ao reabrir, nada relançado; código sai, saída só quando pedida | `RemoteDeliveryTest.resultadoPendenteNaoEApagadoAntesDeAPlatformConfirmarORecebimento`, `RemoteWorkTest.aplicativoReabertoComAExecucaoEmAndamentoAdotaOProcessoENaoLancaOutro`, `RemoteWorkTest.limpezaOCodigoSaiSempreASaidaSoQuandoPedidaEAVarreduraNuncaApagaResultado` |
| Confirmação que corresponde ao enviado; pendência marcada, visível e com prazo; nada fora da tentativa é tocado | `RemoteDeliveryTest.aPastaDeSaidaSoSaiQuandoAConfirmacaoCorrespondeATentativaEAosArquivosEnviados`, `RemoteWorkTest.resultadoPendenteFicaMarcadoEVisivelESaiSoDepoisDoPrazoSemTocarEmNadaForaDaTentativa`, `RemoteDesktopTest.resultadoGuardadoSemConfirmacaoApareceNaTelaComAPastaEADataDeRemocao` |
| A ponte com o motor: ZIP real, conferência, execução gerenciada por Python real, código de saída, cancelamento, uma por vez, cada recusa | `RemoteWorkTest` (exige `NEKKORA_TEST_PYTHON`) |
| O motor: execução gerenciada | `ManagedRunTest` (exige `NEKKORA_TEST_PYTHON`) |
| Camadas, rotas fechadas, JNA só na proteção, dependências | `ArchitectureTest` |

## Limites desta entrega

- **Só pacote Python externo** é executado. Pacote do Studio é recusado (`CLIENT_INCOMPATIBLE`).
- **Tela bloqueada ou sem sessão interativa: a execução não inicia** (`SESSION_UNAVAILABLE`), como na execução local.
- **A saída do processo (stdout/stderr) não é capturada nem enviada.** A Platform vê o estado, o código de saída e os
  arquivos de resultado. Os registros de texto vêm na próxima tarefa.
- Pasta de saída que não pôde ser confirmada **fica no computador sem prazo**: não há limpeza por idade nem aviso de espaço.
- A limpeza não é informada à Platform (a rota de "limpeza confirmada ou pendente" do plano não existe).
- Desligamento real da máquina durante a execução **não foi testado**; a adoção após reabrir foi provada com o processo
  Python real e um segundo motor sobre a mesma pasta de dados.
- Localizar o Python escolhido refaz a descoberta das instalações (alguns segundos); enquanto isso, pausar e desabilitar
  esperam.
- Não há verificação sob demanda das dependências: a falta só aparece como recusa de uma solicitação.
- A renovação só acontece com o aplicativo aberto dentro da janela de 30 dias.
- Desabilitar exige contato com a Platform.
- Os testes automatizados usam um canal simulado; a prova contra a API real é a do laboratório, registrada na entrega.
