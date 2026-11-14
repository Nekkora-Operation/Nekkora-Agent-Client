# Tokens e ativos visuais do Client

O Client tem **dois temas**, Sakura Dark (padrão) e Nekkora Light. Tudo vem da **Nekkora-lib**; nenhuma cor foi tirada de
imagem ou escolhida aqui.

## Origem

| Item | Valor |
|---|---|
| Repositório | `Nekkora-Operation/Nekkora-lib` |
| Versão | `0.2.0` (`package.json`) |
| Commit | `0a29d95fbadb19da74cfa90a55542fb14885458d` (27/10/2026), o mesmo que a Platform Web fixa em `lib.lock.json` |
| Tokens de cor | `src/tokens/themes.css` — SHA-256 `6b3609f02b0cd912934544315c8dc70fe218b14cfafd4047f9e2814f49920e3f` |
| Fundação | `src/tokens/foundation.css` — SHA-256 `b635b3ba9205d1ea4e7a1711362672d9fd4c14c7add32ac86d380b426d82e397` |
| Logos | `brand/export/lockup/` e `brand/export/app-icon/` (PNG exportado dos SVGs de `brand/`) |

Os dois CSS estão copiados, sem alteração, em `src/test/resources/nekkora-lib/`. O teste `ThemeTokensTest` confere o
SHA-256 deles, lê a paleta e as fórmulas de modo **do CSS** e compara com cada valor que o aplicativo usa.

## Temas

| Tema no Client | `data-nk-theme` na lib | Modo |
|---|---|---|
| Sakura Dark | `sakura-dark` | escuro |
| Nekkora Light | `light` | claro |

Os outros temas da lib (`dark`, `midnight-blue`, `porcelain-light`, `sage-light`) e os do Nekkora Agent não existem no
Client. Uma preferência gravada com outro nome cai no padrão.

## Mapeamento dos tokens para os componentes desktop

Os componentes React da lib não rodam em Swing. Cada componente do Client pinta com os mesmos tokens do componente
equivalente da lib:

| Componente do Client | Equivalente na lib | Tokens usados |
|---|---|---|
| Fundo da janela e da barra de título | `.nk-root` | `--nk-color-bg`, `--nk-color-text` |
| `PrimaryButton` ("Entrar") | `.nk-button--primary` | fundo `accent` / `accent-hover` / `accent-active`; rótulo `text-inverse`; altura `control-md` (40); raio `radius-md` (8); fonte `font-size-md` (14) peso médio; desabilitado 0,45, carregando 0,75 |
| `GhostButton` ("Cancelar", troca de tema) | `.nk-button--ghost`, tamanho `sm` | rótulo `text-secondary`; hover `surface-hover` + `text`; altura `control-sm` (32); raio `radius-sm` (6) |
| Anel de foco | `--nk-focus-ring` | 3 px de `accent` a 35% (escuro) ou 25% (claro) |
| `Spinner` | `.nk-spinner` | 20 px; traço `max(2px, tamanho/10)`; trilho `accent-soft`; arco `accent`; volta em 0,8 s |
| Texto de andamento | — | `text-secondary`, `font-size-sm` (13) |
| Mensagem de erro | — | `danger-text`, `font-size-sm`, `line-height` 1,5 |
| Rodapé (entrada e telas seguintes) | `.nk-auth__footer` | `text-secondary`, `font-size-xs` (12), centralizado |
| Título da tela | — | `text`, `font-size-xl` (18), peso médio |
| Cartão de organização | `.nk-card` | fundo `surface` (hover `surface-hover`), borda de 1 px `border` (com foco, `accent`), raio `radius-lg` (12), espaço `space-5` (20); nome `text` `font-size-md` médio; identificador `text-secondary` `font-size-xs` |
| Quadro com a inicial (no lugar do logotipo, que o contrato não tem) | — | fundo `accent-soft`, letra `accent`, raio `radius-md`. Apoio visual; não é ativo de marca |
| Seletor de ambiente | `.nk-select` | fundo `surface`, borda `border` (foco `accent`), texto `text`; rótulo `font-size-sm` médio |
| Linha de automação | — | nome `text` `font-size-md` médio; detalhe `text-secondary` `font-size-xs`; divisor `border`; Executar = botão primário `sm`; Editar = botão ghost |
| Avatar | — | círculo `accent-soft`, iniciais `text` `font-size-xs` médio; sem nome, desenho neutro em `text-secondary` |
| Menu do usuário | — | `surface`, `text`, divisor `border`, item desabilitado `text-muted`, seleção `surface-hover` |
| Espaços | `--nk-space-*` | 8, 12, 16, 24, 32 |
| Dica (tooltip) | — | `surface`, `text`, `border` |

