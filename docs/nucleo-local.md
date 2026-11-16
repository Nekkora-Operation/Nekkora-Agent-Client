# Núcleo local: pacotes e execução

Dois pacotes Java sem tela e sem rede, só com o JDK:

- `com.nekkora.client.pack` — ler um ZIP não confiável, validar um pacote Python externo, calcular a impressão digital,
  empacotar o código de um projeto (de um ZIP ou de uma pasta), **guardar pacotes por organização e ambiente** e
  conferir e guardar um pacote que outra camada baixou da Platform;
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
  baixados/                                pacotes baixados da Platform (AGENT-CLIENT-PACOTES-001 T02)
    .parcial/                              arquivos em download (*.zip.parcial) e pastas de conferência; nunca tratados
                                           como prontos; sobras > 12 h são limpas no download seguinte
    <origem>/<organizationId>/<automationId>/<automationVersionId>/
      pacote.zip                           o ZIP conferido, como a Platform o entregou
      pacote.json                          metadados (nekkora-agent-client/downloaded-package 1.0.0)
  importacao/op-*/                         temporários de um ZIP de projeto ou de uma PASTA de projeto abertos
                                           (apagados em close(); sobras > 12 h); de uma pasta fica só o retrato
                                           (fonte/), sem credenciais, ambiente virtual, cache nem dados
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

Campos **opcionais** da origem (T02; mesmo esquema e versão — um arquivo antigo, sem eles, continua sendo lido, com origem
desconhecida): `origin` (`LOCAL_ZIP`, `LOCAL_FOLDER`, `BUILT`, `PLATFORM_DOWNLOAD`), `sourceLabel` (arquivo ou pasta de
onde veio, ou `null`) e, para um pacote baixado, `sourceAutomationId`, `automationVersionId` e `versionNumber`. Um valor
de `origin` que esta versão não conhece é lido como origem desconhecida, sem perder o item.

`<origem>` em `baixados/` são os 16 primeiros hexadecimais do SHA-256 do endereço da Platform (minúsculas, sem a barra
final). `pacote.json`: `origin`, `organizationId`, `automationId`, `automationVersionId`, `versionNumber`, `sha256`,
`sizeBytes`, `fingerprint`, `installedAt`; só vale no lugar a que pertence.

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

### 3A. Gerar um pacote `.zip` a partir de uma pasta (AGENT-CLIENT-PACOTES-001 T01)

A tela não conhece arquivos: fala só com `Workshop`, com caminhos em texto, e recebe **uma** frase por recusa
(`Workshop.Refused`). A entrada é uma **pasta**; a saída é **um arquivo .zip onde a pessoa escolheu**. Nada é guardado em
`pacotes/`, nada é executado, importado ou instalado, e nada vai para a rede. O formato é o de sempre
(`nekkora-external.json`, mesma regra de impressão): `Packager` e `ExecutionPackage` não mudaram de regra.

```java
Draft openFolder(String folderPath) throws Refused
    // Draft: folder(), sourceLabel(), blockers(), notes(), pythonFiles(), suggestedEntry(), python(), dependencies(),
    //        unsupportedDependencies(), dependenciesNeedConfirmation(), existingManifest(), close()
PackageSummary summary(Draft, String name, String version, String entry, String python, List<String> dependencies,
                       boolean dependenciesConfirmed[, String destinationZipPath]) throws Refused      // não grava nada
boolean exists(String destinationZipPath)
Generated generate(Draft, String name, String version, String entry, String python, List<String> dependencies,
                   boolean dependenciesConfirmed, String destinationZipPath, boolean overwrite, SafeZip.Cancel cancel)
        throws Refused        // DestinationExists (subclasse de Refused): destino existe e overwrite == false

record Manifest(String name, String version, String entry, String python, List<String> dependencies, String fingerprint)
record ExcludedFile(String path, String reason)
record PackageSummary(String name, String version, String entry, String python, List<String> dependencies,
                      List<String> included, List<ExcludedFile> excluded, List<String> warned, String fingerprint,
                      long sizeBytes, boolean manifestReused)
record Generated(String path, String name, String version, String entry, String fingerprint, String sha256,
                 int fileCount, long sizeBytes)
```

