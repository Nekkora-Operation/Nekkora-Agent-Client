# Gerar pacote novo: campos, requisito de Python e conclusão

AGENT-CLIENT-PREPARACAO-002. Como o formulário "Gerar pacote novo" decide o que é obrigatório, como o requisito de Python é
lido e gravado, e como a geração termina. Gerar não cria nada na Platform, não executa o código e não instala nada.

Código: `com.nekkora.client.pack` — `PythonRequirement`, `ExecutionPackage`, `Packager`, `Workshop`; tela em
`com.nekkora.client.ui` — `GenerateForm`, `Notice`, `LocalActions.requestGenerate / generateTo`.

## 1. Campos

| Campo | Onde | Regra |
|---|---|---|
| Pasta do projeto | principal | **obrigatório** |
| Arquivo de entrada | principal | **obrigatório**; um `.py` da pasta |
| Nome da automação | principal | **obrigatório** |
| Versão declarada do pacote | opções avançadas | já vem preenchida: `1.0.0` num projeto novo; a do manifesto da pasta, se houver (nunca aumentada sozinha) |
| Requisito de Python | opções avançadas | **opcional** |
| Dependências | opções avançadas | ver a seção de dependências |

O destino é escolhido no "Salvar como". A versão do Python instalado **neste computador** nunca é posta como requisito do
projeto. O campo só vem preenchido quando a pasta declara um requisito — no manifesto dela, em `.python-version` ou em
`requires-python` do `pyproject.toml` — e a tela diz de onde ele veio. A pessoa pode revisar ou apagar.

## 2. Requisito de Python

Uma única leitura, `PythonRequirement`, usada pelo validador, pelo empacotador, pelo executor e pelo formulário.

| O que a pessoa digita | Interpretação (mostrada antes de gerar) | No manifesto |
|---|---|---|
| *(em branco)* | Sem restrição de Python declarada | a chave `python` não existe |
| `3.12` ou `3.12.*` | qualquer Python 3.12 (3.12.x) | `==3.12.*` |
| `3.12.0` | exatamente o Python 3.12.0 | `==3.12.0` |
| `>=3.10,<3.14`, `~=3.11`, `==3.12` | como está (os espaços saem) | o próprio texto |
| qualquer outra coisa | **recusado**: corrigir ou apagar | — |

Texto inválido nunca é trocado por um padrão. Enquanto a pessoa digita, nada fica vermelho; o erro aparece junto do campo
quando ela sai dele ou pede para gerar.

### Manifesto 1.0 e 1.1

O `nekkora-external.json` 1.0 **exige** `python`. Para representar a ausência sem inventar um valor, o manifesto ganhou a
versão **1.1**:

- 1.1: `python` é opcional (a chave não existe) e aceita a versão exata `==X.Y.Z`, comparada inteira;
- o resto do formato, o hash por arquivo e a regra da impressão digital não mudam;
- o empacotador só grava `1.1.0` quando o pacote precisa (sem requisito, ou com versão exata). Com um requisito da 1.0 ele
  grava `1.0.0`, byte a byte como antes;
- chave presente com `null`, vazio, número, `>=0` ou outro texto: pacote inválido. Versão de manifesto desconhecida (1.2,
  2.0): pacote inválido.

**Sem restrição declarada não é compatibilidade garantida.** Na execução, o ambiente escolhido ainda precisa responder à
sondagem do executor e ter cada dependência declarada na versão exata. A faixa que o executor suporta não foi ampliada: ele
nunca teve faixa própria além da do manifesto.

Compatibilidade: a Platform precisa do PR da API desta missão (migration 041) para aceitar um pacote 1.1. O Nekkora Agent
antigo e o Client anterior **recusam** um pacote 1.1 como inválido; nenhum o executa ignorando o campo. Pacotes 1.0 continuam
valendo em todos.

## 3. Geração: do clique ao resultado

1. **Gerar pacote** confere **todos** os campos antes de abrir o "Salvar como". Com um campo a corrigir, o seletor não abre:
   a frase aparece junto do campo (a seção avançada abre se o campo é dela) e os campos ficam como estão.
2. Desistir no "Salvar como" não mostra sucesso nem erro.
3. Confirmado o destino: **Gerando pacote…**, sem aceitar outro pedido. A tela continua respondendo.
4. O ZIP é escrito num temporário ao lado do destino, relido pelo validador real e só então recebe o nome final.
5. Fim, sempre num estado visível:
   - **Pacote gerado**, com o caminho, **Abrir pasta do arquivo** e a orientação de envio pela Platform; ou
   - o **erro**, com o motivo (sem permissão, arquivo em uso, sem espaço, pasta alterada…), os campos preservados e nenhum
     arquivo parcial.

Arquivo existente só é substituído com o sim da pessoa; o não é uma informação, não um erro.

### O defeito que isto corrige

