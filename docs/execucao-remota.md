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

## O que a tela mostra (ajustes do aceite de 17/11)

- **Tela principal (depois da entrada):** um resumo curto, uma informação por linha — vínculo e comunicação; organização; Worker;
  e a atividade de agora — com "Ver detalhes", que abre a tela "Execução remota".
- **Vinculado × conectado:** "vinculado" é ter a credencial da máquina guardada; "conectado" só quando a Platform aceitou o último
  contato (a hora aparece na tela "Execução remota"). Ter a credencial não comprova contato: os dois são ditos em separado.
- **Tela "Execução remota", parte principal:** estado, vínculo, organização e Worker, comunicação, atividade de agora, pendências (só
  quando há) e as ações. **"Detalhes técnicos"**, recolhido por padrão, traz as explicações extensas, a validade da credencial e os
  ambientes Python informados (com "Atualizar versões de Python").
- **Habilitar:** o botão fica desabilitado enquanto a confirmação está desmarcada; a ação confere a confirmação de novo.

## Presença, pausar, desabilitar

- **Presença:** uma ao abrir e depois a cada 60 s, enquanto o aplicativo estiver aberto e houver vínculo. Nesta versão
  ela **não declara nenhuma capacidade de execução remota**, então a Platform mostra o computador como *incompatível* e
  não lhe entrega nada.
- **Pausa (sem botão na tela desde 17/11):** "Pausar novos trabalhos" e "Voltar a receber trabalhos" foram retirados da tela a
  pedido do aceite. O mecanismo interno continua (`RemoteMachine.pause()`/`resume()` e o canal da máquina). Um computador que **já
  esteja pausado** mostra o estado e, só nele, "Voltar a receber trabalhos": a recuperação explícita, que preserva o vínculo e as
  autorizações. Nada é retomado sozinho, e a tela não oferece pausar. A pausa vale no servidor, que é a referência; a presença traz o valor de
  lá. Pausar **não** desabilita o computador e **não** interrompe a execução em andamento. (Até o Client #41 os botões
  chamavam-se "Pausar recebimento" e "Retomar recebimento".)
- **Desabilitar este computador:** pede um segundo passo de confirmação, encerra o vínculo na Platform e remove a
  credencial daqui. **Sem contato com a Platform, nada é removido** e a tela avisa: a pessoa tenta de novo, ou quem
  administra revoga a máquina na Platform.
- **Vínculo encerrado na Platform** (máquina revogada): a credencial deixa de ser enviada e a tela oferece só remover.

### Pausar, desabilitar e perder o vínculo com uma execução ativa

São três situações diferentes, com três tratamentos:

| Situação | O que acontece com a execução da Platform que está ativa neste computador |
|---|---|
| **Pausa (mecanismo interno, sem botão na tela)** | Nada. Ela continua, é acompanhada até terminar e o desfecho verdadeiro é informado. Só não chega trabalho novo |
| **Desabilitar este computador** | **Recusado** enquanto houver uma tentativa recebida e não resolvida com a Platform (em preparação, em execução, ou com o desfecho ainda por informar). Nada muda, nem aqui nem lá; o desvínculo nem é pedido. A tela diz o que fazer: esperar terminar, pedir o cancelamento na Platform, ou só pausar. Assim o desvínculo pela pessoa nunca abandona o estado de uma execução |
| **Vínculo encerrado na Platform** (quem administra revogou a máquina; credencial vencida) | Não dá para recusar: é o caminho de emergência. O computador deixa de estar autorizado a executar trabalho da Platform, então **o processo é parado, de forma controlada**. O que ele já produziu (registros e pasta de saída) **fica neste computador**, dito na tela como "não podem mais ser enviados: o vínculo foi encerrado", até o prazo de guarda. **Nada é enviado por outro caminho** e a máquina não informa desfecho: quem encerra a tentativa é a Platform ("Resultado não confirmado", ou "Não iniciada") |

Com registros ou resultados ainda **sendo enviados** (tentativa já resolvida), desabilitar é permitido, e o segundo passo avisa
antes: depois de desabilitar eles não poderão mais ser enviados.

Uma nova habilitação cria outra máquina na Platform: ela **não** entrega o que a anterior deixou guardado.

## Renovação da credencial (T01b)