**Retrato.** `openFolder` resolve a pasta (`toRealPath`) e a lê **uma vez** para `importacao/op-*/fonte`. Tudo o que é
mostrado, conferido e empacotado depois é esse retrato; a pasta da pessoa só é lida de novo para saber se mudou. A pasta
(e um manifesto que esteja nela) **nunca é alterada**.

- Nada é seguido: ligação simbólica, junção do Windows ou outro item especial recusa a pasta (`AGT-PAC-071`), a não ser
  dentro do que já fica fora. Não há como trazer um arquivo de fora da pasta por ligação, junção, `..` ou caminho absoluto.
- Pastas `.git`, `.venv`, `venv`, `env`, `node_modules`, `__pycache__`, `.idea`, `.vscode`… (regra `forbidden` do
  validador) **não são lidas, nem copiadas, nem contam nos limites**; aparecem no resumo como `nome/`, com o motivo.
- Arquivos que não entram (credenciais, dados, configuração iniciada por ponto) são **listados com o motivo e não
  copiados** — uma credencial nunca chega à pasta de trabalho. Exceção: um arquivo de dados que o
  `nekkora-external.json` da raiz lista é copiado, para o manifesto poder ser conferido e mantido. Passando de 2000
  arquivos de fora, o resto vira só uma contagem (em `notes()`).
- Limites dos arquivos copiados: os de `ZipLimits` (1000 arquivos, 50 MiB por arquivo, 100 MiB no total, 16 níveis) →
  `AGT-PAC-073/074/075/077`. Nomes: regras do Windows de `ZipNames` (reservado, caractere inválido, colisão só por
  maiúsculas) → `AGT-PAC-076`. O limite do **pacote** (500 arquivos, 50 MiB, nomes do contrato) continua em `summary`.

**Manifesto que já está na pasta** (só o da raiz conta):

| Raiz da pasta | `Draft` |
|---|---|
| sem descritor | código; `existingManifest()` vazio; versão padrão `1.0.0` |
| `nekkora-external.json` válido (arquivos listados conferem) | `existingManifest()` com nome, versão, entrada, Python e dependências: são os valores usados quando o campo vem nulo ou em branco. **A versão nunca sobe sozinha.** |
| `nekkora-external.json` inválido ou que não confere | `blockers()` com `AGT-PAC-081` (e o código do validador) |
| `nekkora-external.json` junto com `manifest.json`/`nekkora-agent-lab.json` | `blockers()` com `AGT-PAC-080` |
| `manifest.json` do Studio ou `nekkora-agent-lab.json` | `blockers()` com `AGT-PAC-082` |

Com manifesto válido, se as escolhas e os arquivos são **exatamente** os que ele declara, o descritor da pasta vai para o
ZIP byte a byte (`manifestReused`, mesma impressão digital do pacote que a pasta já é). Se algo muda (versão, entrada,
arquivo novo…), um descritor novo é escrito **só no ZIP**. Um arquivo de dados que o manifesto lista continua incluído.

**Resumo e geração.**

- Nome, versão, Python e dependências: regras do `Packager` (`AGT-PAC-035..038`, `-042`). A entrada precisa ser
  exatamente um item de `pythonFiles()` — `..`, caminho absoluto, outra caixa, barra invertida ou arquivo que fica fora →
  `AGT-PAC-034`.
- Destino: um `.zip` numa pasta que existe (`AGT-PAC-085`). Existe e `overwrite == false` → `DestinationExists`
  (`AGT-PAC-086`), e o arquivo fica como estava.
- **O pacote não se inclui.** O destino é escolhido depois do retrato; por isso, em `summary` (com destino) e em
  `generate`, o arquivo do retrato cujo caminho é o do destino fica fora (motivo "é o próprio arquivo de destino"). O
  temporário nasce depois do retrato e nunca entra. Atenção: `InclusionRules` **não** trata `*.zip` como saída de build —
  outro `.zip` que esteja na pasta entra como qualquer arquivo (a regra de conteúdo não foi mudada).
- Antes de gravar, e de novo antes da troca de nome, cada arquivo **incluído** é conferido na pasta (tamanho e data; na
  primeira vez também o conteúdo). Mudou → `AGT-PAC-072`, "abra a pasta de novo". O que não entra no pacote pode mudar.
