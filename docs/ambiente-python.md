# Ambiente Python da execução local

AGENT-CLIENT-PYTHON-001. Como o aplicativo encontra os ambientes Python que a pessoa pode escolher e como confere cada um
contra o pacote. **Nada é instalado, criado, baixado ou alterado**: nem Python, nem ambiente virtual, nem dependência, nem
PATH, nem registro do Windows.

Código: `com.nekkora.client.run` — `PythonSources` (fontes), `PythonProbe` (sondagem), `PythonEnvironments` (regras) e a
fachada `Runner.pythons / projectPythons / recheckPython`.

## 1. Instalações deste computador: descoberta limitada

Só são consultadas fontes que o próprio sistema mantém. O aplicativo **não** percorre discos, não lê pastas do usuário e
não procura ambientes virtuais de projetos.

| Fonte (Windows) | Como | Quando não existe |
|---|---|---|
| Inicializador do Python | `py.exe -0p` (em `%SystemRoot%` ou no PATH) | a fonte é ignorada |
| Registro de instalações (PEP 514) | `reg.exe export` de `HKCU\Software\Python`, `HKLM\Software\Python` e `HKLM\Software\WOW6432Node\Python` para um arquivo na pasta de trabalho do aplicativo, apagado em seguida. **Só leitura** | chave ausente não é falha |
| PATH | `python.exe` em cada pasta absoluta do PATH | lista vazia |

Fora do Windows, só o PATH (`python3`, `python`).

- Os dois programas do sistema são chamados pelo caminho absoluto, com argumentos separados, **sem shell**, com prazo de 10 s.
- Uma fonte que existe e não responde marca a busca como **parcial**; as outras continuam.
- **Microsoft Store:** nada da pasta `WindowsApps` é candidato e nada dela é executado. É lá que fica o alias que, sem o
  Python da Store, abre a loja. Consequência declarada: um Python instalado pela Store não aparece na lista.
- Candidato que não existe, não é arquivo comum ou não responde como Python **não vira opção**.

### Duplicatas e ambientes virtuais

Cada caminho distinto é sondado uma vez. Dois candidatos são o mesmo ambiente quando o interpretador responde o **mesmo
prefixo**. Se o interpretador responde que é um ambiente virtual (prefixo diferente do da instalação-base), ele é
apresentado como **ambiente virtual**, nunca como instalação do computador, mesmo tendo chegado pelo PATH. Um ambiente
virtual e a sua instalação-base nunca são fundidos: as dependências de um não são as do outro.

## 2. Pasta da automação

A pessoa indica uma **pasta** (não um `.py`, não um `python.exe`). O aplicativo olha só, diretamente dentro dela:

- `.venv\Scripts\python.exe`
- `venv\Scripts\python.exe`

(nos outros sistemas, `bin/python`). Regras:

- Para ser um ambiente, a pasta precisa ter `pyvenv.cfg` e o interpretador, e o interpretador precisa responder que é um
  ambiente virtual **daquela** pasta. O nome do diretório sozinho não basta.
- Existindo os dois, os dois são listados; nenhum é escolhido sozinho.
- Nada é procurado em subpastas, em pastas vizinhas ou acima.
- Se `.venv` ou `venv` for um link ou uma junção que leva para fora da pasta escolhida, **não é seguido** e isso é
  informado.
- Um ambiente legítimo aponta, no `pyvenv.cfg`, para uma instalação-base fora da pasta. Isso não é busca em outro projeto:
  é resolvido pelo próprio interpretador. Se a base não existir mais, a sondagem falha e o ambiente aparece como "não foi
  possível verificar".
- Limite: um ambiente cujo interpretador é um link (criado com `--symlinks`) é tratado como incompleto, porque o executor
  só aceita arquivo comum.

## 3. Sondagem

`<python> -I -B -c <código fixo> <nomes das distribuições>`, na pasta de trabalho do aplicativo (nunca na do projeto), só
com as variáveis de ambiente da lista fechada do executor, com prazo de 12 s e cancelamento. O processo iniciado pelo
aplicativo (e o que ele tiver aberto) é encerrado se não responder; nenhum outro processo é tocado.

- O código é fixo e do aplicativo. Ele responde a versão, o prefixo, o prefixo-base e, pelos **metadados de distribuição**
  (`importlib.metadata`), a versão instalada de cada dependência declarada. Nenhuma dependência é importada e nenhum
  módulo do projeto é carregado.
- `-I` tira da busca de módulos a pasta atual, a do script e a de usuário, e ignora as variáveis `PYTHON*`; `-B` não grava
  `.pyc`.

**O que isto não garante.** Iniciar um interpretador executa a inicialização dele: o módulo `site`, os arquivos `.pth` e
um eventual `sitecustomize` do próprio ambiente. O aplicativo não tem como provar que um arquivo chamado `python.exe` é um
Python legítimo. Por isso só são sondados os candidatos da descoberta limitada e o ambiente da pasta que a própria pessoa
indicou.

## 4. Situação de cada ambiente

As mesmas regras do executor:

| Situação | Quando |
|---|---|
| Compatível | a versão atende à faixa do pacote **e** cada dependência declarada está instalada na versão exata |
| Versão incompatível | a versão do Python não atende à faixa do pacote |
| Dependências pendentes | a versão atende, mas algum requisito declarado está ausente ou em outra versão (a lista diz qual e o que foi encontrado) |
| Não foi possível verificar | a sondagem falhou ou expirou, o ambiente não informa dependências, ou o interpretador de um ambiente de projeto não é um ambiente virtual daquela pasta |

Só "Compatível" permite iniciar. "Compatível" não promete que a execução dá certo: o que o pacote não declara (programas
externos, arquivos, acessos) não é verificável.

## 5. Antes de iniciar

O início confere de novo, no próprio executor (`RunController.start`): o interpretador ainda existe e responde, a versão
atende e as dependências exatas estão instaladas. Se o interpretador foi removido ou o ambiente mudou depois da
conferência, a execução não começa. Nenhum outro ambiente é posto no lugar.
