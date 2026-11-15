package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.FakeDirectory;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Workspace;
import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.JsonReader;
import com.nekkora.client.pack.PackFixtures;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.run.ApprovalLedger;
import com.nekkora.client.run.PythonEnvironments;
import com.nekkora.client.run.PythonSources;
import com.nekkora.client.run.RunController;
import com.nekkora.client.run.RunStore;
import com.nekkora.client.run.Runner;
import com.nekkora.client.run.SessionProbe;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.theme.ClientTheme;

/**
 * AGENT-CLIENT-PREPARACAO-002 T01/T02: a preparacao COMPACTA da execucao, na janela real - a ordem e o conteudo da tela
 * principal, o local da automacao, a caixa de selecao do Python, "Localizar ambiente virtual" com o seletor de PASTA real
 * (conduzido por dentro do processo), as mensagens com a gravidade certa, a area propria da ultima execucao e o encaixe na
 * tela. Processo Python REAL ({@code NEKKORA_TEST_PYTHON}; sem ele, os testes sao PULADOS, nunca dados por aprovados). As
 * fontes da maquina sao uma lista do teste; os ambientes virtuais sao fixtures criadas pelo teste em pasta temporaria (sem
 * pip, sem rede). Entrada e Platform simuladas. Nao e o aceite da pessoa.
 */
@Tag("desktop")
class RunPreparationDesktopTest {

    private static final String PYTHON = System.getenv("NEKKORA_TEST_PYTHON");
    private static final String SKIP = "sem Python de teste (NEKKORA_TEST_PYTHON): ambientes NAO conferidos; teste pulado, nunca dado por aprovado";
    /** Automacao sintetica: anota qual interpretador a executou. */
    private static final String ONDE = "import json, os, sys\n"
            + "open(os.path.join(os.environ['NEKKORA_SAIDA'], 'interpretador.json'), 'w', encoding='utf-8').write(json.dumps({'executavel': sys.executable,\n"
            + "    'prefixo': sys.prefix, 'versao': '%d.%d.%d' % sys.version_info[:3], 'pasta': os.getcwd(), 'arquivo': os.path.abspath(__file__)}))\n";
    private static final Path EVIDENCE = Path.of("target", "evidencias", "preparacao-002");

    @TempDir
    Path tmp;
    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private Workshop workshop;
    private Runner runner;
    private FakeSession session;
    private final List<Path> candidatos = new CopyOnWriteArrayList<>();
    private volatile boolean fonteFalha;
    /** As pastas que a tela pediu para o sistema mostrar ("Abrir pasta"). */
    private final List<String> abertas = new CopyOnWriteArrayList<>();
    private final Organization a = FakeDirectory.org(1, "Acme Indústria");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");

    @BeforeEach
    void open() throws Exception {
        assumeTrue(PYTHON != null && !PYTHON.isBlank() && Files.isRegularFile(Path.of(PYTHON)), SKIP);
        fake = new FakeSignIn();
        background = new FakeDirectory.Manual();
        Path dados = tmp.resolve("dados");
        workshop = new Workshop(dados);
        SessionProbe sessao = () -> new SessionProbe.Session(true, 1, false, null);
        RunController controller = new RunController(new RunStore(dados.resolve("execucoes"), AtomicWriter.DEFAULT),
                new ApprovalLedger(ApprovalLedger.defaultFile(dados), AtomicWriter.DEFAULT), sessao, Clock.systemUTC(), dados);
        candidatos.add(Path.of(PYTHON));
        runner = new Runner(workshop.packages(), controller, new PythonEnvironments(cancel -> {
            List<PythonSources.Found> found = new ArrayList<>();
            if (fonteFalha) {
                found.add(new PythonSources.Found(PythonSources.LAUNCHER, List.of(), true));
            }
            found.add(new PythonSources.Found(PythonSources.PATH, List.copyOf(candidatos), false));
            return found;
        }, controller.probeDir()));
        SwingUtilities.invokeAndWait(() -> {
            Themes.apply(ClientTheme.SAKURA_DARK);
            flow = new SignInFlow(fake, r -> {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
            });
            window = new ClientWindow(flow, t -> { }, () -> { }, BuildInfo.load(), background, workshop, runner);
            window.actions().openFolder = folder -> {
                abertas.add(folder);
                return true;
            };
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        settle();
        session = new FakeSession();
        session.directory.organizations = List.of(a);
        session.directory.workspaces.put(a.id(), List.of(fin));
        session.directory.automations.put(fin.id(), FakeDirectory.listing(true, true));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        answer();
        edt(() -> window.organizationsView().cards().get(0).choose());
        answer();
        edt(() -> window.contextView().workspaceBox().setSelectedItem(fin));
        answer();
    }

    @AfterEach
    void close() throws Exception {
        if (window == null) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            JFileChooser open = read(RunPreparationDesktopTest::chooserOnScreen);
            if (open == null) {
                break;
            }
            edt(open::cancelSelection);
        }
        runner.cancel();
        long end = System.nanoTime() + 20_000_000_000L;
        while (runner.running().isPresent() && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> {
            window.actions().close();
            flow.close();
            window.dispose();
        });
        background.runAll();
        runner.close();
    }

    // ------------------------------------------------------------------ apoio

    private static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    private void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
        settle();
    }

