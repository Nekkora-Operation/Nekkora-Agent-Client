# Ambiente gerenciado por execução (AMBIENTE-PYTHON-001)

Contrato: `docs/ambiente-python-001/CONTRATO.md`, no repositório da API (seções 0, 3, 4 e 5 tratam deste aplicativo).

O modo de sempre continua sendo o **Python preparado** (PREPARED): a automação roda no Python escolhido na Platform, como ele
está, e o aplicativo só confere as dependências. O modo **ambiente gerenciado** (MANAGED) existe só quando a Platform o oferece
(chave `NEKKORA_AMBIENTE_GERENCIADO`), a máquina declara `MANAGED_ENV_1_0` e há um aceite vigente para a versão e a máquina.

**Nada é instalado no Python deste computador.** Para cada execução, um ambiente virtual temporário é criado **a partir** de
uma instalação de Python daqui, recebe exatamente a lista de pacotes aceita na Platform e é apagado no fim.

## Quem faz o quê

| Camada | Papel |
|---|---|
| `env` (nova) | A **única** que cria ambiente virtual e chama o `pip`: resolução, preparo, cache. Cria processos só por `EnvCommand`; sem rede própria, sem tela, sem conhecer o canal da máquina |
| `remote` | Conversa com a Platform: capacidade, pedido e resposta de resolução, etapas, relatório, recusas, diário. Não cria processos |
| `run` | Lança a automação (com o interpretador do ambiente preparado) e oferece a contenção de processos (`ProcessContainer`) e a remoção sem seguir ligações (`SafeTree`) |
| raiz (`RemoteWork`) | A ponte: traduz o pedido do canal para `env` e `run` |

O `ArchitectureTest` prova o estreitamento: os argumentos de instalação (`-m`, `pip`, `install`, `venv`, `ensurepip`, `--target`,
`uv`, `virtualenv`, `conda`, `poetry`) só aparecem em `env`; `pack`, `run` e `nav` continuam proibidas, e todas as outras também.
Em `env`, todo processo nasce em `EnvCommand`, com `-I` como primeiro argumento (seguido de `-X utf8`, para a saída chegar em UTF-8), e todo `pip install` leva `--isolated --no-input`.

## Capacidade

A presença só declara `MANAGED_ENV_1_0` **depois** de uma resposta de presença trazer `managedEnvironments: true` (na raiz; também
aceito dentro de `remote`). Sem isso o corpo da presença é exatamente o de antes. Quando a oferta aparece (aplicativo recém-aberto ou chave recém-ligada), uma segunda presença sai na hora, já com a capacidade, para a máquina não aparecer como "não compatível" até o minuto seguinte. Se a Platform deixar de oferecer, a capacidade
deixa de ir na presença seguinte.

## Resolução (D1)

Pedido em `GET /remote/next` (`environmentResolution`, junto da consulta ou sozinho). Roda no **executor próprio** do ambiente
gerenciado (uma tarefa por vez); a resposta vai na consulta seguinte (`POST /remote/environment-resolutions/{id}/answer`).

1. O Python é localizado pelo identificador opaco publicado no inventário (o mesmo cálculo da execução). Não está mais aqui:
   `ENVIRONMENT_GONE`, sem escolher outro.
2. `<base> -I -m venv remota\resolucoes\<id>\venv`.
3. `pip --version` no ambiente: anterior a 22.2 (ou ausente) é `NOT_RESOLVED / PIP_UNAVAILABLE`.
4. `python -I -m pip install --isolated --no-input --disable-pip-version-check -vv --progress-bar off --dry-run
   --ignore-installed --only-binary=:all: --report <f> --index-url <índice> (--cache-dir remota\cache-pip | --no-cache-dir)
   -r requisitos.txt`.
5. O lock sai do relatório por um código fixo, no próprio Python do ambiente temporário: `metadata.name`, `metadata.version`,
   `download_info.archive_info.hashes.sha256` e o nome do arquivo da URL. É conferido como o contrato pede (0 a 400 itens, nomes
   sem repetição pela PEP 503, cada item pedido na mesma versão, SHA-256 com 64 hex, `.whl` sem barra, até 200 caracteres).
6. A pasta da resolução é apagada sempre, com sucesso ou não.

| Resultado | Fase | Quando |
|---|---|---|
| RESOLVED | — | o lock passou na conferência |
| INCOMPATIBLE | `NO_COMPATIBLE_WHEEL` (com o item) | "No matching distribution found" (por exemplo, só há sdist) |
| INCOMPATIBLE | `NOT_FOUND` (com o item) | o índice respondeu 404 para o projeto |
| INCOMPATIBLE | `RESOLUTION_CONFLICT` | `ResolutionImpossible` / dependências em conflito |
| NOT_RESOLVED | `INDEX_UNREACHABLE` | erro de conexão com o índice (decidido antes de "não achou", porque sem rede o pip também diz que não achou) |
| NOT_RESOLVED | `PIP_UNAVAILABLE` | sem pip, ou pip anterior a 22.2 |
| NOT_RESOLVED | `TIMEOUT` | 8 minutos |
| NOT_RESOLVED | `OTHER` | qualquer outra coisa (inclui lock fora do contrato) |

