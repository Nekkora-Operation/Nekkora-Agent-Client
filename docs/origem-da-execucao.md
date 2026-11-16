# Origem da execução e acompanhamento

AGENT-CLIENT-PACOTES-001, T04. Esta regra substitui, **neste aplicativo**, o comportamento anterior em que uma execução
iniciada na máquina podia ser monitorada pela Platform quando havia autorização.

## A regra

| Onde a execução nasce | Para onde vão estado, eventos, saída e término |
|---|---|
| **No Agent** (botão Executar deste aplicativo) | Só para este computador. Nada vai à Platform, nem depois |
| **Na Platform** (pedido real, com vínculo de máquina, Worker e autorizações) | Para a Platform, pelo canal da máquina |

Executar no Agent é local **mesmo** com a conta conectada, a organização escolhida, a rede disponível, uma autorização
de monitoramento válida ou **este computador habilitado para execução remota**. Não há envio retroativo quando a rede
volta, nem depois de habilitar a máquina (`docs/execucao-remota.md`): a camada `remote` não conhece `run` nem `pack`, e
`run`, `pack` e `nav` não conhecem `remote` (`ArchitectureTest.aCamadaRemotaESeparadaDaEntradaEDaExecucaoLocal`).

Login, catálogo (organizações, ambientes, automações, versões) e download de pacote continuam sendo operações remotas.
Elas têm a auditoria delas na Platform e não são registros de execução.

## O que este aplicativo entrega

Só o lado **local**:

- `Runner` e `RunController` não recebem origem de quem chama, não conhecem sessão nem Platform e não falam com a rede
  (`ArchitectureTest`, `ExecutionOriginTest`).
- Cada registro local (`execucoes/<id>/estado.json`) grava `"origin": "LOCAL_AGENT"`. Nessa pasta, um arquivo com outra
  origem é recusado na leitura. Registro antigo, sem o campo, continua local.
- Não existe fila, pasta ou arquivo de envio. O teste com execução real confere o disco e as chamadas, com um estado de
  pareamento e autorização em fixture.
- As rotas que o aplicativo conhece são as de `/api/v1/desktop/`; nenhuma é de execução, evento ou máquina.

## Execução pedida pela Platform: a entrada no motor (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03)

O motor ganhou uma segunda entrada, a execução **gerenciada**: a que não nasce de uma aprovação da pessoa neste
aplicativo. Quem a pede responde pela autorização e entrega o pacote já conferido e a pasta de saída.

| | Execução local | Execução gerenciada |
|---|---|---|
| Entrada | `RunController.start` | `RunController.startManaged` |
| Aprovação local (`aprovacoes.json`) | exigida | **não consultada nem criada** |
| Registro | `execucoes/<id>/`, origem `LOCAL_AGENT` | `remota/execucoes/<id>/`, origem `PLATFORM` |
| Pasta de saída | escolhida pela pessoa, fora da pasta de dados | só dentro da raiz gerenciada (`remota/tentativas/`) |
| Vaga | a mesma: **uma execução por vez no computador**, de qualquer tipo |
| Pacote reconferido, impressão igual à pedida, sessão utilizável, Python e dependências do pacote, cópia executada, posse do processo, prazo, cancelamento, reconciliação | iguais | iguais |

- Um armazém **não lê** registro do outro: um arquivo "da Platform" copiado para `execucoes/` não vira execução local, e um
  registro local (ou sem origem) em `remota/execucoes/` não vira gerenciada. O histórico local não mostra execução gerenciada.
- A execução local continua sem poder gravar dentro da pasta de dados, inclusive na raiz gerenciada.
- **Sessão bloqueada**: a execução gerenciada é recusada com a tela do Windows bloqueada, como a local.

**Nesta etapa só o motor muda.** Nada no aplicativo chama a entrada nova ainda: o canal da máquina continua recusando o que
recebe (`docs/execucao-remota.md`). Baixar, conferir e iniciar pela Platform vem na etapa seguinte.

## O comportamento anterior continua em outros componentes

| Componente | Estado |
|---|---|
| Nekkora Agent administrativo (`Nekkora-Agent`, `9b1e7e1`) | Continua podendo monitorar execução iniciada na máquina quando há autorização de monitoramento (`ExecutionRecord.Origin`: `LOCAL_UI`, `EXTERNAL_SCHEDULER`, além de `PLATFORM_REQUEST`). **Não alterado.** |
| Platform API | `agent_runs.origin`: `1 = LOCAL_AUTHORIZED` (iniciada na máquina) e `2 = PLATFORM_REQUEST` (030). O canal da máquina aceita os dois. **Não alterado.** |

Aplicar a regra nova ao Agent administrativo e à API muda o canal das máquinas e o que as pessoas já veem na Platform;
é trabalho de uma tarefa própria, com decisão sobre os dados existentes.
