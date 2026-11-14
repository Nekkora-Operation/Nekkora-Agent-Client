# Origem da execução e acompanhamento

AGENT-CLIENT-PACOTES-001, T04. Esta regra substitui, **neste aplicativo**, o comportamento anterior em que uma execução
iniciada na máquina podia ser monitorada pela Platform quando havia autorização.

## A regra

| Onde a execução nasce | Para onde vão estado, eventos, saída e término |
|---|---|
| **No Agent** (botão Executar deste aplicativo) | Só para este computador. Nada vai à Platform, nem depois |
| **Na Platform** (pedido real, com vínculo de máquina, Worker e autorizações) | Para a Platform, pelo canal da máquina |

Executar no Agent é local **mesmo** com a conta conectada, a organização escolhida, a rede disponível ou uma autorização
de monitoramento válida. Não há envio retroativo quando a rede volta, nem quando a máquina for pareada no futuro.

Login, catálogo (organizações, ambientes, automações, versões) e download de pacote continuam sendo operações remotas.
Elas têm a auditoria delas na Platform e não são registros de execução.

## O que este aplicativo entrega

Só o lado **local**:

- `Runner` e `RunController` não recebem origem de quem chama, não conhecem sessão nem Platform e não falam com a rede
  (`ArchitectureTest`, `ExecutionOriginTest`).
- Cada registro (`execucoes/<id>/estado.json`) grava `"origin": "LOCAL_AGENT"`. É a única origem que o aplicativo produz.
  Um arquivo com outra origem não foi escrito por ele e é recusado na leitura. Registro antigo, sem o campo, continua local.
- Não existe fila, pasta ou arquivo de envio. O teste com execução real confere o disco e as chamadas, com um estado de
  pareamento e autorização em fixture.
- As rotas que o aplicativo conhece são as de `/api/v1/desktop/`; nenhuma é de execução, evento ou máquina.

## O que não é entregue aqui

**Execução solicitada pela Platform não existe neste aplicativo.** Ele não tem credencial de máquina, pareamento, Worker
nem canal de pedidos. Quando existir, entra por um caminho próprio (credencial de máquina, pedido verificável), com o
acompanhamento apresentado separado do histórico local. Passar uma marca "platform" por este aplicativo não cria
execução remota: não há onde passá-la.

## O comportamento anterior continua em outros componentes

| Componente | Estado |
|---|---|
| Nekkora Agent administrativo (`Nekkora-Agent`, `9b1e7e1`) | Continua podendo monitorar execução iniciada na máquina quando há autorização de monitoramento (`ExecutionRecord.Origin`: `LOCAL_UI`, `EXTERNAL_SCHEDULER`, além de `PLATFORM_REQUEST`). **Não alterado.** |
| Platform API | `agent_runs.origin`: `1 = LOCAL_AUTHORIZED` (iniciada na máquina) e `2 = PLATFORM_REQUEST` (030). O canal da máquina aceita os dois. **Não alterado.** |

Aplicar a regra nova ao Agent administrativo e à API muda o canal das máquinas e o que as pessoas já veem na Platform;
é trabalho de uma tarefa própria, com decisão sobre os dados existentes.