A credencial da máquina vale 90 dias. O aplicativo a renova **sozinho**, sem pessoa e sem código, quando faltarem até 30
dias, a cada presença (portanto só com o aplicativo aberto):

1. gera o segredo novo e o **grava protegido** (`remota
enovacao.json`, DPAPI) antes de pedir qualquer coisa;
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

## Conferência de dependências sob demanda (plano 5.9)

A Platform pode pedir a este computador que **confira** — sem instalar, sem criar ambiente, sem executar — se as dependências
declaradas de uma automação estão no Python escolhido para ela aqui.

| Ponto | Como é |
|---|---|
| Como o pedido chega | junto com a consulta de trabalho; com uma tentativa em andamento, por uma consulta própria a cada ciclo. Conferir não é executar: não ocupa a vaga de execução nem mexe na execução em andamento, e chega com o recebimento pausado |
| O que é conferido | **só** as dependências do pedido (`nome==versao`), **só** no Python do identificador opaco indicado — o mesmo que este computador publicou no inventário |
| Como | a mesma conferência feita antes de iniciar uma tentativa (`Runner.verifyManaged`): a sondagem pergunta ao Python as versões instaladas daqueles nomes. Versão exata |
| O que é respondido | `SATISFIED`; `MISSING` com os itens **pedidos** que faltam ou estão em outra versão; `ENVIRONMENT_GONE` se aquele Python não está mais aqui (nenhum outro é usado); `NOT_VERIFIED` se a conferência falhou |
| O que nunca vai | caminho, nome de pasta, lista do que está instalado |
| Sem resposta | se a resposta não chega (rede, servidor), nada é repetido aqui: a Platform fica sem resposta e mostra "não foi possível verificar" |

É informação para quem vai solicitar. A conferência que vale continua sendo a de antes de iniciar a tentativa.

Testes: `RemoteWorkTest.aConferenciaSobDemandaDizSoOQueFaltaDoQueFoiPerguntadoENaoInstalaNemExecutaNada` (Python real) e
`RemoteDeliveryTest.aPlatformPedeAConferenciaDasDependenciasEAMaquinaRespondeSoOQueFaltaSemCaminhoNemExecucao`.

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
| Pasta de saída ainda não confirmada | **fica guardada**, marcada (`pendente.txt`: início e motivo), e aparece na tela "Execução remota" com a quantidade, o tamanho, a pasta, o motivo e a data de remoção. É removida quando vence o **prazo de guarda** (padrão 7 dias, configurável; ver "Prazo de guarda"), contado do desfecho. Os motivos estão em "Entrega depois do desfecho" |
| Sobra de uma queda entre o desfecho e a limpeza | ao abrir o aplicativo e de hora em hora, a varredura tira o **código** das tentativas que não são a pendente. A pasta de saída que sobrou sem marca é marcada nesse momento: o prazo de guarda começa aí, nunca antes |
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

**Recusa da Platform antes do início (PLATFORM-LICENCIAMENTO-001).** A Platform reconfere as condições antes de entregar e
antes de autorizar o início. Quando ela recusa a tentativa (estado `REFUSED`), nada é iniciado neste computador e a tela
"Execução remota" passa a dizer isso junto do estado operacional, até uma execução nova ser recebida:

| Motivo da Platform | O que a tela diz |
|---|---|
| `LICENSE_REQUIRED` | a automação está sem licença na organização; quem administra vincula uma licença na Platform |
| `LICENSE_EXPIRED` | a licença da automação venceu; a renovação é feita pelo suporte da Nekkora |
| qualquer outro | a Platform encerrou a execução antes do início; o motivo aparece na Platform |

O aplicativo não tem função de licença: não consulta, não concede e não confere. Só repete o motivo que a Platform deu. O
aviso fica na memória do aplicativo (some ao fechar). Cancelamento pedido na Platform não é recusa e não gera aviso.

Quando a organização está sem vaga de execução, a Platform simplesmente não entrega a tentativa: para o aplicativo é
"nada a fazer", e a execução continua pendente na Platform.

**Habilitação com a organização no limite de máquinas.** O resgate do código responde `worker_limit_reached`. A tela diz que
a organização atingiu o limite de máquinas cadastradas (limite de capacidade) e que nada foi alterado. Antes, esse caso
aparecia como "o posto de trabalho deste código já tem uma máquina".

