# Ambiente Python da execução local

AGENT-CLIENT-PYTHON-001, revisto na AGENT-CLIENT-PREPARACAO-002. Como o aplicativo encontra os ambientes Python que a pessoa pode escolher e como confere cada um
contra o pacote. **Nada é instalado, criado, baixado ou alterado**: nem Python, nem ambiente virtual, nem dependência, nem
PATH, nem registro do Windows.

Código: `com.nekkora.client.run` — `PythonSources` (fontes), `PythonProbe` (sondagem), `PythonEnvironments` (regras) e a
fachada `Runner.pythons / virtualEnvironment / recheckPython`.

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

## 2. Ambiente virtual localizado pela pessoa

AGENT-CLIENT-PREPARACAO-002: **Localizar ambiente virtual…** substitui o antigo "Selecionar pasta da automação". A pessoa
escolhe diretamente a **pasta raiz do ambiente virtual** — não o projeto, não o `principal.py`, não o `python.exe`. O
aplicativo deixou de procurar `.venv` e `venv` dentro de uma pasta de projeto: a lista fechada desses dois nomes saiu do
código, e o nome da pasta do ambiente é livre.

A pasta escolhida é um ambiente virtual quando:

- é uma pasta de verdade (um link ou uma junção **não é seguido**, e isso é informado);
- tem `pyvenv.cfg`;
- tem o interpretador **deste sistema**, como arquivo comum: `Scripts\python.exe` no Windows, `bin/python` nos outros;
- o próprio interpretador responde que é um ambiente virtual **daquela** pasta.

Regras:

- Só a pasta escolhida é conferida. Nada é procurado dentro dela, ao lado ou acima.
- A instalação-base que o ambiente referencia no `pyvenv.cfg` é resolvida pelo próprio interpretador; ela não vira outro
  ambiente na lista.
- Escolher o ambiente **não muda** a pasta do código nem a pasta de saída.
- Limite: um ambiente cujo interpretador é um link (criado com `--symlinks`) é tratado como incompleto, porque o executor
  só aceita arquivo comum.

| O que a pessoa apontou | O que acontece |
|---|---|
| Ambiente virtual totalmente compatível | entra nas opções, no alto, e fica **escolhido**: foi para usá-lo que ela o localizou |
| Ambiente virtual que não atende ao pacote | entra nas opções, com o motivo; a escolha anterior é mantida. Se a escolha anterior é o ambiente localizado antes, ele fica e as opções não mudam |
| Pasta sem `pyvenv.cfg`, incompleta, link, ou que não respondeu | **nada muda**: nem as opções, nem a escolha |
| Desistir no seletor | nada muda |
| Outro ambiente válido | substitui o localizado antes; não se acumulam |

Uma tentativa que não serve nunca troca a escolha em silêncio. Quando há outro Python utilizável, a frase é uma
**informação** — "Nenhum ambiente virtual encontrado na pasta selecionada. Você pode continuar usando um Python compatível
da lista." — e não um erro do formulário.

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

## 6. A tela (preparação da execução)

AGENT-CLIENT-PREPARACAO-002: versão compacta. Código: `RunForm`, `Notice`, `LocalActions` (seção "ambiente Python").

Na tela principal, em ordem:

1. **Executar neste computador** e a frase "Execução local. Os registros ficam neste computador."
2. **Automação**: nome e versão.
3. **Local da automação**: a pasta do pacote de origem, com **Abrir pasta**.
4. **Ambiente Python**: uma caixa de seleção com a versão e a situação de cada candidato; **Localizar ambiente virtual…** e,
   discreto, **Buscar novamente**.
5. **Pasta de saída**: o caminho e **Escolher...**.
6. **Aprovação** e **Iniciar execução**.

Nome completo, nome declarado, entrada, requisito de Python, dependências, impressão digital, caminho do interpretador e o
motivo completo de cada situação ficam em **Detalhes técnicos**, recolhido. A regra da execução local (nada é enviado à
Platform) continua escrita lá.

### Três caminhos, três campos

| Campo | O que é |
|---|---|
| Local da automação | onde está o **código**: a pasta do pacote conferido, gerenciada pelo aplicativo |
| Ambiente Python | qual **interpretador** executa (o caminho fica na dica da opção e nos detalhes técnicos) |
| Pasta de saída | onde a automação **grava** |

Escolher um não muda os outros. Cada caminho fica num campo de uma linha, só leitura, alinhado à esquerda, selecionável e
com o texto inteiro na dica.

**Local da automação não é o local de execução.** O processo não roda na pasta do pacote: ao iniciar, o executor cria uma
cópia conferida, própria daquela execução (`<dados>\execucoes\<id>\pacote`). Antes do início essa pasta não existe, e a
tela diz "Local de execução ainda não preparado" em vez de inventar um caminho. Depois, o caminho real aparece na área da
última execução. **Abrir pasta** só pede ao sistema que mostre a pasta; nada dela é executado. Alterar um arquivo do pacote
muda a impressão digital: a conferência de integridade do início recusa, e a aprovação anterior não vale.

### A caixa de seleção

| Situação | O que acontece |
|---|---|
| Busca ao abrir, sem escolha anterior, exatamente um ambiente totalmente compatível | ele vem escolhido. Escolher não aprova nem inicia |
| Busca ao abrir, mais de um compatível | nada vem escolhido; a caixa diz "Escolha o ambiente Python" |
| Nenhum encontrado | a caixa diz "Nenhum ambiente Python encontrado"; nunca fica em branco |
| Buscar novamente | a escolha é preservada pela identidade do ambiente; se ele sumiu, a seleção é limpa e nenhum outro entra no lugar |

Abaixo da caixa, um resumo de uma frase: "Compatível com o pacote…", ou o motivo ("Versão incompatível: o pacote pede…",
"Faltam 3 requisitos declarados…"). Um ambiente que não serve pode ser escolhido para ver o motivo, mas não habilita o
início. Só "Compatível" habilita, e o início confere tudo de novo.

### Gravidade das mensagens

`Notice` escreve a gravidade em palavras, com a cor do token da lib; nunca só a cor.

| Situação | Gravidade |
|---|---|
| Ambiente escolhido que não serve; pasta de saída inválida; início recusado; busca que falhou | **Erro**, junto do campo |
| Nenhum ambiente compatível encontrado (sem escolha); ambiente localizado que não atende; escolha que sumiu | **Aviso** |
| Pasta sem ambiente virtual quando há outro Python que serve; busca em andamento | **Informação** |
| Compatível; falta escolher; aprovação pendente ou feita | texto comum |

Nada fica vermelho antes de haver um problema: a pasta de saída ainda não escolhida não é marcada, e uma pasta válida não
recebe mensagem.

### Última execução

O andamento e o resultado ficam numa área própria, **Última execução**, separada da preparação por uma linha: a situação,
o **local de execução** (a cópia que foi executada) e a **saída desta execução**, cada um com Abrir pasta. Nada disso é
escrito nos campos da preparação da próxima execução.

### Tela pequena e escala alta

Com os detalhes recolhidos, a preparação cabe em 690 px de altura (a área útil de uma tela de 1080 linhas a 150%). Quando
não cabe — 200%, detalhes abertos, última execução na tela —, a janela fica na área útil e o conteúdo rola na vertical, com
a rolagem acompanhando o foco do teclado. O mesmo vale para Editar, Gerar pacote novo e Renomear.
