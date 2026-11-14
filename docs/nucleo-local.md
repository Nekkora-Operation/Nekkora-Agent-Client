# Núcleo local: pacotes e execução

Dois pacotes Java sem tela e sem rede, só com o JDK:

- `com.nekkora.client.pack` — ler um ZIP não confiável, validar um pacote Python externo, calcular a impressão digital,
  empacotar o código de um projeto e **guardar pacotes por organização e ambiente**;
- `com.nekkora.client.run` — executar um pacote guardado, depois da aprovação da pessoa, e registrar o que aconteceu.

Origem: portado de `Nekkora-Agent` (branch `dev`, commit `9b1e7e1`). O que mudou de nome está em
[De → para](#de--para-classes); o que não veio, em [O que ficou de fora](#o-que-ficou-de-fora-e-por-quê).

Garantias conferidas por `ArchitectureTest`: `pack`, `run` e `nav` não importam Swing/AWT nem rede; só `run` cria
processos; disco só em `storage`, `pack` e `run`; nada referencia o código nem o perfil do Nekkora Agent administrativo.

**Nada aqui fala com a Platform nem exige máquina pareada.** Importar, empacotar e guardar nunca executam código, nunca
instalam nada. Os registros das execuções são **locais**: ficam no disco desta máquina e **não existe fila de envio** neste
núcleo.

## Montagem

```java
ClientDataDir data = ClientDataDir.resolve();                     // storage
AtomicWriter w = AtomicWriter.DEFAULT;
ProjectImporter importer = new ProjectImporter(data.importWorkDir(), ZipLimits.DEFAULT);
LocalPackages packages = new LocalPackages(data.packagesDir(), w, Clock.systemUTC());
RunController runs = new RunController(
        new RunStore(data.runsDir(), w),
        new ApprovalLedger(data.approvalsFile(), w),
        SessionProbe.system(true, System.getenv(), GraphicsEnvironment::isHeadless),   // ver "Sessão"
        Clock.systemUTC(), data.root());
runs.reconcile();   // UMA vez, na abertura (fora da linha de eventos)
```

Uma instância de cada por aplicativo. `LocalPackages`, `ApprovalLedger`, `RunStore` e `RunController` são seguras entre
linhas. **Tudo o que toca disco ou processo bloqueia**: chame fora da linha de eventos do Swing.

**Sessão.** `SessionProbe` precisava de `java.awt.GraphicsEnvironment.isHeadless()`. Como `run` não pode conhecer AWT, a
resposta entra por um `BooleanSupplier` entregue por quem monta o aplicativo (a raiz ou `ui`), como no exemplo.

## Disco

```
<dados>/                                   %LOCALAPPDATA%\Nekkora\AgentClient
  pacotes/
    <organizationId>/<workspaceId>/
      <impressão-hex>/                     cópia conferida: nekkora-external.json + arquivos listados
      <impressão-hex>.json                 metadados locais do item (nekkora-agent-client/local-package 1.0.0)
    .preparo/preparo-*/                    temporários das operações (apagados ao fim; sobras > 12 h são limpas)
  importacao/op-*/                         temporários de um ZIP de projeto aberto (apagados em close(); sobras > 12 h)
  execucao/aprovacoes.json                 aprovações do perfil (nekkora-agent/approvals 1.0.0), por impressão digital
  execucoes/<ex-…>/
    estado.json                            registro (nekkora-agent-client/execution 1.0.0), com o contexto
    eventos.jsonl                          nomes de evento, códigos, tempos (até 500 linhas)
    pacote/                                cópia conferida de onde o processo roda
    processo.json, saida.ref               posse do processo e pasta de saída (para o reinício)
  sonda/                                   script fixo que lê a versão do Python
<pasta de saída escolhida>/<ex-…>/         o que o script grava; sinal.json opcional
```

Retenção: as 50 execuções **terminadas** mais recentes de cada organização + ambiente; nada em andamento é apagado.

Metadados do item (`<impressão-hex>.json`): `organizationId`, `workspaceId`, `fingerprint`, `name` (declarado),
`packageVersion`, `entry`, `python`, `dependencies`, `localName` (ou `null`), `linkedAutomationId` (ou `null`), `addedAt`.
Um arquivo de metadados só vale no lugar a que pertence (organização, ambiente e impressão gravados = os do caminho).

## Erros e textos para a pessoa

| Tipo | Quando | Texto para a tela | Código |
|---|---|---|---|
| `ImportProblem` (checked) | ZIP, projeto, empacotamento, pacotes guardados | `e.message()` → `"<texto> (<detalhe>) [AGT-PAC-xxx]"`; ou `e.code().text()` e `e.detail()` | `e.code().code()` |
| `ExecRefused` (checked) | validador do pacote, aprovação, Python, sessão, início | `e.code().message()` → `"<descrição> [AGT-EXE-xxx]"`; ou `e.code().description()` | `e.code().code()` |
| `IllegalArgumentException` | identificador que não é UUID / impressão fora do formato (erro de programação) | — | — |

Um registro terminado traz `record.code()` (`ErrorCode` ou `null`) e `record.state().label()`.
`ImportProblem.detail()` pode ter nome de arquivo do cliente: só na tela, nunca em log.

## `pack` — o que a interface usa

```java
record PackageContext(UUID organizationId, UUID workspaceId)
    static PackageContext parse(String organizationId, String workspaceId)   // IllegalArgumentException se não for UUID canônico
```

### 1. Importar um pacote pronto (ZIP com `nekkora-external.json`)

```java
LocalPackages.Added add(PackageContext context, Path zip, SafeZip.Cancel cancel) throws ImportProblem, ExecRefused
record Added(Item item, ExecutionPackage pkg, boolean alreadyPresent)
```

Bloqueia (lê até 50 MiB). Idempotente: o mesmo conteúdo de novo devolve o item que já existe (`alreadyPresent`), com os
metadados preservados. `SafeZip.Cancel` é `() -> boolean`; cancelado → `ImportProblem` `CANCELLED` e nada fica.
Recusas típicas: ZIP hostil ou inválido → `ImportProblem` (`AGT-PAC-001..026`); pacote adulterado → `AGT-EXE-004`;
**pacote do Studio ou de laboratório → `AGT-EXE-030`**; ZIP só de código (sem descritor) → `AGT-EXE-002` (ofereça o
caminho 2).

### 2. Nova automação a partir de um ZIP de código

```java
ImportedProject p = importer.open(zip, cancel);          // throws ImportProblem; bloqueia; nada do ZIP é executado
…                                                        // a pessoa preenche o plano (abaixo)
Packager.Preview pv = Packager.preview(p, plan);         // throws ImportProblem; não grava nada; rápido
LocalPackages.Added a = packages.addBuilt(context, p, plan, cancel);   // throws ImportProblem, ExecRefused; bloqueia
importer.close(p);                                       // SEMPRE (finally): apaga a pasta temporária
```

`p.kind()`: `CODE` (segue), `EXTERNAL_PACKAGE` (já é um pacote: use o caminho 1, ou `rebuild = true` para regravar),
`EXTERNAL_INVALID`, `UNSUPPORTED_PACKAGE` (Studio/laboratório) e `CONFLICT` — os três últimos com o motivo em
`p.blockers()` (lista de `ImportProblem`). `p.notes()` são avisos em texto, que não impedem.

O plano — `new PackagePlan(name, version, entry, python, dependencies, dependenciesConfirmed, includeData, exclude, rebuild)`:

| Campo | Detectado? | A pessoa precisa |
|---|---|---|
| `name` | não | informar. `[A-Za-z0-9][A-Za-z0-9._-]{0,79}` (`AGT-PAC-035`) |
| `version` | não (sugira `1.0.0`) | informar. `x.y.z` (`AGT-PAC-036`) |
| `entry` | sugestão em `p.suggestedEntry()` (`main.py`, `__main__.py` ou o único `.py` da raiz); opções em `p.pythonFiles()` | **escolher explicitamente** — a sugestão nunca é confirmada sozinha (`AGT-PAC-034`) |
| `python` | `p.declarations().python()` (de `.python-version` ou `requires-python`; origem em `pythonSource()`; texto não aceito em `pythonUnsupported()`) | informar se vazio. Ex.: `>=3.10,<3.14`, `==3.12.*`, `~=3.11` (`AGT-PAC-037`) |
| `dependencies` | `p.declarations().exact()` (linhas `nome==versão` do `requirements.txt`); o que não coube em `unsupported()`; outros arquivos de declaração em `otherFiles()` | conferir/completar. Só versão exata, sem repetir (`AGT-PAC-038`) |
| `dependenciesConfirmed` | — | obrigatório `true` quando `p.declarations().dependenciesNeedConfirmation()` (status `PARTIAL` ou `NOT_DECLARED`); senão `AGT-PAC-042`. Falta de declaração nunca vira "nenhuma dependência" |
| `includeData` | arquivos com regra `DATA` em `p.files()` ficam fora por padrão | opcional: incluir um exemplo sem dados reais |
| `exclude` | arquivos com regra `INCLUDE`/`INCLUDE_WARNED` entram por padrão | opcional: deixar fora (necessário para nomes com espaço/acento: `AGT-PAC-039`) |
| `rebuild` | — | `true` só para regravar a partir de um `EXTERNAL_PACKAGE` |

`p.files()` → `ProjectFile(path, size, hash, rule, contractSafe)`; `rule.label()` é o motivo mostrável
(`ENVIRONMENT`, `SECRET` e `TOOLING` nunca entram). `Preview` traz `fingerprint`, `files`, `excluded` (com o motivo),
`warned` (nomes que sugerem credencial) e `bytes`. Nomes vindos do ZIP: mostre com `ZipNames.display(...)`.

Para gravar o ZIP numa pasta escolhida em vez de guardar direto:
`Packager.build(p, plan, outDir, overwrite, cancel)` → `Built(zip, fingerprint, sha256, …)` e depois
`packages.addBuilt(context, built, cancel)`. `Packager.exists(outDir, plan)` diz se é preciso pedir consentimento.

### 3. Listar, renomear, associar, remover

```java
List<Item> list(PackageContext context)                              // ordem fixa: addedAt, depois impressão
Optional<Item> find(PackageContext context, String fingerprint)
ExecutionPackage inspect(PackageContext context, String fingerprint) throws ExecRefused   // confere a pasta de novo
Item rename(PackageContext context, String fingerprint, String localName) throws ImportProblem   // null/vazio = volta ao declarado
Item link(PackageContext context, String fingerprint, UUID automationId) throws ImportProblem
Item unlink(PackageContext context, String fingerprint) throws ImportProblem
boolean remove(PackageContext context, String fingerprint) throws ImportProblem

record Item(PackageContext context, String fingerprint, String declaredName, String packageVersion, String entry,
            String pythonRequirement, List<String> dependencies, String localName, UUID linkedAutomationId,
            Instant addedAt, Path dir)
    String displayName()        // localName, ou declaredName
    String shortFingerprint()   // 12 primeiros hexadecimais
```

- Um item só existe no contexto em que foi guardado; a mesma impressão em dois contextos são dois itens independentes.
- `rename`, `link` e `unlink` gravam **só** o arquivo de metadados: descritor, arquivos e impressão não mudam;
  `declaredName` continua disponível. Nome local: 1–80 caracteres, sem controle (`AGT-PAC-060`).
- Item que não está no contexto → `AGT-PAC-061`; falha de disco → `AGT-PAC-062` (ou `AGT-PAC-051`, sem espaço).
- `remove` não toca execuções já registradas nem uma execução em andamento (ela roda da própria cópia).
- `link`/`unlink` não enviam nada: é uma anotação local.

## `run` — o que a interface usa

### 4. Preparar

```java
ExecutionPackage pkg = packages.inspect(context, item.fingerprint());            // ExecRefused
SessionProbe.Session s = runs.session().probe();                                 // bloqueia (tasklist)
PythonRuntime.Probe py = PythonRuntime.probe(pythonEscolhido, runs.probeDir());  // ExecRefused AGT-EXE-008; bloqueia até 25 s
PythonRuntime.requireCompatible(py, pkg);                                        // ExecRefused AGT-EXT-022
PythonRuntime.requireDependencies(py.python(), pkg);                             // ExecRefused AGT-EXT-022; bloqueia até 25 s
boolean ok = runs.approvals().approved(pkg);
runs.approvals().approve(pkg, Instant.now());                                    // ato explícito da pessoa; NÃO inicia nada
runs.approvals().revoke(pkg);
```

- O interpretador é **o que a pessoa escolhe** (`python.exe` de um ambiente que ela preparou). `PythonRuntime.validate`
  exige caminho absoluto, arquivo comum e nome de interpretador. **Nada é instalado**: sem `pip`, sem criar ambiente.
  As dependências são só conferidas (versão exata instalada); se faltar, a pessoa instala por fora e confere de novo.
- Aprovação: por perfil (pasta de dados) e por impressão digital — vale para o mesmo conteúdo em qualquer contexto.
  `approved(String fingerprint)` serve para marcar a lista.
- `ExecutionSetup` (opcional) junta as escolhas e diz o que falta, em ordem:

```java
ExecutionSetup.empty().withSession(s).withPackage(pkg, null, ok).withPython(py, null).withOutput(pasta)
    .withEnvironmentProblem(code)          // quando requireDependencies recusar
List<ErrorCode> missing();  boolean canStart();
Optional<RunController.Request> request(RunContext context, Duration timeout)
// textos: kindLabel(pkg), originLabel(pkg), sessionLabel(s), cancellationLabel(c), EXTERNAL_OUTPUT_LABEL,
//         duration(ms), pythonFolder(path), shortName(path)
```

- Pasta de saída: precisa existir, fora do pacote e fora de `<dados>` (nem contê-la) → senão `AGT-EXE-011`.
  O script recebe uma subpasta por execução em `NEKKORA_SAIDA`; a pasta de trabalho dele é a **cópia** do pacote.
- O pacote Python externo não tem arquivo de entrada: o pedido não tem esse campo.

### 5. Contexto imutável

```java
record RunContext(UUID organizationId, UUID workspaceId, String fingerprint, String localName,
                  UUID automationId, UUID automationVersionId)
    static RunContext of(LocalPackages.Item item, UUID automationVersionId)   // nome e associação do item AGORA
    PackageContext where();  boolean in(PackageContext context)
```

Capturado no início e gravado em todos os estados do registro. `ExecutionRecord` não tem método que o troque;
`RunStore.save` recusa (`AGT-EXE-016`) gravar na pasta de uma execução um registro com outro contexto, e recusa trocar
um registro terminado. Renomear, desassociar ou remover o pacote, ou mudar de organização na tela, não alteram execuções
em andamento nem terminadas. Registro sem contexto válido é ilegível (conta em `Listing.unreadable`).

### 6. Iniciar, observar, cancelar

```java
RunController.Snapshot start(RunController.Request req) throws ExecRefused       // bloqueia nas conferências (segundos)
record Request(Path packageDir, RunContext context, Path python, Path outputRoot, Duration timeout)   // timeout null = 60 min
record Snapshot(ExecutionRecord record, long elapsedMillis, boolean running, boolean recordSaved)
    RunContext context()

void addListener(Listener l);  void removeListener(Listener l)                   // Listener.changed(Snapshot)
Optional<Snapshot> running()      // a execução ATIVA e de quem ela é; vazio se não há
boolean isRunning()
Optional<Snapshot> current()      // a ativa, ou a última terminada desde a abertura
boolean cancel()                                                                 // a ativa, pela janela; não bloqueia
CancelResult cancel(ExecutionId id, ExecutionRecord.CancelSource source)         // REQUESTED, ALREADY_REQUESTED, ALREADY_ENDED, NOT_ACTIVE, UNKNOWN_EXECUTION
int reconcile()                   // na abertura
void close()                      // no encerramento
RunStore store()                  // histórico: store().all(context), store().list().in(context), store().events(id)
```

- `start` confere de novo, nesta ordem: uma só execução ativa (`AGT-EXE-014`, sem criar registro); pacote e impressão do
  pedido (`AGT-EXE-001..005`, `-004` se mudou); aprovação da **mesma** impressão (`-007`); sessão (`-012`, `-013`, `-017`);
  Python, versão e dependências (`-008`, `AGT-EXT-022`); pasta de saída (`-011`). Cada recusa vira um registro `BLOCKED`
  com o contexto do pedido, e o `Listener` é avisado.
- O processo: `python -E -s -B <entrada>`, sem shell, sem argumento de fora, da cópia conferida, com ambiente mínimo
  (`PythonRuntime.ENV_KEEP` + `PYTHONIOENCODING`, `PYTHONUTF8`, `NEKKORA_SAIDA`, `NEKKORA_SINAL`). A saída padrão é drenada
  e descartada; só a contagem de bytes fica (`record.outputBytes()`).
- Os `Listener` são chamados na linha `nekkora-client-execucao` (a cada ~0,5 s enquanto roda) ou na que chamou `start`:
  repasse para a linha de eventos. `recordSaved == false` = o estado mostrado ainda não chegou ao disco (é tentado de novo).
- Desfecho (`record.state()`, `record.code()`):

| Situação | Estado | Código |
|---|---|---|
| código 0 + sinal `BUSINESS_SUCCESS` | `SUCCEEDED` | — |
| código 0 sem sinal | `SUCCEEDED` | `AGT-EXT-001` (sem erro sinalizado; **não** é sucesso de negócio) |
| sinal `BUSINESS_FAILURE` | `FAILED` | `AGT-EXT-003` |
| código ≠ 0 | `FAILED` | `AGT-EXT-002` |
| cancelado (árvore de processos encerrada) | `CANCELLED` | `AGT-EXE-024` |
| prazo esgotado (árvore encerrada) | `TIMED_OUT` | `AGT-EXE-023` |
| suspensão do Windows no meio e nenhum sinal | `OUTCOME_UNKNOWN` | `AGT-EXE-026` |
| fim não observado (aplicativo reiniciou) | `OUTCOME_UNKNOWN` | `AGT-EXE-025` |

- Cancelar: `record.cancellation()` = pedido (`requested`, `source`) + situação (`PENDING` → `CONFIRMED` ou
  `NOT_EFFECTIVE` se o processo terminou antes). Encerra o processo e os descendentes que ele criou, nunca outro processo.
- `reconcile()`: fecha registros abertos de uma sessão anterior sem inventar resultado e sem relançar nada. Processo ainda
  vivo é **adotado**: `running()` o mostra com o contexto original e ele pode ser cancelado; o fim dele é `OUTCOME_UNKNOWN`.

### 7. Sair, trocar de contexto, encerrar a sessão da pessoa

`close()` é o do Nekkora Agent: cancela a execução ativa com a origem `APPLICATION_SHUTDOWN`, espera até 15 s
(`RunController.CLOSE_WAIT`) o registro fechar e tenta gravar o que faltava. Bloqueia; sem execução ativa, volta logo.

Regra para a tela, com `running()`:

- **Sair do aplicativo** com execução ativa: pedir confirmação; confirmado, `close()` (fora da linha de eventos) e só
  então sair.
- **Trocar de organização/ambiente ou encerrar a sessão da pessoa** com execução ativa: **bloquear**, explicando que há
  uma execução em andamento (de `running().get().context()`), e oferecer cancelar. O núcleo não troca o contexto de uma
  execução em nenhuma hipótese; quem impede a troca na tela é a interface.

## De → para (classes)

| Nekkora-Agent (`com.nekkora.agent.…`) | Client |
|---|---|
| `exec.ExecutionController` | `run.RunController` (nome antigo proibido pelo `ArchitectureTest`) |
| `exec.ApprovalStore` | `run.ApprovalLedger` (idem) |
| `exec.ExternalLaunch` (só `SIGNAL_*`, `Signal`, `readSignal`, `classify`, `Outcome`) | `run.ExternalSignal` |
| `exec.Launch`, `PythonRuntime`, `ExecutionSetup`, `RunStore`, `ExecutionRecord`, `ExecutionState`, `ExecutionId`, `ProcessOwner`, `SessionProbe` | `run.*`, mesmos nomes |
| — | `run.RunContext` (novo) |
| `exec.ExecutionPackage`, `JsonReader`, `ExecRefused` | `pack.*`, mesmos nomes |
| `core.ErrorCode` (subconjunto) | `pack.ErrorCode` |
| `storage.AtomicWriter`, `storage.StrictJson` | `pack.AtomicWriter`, `pack.StrictJson` |
| `pack.ZipStager` | `pack.LocalPackages` (reescrito) + `pack.PackageContext` (novo) |
| `pack.SafeZip`, `ZipNames`, `ZipLimits`, `ImportProblem`, `Io`, `ProjectImporter`, `ImportedProject`, `Declarations`, `InclusionRules`, `PackagePlan`, `Packager` | `pack.*`, mesmos nomes |

Métodos: `ExecutionController.running()` (booleano) → `RunController.isRunning()`; `running()` agora devolve a execução
ativa. `supervise(…)` (interno) → `watch(…)`. `CancelSource.AGENT_SHUTDOWN` → `APPLICATION_SHUTDOWN`.
`AtomicWriter` e `StrictJson` ficam em `pack` (e não em `storage`) porque a regra de palavras do `storage`
(`token`, `code`, `state`…) não deixa esse código morar lá.

## O que ficou de fora, e por quê

| Fora | Por quê |
|---|---|
| Pacote do Studio (`manifest.json`) e de laboratório; `Mode`, opções de linha de comando, `nekkora_rpa` | o Client só aceita o pacote Python externo; os outros são **recusados** com motivo próprio |
| Arquivo de entrada (`Request.input`, `AGT-EXE-010`) | o pacote externo não declara entrada |
| `Request.presetId`, `origin`, `preLaunch`; `ExecutionRecord.Origin`; pedidos remotos; lançador do agendador; vínculos | ganchos da Platform e da máquina pareada: aqui a única origem é a pessoa, na janela |
| Retenção por entrega pendente, `RecordDiagnostics`, fila de envio | não há envio de registros neste núcleo |
| `ProtocolEvidence`, `CheckpointProgress`, `counts`, `progress`, `recovered` | protocolo do framework do Studio; depois de um reinício o desfecho é sempre `OUTCOME_UNKNOWN` |
| `FaultPoints` | os testes usam o `AtomicWriter` injetado como ponto de falha |
| `PackageHistory`, `ImportFlow`, `PackageShelf`, `ApprovalStore.clearAll` | dependem da Platform ou do desvínculo da máquina |
| `java.awt.GraphicsEnvironment` em `SessionProbe` | `run` não conhece AWT; entra por `BooleanSupplier` |

## Textos e códigos preservados

Sem mudança de código nem de texto em relação ao Nekkora-Agent `9b1e7e1` (conferido literal por literal):

- `ErrorCode` (22): `AGT-EXE-001`, `-002`, `-003`, `-004`, `-005`, `-007`, `-008`, `-011`, `-012`, `-013`, `-014`, `-015`,
  `-016`, `-017`, `-023`, `-024`, `-025`, `-026`; `AGT-EXT-001`, `-002`, `-003`, `-022`.
- `ImportProblem.Code` (46): `AGT-PAC-001`…`-018`, `-020`…`-026`, `-030`, `-031`, `-033`…`-042`, `-044`, `-045`, `-050`…`-056`.
- Rótulos: `ExecutionState.label()` (9), `InclusionRules.Rule.label()` (7), `ImportedProject.Kind` (`CODE`,
  `EXTERNAL_PACKAGE`, `EXTERNAL_INVALID`, `CONFLICT`), e em `ExecutionSetup`: `kindLabel`, `originLabel`, `sessionLabel`,
  `cancellationLabel`, `EXTERNAL_OUTPUT_LABEL`, `duration`.
- Formato e regras: `nekkora-external.json` (`nekkora-agent/external-python-package` 1.0.x), regra
  `nekkora-agent/external-python-fingerprint/1`, hash por arquivo, lista de arquivos proibidos, limites do ZIP e do pacote,
  gerador declarado `nekkora-agent-assistente/1.0.0`, sinal `nekkora-agent/external-signal` 1.0.0, `NEKKORA_SINAL`,
  `NEKKORA_SAIDA`, aprovações `nekkora-agent/approvals` 1.0.0.

Novos neste aplicativo: `AGT-EXE-030` (pacote do Studio ou de laboratório), `AGT-PAC-046` (idem, no ZIP),
`AGT-PAC-060` (nome local inválido), `AGT-PAC-061` (pacote não está neste ambiente), `AGT-PAC-062` (falha ao gravar na
pasta de pacotes); rótulo `UNSUPPORTED_PACKAGE`. Retirados: `AGT-PAC-032`, `AGT-PAC-043` e os códigos do Studio, da
Platform e da observabilidade.

Atenção — textos preservados que citam o produto antigo e que a tela pode querer rever (não foram alterados aqui):
`AGT-EXE-002` ("…manifesto do Studio ou descritor de laboratório"), `AGT-EXE-011` ("…dentro do pacote ou do Agent"),
`AGT-EXE-025` ("O Agent reiniciou…"), `AGT-PAC-042` e `AGT-PAC-054` ("o Agent…"), e `cancellationLabel`
("pelo encerramento do Agent").

## Testes

`./mvnw -q -B test` roda tudo sem Python; os 12 testes de `RunControllerTest` (processos reais) são **pulados** com o
motivo. Com `NEKKORA_TEST_PYTHON=<caminho do python.exe>` eles executam esse interpretador sobre scripts gerados na pasta
temporária do teste. Nenhum teste usa `%LOCALAPPDATA%`.