A camada `remote` continua sem criar processos e sem abrir pacotes: quem executa é entregue por quem monta o aplicativo
(`RemoteWork`, no ponto de montagem), e a execução é uma **execução gerenciada** do motor.

### Entrega depois do desfecho: até confirmar, vencer o prazo ou haver impedimento

**Informar o desfecho resolve a tentativa, não a entrega.** Assim que a Platform aceita o desfecho, o diário da tentativa sai,
o código é apagado e a máquina fica livre para outro trabalho. Os registros e a pasta de saída passam para a **entrega
continuada**, que guarda o token da atribuição (protegido, em `remota/guarda/`) e tenta de novo a cada consulta, com espera
crescente entre tentativas (30 s até 15 min). Ela acaba, para cada tipo, de um de três jeitos:

| Fim | Quando | O que fica dito |
|---|---|---|
| **Confirmação** | a Platform aceita o fechamento com as mesmas contagens (registros) e confirma cada arquivo com o mesmo SHA-256 (resultados) | só então o que estava guardado é apagado; não sobra registro nem token |
| **Impedimento**, com o motivo | **limite** (`OUT_OF_LIMIT`): a Platform fechou o envio sabendo que há arquivo acima do limite, que só existe aqui. **Conflito** (`NOT_CONFIRMED`, `CHANGED_AFTER_SEND`): a Platform tem outra coisa, ou outro fechamento, ou a pasta mudou depois do envio. **Autorização** (`LINK_ENDED`, `NOT_AUTHORIZED`): o vínculo deste computador acabou, ou a Platform não reconhece mais a atribuição | não há novo envio; o que está guardado fica até o prazo de guarda, e a tela diz o motivo. Um impedimento de autorização **nunca é contornado** |
| **Prazo de guarda** | o prazo vence antes da confirmação | a limpeza remove, registra a perda e avisa a Platform quando possível |

Enquanto nada impede (`SENDING`), a tela diz "O envio à Platform continua sendo tentado". Falha passageira — rede, servidor
indisponível, limite de requisições, arquivo que não pôde ser lido agora — **não** vira impedimento: é tentada de novo.

O prazo de guarda conta **do desfecho**, não da última tentativa de envio: tentar de novo não recomeça o prazo.

### A fatia de envio: quanto de uma consulta é gasto com o que ficou guardado

A máquina consulta a Platform a cada 15 s. Em cada consulta ela gasta **no máximo 10 s** enviando o que tentativas **já
terminadas** deixaram guardado, somando todas elas, e devolve a vez. Os 10 s são o tamanho da vez — não são prazo de entrega
nem tempo limite de um envio. A presença, a renovação do prazo da tentativa em execução, o pedido de cancelamento e a consulta
de trabalho novo passam pela mesma vez; por isso ela é limitada.

| | Registros | Resultados |
|---|---|---|
| Lote | até 200 linhas e 256 KiB | um arquivo (até 20 MiB) |
| Confirmação | a Platform responde até qual linha tem | a Platform responde o SHA-256 que recebeu |
| Retomada | de onde a Platform confirmou | o que foi confirmado neste processo não é enviado de novo |

- A fatia só é conferida **entre** lotes: pelo menos um lote por consulta; um lote em andamento não é cortado.
- **Com uma tentativa ativa** neste computador, o que ficou de tentativas anteriores cede a vez: um lote de registros por
  consulta e nenhum arquivo de resultado. O estado da tentativa ativa e o cancelamento são tratados antes, em toda consulta.
- Limite conhecido: um arquivo grande numa conexão lenta ainda atrasa a presença, porque ela usa a mesma vez.

### Resultado não confirmado: quando este computador confirma que o processo acabou

"Terminou sem prova do resultado" bloqueia, na Platform, a repetição da solicitação e deixa esta máquina indisponível até a
tentativa ser reconciliada. O aplicativo reconcilia a tentativa **ele mesmo** quando pode: junto do desfecho desconhecido, afirma
que conferiu que o processo daquela execução não está mais em execução aqui. Isso só acontece quando a execução chegou a ser
lançada e o motor a conhece — ele confere o processo que lançou (PID, início e executável) antes de fechá-la.

Não afirma nada quando o aplicativo fechou entre pedir o início e registrar a execução, ou quando o motor não a conhece mais.
Nesses casos só a declaração de um ORG_ADMIN, na Platform, libera uma nova tentativa — e esta máquina continua indisponível.

