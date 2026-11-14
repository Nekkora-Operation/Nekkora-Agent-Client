package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * "Ambiente Python" na preparacao da execucao (AGENT-CLIENT-PYTHON-001 T02), na janela real: a lista de ambientes, o
 * seletor de PASTA real (conduzido por dentro do processo), "Buscar novamente" e a execucao pelo ambiente escolhido.
 * Processo Python REAL ({@code NEKKORA_TEST_PYTHON}; sem ele, os testes sao PULADOS, nunca dados por aprovados). As fontes
 * da maquina sao uma lista do teste; os ambientes virtuais sao fixtures criadas pelo teste em pasta temporaria (sem pip,
 * sem rede). Entrada e Platform simuladas. Nao e o aceite da pessoa.
 */
@Tag("desktop")
class PythonEnvironmentDesktopTest {

    private static final String PYTHON = System.getenv("NEKKORA_TEST_PYTHON");
    private static final String SKIP = "sem Python de teste (NEKKORA_TEST_PYTHON): ambientes NAO conferidos; teste pulado, nunca dado por aprovado";
    /** Automacao sintetica: anota qual interpretador a executou. */
    private static final String ONDE = "import json, os, sys\n"
            + "open(os.path.join(os.environ['NEKKORA_SAIDA'], 'interpretador.json'), 'w', encoding='utf-8').write(json.dumps({'executavel': sys.executable,\n"
            + "    'prefixo': sys.prefix, 'versao': '%d.%d.%d' % sys.version_info[:3]}))\n";

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
            JFileChooser open = read(PythonEnvironmentDesktopTest::chooserOnScreen);
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
        assertTrue(read(() -> f.selectFolderButton().isShowing() && f.selectFolderButton().isEnabled()));
        SwingUtilities.invokeLater(() -> f.selectFolderButton().doClick(0));
        long limit = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < limit) {
            JFileChooser c = read(PythonEnvironmentDesktopTest::chooserOnScreen);
            if (c != null) {
                return c;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("o seletor de pasta nao abriu");
    }

    private void selecionarPasta(RunForm f, Path pasta) throws Exception {
        JFileChooser c = abrirSeletor(f);
        assertEquals(JFileChooser.DIRECTORIES_ONLY, (int) read(c::getFileSelectionMode), "seletor de PASTA: nao de .py nem de python.exe");
        assertEquals("Selecionar a pasta da automação", read(c::getDialogTitle));
        edt(() -> {
            c.setSelectedFile(pasta.toFile());
            c.approveSelection();
        });
        assertFalse(read(c::isShowing));
        listados(f);
    }

    private static String texts(Container c) {
        StringBuilder b = new StringBuilder();
        for (Component child : c.getComponents()) {
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

    // ------------------------------------------------------------------ a tela

    @Test
    void aSecaoChamaAmbientePythonENaoPedeMaisOCaminhoDoExecutavel() throws Exception {
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        String tela = read(() -> texts(f));
        assertTrue(tela.contains("Ambiente Python\n"), tela);
        assertEquals("Escolha o Python que executará esta automação. Você também pode selecionar a pasta do projeto para usar seu ambiente virtual. Nada será instalado.",
                read(f::pythonHintText));
        assertEquals("Selecionar pasta da automação…", read(() -> f.selectFolderButton().getText()));
        assertEquals("Buscar novamente", read(() -> f.searchAgainButton().getText()));
        assertFalse(tela.contains("Interpretador") || tela.contains("Escolher o interpretador"), "nada na tela pede o executavel: " + tela);
        assertEquals("Ambientes Python disponíveis", read(() -> f.pythonList().getAccessibleContext().getAccessibleName()));
        assertTrue(read(() -> f.pythonList().isFocusable()), "a lista e alcancavel pelo teclado");
    }

    @Test
    void umaUnicaInstalacaoCompativelVemSelecionadaEATelaDizQualSeraUsada() throws Exception {
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        List<String> linhas = read(f::pythonLines);
        assertEquals(1, linhas.size());
        assertTrue(linhas.get(0).matches("Python 3\\.\\d+\\.\\d+ — instalado neste computador — Compatível"), linhas.toString());
        assertNotNull(read(f::chosenPython), "exatamente uma opcao totalmente compativel, sem escolha anterior");
        assertTrue(read(f::pythonText).startsWith("Será usado: Python "), read(f::pythonText));
        assertTrue(read(f::pythonText).contains("Caminho: " + Path.of(PYTHON)), "o caminho fica disponivel para conferencia, sem ser digitado");
        // a dica do item tambem traz o caminho
        String dica = read(() -> ((javax.swing.JComponent) f.pythonList().getCellRenderer().getListCellRendererComponent(f.pythonList(), f.pythonList().getModel().getElementAt(0), 0,
                false, false)).getToolTipText());
        assertEquals(Path.of(PYTHON).toString(), dica);
        assertFalse(read(() -> f.startButton().isEnabled()), "selecionar o ambiente nao aprova nem inicia: faltam a aprovacao e a pasta de saida");
        assertTrue(runner.current().isEmpty());
    }

    @Test
    void comVariasOpcoesCompativeisAPessoaEscolheENadaVemSelecionado() throws Exception {
        Path outro = venv(tmp.resolve("no-path").resolve("ambiente"));
        candidatos.add(exe(outro));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        List<String> linhas = read(f::pythonLines);
        assertEquals(2, linhas.size(), linhas.toString());
        assertTrue(linhas.get(0).contains("instalado neste computador — Compatível"), linhas.toString());
        assertTrue(linhas.get(1).startsWith("Ambiente virtual (ambiente) — Python "), "o que veio pelo PATH e e ambiente virtual nao e apresentado como instalacao: " + linhas);
        assertNull(read(f::chosenPython));
        assertEquals("Escolha na lista o ambiente Python que executará esta automação.", read(f::pythonText));
        aprovarESaida(f);
        assertFalse(read(() -> f.startButton().isEnabled()), "sem ambiente escolhido, nao inicia");
        edt(() -> f.pythonList().setSelectedIndex(1));
        assertTrue(read(f::pythonText).startsWith("Será usado: Ambiente virtual (ambiente) — Python "), read(f::pythonText));
        assertTrue(read(() -> f.startButton().isEnabled()));
        edt(() -> f.pythonList().clearSelection());
        assertFalse(read(() -> f.startButton().isEnabled()));
    }

    // ------------------------------------------------------------------ pasta da automacao

    @Test
    void selecionarAPastaMostraOAmbienteDoProjetoEAExecucaoUsaEsseInterpretador() throws Exception {
        Path projeto = tmp.resolve("Automação de relatório");
        Path ambiente = venv(projeto.resolve(".venv"));
        distribution(ambiente, "nekkora-fixture-a", "1.2.3");
        List<String> antes;
        try (Stream<Path> s = Files.walk(projeto)) {
            antes = s.map(Path::toString).sorted().toList();
        }
        importar("relatorio", ONDE, List.of("nekkora-fixture-a==1.2.3"));
        RunForm f = abrir("relatorio");
        assertTrue(read(f::pythonLines).get(0).endsWith("instalado neste computador — Dependências ausentes"), read(f::pythonLines).toString());
        assertNull(read(f::chosenPython));
        assertTrue(read(f::pythonText).startsWith("Nenhum ambiente Python compatível encontrado. Esta automação precisa de Python "), read(f::pythonText));
        assertTrue(read(f::pythonText).contains("nekkora-fixture-a==1.2.3"), "o requisito real do pacote");

        // desistir no seletor preserva a lista e a mensagem
        String mensagem = read(f::pythonText);
        JFileChooser c = abrirSeletor(f);
        edt(c::cancelSelection);
        answer();
        assertEquals(mensagem, read(f::pythonText));
        assertEquals(1, (int) read(() -> f.pythonLines().size()));

        // a pasta escolhida no seletor real atualiza a lista
        selecionarPasta(f, projeto);
        List<String> linhas = read(f::pythonLines);
        assertEquals(2, linhas.size(), linhas.toString());
        assertTrue(linhas.get(0).matches("Ambiente do projeto \\(\\.venv\\) — Python 3\\.\\d+\\.\\d+ — Compatível"), "no alto da lista: " + linhas);
        Runner.PythonOption escolhido = read(f::chosenPython);
        assertNotNull(escolhido, "a pasta tem um unico ambiente, totalmente compativel, e nao havia escolha: e ele que sera usado");
        assertEquals(exe(ambiente).toRealPath(), Path.of(escolhido.path()).toRealPath());
        assertTrue(read(f::pythonText).startsWith("Será usado: Ambiente do projeto (.venv) — Python "), read(f::pythonText));
        try (Stream<Path> s = Files.walk(projeto)) {
            assertEquals(antes, s.map(Path::toString).sorted().toList(), "a pasta do projeto nao foi alterada pela deteccao");
        }

        // execucao real pelo ambiente escolhido
        aprovarESaida(f);
        assertTrue(read(() -> f.startButton().isEnabled()));
        edt(() -> f.startButton().doClick(0));
        await("execucao terminar", () -> {
            try {
                return read(f::statusText).contains("Registro só neste computador");
            } catch (Exception e) {
                return false;
            }
        });
        Path onde;
        try (Stream<Path> s = Files.walk(tmp.resolve("saida"))) {
            onde = s.filter(x -> x.getFileName().toString().equals("interpretador.json")).findFirst().orElseThrow();
        }
        Map<String, Object> m = JsonReader.object(Files.readString(onde));
        assertEquals(exe(ambiente).toRealPath(), Path.of((String) m.get("executavel")).toRealPath(), "o interpretador usado e o do ambiente escolhido");
        assertEquals(ambiente.toRealPath(), Path.of((String) m.get("prefixo")).toRealPath());
        assertEquals(escolhido.version(), m.get("versao"));
        // a execucao continua local: nada foi pedido a Platform por causa dela
        assertTrue(session.directory.calls.stream().noneMatch(x -> x.toLowerCase(java.util.Locale.ROOT).contains("run") || x.toLowerCase(java.util.Locale.ROOT).contains("exec")),
                session.directory.calls.toString());
    }

    @Test
    void pastaComOsDoisAmbientesListaOsDoisEDeixaAPessoaEscolher() throws Exception {
        Path projeto = tmp.resolve("projeto");
        venv(projeto.resolve(".venv"));
        venv(projeto.resolve("venv"));
        candidatos.clear();
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        assertEquals(List.of(), read(f::pythonLines));
        selecionarPasta(f, projeto);
        List<String> linhas = read(f::pythonLines);
        assertEquals(2, linhas.size());
        assertTrue(linhas.get(0).startsWith("Ambiente do projeto (.venv) — ") && linhas.get(1).startsWith("Ambiente do projeto (venv) — "), linhas.toString());
        assertNull(read(f::chosenPython), "dois ambientes na pasta: nenhum e escolhido sozinho");
        assertEquals("Escolha na lista o ambiente Python que executará esta automação.", read(f::pythonText));
        edt(() -> f.pythonList().setSelectedIndex(1));
        assertTrue(read(f::pythonText).contains(projeto.resolve("venv").toString()), "cada um com o seu caminho: " + read(f::pythonText));
    }

    @Test
    void pastaSemAmbienteExplicaEOutraPastaSubstituiOsCandidatosAnteriores() throws Exception {
        Path vazia = Files.createDirectories(tmp.resolve("sem-ambiente"));
        Path um = tmp.resolve("um");
        venv(um.resolve(".venv"));
        Path dois = tmp.resolve("dois");
        venv(dois.resolve("venv"));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        Runner.PythonOption instalacao = read(f::chosenPython);
        assertNotNull(instalacao);

        selecionarPasta(f, vazia);
        assertTrue(read(f::pythonText).startsWith("Nenhum ambiente virtual encontrado nesta pasta. Escolha outra pasta ou utilize uma das instalações de Python listadas."),
                read(f::pythonText));
        assertEquals(1, (int) read(() -> f.pythonLines().size()), "as instalacoes continuam na lista");
        assertEquals(instalacao.id(), read(f::chosenPython).id(), "a escolha anterior fica");

        selecionarPasta(f, um);
        assertEquals(2, (int) read(() -> f.pythonLines().size()));
        assertTrue(read(f::pythonLines).get(0).startsWith("Ambiente do projeto (.venv)"));
        assertEquals(instalacao.id(), read(f::chosenPython).id(), "o ambiente do projeto e destacado, mas NAO substitui a escolha ja feita");

        // a pessoa escolhe o ambiente do projeto e depois indica outra pasta
        edt(() -> f.pythonList().setSelectedIndex(0));
        selecionarPasta(f, dois);
        List<String> linhas = read(f::pythonLines);
        assertEquals(2, linhas.size(), "os candidatos da pasta anterior saem: " + linhas);
        assertTrue(linhas.get(0).startsWith("Ambiente do projeto (venv)"), linhas.toString());
        assertNull(read(f::chosenPython), "o ambiente escolhido era o da pasta anterior: a selecao e limpa, nenhum outro entra no lugar");
        assertTrue(read(f::pythonText).contains("O ambiente da pasta anterior saiu da lista."), read(f::pythonText));
    }

    @Test
    void ambienteIncompletoNaPastaEExplicadoENaoViraOpcao() throws Exception {
        Path projeto = Files.createDirectories(tmp.resolve("projeto"));
        Files.createDirectories(projeto.resolve(".venv").resolve("Scripts"));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        selecionarPasta(f, projeto);
        assertEquals(1, (int) read(() -> f.pythonLines().size()));
        assertTrue(read(f::pythonText).startsWith("A pasta .venv existe, mas não é um ambiente virtual completo"), read(f::pythonText));
    }

    // ------------------------------------------------------------------ buscar novamente e respostas atrasadas

    @Test
    void buscarNovamentePreservaAEscolhaPelaIdentidadeELimpaQuandoOAmbienteSome() throws Exception {
        Path outro = venv(tmp.resolve("no-path").resolve("ambiente"));
        candidatos.add(exe(outro));
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");
        edt(() -> f.pythonList().setSelectedIndex(1));
        String id = read(f::chosenPython).id();

        // a busca repetida inverte a ordem dos candidatos e uma fonte falha: a escolha continua a mesma, e a tela diz que foi parcial
        candidatos.clear();
        candidatos.add(exe(outro));
        candidatos.add(Path.of(PYTHON));
        fonteFalha = true;
        edt(() -> f.searchAgainButton().doClick(0));
        assertTrue(read(f::pythonSearching));
        assertFalse(read(() -> f.startButton().isEnabled()), "enquanto verifica, nao inicia");
        listados(f);
        assertEquals(id, read(f::chosenPython).id());
        assertTrue(read(f::pythonText).contains("A busca foi parcial: não houve resposta de inicializador do Python (py)."), read(f::pythonText));
        assertEquals(2, (int) read(() -> f.pythonLines().size()), "falha de uma fonte nao derruba a lista");

        // o ambiente escolhido deixa de existir
        fonteFalha = false;
        candidatos.remove(exe(outro));
        edt(() -> f.searchAgainButton().doClick(0));
        listados(f);
        assertNull(read(f::chosenPython), "nao seleciona outro automaticamente");
        assertTrue(read(f::pythonText).startsWith("O ambiente Python escolhido antes não foi encontrado nesta busca."), read(f::pythonText));
        assertEquals(1, (int) read(() -> f.pythonLines().size()));
    }

    @Test
    void respostaAtrasadaDeOutraPastaOuDeJanelaFechadaNaoAtualizaATela() throws Exception {
        Path um = tmp.resolve("um");
        venv(um.resolve(".venv"));
        Path dois = tmp.resolve("dois");
        venv(dois.resolve("venv"));
        candidatos.clear();
        importar("relatorio", ONDE, List.of());
        RunForm f = abrir("relatorio");

        // duas pastas pedidas em seguida: as duas verificacoes ficam pendentes; so a ultima vale
        edt(() -> window.actions().setPythonFolder(um.toString()));
        edt(() -> window.actions().setPythonFolder(dois.toString()));
        assertTrue(read(f::pythonSearching));
        listados(f);
        List<String> linhas = read(f::pythonLines);
        assertEquals(1, linhas.size(), linhas.toString());
        assertTrue(linhas.get(0).startsWith("Ambiente do projeto (venv)"), "a resposta da primeira pasta chegou e foi descartada: " + linhas);

        // a janela fecha com uma verificacao pendente: a resposta nao reabre nem habilita nada
        edt(() -> window.actions().setPythonFolder(um.toString()));
        edt(() -> f.closeButton().doClick(0));
        answer();
        assertNull(read(() -> window.actions().runForm()));
        assertTrue(runner.current().isEmpty());
        // outra preparacao comeca limpa: sem os ambientes de pasta da anterior
        RunForm g = abrir("relatorio");
        assertEquals(List.of(), read(g::pythonLines));
    }

    @Test
    void ambienteQueMudaDepoisDaValidacaoNaoIniciaEAListaPassaADizerASituacaoDeAgora() throws Exception {
        Path projeto = tmp.resolve("projeto");
        Path ambiente = venv(projeto.resolve(".venv"));
        Path metadados = distribution(ambiente, "nekkora-fixture-a", "1.2.3");
        candidatos.clear();
        importar("relatorio", ONDE, List.of("nekkora-fixture-a==1.2.3"));
        RunForm f = abrir("relatorio");
        selecionarPasta(f, projeto);
        aprovarESaida(f);
        assertTrue(read(() -> f.startButton().isEnabled()), "validado: pode iniciar");

        Files.delete(metadados); // a dependencia saiu do ambiente depois da conferencia
        edt(() -> f.startButton().doClick(0));
        answer();
        answer();
        assertTrue(read(f::statusText).contains("AGT-EXT-022"), read(f::statusText));
        assertTrue(runner.running().isEmpty());
        try (Stream<Path> s = Files.walk(tmp.resolve("saida"))) {
            assertEquals(List.of(), s.filter(Files::isRegularFile).toList(), "nada foi executado: a automacao nao gravou nada");
        }
        assertTrue(read(f::pythonLines).get(0).endsWith("— Dependências ausentes"), read(f::pythonLines).toString());
        assertNotNull(read(f::chosenPython), "o ambiente continua o escolhido: nenhum outro e posto no lugar");
        assertFalse(read(() -> f.startButton().isEnabled()));
    }
}
