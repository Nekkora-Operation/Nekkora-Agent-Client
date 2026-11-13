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

## O que não foi reaproveitado

Nenhum código do repositório `Nekkora-Agent` foi copiado, e o Client não depende do JAR dele. Também não usa os módulos
`nekkora-design-tokens` e `nekkora-swing-ui` do Nekkora-Studio. A fonte da interface é a Segoe UI do Windows; nenhuma
fonte é embarcada.