    private <T> T read(java.util.function.Supplier<T> s) throws Exception {
        AtomicReference<T> v = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> v.set(s.get()));
        return v.get();
    }

    private void answer() throws Exception {
        for (int i = 0; i < 6; i++) {
            background.runAll();
            settle();
        }
    }

    private void await(String what, BooleanSupplier ok) throws Exception {
        long end = System.nanoTime() + 60_000_000_000L;
        while (!ok.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("nao aconteceu: " + what);
            }
            Thread.sleep(50);
            answer();
        }
    }

    /** As buscas rodam no executor do teste: responder tudo e esperar a lista voltar a ficar disponivel. */
    private void listados(RunForm f) throws Exception {
        answer();
        assertFalse(read(f::pythonSearching), "a busca termina");
        assertTrue(read(() -> f.searchAgainButton().isEnabled()), "e permite nova tentativa");
    }

    private void importar(String name, String script, List<String> dependencies) throws Exception {
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), name, Map.of("main.py", script), "main.py", dependencies);
        workshop.importZip(a.id(), fin.id(), zip.toString());
        edt(() -> window.reloadLocal());
        answer();
    }

    private RunForm abrir(String name) throws Exception {
        JButton executar = read(() -> window.contextView().rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow().actions().get(0));
        edt(() -> executar.doClick(0));
        RunForm f = read(() -> window.actions().runForm());
        assertNotNull(f);
        listados(f);
        return f;
    }

    /** Fixture: ambiente virtual criado PELO TESTE (sem pip, sem rede). Devolve a pasta do ambiente. */
    private Path venv(Path dir) throws Exception {
        Files.createDirectories(dir.getParent());
        Process p = new ProcessBuilder(PYTHON, "-I", "-m", "venv", "--without-pip", dir.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, "fixture: o ambiente virtual de teste nao foi criado");
        return dir;
    }

    private static Path exe(Path venvDir) {
        return venvDir.resolve("Scripts").resolve("python.exe");
    }

    /** Fixture: "instala" uma distribuicao escrevendo SO os metadados dela no ambiente de teste. */
    private static Path distribution(Path venvDir, String name, String version) throws Exception {
        Path info = venvDir.resolve("Lib").resolve("site-packages").resolve(name.replace('-', '_') + "-" + version + ".dist-info");
        Files.createDirectories(info);
        return Files.writeString(info.resolve("METADATA"), "Metadata-Version: 2.1\nName: " + name + "\nVersion: " + version + "\n", StandardCharsets.UTF_8);
    }

    private static JFileChooser find(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JFileChooser f) {
                return f;
            }
            if (child instanceof Container inner) {
                JFileChooser f = find(inner);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static JFileChooser chooserOnScreen() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w instanceof JDialog dialog) {
                JFileChooser f = find(dialog.getContentPane());
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Clique real em "Selecionar pasta da automacao": o seletor e modal, entao o clique vai para a fila e o teste segue. */
    private JFileChooser abrirSeletor(RunForm f) throws Exception {
        assertTrue(read(() -> f.locateButton().isShowing() && f.locateButton().isEnabled()));
        SwingUtilities.invokeLater(() -> f.locateButton().doClick(0));
        long limit = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < limit) {
            JFileChooser c = read(RunPreparationDesktopTest::chooserOnScreen);
            if (c != null) {
                return c;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("o seletor de pasta nao abriu");
    }

    private void selecionarPasta(RunForm f, Path pasta) throws Exception {
        JFileChooser c = abrirSeletor(f);
        edt(() -> {
            c.setSelectedFile(pasta.toFile());
            c.approveSelection();
        });
        assertFalse(read(c::isShowing));
        listados(f);
    }

    private void evidence(String name, Container root) throws Exception {
        Files.createDirectories(EVIDENCE);
        java.awt.image.BufferedImage img = read(() -> {
            java.awt.image.BufferedImage i = new java.awt.image.BufferedImage(root.getWidth() * 2, root.getHeight() * 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = i.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(2, 2);
            root.paint(g);
            g.dispose();
            return i;
        });
        javax.imageio.ImageIO.write(img, "png", EVIDENCE.resolve(name + "-200.png").toFile());
    }

    /** Os textos da tela principal, de cima para baixo (rotulos, botoes e frases VISIVEIS; o conteudo dos campos de caminho nao entra). */
    private static String texts(Container c) {
        StringBuilder b = new StringBuilder();
        for (Component child : c.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof JLabel l) {
                b.append(l.getText()).append('\n');
            } else if (child instanceof JButton x) {
                b.append(x.getText()).append('\n');
            } else if (child instanceof WrappedText w) {
                b.append(w.getText()).append('\n');
            }
            if (child instanceof Container inner) {
                b.append(texts(inner));
            }
        }
        return b.toString();
    }

    private void aprovarESaida(RunForm f) throws Exception {
        edt(() -> f.approveButton().doClick(0));
        answer();
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        edt(() -> window.actions().setOutput(saida.toString()));
        answer();
    }

    // ------------------------------------------------------------------ a tela compacta

    @Test
    void telaPrincipalTemSoOEssencialNaOrdemEOsDetalhesFicamRecolhidos() throws Exception {
        importar("relatorio-mensal-de-conciliacao-bancaria-da-filial-sao-joao-del-rei-e-regiao", ONDE,
                List.of("pacote-que-nao-existe-nekkora==9.9.9", "outro-que-falta==1.0.0", "mais-um==2.0.0"));
        RunForm f = abrir("relatorio-mensal-de-conciliacao-bancaria-da-filial-sao-joao-del-rei-e-regiao");
        String tela = read(() -> texts(f));
        // a ordem dos blocos, de cima para baixo
        List<String> ordem = List.of("Executar neste computador", "Execução local. Os registros ficam neste computador.", "Automação", "Local da automação",
                "Ambiente Python", "Pasta de saída", "Aprovação", "Iniciar execução");
        int at = -1;
        for (String bloco : ordem) {
            int i = tela.indexOf(bloco + "\n", at + 1);
            assertTrue(i > at, "falta ou esta fora de ordem: " + bloco + "\n" + tela);
            at = i;
        }
        assertEquals("relatorio-mensal-de-conciliacao-bancaria-da-filial-sao-joao-del-rei-e-regiao · versão 1.0.0", read(f::packageText));
        // o que e extenso NAO esta na tela principal: entrada, requisitos, impressao digital, caminho do interpretador
        for (String extenso : List.of("main.py", "sha256", "pacote-que-nao-existe-nekkora", "impressão digital", "python.exe")) {
            assertFalse(tela.toLowerCase(java.util.Locale.ROOT).contains(extenso.toLowerCase(java.util.Locale.ROOT)), "na tela principal: " + extenso + "\n" + tela);
        }
        assertFalse(read(f::detailsOpen), "detalhes tecnicos recolhidos");
        edt(() -> f.pythonBox().setSelectedIndex(0));
        String detalhes = read(f::detailsText);
        assertTrue(tela.contains("e de 3 dependências declaradas (veja em detalhes técnicos)"), "muitas dependencias: so a contagem na tela principal\n" + tela);
        for (String esperado : List.of("Entrada: main.py", "Impressão digital: sha256:", "Dependências declaradas: pacote-que-nao-existe-nekkora==9.9.9, outro-que-falta==1.0.0",
                "Requisito de Python: >=3.", "Interpretador: " + Path.of(PYTHON), "requisitos declarados não atendidos: pacote-que-nao-existe-nekkora==9.9.9 (não instalado)",
                "verificação de integridade")) {
            assertTrue(detalhes.contains(esperado), esperado + "\n" + detalhes);
        }
        edt(() -> f.detailsToggle().doClick(0));
        assertTrue(read(f::detailsOpen));
        assertEquals("Ocultar detalhes técnicos", read(() -> f.detailsToggle().getText()));
        // nenhuma mensagem de problema antes de a pessoa mexer na pasta de saida, e a aprovacao pendente nao e um erro
        assertEquals("", read(f::outputText), "a pasta de saida ainda nao escolhida nao aparece como invalida");
        assertEquals(Notice.Kind.PLAIN, read(() -> f.approvalNotice().kind()));
        assertFalse(read(f::lastShown), "sem execucao nesta janela, a area da ultima execucao nao aparece");
        evidence("sakura-dark-preparacao-compacta", f);
    }

    @Test
    void localDaAutomacaoEAPastaDoPacoteEAbrirPastaSoPedeParaMostrar() throws Exception {
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        Path codigo = Path.of(read(() -> f.codeField().getText()));
        assertTrue(Files.isDirectory(codigo), "a pasta existe de verdade: nada inventado - " + codigo);
        assertTrue(Files.isRegularFile(codigo.resolve("main.py")) && Files.isRegularFile(codigo.resolve("nekkora-external.json")), "e a pasta do pacote conferido");
        assertTrue(codigo.startsWith(tmp.resolve("dados").toRealPath()), "pasta gerenciada pelo aplicativo");
        assertFalse(read(() -> f.codeField().isEditable()));
        assertEquals(codigo.toString(), read(() -> f.codeField().getToolTipText()), "o caminho inteiro fica acessivel na dica");
        assertEquals(javax.swing.JTextField.LEFT, (int) read(() -> f.codeField().getHorizontalAlignment()));
        assertTrue(read(f::codeNoteText).contains("Pacote de origem") && read(f::codeNoteText).contains("Local de execução ainda não preparado"), read(f::codeNoteText));
        assertTrue(read(() -> f.openCodeButton().getToolTipText()).contains("verificação de integridade"), "a regra de integridade fica na dica e nos detalhes");
        assertTrue(read(f::detailsText).contains("verificação de integridade"));
        // tres caminhos, tres campos: codigo, interpretador e saida nao sao o mesmo lugar
        aprovarESaida(f);
        assertNotEquals(read(() -> f.codeField().getText()), read(f::outputFolder));
        assertNotEquals(read(() -> f.codeField().getText()), read(f::pythonPath));
        assertFalse(read(f::pythonPath).isEmpty());

        edt(() -> f.openCodeButton().doClick(0));
        assertEquals(List.of(codigo.toString()), abertas, "Abrir pasta pede ao sistema a pasta da automacao, e so ela");
        assertTrue(runner.current().isEmpty(), "abrir a pasta nao executa nada");
    }

    // ------------------------------------------------------------------ caixa de selecao do Python

    @Test
    void semNenhumaInstalacaoACaixaDizQueNaoHaEAvisaOQueAAutomacaoPrecisa() throws Exception {
        candidatos.clear();
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        assertEquals(List.of(), read(f::pythonLines));
        assertEquals("Nenhum ambiente Python encontrado", read(f::pythonShown), "a caixa nunca fica em branco");
        assertEquals("Aviso", read(() -> f.pythonNotice().label()));
        assertTrue(read(f::pythonText).startsWith("Nenhum ambiente Python compatível encontrado. Esta automação precisa de Python >="), read(f::pythonText));
        assertFalse(read(() -> f.startButton().isEnabled()));
    }

    @Test
    void umaUnicaInstalacaoCompativelVemEscolhidaENaoIniciaNada() throws Exception {
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        assertEquals(1, read(f::pythonLines).size());
        assertTrue(read(f::pythonShown).matches("Python \\d+\\.\\d+\\.\\d+ — Compatível"), read(f::pythonShown));
        assertEquals(NavText.PY_COMPATIBLE, read(f::pythonText), "resumo curto; o caminho fica na dica e nos detalhes");
        assertEquals("", read(() -> f.pythonNotice().label()), "compativel nao e aviso nem erro");
        assertFalse(read(f::pythonText).contains(PYTHON));
        assertFalse(read(() -> f.startButton().isEnabled()), "escolher nao aprova nem inicia");
        assertTrue(runner.current().isEmpty());
    }

    @Test
    void comVariasInstalacoesAPessoaEscolheEAIncompativelDizOMotivoEmUmaFrase() throws Exception {
        Path outro = venv(tmp.resolve("outra-instalacao"));
        candidatos.add(exe(outro));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        assertEquals(2, read(f::pythonLines).size(), read(f::pythonLines).toString());
        assertNull(read(f::chosenPython), "com varias opcoes compativeis, nada vem escolhido");
        assertEquals("Escolha o ambiente Python", read(f::pythonShown));
        assertEquals(NavText.PY_CHOOSE, read(f::pythonText));
        assertEquals("", read(() -> f.pythonNotice().label()), "faltar escolher nao e erro");
        edt(() -> f.pythonBox().setSelectedIndex(1));
        assertNotNull(read(f::chosenPython));
        assertEquals(NavText.PY_COMPATIBLE, read(f::pythonText));
        edt(() -> f.closeButton().doClick(0));

        // um pacote que nenhum ambiente atende: a opcao pode ser escolhida para ver o motivo, e o motivo e um erro do campo
        importar("exigente", ONDE, List.of("pacote-que-nao-existe-nekkora==9.9.9", "outro-que-falta==1.0.0", "mais-um==2.0.0"));
        RunForm g = abrir("exigente");
        assertTrue(read(g::pythonLines).stream().allMatch(l -> l.endsWith("— Dependências ausentes")), read(g::pythonLines).toString());
        assertNull(read(g::chosenPython));
        assertEquals("Aviso", read(() -> g.pythonNotice().label()));
        edt(() -> g.pythonBox().setSelectedIndex(0));
        assertEquals("Erro", read(() -> g.pythonNotice().label()), "o ambiente escolhido nao serve: impedimento, junto do campo");
        String resumo = read(g::pythonText);
        assertTrue(resumo.startsWith("Faltam 3 requisitos declarados neste ambiente: pacote-que-nao-existe-nekkora==9.9.9 (não instalado); outro-que-falta==1.0.0 (não instalado)"
                + " e mais 1 (veja em detalhes técnicos). Nada é instalado pelo aplicativo."), resumo);
        assertTrue(read(g::detailsText).contains("mais-um==2.0.0 (não instalado)"), "a lista completa fica nos detalhes");
        assertFalse(read(() -> g.startButton().isEnabled()));
    }

    @Test
    void buscarNovamentePreservaAEscolhaELimpaQuandoOAmbienteSome() throws Exception {
        Path outro = venv(tmp.resolve("outra-instalacao"));
        candidatos.add(exe(outro));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        edt(() -> f.pythonBox().setSelectedIndex(1));
        String escolhido = read(f::chosenPython).id();
        java.util.Collections.reverse(candidatos);
        fonteFalha = true;
        edt(() -> f.searchAgainButton().doClick(0));
        assertTrue(read(f::pythonSearching));
        assertFalse(read(() -> f.startButton().isEnabled()), "durante a busca nao se inicia");
        listados(f);
        assertEquals(escolhido, read(f::chosenPython).id(), "a escolha e preservada pela identidade do ambiente");
        assertTrue(read(f::pythonText).contains("A busca foi parcial"), read(f::pythonText));
        assertTrue(read(f::detailsText).contains("Busca parcial: sem resposta de"), read(f::detailsText));

        fonteFalha = false;
        String caminhoDoEscolhido = read(f::chosenPython).path();
        candidatos.removeIf(c -> c.toString().equalsIgnoreCase(caminhoDoEscolhido));
        edt(() -> f.searchAgainButton().doClick(0));
        listados(f);
        assertNull(read(f::chosenPython), "o ambiente escolhido sumiu: nenhum outro entra no lugar");
        assertEquals("Aviso", read(() -> f.pythonNotice().label()));
        assertTrue(read(f::pythonText).startsWith(NavText.PY_GONE), read(f::pythonText));
    }

    // ------------------------------------------------------------------ localizar ambiente virtual

    @Test
    void localizarAmbienteVirtualEscolheAPastaDoAmbienteComQualquerNome() throws Exception {
        Path projeto = tmp.resolve("Automação do robô");
        Path ponto = venv(projeto.resolve(".venv"));
        Path outroNome = venv(projeto.resolve("ambiente do robô"));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        aprovarESaida(f);
        String codigo = read(() -> f.codeField().getText());
        String saida = read(f::outputFolder);
        String antes = read(f::chosenPython).id();
        assertEquals("Localizar ambiente virtual…", read(() -> f.locateButton().getText()));
        assertFalse(read(() -> texts(f)).contains("Selecionar pasta da automação"), "o botao antigo saiu");

        // desistir no seletor preserva as opcoes e a escolha
        JFileChooser c = abrirSeletor(f);
        assertEquals(JFileChooser.DIRECTORIES_ONLY, (int) read(c::getFileSelectionMode), "seletor de PASTA: nao de principal.py nem de python.exe");
        assertEquals("Selecionar a pasta do ambiente virtual", read(c::getDialogTitle));
        edt(c::cancelSelection);
        answer();
        assertEquals(antes, read(f::chosenPython).id());
        assertEquals(1, read(f::pythonLines).size());

        selecionarPasta(f, ponto);
        List<String> linhas = read(f::pythonLines);
        assertEquals(2, linhas.size(), linhas.toString());
        assertTrue(linhas.get(0).matches("Python \\d+\\.\\d+\\.\\d+ \\(ambiente virtual \\.venv\\) — Compatível"), "o ambiente localizado vem primeiro: " + linhas);
        assertEquals(exe(ponto).toRealPath(), Path.of(read(f::chosenPython).path()).toRealPath(), "localizado e compativel: fica escolhido");
        assertEquals(NavText.PY_COMPATIBLE, read(f::pythonText));
        assertEquals(codigo, read(() -> f.codeField().getText()), "escolher o ambiente nao muda a pasta do codigo");
        assertEquals(saida, read(f::outputFolder), "nem a pasta de saida");
        assertTrue(read(() -> f.startButton().isEnabled()));

        // outro ambiente, com outro nome: substitui o localizado antes (nao se acumulam)
        selecionarPasta(f, outroNome);
        linhas = read(f::pythonLines);
        assertEquals(2, linhas.size(), linhas.toString());
        assertTrue(linhas.get(0).contains("(ambiente virtual ambiente do robô)"), "o nome nao precisa ser um dos convencionais: " + linhas);
        assertEquals(exe(outroNome).toRealPath(), Path.of(read(f::chosenPython).path()).toRealPath());
        evidence("sakura-dark-ambiente-virtual-localizado", f);
    }

    @Test
    void pastaSemAmbienteVirtualEUmaInformacaoQuandoHaOutroPythonQueServe() throws Exception {
        Path projeto = Files.createDirectories(tmp.resolve("projeto"));
        venv(projeto.resolve(".venv")); // a pessoa aponta o PROJETO, nao o ambiente: nada e procurado dentro
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        aprovarESaida(f);
        String antes = read(f::chosenPython).id();
        List<String> linhas = read(f::pythonLines);
        assertTrue(read(() -> f.startButton().isEnabled()));

        selecionarPasta(f, projeto);
        assertEquals("Nenhum ambiente virtual encontrado na pasta selecionada. Você pode continuar usando um Python compatível da lista.", read(f::pythonText));
        assertEquals("Informação", read(() -> f.pythonNotice().label()), "nao e uma falha: ha um Python utilizavel");
        assertFalse(read(() -> f.pythonNotice().isError()));
        assertFalse(read(f::pythonText).contains(PYTHON), "o caminho do Python nao entra na frase");
        assertEquals(antes, read(f::chosenPython).id(), "a escolha valida de antes continua");
        assertEquals(linhas, read(f::pythonLines), "as opcoes ficam como estavam");
        assertTrue(read(() -> f.startButton().isEnabled()), "e a execucao continua liberada");
        assertEquals("", read(() -> f.startNotice().text()), "nenhum erro geral no formulario");
        assertEquals("", read(f::outputText), "o campo de saida, que esta valido, nao e marcado");
        evidence("sakura-dark-pasta-sem-ambiente-informacao", f);

        // ambiente incompleto: tambem nao troca nada, e diz o motivo
        Path incompleto = Files.createDirectories(tmp.resolve("incompleto"));
        Files.writeString(incompleto.resolve("pyvenv.cfg"), "home = C:\\nada\n");
        selecionarPasta(f, incompleto);
        assertTrue(read(f::pythonText).startsWith("A pasta incompleto tem pyvenv.cfg, mas falta o interpretador Python deste sistema"), read(f::pythonText));
        assertTrue(read(f::pythonText).endsWith(NavText.PY_KEPT), read(f::pythonText));
        assertEquals(antes, read(f::chosenPython).id());
    }

    @Test
    void semNenhumPythonQueSirvaAPastaSemAmbienteEUmAvisoComOQueFazer() throws Exception {
        candidatos.clear();
        Path projeto = Files.createDirectories(tmp.resolve("projeto"));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        selecionarPasta(f, projeto);
        assertEquals("Aviso", read(() -> f.pythonNotice().label()));
        assertEquals("Nenhum ambiente virtual encontrado na pasta selecionada. Selecione a pasta raiz do ambiente virtual (a que contém pyvenv.cfg).", read(f::pythonText));
        assertEquals(List.of(), read(f::pythonLines));
    }

    @Test
    void ambienteLocalizadoQueNaoAtendeNaoTomaOLugarDeUmaEscolhaValida() throws Exception {
        Path bom = venv(tmp.resolve("bom"));
        distribution(bom, "nekkora-fixture-a", "1.2.3");
        Path ruim = venv(tmp.resolve("ruim"));
        importar("relatorio", ONDE, List.of("nekkora-fixture-a==1.2.3"));
        RunForm f = abrir("relatorio");
        assertNull(read(f::chosenPython), "a instalacao do computador nao tem a dependencia");
        selecionarPasta(f, bom);
        String escolhido = read(f::chosenPython).id();
        assertEquals(exe(bom).toRealPath(), Path.of(read(f::chosenPython).path()).toRealPath());

        selecionarPasta(f, ruim);
        assertEquals(escolhido, read(f::chosenPython).id(), "um ambiente que nao atende nao troca a escolha em silencio");
        assertEquals("Aviso", read(() -> f.pythonNotice().label()));
        String frase = read(f::pythonText);
        assertTrue(frase.startsWith("O ambiente virtual localizado não atende ao pacote:") && frase.contains("Dependências ausentes") && frase.endsWith(NavText.PY_KEPT), frase);
        assertTrue(read(f::pythonLines).get(0).contains("(ambiente virtual bom)"), read(f::pythonLines).toString());
    }

    // ------------------------------------------------------------------ execucao: caminho real e area da ultima execucao

    @Test
    void aExecucaoRodaPeloAmbienteEscolhidoEAUltimaExecucaoTemAreaPropriaComOLocalReal() throws Exception {
        Path ambiente = venv(tmp.resolve("projeto").resolve(".venv"));
        distribution(ambiente, "nekkora-fixture-a", "1.2.3");
        importar("relatorio", ONDE, List.of("nekkora-fixture-a==1.2.3"));
        RunForm f = abrir("relatorio");
        selecionarPasta(f, ambiente);
        aprovarESaida(f);
        String origem = read(() -> f.codeField().getText());
        String nota = read(f::codeNoteText);
        edt(() -> f.startButton().doClick(0));
        await("execucao terminada", () -> runner.current().map(Runner.Status::finished).orElse(false));
        await("resultado na tela", () -> {
            try {
                return read(() -> f.lastNotice().text()).startsWith("Concluída com sucesso");
            } catch (Exception e) {
                return false;
            }
        });
        Runner.Status s = runner.current().orElseThrow();
        assertTrue(s.succeeded(), s.state() + " " + s.detail());
        Map<String, Object> m = com.nekkora.client.pack.JsonReader.object(Files.readString(Path.of(s.outputFolder()).resolve("interpretador.json")));
        assertEquals(exe(ambiente).toRealPath(), Path.of((String) m.get("executavel")).toRealPath(), "o processo e o do ambiente escolhido");
        assertEquals(ambiente.toRealPath(), Path.of((String) m.get("prefixo")).toRealPath());

        // a area da ultima execucao: identificada, com a situacao, o local REAL de execucao e a saida
        assertTrue(read(f::lastShown));
        assertEquals("Concluído", read(() -> f.lastNotice().label()));
        assertTrue(read(() -> f.lastNotice().text()).contains("Registro só neste computador."));
        Path exibido = Path.of(read(() -> f.lastRunField().getText()));
        assertEquals(Path.of((String) m.get("pasta")).toRealPath(), exibido.toRealPath(), "o caminho exibido e o que o processo Python usou como pasta de trabalho");
        assertEquals(Path.of((String) m.get("arquivo")).toRealPath().getParent(), exibido.toRealPath(), "e e de la que o arquivo de entrada foi executado");
        assertEquals(tmp.resolve("dados").resolve("execucoes").resolve(s.id()).resolve("pacote").toRealPath(), exibido.toRealPath());
        assertEquals(Path.of(s.outputFolder()), Path.of(read(() -> f.lastOutputField().getText())));
        // nada da ultima execucao foi escrito nos campos da preparacao
        assertEquals(origem, read(() -> f.codeField().getText()), "o local da automacao da preparacao continua sendo o pacote de origem");
        assertEquals(nota, read(f::codeNoteText));
        assertEquals("", read(() -> f.startNotice().text()));
        assertNotEquals(read(() -> f.codeField().getText()), read(() -> f.lastRunField().getText()));
        edt(() -> f.openLastRunButton().doClick(0));
        edt(() -> f.openLastOutputButton().doClick(0));
        assertEquals(List.of(exibido.toString(), s.outputFolder()), abertas);
        evidence("sakura-dark-ultima-execucao", f);
        // nada foi a Platform
        assertTrue(session.directory.calls.stream().noneMatch(c -> c.contains("execu") || c.contains("run")), session.directory.calls.toString());
    }

    @Test
    void ambienteQueMudaDepoisDaValidacaoNaoIniciaEOErroFicaJuntoDoBotao() throws Exception {
        Path ambiente = venv(tmp.resolve("projeto").resolve(".venv"));
        Path metadados = distribution(ambiente, "nekkora-fixture-a", "1.2.3");
        candidatos.clear();
        importar("relatorio", ONDE, List.of("nekkora-fixture-a==1.2.3"));
        RunForm f = abrir("relatorio");
        selecionarPasta(f, ambiente);
        aprovarESaida(f);
        assertTrue(read(() -> f.startButton().isEnabled()), "validado: pode iniciar");

        Files.delete(metadados); // a dependencia saiu do ambiente depois da conferencia
        edt(() -> f.startButton().doClick(0));
        answer();
        answer();
        assertTrue(read(() -> f.startNotice().isError()), "o inicio recusado e um erro de verdade");
        assertTrue(read(() -> f.startNotice().text()).contains("AGT-EXT-022"), read(() -> f.startNotice().text()));
        assertFalse(read(f::lastShown), "nao houve execucao: a area da ultima execucao nao aparece");
        assertTrue(runner.running().isEmpty());
        try (Stream<Path> s = Files.walk(tmp.resolve("saida"))) {
            assertEquals(List.of(), s.filter(Files::isRegularFile).toList(), "nada foi executado: a automacao nao gravou nada");
        }
        assertTrue(read(f::pythonLines).get(0).endsWith("— Dependências ausentes"), read(f::pythonLines).toString());
        assertNotNull(read(f::chosenPython), "o ambiente continua o escolhido: nenhum outro e posto no lugar");
        assertFalse(read(() -> f.startButton().isEnabled()));
    }

    @Test
    void pastaDeSaidaSoApareceComoProblemaQuandoEUmProblema() throws Exception {
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        assertEquals("", read(f::outputText));
        edt(() -> window.actions().setOutput(tmp.resolve("nao-existe").toString()));
        answer();
        assertTrue(read(() -> f.outputNotice().isError()));
        assertEquals("A pasta de saída não existe. Escolha uma pasta existente.", read(f::outputText));
        Path saida = Files.createDirectories(tmp.resolve("saída com espaços e acentuação"));
        edt(() -> window.actions().setOutput(saida.toString()));
        answer();
        assertEquals("", read(f::outputText), "pasta valida: nenhuma marcacao");
        assertEquals(saida.toString(), read(() -> f.outputField().getText()));
        assertEquals(saida.toString(), read(() -> f.outputField().getToolTipText()));
    }

    @Test
    void respostaAtrasadaDeOutraPastaOuDeJanelaFechadaNaoAtualizaATela() throws Exception {
        Path um = venv(tmp.resolve("um"));
        Path dois = venv(tmp.resolve("dois"));
        candidatos.clear();
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");

        // duas pastas pedidas em seguida: as duas verificacoes ficam pendentes; so a ultima vale
        edt(() -> window.actions().setEnvironmentFolder(um.toString()));
        edt(() -> window.actions().setEnvironmentFolder(dois.toString()));
        assertTrue(read(f::pythonSearching));
        listados(f);
        List<String> linhas = read(f::pythonLines);
        assertEquals(1, linhas.size(), linhas.toString());
        assertTrue(linhas.get(0).contains("(ambiente virtual dois)"), "a resposta da primeira pasta chegou e foi descartada: " + linhas);

        // a janela fecha com uma verificacao pendente: a resposta nao reabre nem habilita nada
        edt(() -> window.actions().setEnvironmentFolder(um.toString()));
        edt(() -> f.closeButton().doClick(0));
        answer();
        assertNull(read(() -> window.actions().runForm()));
        assertTrue(runner.current().isEmpty());
        // outra preparacao comeca limpa: sem o ambiente localizado na anterior
        RunForm g = abrir("relatorio");
        assertEquals(List.of(), read(g::pythonLines));
    }

    // ------------------------------------------------------------------ tela pequena / escala alta / tema / teclado

    @Test
    void emAreaMenorQueAPreparacaoAJanelaCabeNaTelaEOConteudoRola() throws Exception {
        // 150% numa tela de 1080 linhas deixa cerca de 690 de altura util; 200%, cerca de 515
        edt(() -> window.actions().usableArea = new java.awt.Rectangle(0, 0, 960, 400));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        edt(() -> f.detailsToggle().doClick(0));
        answer();
        java.awt.Window w = read(() -> SwingUtilities.getWindowAncestor(f));
        java.awt.Rectangle b = read(w::getBounds);
        assertTrue(b.height <= 400 && b.width <= 960 && b.x >= 0 && b.y >= 0 && b.x + b.width <= 960 && b.y + b.height <= 400, "a janela fica dentro da area util: " + b);
        javax.swing.JScrollPane scroll = read(() -> (javax.swing.JScrollPane) SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, f));
        assertNotNull(scroll);
        assertTrue(read(() -> scroll.getVerticalScrollBar().isVisible()), "o conteudo nao cabe: rola");
        assertFalse(read(() -> scroll.getHorizontalScrollBar().isVisible()), "sem rolagem horizontal");
        // o que esta abaixo da dobra e alcancavel: a rolagem leva ate o botao de iniciar
        edt(() -> f.startButton().scrollRectToVisible(new java.awt.Rectangle(0, 0, f.startButton().getWidth(), f.startButton().getHeight())));
        java.awt.Rectangle visivel = read(() -> scroll.getViewport().getViewRect());
        java.awt.Rectangle botao = read(() -> SwingUtilities.convertRectangle(f.startButton().getParent(), f.startButton().getBounds(), f));
        assertTrue(visivel.contains(botao), "Iniciar execucao visivel depois de rolar: " + visivel + " / " + botao);
        // o foco pelo teclado tambem traz o controle para a vista
        edt(() -> scroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));
        edt(() -> f.closeButton().requestFocusInWindow());
        await("a rolagem acompanha o foco", () -> {
            try {
                return read(() -> scroll.getViewport().getViewRect().contains(SwingUtilities.convertRectangle(f.closeButton().getParent(), f.closeButton().getBounds(), f)));
            } catch (Exception e) {
                return false;
            }
        });
        // todos os controles da preparacao recebem foco pelo teclado
        for (java.awt.Component c : List.of(read(f::openCodeButton), read(f::pythonBox), read(f::locateButton), read(f::searchAgainButton), read(f::approveButton),
                read(f::detailsToggle), read(f::closeButton))) {
            assertTrue(read(c::isFocusable), "alcancavel por Tab: " + c.getClass().getSimpleName());
        }
        // em area folgada, a mesma janela volta a ter o tamanho do conteudo, sem barra
        edt(() -> window.actions().usableArea = new java.awt.Rectangle(0, 0, 1920, 2000));
        edt(() -> f.searchAgainButton().doClick(0));
        listados(f);
        assertFalse(read(() -> scroll.getVerticalScrollBar().isVisible()));
    }

    @Test
    void aPreparacaoCompactaCabeEmUmaTelaDe1080LinhasA150PorCentoSemRolar() throws Exception {
        // a queixa era o excesso: com os detalhes recolhidos, a preparacao inteira cabe na area util de 150% (cerca de 690 px)
        importar("relatorio", ONDE, List.of("nekkora-fixture-a==1.2.3"));
        RunForm f = abrir("relatorio");
        aprovarESaida(f);
        int altura = read(() -> SwingUtilities.getWindowAncestor(f).getHeight());
        assertTrue(altura <= 690, "altura da janela da preparacao: " + altura);
    }

    @Test
    void trocarOTemaComAPreparacaoAbertaRepintaOFormularioEMantemAEscolha() throws Exception {
        importar("relatorio", ONDE, List.of("pacote-que-nao-existe-nekkora==9.9.9"));
        RunForm f = abrir("relatorio");
        edt(() -> f.pythonBox().setSelectedIndex(0));
        String escolhido = read(f::chosenPython).id();
        String mensagem = read(f::pythonText);
        assertEquals(Themes.color(ClientTheme.SAKURA_DARK.bg()), read(f::getBackground));
        assertEquals(Themes.color(ClientTheme.SAKURA_DARK.dangerText()), read(() -> f.pythonNotice().color()));
        try {
            edt(() -> window.themeToggle().doClick(0));
            answer();
            assertEquals(ClientTheme.NEKKORA_LIGHT, read(Themes::current));
            assertEquals(Themes.color(ClientTheme.NEKKORA_LIGHT.bg()), read(f::getBackground), "o fundo acompanha o tema");
            assertEquals(Themes.color(ClientTheme.NEKKORA_LIGHT.dangerText()), read(() -> f.pythonNotice().color()), "o erro continua na cor de erro, a do tema novo");
            assertEquals("Erro", read(() -> f.pythonNotice().label()), "e continua dito em palavras, nao so na cor");
            assertEquals(escolhido, read(f::chosenPython).id(), "trocar o tema nao mexe na escolha");
            assertEquals(mensagem, read(f::pythonText));
            evidence("light-preparacao-compacta-ambiente-incompativel", f);
        } finally {
            edt(() -> Themes.apply(ClientTheme.SAKURA_DARK));
        }
    }
}