O `-vv` só serve para distinguir `NOT_FOUND` (a linha do 404 é de depuração no pip); a saída fica num arquivo da pasta temporária
e não vai à Platform: só o resumo (até 500 caracteres, sem segredos).

## Preparo de uma tentativa MANAGED

A oferta traz `environment` (`mode: MANAGED`, lock aceito, `lockSha256`, `checks`); sem o campo, é PREPARED e nada muda. Um
aplicativo que não prepara ambientes recusa a tentativa MANAGED como `CLIENT_INCOMPATIBLE`.

Depois do PREPARING, do download do pacote e de localizar o Python escolhido (como sempre):

1. **diário em `ENV_PREPARING`** (com o lock) — gravado **antes** de criar qualquer coisa; sem conseguir gravar, `DISK_PRESSURE`;
2. o preparo vai para o executor próprio. Etapa `ENV_CREATING`: `<base> -I -m venv remota\tentativas\<t>\ambiente`;
3. etapa `DEPS_INSTALLING`: `ambiente\Scripts\python.exe -I -m pip install --isolated --no-input --disable-pip-version-check
   --progress-bar off --no-deps --require-hashes --only-binary=:all: --index-url <índice> (--cache-dir … | --no-cache-dir)
   -r lock.txt`, com linhas `nome==versão --hash=sha256:…`. Lock vazio: o pip nem roda;
4. conferência pela mesma sondagem de metadados da execução: as versões instaladas são **exatamente** as do lock e a lista efetiva da
   versão continua satisfeita;
5. `checks`: `PLAYWRIGHT_CHROMIUM`, `PLAYWRIGHT_FIREFOX`, `PLAYWRIGHT_WEBKIT` perguntam ao Playwright **do ambiente**
   (`browser_type.executable_path`) e conferem que o arquivo existe. **Nada é instalado** (sem `playwright install`). Código
   desconhecido, ou Playwright fora do lock: falha;
6. relatório `READY` (`PUT /remote/attempts/{t}/environment-report`), com o que foi instalado, os tempos e o registro;
7. a Platform reconfere (`STARTING`; aceite revogado é `AUTHORIZATION_REVOKED`);
8. **diário em `STARTING`** e, só então, o lançamento: `ambiente\Scripts\python.exe -E -s -B -u <entrada>`, com o ambiente
   reduzido de sempre. O motor confere de novo, nesse interpretador, as dependências da Platform e as do pacote.

Durante o preparo, a consulta (15 s) informa cada etapa nova assim que a vê e, sem novidade há 30 s, repete a última etapa (ou
PREPARING) para renovar o prazo. A presença (60 s) e a consulta continuam a correr: o pip roda em outra thread. A Platform mandando
parar (cancelamento ou fim) encerra o pip e o que ele abriu pelo grupo de processos do Windows: relatório `INTERRUPTED` e estado
`CANCELLED` (ou nada, se a Platform já encerrou). Prazo do preparo inteiro: 15 minutos.

### Falha

A automação **não** é iniciada. Primeiro vai o relatório `FAILED` (item, fase, resumo e registro sem segredos); depois, o estado
`REFUSED` com o motivo. O ambiente já foi apagado.

| Motivo de recusa | Fases |
|---|---|
| `ENVIRONMENT_SETUP_FAILED` | `VENV`, e `TIMEOUT`/`NO_SPACE`/`OTHER` na criação do ambiente |
| `DEPENDENCY_INSTALL_FAILED` | `DOWNLOAD`, `HASH_MISMATCH` (com o item), `NO_COMPATIBLE_WHEEL`, `INSTALL`, `VERIFY`, e `TIMEOUT`/`NO_SPACE`/`OTHER` na instalação |
| `EXTERNAL_PREREQUISITE_MISSING` | `PREREQUISITE` (com `playwright==<versão do lock>` e quais navegadores faltam) |
| `ENVIRONMENT_SETUP_INTERRUPTED` | o aplicativo foi fechado no meio do preparo (abaixo) |

### Reinício

Só a fase `ENV_PREPARING` vira `REFUSED ENVIRONMENT_SETUP_INTERRUPTED`, com relatório `INTERRUPTED`. É certo que a automação não foi
iniciada: o lançamento só acontece depois de o diário gravar `STARTING`. `STARTING` e `RUNNING` seguem a regra de sempre (processo
vivo é adotado; `STARTING` sem prova vira `ENDED_UNKNOWN`).

### Registro do preparo

O que o `venv` e o `pip` escreveram, com linhas do aplicativo (`[Nekkora Agent] …`), sem segredos — credencial em URL
(`://usuario:senha@`), cabeçalhos `Authorization`/`Proxy-Authorization`, `Bearer`/`Basic`, `token=`, `senha=`, `api_key=`, parâmetros
de URL como `?token=`, credencial da máquina — e com no máximo 64 KiB (passando disso fica o começo e o fim, com o corte dito).
Só a falha estruturada (`failure`) vai em `FAILED`; em `READY` e `INTERRUPTED` ela vai nula, como a Platform exige.

