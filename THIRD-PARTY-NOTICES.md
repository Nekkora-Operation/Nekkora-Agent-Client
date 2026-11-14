# Componentes de terceiros e origem dos ativos

## Execução (vão no aplicativo)

| Componente | Versão | Licença | Uso |
|---|---|---|---|
| FlatLaf (`com.formdev:flatlaf`) | 3.7.2 | Apache-2.0 | Aparência base do Swing e barra de título |
| OpenJDK (runtime reduzido por `jlink`) | 21 | GPLv2 com Classpath Exception | Runtime Java embarcado |

É a única biblioteca de execução. O teste `ArchitectureTest.umaUnicaDependenciaDeExecucao` falha se outra for
acrescentada sem atualizar este inventário.

## Só na construção e nos testes (não vão no aplicativo)

| Componente | Versão | Licença |
|---|---|---|
| JUnit Jupiter | 5.11.4 | EPL-2.0 |
| Maven Wrapper (`mvnw`, `mvnw.cmd`) | 3.3.4 | Apache-2.0 |
| Plugins do Maven (compiler, surefire, jar, dependency, resources) | ver `pom.xml` | Apache-2.0 |

## Ativos da Nekkora

| Ativo | Origem | Observação |
|---|---|---|
| Logos (lockup e ícone do aplicativo, PNG) | `Nekkora-Operation/Nekkora-lib`, `brand/export`, commit `0a29d95` | Marca da Nekkora; uso interno. Hashes em `docs/design/tokens-e-ativos.md` |
| Tokens de cor e de fundação | `Nekkora-Operation/Nekkora-lib`, `src/tokens`, commit `0a29d95` | Valores copiados para `ClientTheme`; os CSS ficam só nos testes |

A Nekkora-lib é privada (`"license": "UNLICENSED"`), da própria Nekkora.

## Código portado do Nekkora-Agent (núcleo local)

O núcleo local do Client foi **portado** (copiado e adaptado) do repositório `Nekkora-Operation/Nekkora-Agent`, branch
`dev`, commit `9b1e7e1`. É código da própria Nekkora; o Client continua **sem depender do JAR** do Nekkora Agent e sem
referenciar os pacotes dele (`ArchitectureTest.nenhumCodigoNemIntegracaoDoAgentAdministrativo`). Só o JDK é usado.

### Pacotes locais — `com.nekkora.client.pack`

| Classe de origem (`com.nekkora.agent.…`) | Classe no Client | O que mudou |
|---|---|---|
| `pack.SafeZip`, `pack.ZipNames`, `pack.ZipLimits`, `pack.Io` | mesmos nomes | nada (código idêntico) |
| `pack.ImportProblem` | `ImportProblem` | 46 motivos com o mesmo código e o mesmo texto; saíram `STUDIO_INVALID` e `NOT_CONVERTIBLE`; novos `AGT-PAC-046` e `AGT-PAC-060..062` |
| `pack.ProjectImporter`, `pack.ImportedProject` | mesmos nomes | ZIP de pacote do Studio ou de laboratório passa a ser **recusado** (`UNSUPPORTED_PACKAGE`) |
| `pack.Declarations`, `pack.InclusionRules`, `pack.PackagePlan` | mesmos nomes | nada (código idêntico) |
| `pack.Packager` | `Packager` | só a recusa do formato não aceito; descritor e ZIP gerados são os mesmos, byte a byte |
| `exec.ExecutionPackage` | `ExecutionPackage` | só o pacote Python externo (`nekkora-external.json`); formato do descritor, hash por arquivo, texto da regra da impressão digital e lista de arquivos proibidos sem mudança |
| `exec.JsonReader`, `exec.ExecRefused` | mesmos nomes | nada (código idêntico) |
| `core.ErrorCode` | `ErrorCode` | subconjunto: 22 códigos com o mesmo texto; novo `AGT-EXE-030` |
| `storage.AtomicWriter`, `storage.StrictJson` | `AtomicWriter`, `StrictJson` (em `pack`) | nada (código idêntico) |
| `pack.ZipStager` | `LocalPackages` (classe nova) | reescrito: pacotes guardados por organização e ambiente, com metadados locais |

Testes portados: `ZipForge`, `SafeZipTest`, `ProjectImporterTest`, `PackagerTest`, `ExternalPackageTest` (o vetor sintético
da impressão digital) e os projetos de exemplo de `src/test/resources/agent-pacotes-001/projetos` (aqui em
`src/test/resources/pacotes-locais/projetos`).

### O que ficou de fora, de propósito

- Pacote do Studio (`manifest.json`) e de laboratório (`nekkora-agent-lab.json`): não são aceitos; um ZIP desses formatos é
  recusado com `AGT-PAC-046` ou `AGT-EXE-030`.
- Tudo o que liga a máquina à Platform: `platform.*` (canal da máquina, credencial, pareamento, entrega de registros),
  `pack.ImportFlow`, `pack.PackageHistory`, `exec.PackageShelf`, `exec.ExternalBindings`, `exec.ExternalRuns`, o lançador do
  agendador (`exec.ExternalLaunch`, exceto o sinal e o desfecho) e os pedidos remotos de execução.
- Protocolo do framework do Studio (`exec.ProtocolEvidence`, `exec.CheckpointProgress`), observabilidade (`obs.*`), log e
  diagnóstico (`diagnostics.*`), pontos de falha de laboratório (`core.FaultPoints`), bandeja, atalhos e telas (`ui.*`).
- Nenhuma instalação: nem `pip`, nem criação de ambiente virtual, nem download.

## O que não foi reaproveitado

O Client não usa os módulos `nekkora-design-tokens` e `nekkora-swing-ui` do Nekkora-Studio. A fonte da interface é a Segoe
UI do Windows; nenhuma fonte é embarcada.