- Gravação: temporário `.<nome>.….parcial` ao lado do destino → relido por `SafeZip` + `ExecutionPackage.inspect` com a
  impressão igual à calculada → troca de nome (`ATOMIC_MOVE`; substituir só com `overwrite`). Falha ou cancelamento
  (`AGT-PAC-084`) apagam o temporário; nunca fica um arquivo com o nome final.
- Mesma pasta + mesmas escolhas ⇒ mesmos bytes e mesma impressão digital.

Os métodos antigos do ZIP (`open`, `build`, `importZip`) continuam como eram.

### 3B. Pacote nesta máquina e pacote baixado (AGENT-CLIENT-PACOTES-001 T02)

Sem rede aqui: o download é feito por outra camada, que recebe de `Workshop` o lugar do arquivo parcial.

```java
LocalCandidate inspectLocal(String path)                               // não guarda nada, nunca lança
UseLocalResult useLocal(UUID organization, UUID workspace, UUID automationId, String path,
                        String publishedFingerprintOrNull, Choice choice) throws Refused

enum LocalKind { PACKAGE_ZIP, PACKAGE_FOLDER, CODE_FOLDER, UNSUPPORTED }
record LocalCandidate(LocalKind kind, String pathLabel, String declaredName, String packageVersion, String fingerprint,
                      String message)            // isPackage(), originLabel()
enum Choice { ASK, SEPARATE_LOCAL }
enum UseLocalOutcome { MATCHES_PUBLISHED, DIFFERS, STORED_SEPARATE }
record UseLocalResult(UseLocalOutcome outcome, LocalItem item, String origin, String pathLabel, String fingerprint,
                      String declaredName, String packageVersion)

String downloadTarget(String platformOrigin, UUID organization, UUID automationId, UUID versionId) throws Refused
OutputStream downloadStream(String tempFilePath) throws Refused
LocalItem installDownloaded(String platformOrigin, UUID organization, UUID workspace, UUID automationId, UUID versionId,
                            int versionNumber, String tempFilePath, String expectedSha256, long expectedSizeBytes,
                            String expectedFingerprint, SafeZip.Cancel cancel) throws Refused
void discardDownload(String tempFilePath) throws Refused

record LocalItem(String fingerprint, String name, String declaredName, String packageVersion, UUID linkedAutomationId,
                 LocalPackages.Origin origin, String sourceLabel, UUID sourceAutomationId, UUID automationVersionId,
                 Integer versionNumber)          // matches(publishedFingerprint), originLabel()
```

**Escolha local.** Um `.zip` passa por `SafeZip` + `ExecutionPackage.inspect`; uma pasta passa pelo retrato de
`openFolder` (mesma recusa de ligação e junção) e o manifesto e os arquivos listados são conferidos pelo mesmo validador.

| Escolha | `kind` | `useLocal` |
|---|---|---|
| ZIP de pacote válido | `PACKAGE_ZIP` | compara a impressão |
| pasta com `nekkora-external.json` válido | `PACKAGE_FOLDER` | compara a impressão |
| pasta com código Python, sem manifesto | `CODE_FOLDER` | `Refused` (`AGT-PAC-092`, "Gerar pacote novo"); nada guardado |
| pacote do Studio/laboratório, inválido, ZIP só de código, inexistente | `UNSUPPORTED` | `Refused` com a explicação |

- A comparação é **só pela impressão digital** (conteúdo canônico), nunca pelo nome nem pelos bytes do ZIP: o mesmo
  pacote recompactado (outra ordem, outra compressão, dentro de uma pasta externa) é o mesmo pacote.
- Igual à publicada → guardado (`LocalPackages.addInspected`, uma única leitura: o que foi conferido é o que é guardado)
  e associado (`link`) → `MATCHES_PUBLISHED`.
- Diferente, ou sem impressão publicada → `Choice.ASK` (ou `null`): **nada é guardado**, `DIFFERS`;
  `Choice.SEPARATE_LOCAL`: guardado como item só local, **sem associação** → `STORED_SEPARATE`. Um item assim nunca é
  apresentado como a versão publicada (`item.matches(publicada)` é falso).