## Limpeza

- `ambiente\` sai no fim da tentativa (com a mesma regra do código: nunca com processo da execução vivo), depois de qualquer falha
  do preparo e na varredura ao abrir e de hora em hora (tentativas que não são a pendente).
- `resolucoes\<id>\` sai no fim de cada resolução e na varredura (nunca a de uma resolução em andamento).
- A pasta de uma tentativa em preparo nunca é apagada por fora.
- A remoção nunca segue junção nem ligação simbólica (`SafeTree`: a ligação some como entrada; o outro lado fica intacto).
- Resultados e registros não entregues seguem a regra de hoje.

## Cache do pip (D4)

| Ponto | Como é |
|---|---|
| Onde | `remota\cache-pip` |
| Limite | padrão 2048 MiB; de 0 a 20480, na tela (gravado em `preferencias.properties`, chave `ambienteGerenciado.cacheMiB`). Fora da faixa: o padrão |
| 0 | o pip roda com `--no-cache-dir`, e o cache é apagado com a máquina parada |
| Poda | depois de cada resolução ou preparo, **só com o executor parado** (mesma thread, nada na fila): os arquivos mais antigos saem até o total ficar abaixo de 80% do limite. Nunca durante um preparo |
| "Limpar cache" | na tela, só habilitado com a máquina parada; a ação confere de novo e roda no executor |

O pip só guarda no cache respostas HTTPS (o PyPI); um índice de laboratório em HTTP não usa o cache.

## Índice e ambiente do pip

- Índice: `https://pypi.org/simple`. A propriedade de laboratório `nekkora.client.managedIndexUrl` só vale quando a `apiOrigin` do
  aplicativo é `localhost` ou `127.0.0.1`, e só com um endereço `http(s)` sem credencial; em qualquer outro caso é ignorada.
- O processo do pip recebe só as variáveis da lista reduzida da execução (nenhuma `PIP_*`, nenhum `*_PROXY`, nada de `PYTHONPATH`)
  e roda com `--isolated` (sem configuração pessoal).
- **Proxy:** este aplicativo não tem configuração de proxy; o pip vai direto ao índice. Uma configuração de proxy do Client, se um
  dia existir, é o único lugar de onde ela poderá vir.

## Tela

Em "Execução remota" → "Detalhes técnicos" → **Ambientes gerenciados**: o que é, quanto o cache ocupa, o limite editável
("Salvar limite") e **Limpar cache**. Valor de limite inválido é recusado na tela; nada é gravado.

## Limites conhecidos

- Com uma tentativa no diário, o aplicativo não consulta `remote/next`; um pedido de resolução espera a tentativa terminar.
- Cada processo do pip roda no seu próprio grupo de processos do Windows (no preparo ainda não existe o grupo da execução, que só
  nasce no lançamento). Se o aplicativo cair (não fechar), um pip em andamento pode seguir até terminar; o que ele deixar sai na
  varredura seguinte.
- `NOT_FOUND` depende da linha de 404 do pip em `-vv`; um índice que responda de outro jeito aparece como `NO_COMPATIBLE_WHEEL`.

## Testes

| O que | Teste |
|---|---|
| Resolução com dependência indireta, sem wheel, não encontrado, conflito, índice fora do ar; preparo de ponta a ponta com conferência e Playwright (falso, do índice de teste); SHA-256 diferente; cancelamento encerra o pip; cache (limite 0 e poda até 80%); varredura sem seguir junção | `env.ManagedEnvironmentsTest` (Python real; índice local em 127.0.0.1, sem internet) |
| Resolve, prepara e executa a automação no ambiente (importa um pacote que só existe nele; `sys.prefix` é o do ambiente); interpretador fora do ambiente da tentativa recusado; varredura; limite do cache | `ManagedEnvironmentWorkTest` (Python real) |
| Capacidade só depois da presença; corpo de antes inalterado; PREPARED igual; ordem do preparo (ENV_PREPARING antes de criar, etapas, READY antes de STARTING, lançamento só com STARTING no diário); falha (relatório antes da recusa); reinício em ENV_PREPARING; presença e consulta seguem com o preparo parado; renovação a cada 30 s; cancelamento; resolução e ENVIRONMENT_GONE; aplicativo sem suporte | `remote.ManagedEnvironmentDeliveryTest` |
| Classificação das falhas do pip e remoção de segredos | `env.PipTextTest` |
| Índice de laboratório só com a API em localhost/127.0.0.1; remoção sem seguir junção | `env.ManagedIndexTest` |
| Estreitamento: venv e pip só em `env` | `ArchitectureTest.soACamadaEnvCriaAmbienteEChamaOPip` |
| Tela: uso, limite inválido recusado, limite gravado, "Limpar cache" desabilitado durante uma resolução e funcionando parado | `ui.ManagedCacheDesktopTest` (`-Pdesktop`) |