### Tokens translúcidos

A lib define alguns tokens como `color-mix(..., transparent)`. No Client eles só aparecem sobre o fundo da janela, então
são calculados já compostos sobre `--nk-color-bg` (mesma fórmula; `Rgb.over`): `border`, `surface-hover`, `accent-soft` e a
cor do anel de foco.

### Diferenças registradas

- **Fonte:** a pilha da lib é `'Inter', 'Segoe UI', system-ui`. A Inter da lib é distribuída em WOFF2, que o Java não
  carrega. O Client usa o nome seguinte da pilha, **Segoe UI** (do Windows), sem embarcar fonte. O peso médio (500) usa
  Segoe UI Semibold, a mais próxima disponível.
- **Sombra do botão** (`--nk-shadow-accent`) e **transições de 160 ms** não são reproduzidas.
- **Glifo da troca de tema:** meio círculo desenhado em código (símbolo usual de claro/escuro). Não é um ativo de marca.

## Logos

Regra da lib (`brand/export/LEIA-ME.md`): `-dark` é para **fundo escuro**; `-light`, para fundo claro. O número é a altura
em px. O Client não redesenha nem distorce: escolhe o arquivo pelo tema e pela escala da tela e reduz mantendo a
proporção do próprio arquivo (852 × 128 no lockup).

| Arquivo (em `src/main/resources/com/nekkora/client/brand/`) | SHA-256 |
|---|---|
| `nekkora-lockup-dark-128.png` | `a65eaf809845e7b97cedd455ec76ecf1e35c84eec4cddd65a91aebb9e8319d88` |
| `nekkora-lockup-dark-256.png` | `7ec7169992eca4fee45f00d1db236608431b778a5bd402be452171f120733ac3` |
| `nekkora-lockup-dark-512.png` | `12cb80a437739c479f5cf63e1782039f73af7d4549bdfd93aad0890b58464757` |
| `nekkora-lockup-light-128.png` | `53c0df9f79b559d8d9d70c71212ae1275d2a63db1d3da8bbf8f00704961c5961` |
| `nekkora-lockup-light-256.png` | `2d6ddf67feb9d1f3bb73e28ebcd4e5b68e124d8e1cb66d0c1cbebce4b9ecd924` |
| `nekkora-lockup-light-512.png` | `5c2a4d4e79b8714493bc0750fec7c85b3539e9c03475da03a3c6638023bc87f8` |
| `nekkora-app-icon-16.png` | `29f37c9ef999a629d88f736e89d1d341bd7f8844f9f8e4deaed471550b0d4493` |
| `nekkora-app-icon-24.png` | `dd559c12b6db0ffc74997ca803cbbb2d25883018ff0d88d77f1d54c734c3288c` |
| `nekkora-app-icon-32.png` | `112050b3e007a3c69db83b320cb2c67be1a7bbdc1252c5248e62a9689101bf1e` |
| `nekkora-app-icon-48.png` | `5918b21715356a378e37dbfd730b38b72b453df9695f09900ec25e7259e2abc5` |
| `nekkora-app-icon-64.png` | `876534a79ccb1fc4fff12844b5b2e664525520fcfbcbf56bec98899ae53e72e9` |
| `nekkora-app-icon-128.png` | `3ca7ff3c1f2ff35854501d30527260b3a731c3fd4bc0da1e40d2ade90fac83aa` |
| `nekkora-app-icon-256.png` | `5bb03d408f78d40c4dd683ff4059d4598db01f6ed791066a13e66d3bb466a9e9` |

O lockup aparece a 44 px de altura lógica: arquivo de 128 px até a escala de 200% e de 256 px acima disso.

## Para atualizar

1. Copiar `themes.css` e `foundation.css` do commit novo da lib para `src/test/resources/nekkora-lib/`.
2. Atualizar os dois SHA-256 em `ThemeTokensTest` e a tabela de origem acima.
3. Rodar os testes: qualquer valor da paleta que mudou na lib aparece como falha, com o token e o tema.
4. Copiar de novo os PNGs de `brand/export` se os SVGs mudaram, e atualizar os hashes.