- Sem informar Python o pacote **não era gerado** (`AGT-PAC-037`), e `3.12` e `3.12.0` também eram recusados.
- A versão declarada aparecia em branco, embora `1.0.0` fosse usada por baixo.
- Os campos só eram conferidos **depois** do "Salvar como": a pessoa escolhia o destino, salvava, e a tela ficava com a mesma
  frase de antes, sem pacote e sem nada novo para ler.
- Em escala alta a janela, com as opções avançadas abertas, passava da altura da tela, e a frase e os botões ficavam fora
  dela. Agora Gerar, Editar e Renomear nunca passam da área útil: o conteúdo rola.

## 4. Mensagens

`Notice` escreve a gravidade em palavras — **Informação**, **Aviso**, **Erro**, **Concluído** — com a cor do token
correspondente da lib (`--nk-color-*-text`). Só erro impede algo. O código de suporte (`AGT-PAC-xxx`) fica numa linha de
detalhe, fora da frase.

## 5. Dependências e o `requirements.txt`

Código: `RequirementsFile` (leitura), `Workshop.Draft.requirements / selectRequirements / clearRequirements`,
`Workshop.DependencyChoice`.

O manifesto só representa `nome==versão` (com extras opcionais), sem repetir. A leitura do arquivo é **estática**: só texto
UTF-8, até 1 MiB. Nada é instalado, nada é resolvido na rede, nenhum `setup.py` roda, e inclusões e endereços não são
seguidos.

### O que cada linha vira

| Linha | Situação |
|---|---|
| `requests==2.31.0`, `openpyxl[lxml] == 3.1.5` | dependência exata: vai para o manifesto |
| em branco, `# comentário`, comentário no fim da linha | ignorada |
| a mesma dependência, na mesma versão, outra vez | "repetida": usada uma vez |
| a mesma dependência com outra versão ou outros extras | **conflito**, nas duas linhas: nenhuma é escolhida |
| `requests>=2`, `~=2.31`, `==2.31.*`, `!=`, `===` | **faixa**: o pacote exige a versão exata |
| `requests` | **sem versão** |
| `...; sys_platform == "win32"` | **marcador de ambiente** |
| `https://…`, `git+…`, `nome @ …` | **endereço remoto** |
| `./pasta`, `C:\…`, `algo.whl`, `-e …` | **caminho local** |
| `-r outro.txt`, `-c constraints.txt` | **inclusão**: não é seguida |
| `--index-url …` e outras opções | **opção do pip** |
| `nome==1.0 --hash=…` | **hash**: o pacote não o registra |
| qualquer outra coisa | **não reconhecida** |

Tudo o que não é dependência exata é dito **por linha**, com o número e o motivo. Nenhuma linha é descartada em silêncio,
nenhuma versão é inventada, e um arquivo com problema nunca é tratado como "sem dependências".

### Origem das dependências: uma só vale

| Origem | Quando existe |
|---|---|
| Manifesto da pasta | a pasta tem um `nekkora-external.json` válido |
| Arquivo de dependências | há um arquivo selecionado: o `requirements.txt` da raiz (reconhecido sozinho, e a tela diz) ou o que a pessoa escolheu |
| Edição manual | sempre |

- Sem a pessoa dizer nada: o manifesto, se houver; senão o arquivo; senão a edição manual (lista vazia).
- Manifesto e arquivo com listas **diferentes**: nenhuma origem é escolhida. A pessoa decide antes de gerar (`AGT-PAC-059`).
- Arquivo como origem com linha não representável: a geração não começa (`AGT-PAC-047`, com a primeira linha). A pessoa
  ajusta o arquivo e o seleciona de novo, usa a edição manual ou remove a seleção.
- **Sem arquivo** é permitido: o pacote sai com "Nenhuma dependência declarada" — que não quer dizer que o projeto não usa
  bibliotecas. Na execução, só o que está declarado é conferido.

### O arquivo no pacote

- O arquivo original nunca é alterado, e o caminho dele nunca vai para o manifesto.
- Um arquivo **do projeto** já entra no pacote como qualquer arquivo do projeto, e a lista do manifesto sai do mesmo
  retrato da pasta.
- Um arquivo **de fora do projeto** só entra se a pessoa marcar "Copiar este arquivo para o pacote". Entra só ele, na raiz,
  como `requirements.txt`; se esse nome já é de um arquivo do projeto, como `requirements-selecionado.txt`; se os dois
  existem, não há cópia (`AGT-PAC-043`). O destino aparece antes, na caixa e no resumo.
- O arquivo é lido uma vez, para uma cópia estável: resumo, manifesto e a cópia no pacote saem dela. Se o arquivo mudar
  depois de lido, a geração é recusada (`AGT-PAC-048`); o do projeto segue a regra da pasta (`AGT-PAC-072`).

Limites: navegador, driver e outros programas externos não são dependências Python e não são garantidos por esta lista.
`pyproject.toml`, `Pipfile` e arquivos de trava não são lidos como dependências.