### Vínculo novo não herda o anterior

No fim de um vínculo o token de cada atribuição é descartado e o que ficou guardado passa a "não pode mais ser enviado: vínculo
encerrado". Habilitar o computador de novo (outro código, outra credencial) **não** reenvia esse conteúdo nem o atribui ao
vínculo novo: ele continua guardado, com o mesmo motivo, até o prazo de guarda, e a perda fica registrada com "não foi possível
avisar". Uma recuperação autorizada desse conteúdo não existe nesta versão.

### Prazo de guarda do que a Platform não confirmou

Vale para a **pasta de saída** e para a **fila de registros** de uma tentativa já terminada cujo recebimento a Platform
não confirmou. É retenção **local**; não substitui a retenção do servidor, que continua pendente.

| Ponto | Como é |
|---|---|
| Padrão | **7 dias** (`MachineVault.DEFAULT_RETENTION_DAYS`) |
| Como mudar, sem alterar o programa | um arquivo de texto `prazo-de-guarda.txt` na pasta `remota` dos dados do aplicativo (`%LOCALAPPDATA%\Nekkora\AgentClient\remota\`), com um número inteiro de dias na primeira linha, de **1 a 90** |
| Valor inválido | arquivo ausente, vazio, com texto, zero, negativo ou acima de 90: vale o padrão. Um valor inválido nunca encurta nem alonga a guarda |
| Quando passa a valer | é lido a cada limpeza (ao abrir e de hora em hora) e a cada vez que a tela é montada. Não é preciso reabrir o aplicativo |
| O que não entra na contagem | a tentativa **em andamento** (do recebimento até o desfecho ser aceito pela Platform). A fila e a pasta dela nunca são removidas pelo prazo. Com uma execução em andamento, a limpeza das pastas não roda |
| De quando conta | **do desfecho**. Pasta de saída: da marca gravada quando a Platform aceita o desfecho. Fila de registros: da última gravação do arquivo, que é a do fim da execução — tentar enviar de novo preserva essa data |
| Aviso prévio | a tela "Execução remota" mostra, enquanto a pendência existe, **a data** de remoção e **o motivo** ("é quando vence o prazo de guarda de N dias sem confirmação de recebimento"). Para resultados, também a quantidade, o tamanho e a pasta |
| O que já foi confirmado | sai antes, na hora da confirmação. O prazo é só para o que não foi confirmado |

**Depois do descarte** fica um registro mínimo, por tentativa, em `remota/guarda/` (protegido pelo Windows): o tipo
(registros ou resultados), quanto havia (linhas capturadas ou arquivos) e quando foi removido. Nada do conteúdo.

- A tela passa a mostrar a perda como perda: "Removido deste computador pelo prazo de guarda, sem confirmação de
  recebimento pela Platform…", com o que a Platform sabe disso. Nunca como entregue.
- O aplicativo **avisa a Platform** (`POST …/attempts/{t}/retention-loss`), ao abrir e de hora em hora, com o computador
  habilitado. O aviso leva a tentativa, o tipo e quanto havia aqui; nenhum conteúdo.
- Para o aviso, o registro guarda o token da atribuição. Ele sai do registro quando o aviso é aceito, quando a Platform
  não reconhece mais a atribuição (aviso impossível, e isso fica dito na tela) ou quando o computador é desabilitado.
- O registro da perda fica 30 dias depois dela (`LossLedger.KEEP_DAYS`) e então sai.
- Pasta guardada que some **antes** do prazo (a pessoa copiou e apagou) não é perda por prazo: nada é registrado nem avisado.

Limites: o aviso depende de o aplicativo voltar a rodar com o computador habilitado. Sobra de uma queda, sem registro
de guarda, é removida no prazo sem aviso à Platform (não há token para avisar).

## Registros: a saída do processo (T05)

**Captura de stdout/stderr é funcionalidade nova.** Antes desta missão o aplicativo lia a saída do processo e a
descartava.

| Ponto | Como é |
|---|---|
| O que é capturado | a saída padrão e a saída de erro do processo de uma **tentativa remota**, linha a linha, enquanto ele roda |
| Para onde vai | para a Platform, em lotes: durante a execução (a cada consulta) e depois do término. É lá que se consulta |
| O que o aplicativo mostra | **nada do conteúdo.** A tela continua mostrando só o estado da máquina |
| Fila local | um arquivo por tentativa em `remota/fila/`, só para a entrega. Cada bloco gravado passa pela proteção do Windows (o mesmo usuário, neste computador) |
| Quando a fila é apagada | quando a Platform confirma o fechamento do envio com as mesmas contagens. Tentativa que não iniciou não tem fila |
| Sem confirmação | a fila fica no disco e o envio continua sendo tentado (ver "Entrega depois do desfecho"). Aparece na tela como pendência (sem o conteúdo), com o motivo, e é apagada quando vence o **prazo de guarda** (padrão 7 dias, configurável), contado do desfecho |
| Segredos | antes de entrar na fila saem do texto: a credencial da máquina, o token da atribuição, códigos de habilitação e valores de cabeçalho de autorização |
| `STDERR` | é um **canal**, não um nível. Nada é classificado pelo texto |
| Limite por linha | 8 KiB; o excedente da linha é lido e desprezado, e a linha vai marcada como cortada |
| Limite por tentativa | 50.000 linhas e 20 MiB. Ao atingir, a fila grava **uma linha do aplicativo dizendo isso** e passa a **contar** o que chega, sem guardar. O número vai no fechamento |
| Resultado do robô | **nunca muda** por causa de registro: nem limite, nem falha de envio, nem fila que não pôde ser confirmada |
| Sem rede | a execução não é interrompida; a fila guarda e o envio é retomado, sem duplicar linha |
| Aplicativo reaberto no meio | a fila volta do disco; uma linha do aplicativo diz que a saída a partir dali não foi capturada |
| Execução iniciada neste computador | **não tem fila e não envia nada.** A saída dela vai para um arquivo deste computador (ver "Execução local") |

### Execução local: a saída fica num arquivo deste computador

| Ponto | Como é |
|---|---|
| O que é capturado | a saída padrão e a saída de erro do processo de uma execução iniciada pelo botão **Executar** |
| Para onde vai | para `saida.txt`, na pasta do registro daquela execução (`execucoes/<id>/`), na pasta de dados do aplicativo. **Nunca é enviado**: `run` não conhece rede, e o canal da máquina não lê essa pasta |
| Formato | texto UTF-8. Primeira linha: o que é o arquivo. Depois, uma linha por linha capturada: instante (relógio deste computador), canal (`saída`, `erro` ou `aplicativo`) e o texto |
| Como se vê | na área "Última execução" da preparação: o caminho do arquivo e **Abrir arquivo**, que pede ao Windows para abri-lo. O aplicativo não tem visualizador próprio |
| Segredos | as mesmas regras da tentativa remota: credencial de máquina, código de habilitação e valor de cabeçalho de autorização viram `[removido]` antes do disco |
| Proteção | arquivo comum na pasta de dados do usuário, como o registro da execução. **Não** passa pela proteção do Windows usada na fila remota |
| Limite por linha | 8 KiB; a linha cortada termina com `[linha cortada no limite de 8 KiB]` |
| Limite por execução | 50.000 linhas e 20 MiB. Ao atingir: uma linha `aplicativo` dizendo isso; as seguintes são lidas e contadas; a última linha do arquivo diz quantas ficaram de fora |
| Resultado da execução | **não muda** por causa do arquivo: nem limite, nem falha de disco |
| Quanto tempo fica | o mesmo do registro da execução: os 50 mais recentes por pacote (`RunStore.KEEP`); o arquivo sai junto com o registro |
| Execução remota | **não** gera esse arquivo |

### Como a saída é lida (vale para as duas origens)

**`-u`.** O processo é lançado com `-u` sempre que a saída tem destino (a fila remota ou o arquivo local), para as linhas
saírem quando são escritas e não só no fim. Muda **quando** a saída chega, não o que o script faz. A variável de ambiente
equivalente não serviria: o `-E`, que o aplicativo já usava, faz o Python ignorá-la.

**Linha.** Os bytes são acumulados até o `\n`. O `\r` é descartado onde estiver. A decisão de codificação é tomada
**por linha inteira, depois de a linha estar completa**; por isso um caractere dividido entre duas leituras do fluxo
chega inteiro.

**Codificação, passo a passo:**

1. Se os bytes da linha são UTF-8 válido, a linha é UTF-8.
2. Se **qualquer** byte da linha não é UTF-8 válido, a linha **inteira** é lida na página de código do Windows deste
   computador (`native.encoding`; neste laboratório, `Cp1252`). É nela que o Python escreve num fluxo redirecionado
   quando o script não muda a codificação, então os acentos chegam certos sem o script fazer nada.
3. Consequência da regra 2: numa linha que mistura UTF-8 válido com um byte inválido, os caracteres UTF-8 de mais de um
   byte aparecem como dois ou mais caracteres da página de código. Nada é descartado e nada lança erro.
4. Se a página de código não puder ser determinada, usa-se UTF-8 com substituição (`\uFFFD`) nos bytes inválidos.
5. Byte sem caractere na página de código vira `\uFFFD`.
6. Caractere de controle (abaixo de `0x20`, exceto tabulação, e `0x7f`) vira `\uFFFD`: uma linha é sempre uma linha de
   texto. Sequências de cor do terminal ficam visíveis como texto, sem o `ESC`.
7. Linha acima de 8 KiB: o excedente é lido e desprezado. Se o corte cair no meio de um caractere UTF-8, os bytes
   partidos saem e o resto continua UTF-8. A linha vai marcada como cortada.
8. A decisão é por linha: uma linha inválida não muda a leitura da seguinte.

**Limite conhecido.** Um texto em página de código cujos bytes formem, por coincidência, UTF-8 válido é lido como UTF-8.
Com `Cp1252` isso exige sequências como `Ã©`, raras em texto real, mas possíveis.

## Estado operacional na tela (T06)

A tela "Execução remota" mostra **só estado operacional**. Nenhum conteúdo de execução pedida pela Platform aparece no
aplicativo.

| O que | Como aparece |
|---|---|
| Habilitação | habilitado e recebendo, pausado, não habilitado, encerrado, sem contato (já existiam) |
| Organização | "Habilitado para a organização *nome*". O nome vem da lista de organizações de quem entrou; se a organização do vínculo não está nela, a tela diz isso, sem mostrar identificador |
| O que roda agora, e de quem é | nada; execução **iniciada neste computador** (local, nada vai à Platform); solicitação da Platform em preparação; em execução; terminada e enviando desfecho, registros e resultados |
| Onde ver o conteúdo | um texto fixo: registros e resultados das execuções pedidas pela Platform são consultados na Platform, na página da versão; os das execuções locais, em "Última execução" |
| Ambientes Python | uma linha por ambiente informado: **código**, versão, tipo e a pasta. O código são os 6 primeiros caracteres do identificador opaco publicado, o mesmo que a Platform mostra. A pasta só aparece aqui |
| Pendências | resultados e registros guardados sem confirmação, com data e motivo da remoção; o que já foi removido pelo prazo |

Limites: o nome do **Worker** não aparece (o aplicativo só recebe o identificador dele). "Onde ver" é texto, não um
atalho que abre a Platform. A lista de ambientes é a da última publicação feita nesta abertura do aplicativo.

| Requisito | Teste |
|---|---|
| Atividade e origem em cada fase; organização; código igual ao começo do identificador publicado; pasta nunca enviada | `RemoteDeliveryTest.aTelaSabeOQueEstaRodandoAgoraEDeQuemEEOCodigoDoAmbienteEOMesmoQueVaiAPlatform` |
| Tela: textos, nada antes de habilitar, sem identificador | `RemoteDesktopTest.aTelaMostraOEstadoOperacionalEOCodigoDeCadaAmbienteSemMostrarConteudoDeExecucao` (`-Pdesktop`) |

### O posto desta máquina (Worker)

A tela "Execução remota" mostra, ao lado da organização, o **nome do Worker** a que este computador está associado na
Platform: é para ele que as versões são autorizadas, e é em nome dele que as execuções chegam aqui. O nome vem da presença
(é o atual de lá; trocado na Platform, a presença seguinte traz o novo), fica só em memória e passa por uma limpeza de
caracteres de controle antes de ser exibido. Enquanto a Platform não o informa (API anterior, ou antes da primeira presença),
a tela mostra só a organização — nunca um identificador.

A regra de arquitetura que proíbe a palavra "worker" fora da camada `remote` ganhou **uma** exceção: o texto exibido
`"Worker na Platform: "`, em `RemoteText`. Identificadores e chamadas com esse nome continuam proibidos fora de `remote`.

## O que continua local

Executar pelo botão **Executar** é local, com o computador habilitado ou não. `run`, `pack` e `nav` não conhecem
`remote`, e `remote` não conhece a execução local nem as aprovações locais: uma aprovação antiga não vira solicitação
remota, e nenhum registro ou resultado de execução local chega ao canal da máquina. Ver `docs/origem-da-execucao.md`.

## Dependência nova

JNA 5.17.0 (`jna-platform`), só para a DPAPI. É a segunda biblioteca de execução do aplicativo; o inventário está em
`THIRD-PARTY-NOTICES.md`. No pacote, a biblioteca nativa do JNA vem da pasta do aplicativo: nada é descompactado em pasta
temporária.

## Testes

| Requisito (fechamento) | Teste |
|---|---|
| Informar o desfecho libera a máquina; a entrega continua até a confirmação, com espera entre tentativas, sem recomeçar o prazo de guarda e atravessando o fechamento do aplicativo | `RemoteDeliveryTest.informarODesfechoNaoEncerraAEntregaQueContinuaAteAPlatformConfirmar` |
| Impedimento de autorização (a Platform não reconhece mais a atribuição): fica dito, nada mais é enviado | `RemoteDeliveryTest.impedimentoDeAutorizacaoFicaDitoComOMotivoENaoEContornado` |
| Vínculo encerrado com execução em andamento: processo parado, o que há fica guardado com o motivo, nenhuma chamada a mais; a perda por prazo fica registrada com aviso impossível | `RemoteDeliveryTest.vinculoEncerradoComExecucaoEmAndamentoParaOProcessoEGuardaOQueHaSemEnviar` |
| Desabilitar é recusado com tentativa ativa; pausar não mexe na execução em andamento | `RemoteDeliveryTest.pausadaNadaEEntregueEComTentativaAtivaOComputadorNaoEDesabilitado` |
| Os três fins da entrega no registro local; vínculo encerrado tira os tokens e mantém o motivo | `LossLedgerTest` (3) |
| Textos: cada motivo, aviso antes de desabilitar com envio em andamento, recusa com execução ativa | `RemoteDesktopTest` (`-Pdesktop`) |

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
| Prazo de guarda: configurável por arquivo, valor inválido volta ao padrão, lido a cada limpeza | `RemoteDeliveryTest.oPrazoDeGuardaEConfiguravelPorArquivoEValorInvalidoNaoEncurtaNemAlongaAGuarda`, `RemoteWorkTest.resultadoPendenteFicaMarcadoEVisivelESaiSoDepoisDoPrazoSemTocarEmNadaForaDaTentativa` |
| Prazo de guarda: execução em andamento fora da contagem; data conhecida antes; fila removida deixa registro; aviso à Platform com rede e sem rede; nada reenviado; resultado inalterado | `RemoteDeliveryTest.filaRemovidaPeloPrazoDeixaRegistroDaPerdaEAPlatformEAvisadaQuandoPossivel` |
| Prazo de guarda: pasta de saída removida deixa registro; aviso impossível fica dito; desabilitar não apaga o registro; sumir antes do prazo não é perda | `RemoteDeliveryTest.pastaDeSaidaRemovidaPeloPrazoDeixaRegistroEOAvisoImpossivelFicaDito`, `.pastaGuardadaQueSomeAntesDoPrazoNaoViraPerdaPorPrazo` |
| Registro de guarda: protegido no disco, token só enquanto há aviso a dar, some 30 dias depois da perda | `LossLedgerTest` (DPAPI real) |
| Tela: data e motivo antes da remoção; registros pendentes sem conteúdo; texto da perda | `RemoteDesktopTest` (`-Pdesktop`) |
| Registros: leitura linha a linha, corte, página de código, quem recebe falhando | `OutputLinesTest` |
| Codificação: caractere de 2, 3 e 4 bytes dividido entre leituras (pedaços de 1 a 7 bytes); linha com byte inválido lida inteira na página de código; sequências UTF-8 malformadas; uma linha não contamina a outra | `OutputLinesTest.caractereDivididoEntreLeiturasChegaInteiroEmQualquerPontoDeCorte`, `OutputLinesTest.aDecisaoDeCodificacaoEPorLinhaInteiraEUmaLinhaNaoContaminaAOutra` |
| Saída da execução local: formato, canais, linha cortada, segredos, limite de linhas e de tamanho, falha de disco | `OutputTextTest` |
| Saída da execução local com Python real: arquivo na pasta do registro, saída e erro; 24 MiB de saída param no limite, avisam e contam; nada do script no registro nem nos eventos | `RunControllerTest.sucesso`, `RunControllerTest.volume` |
| Saída da execução local na tela: caminho mostrado, **Abrir arquivo**, nenhum outro arquivo é aberto | `RunPreparationDesktopTest` (`-Pdesktop`) |
| Execução remota não grava arquivo local de saída | `ManagedRunTest.executaSemAprovacaoLocalEGuardaSoNoArmazemProprio` |
| As duas remoções de segredos dão o mesmo resultado | `RedactionVectors`, usado por `LogQueueTest` e `OutputTextTest` |
| Registros: fila protegida no disco, sem segredos, limites contados, volta ao reabrir | `LogQueueTest` (DPAPI real) |
| Registros: lotes durante a execução, sem rede, fechamento, fila apagada só com confirmação, prazo, reabertura, limite da Platform, tentativa que não executou | `RemoteDeliveryTest` (4 testes) |
| Registros com Python real: linha chega com o processo rodando, acentos, stderr, linha cortada, resultado inalterado | `RemoteWorkTest.aSaidaDoProcessoChegaLinhaALinhaEnquantoEleRodaESemMudarOResultado` |
| A ponte com o motor: ZIP real, conferência, execução gerenciada por Python real, código de saída, cancelamento, uma por vez, cada recusa | `RemoteWorkTest` (exige `NEKKORA_TEST_PYTHON`) |
| O motor: execução gerenciada | `ManagedRunTest` (exige `NEKKORA_TEST_PYTHON`) |
| Camadas, rotas fechadas, JNA só na proteção, dependências | `ArchitectureTest` |

## Limites desta entrega

- **Só pacote Python externo** é executado. Pacote do Studio é recusado (`CLIENT_INCOMPATIBLE`).
- **Tela bloqueada ou sem sessão interativa: a execução não inicia** (`SESSION_UNAVAILABLE`), como na execução local.
- **Saída da execução local:** guardada em arquivo de texto comum, sem visualizador no aplicativo (abre no programa
  padrão do Windows), sem busca e sem filtro. O arquivo não é protegido além das permissões da pasta do usuário.
- A execução local passou a ser lançada com `-u` (antes, sem). Um script que dependia de a saída só aparecer no fim não
  muda de resultado, mas escreve com mais chamadas ao sistema.
- Registros: sem nível (tudo `UNKNOWN`); sem limite de espaço total da fila; até 1 segundo de saída pode se perder se o
  aplicativo cair. A fila que não pôde ser confirmada aparece na tela como pendência, sem o conteúdo.
- O prazo de guarda é configurado por arquivo, não pela tela.
- A espera entre tentativas da entrega continuada (30 s até 15 min) é fixa no programa, e a tela não mostra quando será a
  próxima tentativa.
- Uma nova habilitação não entrega o que a habilitação anterior deixou guardado (o plano T00 previa; não foi feito, por
  contornar a revogação).
- Com o vínculo encerrado pela Platform, o processo em execução é **parado**. O que ele estava fazendo num sistema de
  terceiros não é desfeito.
- Depois de o aplicativo ser reaberto no meio de uma execução, a saída do processo **não é mais capturada** (o fluxo ficou
  sem leitor); a lacuna fica dita nos registros.
- Não há aviso de espaço em disco para pasta de saída ou fila pendentes.
- A limpeza não é informada à Platform (a rota de "limpeza confirmada ou pendente" do plano não existe).
- Desligamento real da máquina durante a execução **não foi testado**; a adoção após reabrir foi provada com o processo
  Python real e um segundo motor sobre a mesma pasta de dados.
- Localizar o Python escolhido refaz a descoberta das instalações (alguns segundos); enquanto isso, pausar e desabilitar
  esperam.
- Não há verificação sob demanda das dependências: a falta só aparece como recusa de uma solicitação.
- A renovação só acontece com o aplicativo aberto dentro da janela de 30 dias.
- Desabilitar exige contato com a Platform.
- Os testes automatizados usam um canal simulado; a prova contra a API real é a do laboratório, registrada na entrega.