- A origem registrada é a do primeiro jeito como o conteúdo chegou ao contexto; um download conferido do mesmo conteúdo
  passa a origem para `PLATFORM_DOWNLOAD`. `importZip` registra `LOCAL_ZIP` e `build`, `BUILT`.

**Download.** `downloadTarget` reserva `baixados/.parcial/<origem>-<versão>-….zip.parcial` (vazio); quem baixa escreve
por `downloadStream` e chama `installDownloaded` ou `discardDownload`. Ordem em `installDownloaded`:

1. tamanho e SHA-256 do arquivo contra o informado pela Platform, **antes** de abri-lo como ZIP (valor esperado ausente
   ou fora do formato também é divergência);
2. leitura segura (`SafeZip`: caminho que sai da pasta, ligação, colisão, limites) e validador real;
3. impressão digital calculada igual à informada;
4. o ZIP conferido é movido (troca atômica) para a pasta da versão, o pacote é guardado no contexto com origem
   `PLATFORM_DOWNLOAD` (automação, versão e número) e associado à automação.

Divergência em 1 ou 3 → `AGT-PAC-095`; em 2 → `AGT-PAC-096` (com o código do leitor ou do validador); cancelamento →
`AGT-PAC-099`. Em qualquer desfecho o parcial deixa de existir; numa recusa nada fica fora de `.parcial` e nada vai para
`pacotes/`. A mesma versão de novo não duplica nada; outra versão da mesma automação tem a sua pasta e o seu item, e
nunca substitui nem apaga a anterior. Organizações, ambientes e endereços da Platform diferentes não dividem pasta.
`discardDownload`, `downloadStream` e `installDownloaded` recusam (`AGT-PAC-097`) qualquer caminho que não seja um filho
direto de `.parcial` com o sufixo `.zip.parcial`. Nada aqui executa script nem instala dependência.

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
- O processo: `python -E -s -B -u <entrada>`, sem shell, sem argumento de fora, da cópia conferida, com ambiente mínimo
  (`PythonRuntime.ENV_KEEP` + `PYTHONIOENCODING`, `PYTHONUTF8`, `NEKKORA_SAIDA`, `NEKKORA_SINAL`). A saída padrão e a de
  erro são lidas linha a linha e gravadas em `saida.txt`, na pasta do registro da execução, só neste computador
  (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05; ver `docs/execucao-remota.md`, "Execução local"). O registro e os eventos
  continuam sem nada do que o script escreve: neles só fica a contagem de bytes (`record.outputBytes()`).
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
pasta de pacotes); `AGT-PAC-070`…`-086` (pacote a partir de uma pasta: pasta inexistente, ligação ou junção, pasta mudou,
limites, nomes, leitura, sem `.py`, manifesto em conflito/inválido/de outro formato, pasta do aplicativo, cancelamento,
destino inválido, destino existente); `AGT-PAC-090`…`-099` (escolha local inválida ou inexistente, pasta de código,
ZIP ou pasta que não é pacote, download que não confere, download que não é pacote válido, caminho que não é um parcial
do aplicativo, falha ao gravar em `baixados/`, download cancelado); rótulo `UNSUPPORTED_PACKAGE`. Retirados: `AGT-PAC-032`, `AGT-PAC-043` e os códigos do Studio, da
Platform e da observabilidade.

Atenção — textos preservados que citam o produto antigo e que a tela pode querer rever (não foram alterados aqui):
`AGT-EXE-002` ("…manifesto do Studio ou descritor de laboratório"), `AGT-EXE-011` ("…dentro do pacote ou do Agent"),
`AGT-EXE-025` ("O Agent reiniciou…"), `AGT-PAC-042` e `AGT-PAC-054` ("o Agent…"), e `cancellationLabel`
("pelo encerramento do Agent").

## Testes

`./mvnw -q -B test` roda tudo sem Python; os 12 testes de `RunControllerTest` (processos reais) são **pulados** com o
motivo. Com `NEKKORA_TEST_PYTHON=<caminho do python.exe>` eles executam esse interpretador sobre scripts gerados na pasta
temporária do teste. Nenhum teste usa `%LOCALAPPDATA%`.
