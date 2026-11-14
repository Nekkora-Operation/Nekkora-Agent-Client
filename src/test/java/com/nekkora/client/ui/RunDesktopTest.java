package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;
import javax.swing.JButton;
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
import com.nekkora.client.pack.PackFixtures;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.run.ApprovalLedger;
import com.nekkora.client.run.RunController;
import com.nekkora.client.run.RunStore;
import com.nekkora.client.run.Runner;
import com.nekkora.client.run.SessionProbe;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.signin.SignInState.Phase;
import com.nekkora.client.theme.ClientTheme;

/**
 * Execucao local e estado da integracao (AGENT-CLIENT-NAVEGACAO-001 T05), na janela real. Area de pacotes e executor
 * REAIS em pasta temporaria; entrada e Platform SIMULADAS. Os testes que lancam um processo usam o Python de
 * {@code NEKKORA_TEST_PYTHON} e sao PULADOS (nunca dados por aprovados) quando a variavel nao existe.
 */
@Tag("desktop")
class RunDesktopTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");
    private static final String PYTHON = System.getenv("NEKKORA_TEST_PYTHON");
    private static final String SKIP = "sem Python de teste (NEKKORA_TEST_PYTHON): execucao real NAO conferida; teste pulado, nunca dado por aprovado";

    private static final String GRAVA = "import os\nopen(os.path.join(os.environ['NEKKORA_SAIDA'], 'feito.txt'), 'w').write('ok')\n";
    private static final String LENTO = "import time\ntime.sleep(300)\n";

    @TempDir
    Path tmp;
    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private Workshop workshop;
    private Runner runner;
    private FakeSession session;
    private final AtomicInteger closes = new AtomicInteger();
    private final List<String> blocked = new ArrayList<>();
    private final List<String> exitQuestions = new ArrayList<>();
    private volatile boolean confirmExit;
    private final Organization a = FakeDirectory.org(1, "Acme Indústria");
    private final Organization b = FakeDirectory.org(2, "Beta Serviços");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");
    private final Workspace fis = FakeDirectory.ws(2, "Fiscal");
    private final Workspace deB = FakeDirectory.ws(4, "Financeiro");

    @BeforeEach
    void open() throws Exception {
        fake = new FakeSignIn();
        background = new FakeDirectory.Manual();
        Path dados = tmp.resolve("dados");
        workshop = new Workshop(dados);
        // sessao interativa "presente": o que se testa aqui e a tela e o executor, nao a sonda de sessao do Windows
        SessionProbe sessao = () -> new SessionProbe.Session(true, 1, false, null);
        runner = new Runner(workshop.packages(), new RunController(new RunStore(dados.resolve("execucoes"), AtomicWriter.DEFAULT),
                new ApprovalLedger(ApprovalLedger.defaultFile(dados), AtomicWriter.DEFAULT), sessao, Clock.systemUTC(), dados));
        SwingUtilities.invokeAndWait(() -> {
            Themes.apply(ClientTheme.SAKURA_DARK);
            flow = new SignInFlow(fake, r -> {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
            });
            window = new ClientWindow(flow, t -> { }, closes::incrementAndGet, BuildInfo.load(), background, workshop, runner);
            window.actions().modal = false;
            window.guard = new ClientWindow.Guard() {
                @Override
                public boolean confirmExit(String title, String text) {
                    exitQuestions.add(title + " | " + text);
                    return confirmExit;
                }

                @Override
                public void blocked(String text) {
                    blocked.add(text);
                }
            };
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        settle();
        session = new FakeSession();
        FakeDirectory d = session.directory;
        d.organizations = List.of(a, b);
        d.workspaces.put(a.id(), List.of(fin, fis));
        d.workspaces.put(b.id(), List.of(deB));
        d.automations.put(fin.id(), FakeDirectory.listing(true, true, FakeDirectory.automation(1, "Conciliação bancária", 3, "1.2.0")));
        d.automations.put(fis.id(), FakeDirectory.listing(false, false, FakeDirectory.automation(4, "Apuração", 7, "2.0.1")));
        d.automations.put(deB.id(), FakeDirectory.listing(false, false));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        answer();
    }

    @AfterEach
    void close() throws Exception {
        runner.close(); // nenhum processo de teste sobrevive
        SwingUtilities.invokeAndWait(() -> {
            window.actions().close();
            flow.close();
            window.dispose();
        });
    }

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
        edt(window::validate);
    }

    private void await(String what, BooleanSupplier ok) throws Exception {
        long end = System.nanoTime() + 40_000_000_000L;
        while (!ok.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("nao aconteceu: " + what);
            }
            Thread.sleep(50);
            answer();
        }
    }

    private void enter(Organization o, Workspace w) throws Exception {
        edt(() -> window.organizationsView().cards().stream().filter(c -> c.organization().id().equals(o.id())).findFirst().orElseThrow().choose());
        answer();
        edt(() -> window.contextView().workspaceBox().setSelectedItem(w));
        answer();
    }

    private void importar(String name, String script, List<String> dependencies) throws Exception {
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), name, Map.of("main.py", script), "main.py", dependencies);
        edt(() -> window.actions().importZip(zip.toString()));
        answer();
    }

    private AutomationRow row(String name) throws Exception {
        return read(() -> window.contextView().rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow());
    }

    private RunForm abrir(String name) throws Exception {
        JButton executar = row(name).actions().get(0);
        assertTrue(read(executar::isEnabled), "Executar habilitado em " + name);
        edt(() -> executar.doClick(0));
        answer();
        RunForm f = read(() -> window.actions().runForm());
        assertNotNull(f);
        return f;
    }

    private void evidence(String name, Container root) throws Exception {
        Files.createDirectories(EVIDENCE);
        BufferedImage img = read(() -> {
            BufferedImage i = new BufferedImage(root.getWidth() * 2, root.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = i.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(2, 2);
            root.paint(g);
            g.dispose();
            return i;
        });
        ImageIO.write(img, "png", EVIDENCE.resolve(name + "-200.png").toFile());
    }

    // ------------------------------------------------------------------ estado da integracao e disponibilidade

    @Test
    void estadoDaIntegracaoESomenteLocalEANotaDeIndisponibilidadeSome() throws Exception {
        ContextView v = window.contextView();
        edt(() -> window.organizationsView().cards().get(0).choose());
        answer();
        assertFalse(read(() -> v.statusLabel().isVisible()), "sem ambiente, nenhum estado de integracao e afirmado");
        edt(() -> v.workspaceBox().setSelectedItem(fin));
        answer();
        assertEquals("Somente local — integração com a Platform não configurada", read(() -> v.statusLabel().getText()));
        assertTrue(read(() -> v.statusLabel().isShowing()));
        assertTrue(read(() -> v.statusLabel().getToolTipText()).contains("sem gatilhos da Platform e sem envio de registros de monitoramento"));
        assertEquals("", read(v::unavailableNote), "todas as acoes existem: a nota de indisponibilidade nao aparece");
        evidence("sakura-dark-estado-somente-local", window.getRootPane());
        // nenhum botao promete integracao
        for (JButton bt : read(() -> List.of(v.createButton(), v.importButton()))) {
            assertFalse(read(bt::getText).toLowerCase().matches(".*(vincul|parear|conectar|integra).*"));
        }
    }

    @Test
    void executarSemOPacoteNoComputadorExplicaEApontaAImportacao() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        JButton executar = row("Conciliação bancária").actions().get(0);
        assertTrue(read(executar::isEnabled));
        edt(() -> executar.doClick(0));
        answer();
        assertEquals("O pacote desta automação não está neste computador. Use Importar (.zip) para trazê-lo: o aplicativo não baixa código da Platform.",
                read(v::noticeText));
        assertNull(read(() -> window.actions().runForm()), "nenhuma preparacao aberta");
        assertTrue(runner.current().isEmpty(), "nada foi iniciado");
        assertFalse(session.directory.calls.stream().anyMatch(c -> c.contains("download") || c.contains("artifact")), "nenhum download inventado");
    }

    @Test
    void semPermissaoDeExecutarNoAmbienteALinhaDaPlatformFicaDesabilitadaEOItemLocalNao() throws Exception {
        enter(a, fis); // capacidades: execute=false
        JButton daPlatform = row("Apuração").actions().get(0);
        assertFalse(read(daPlatform::isEnabled));
        assertEquals("Você não tem permissão para executar automações neste ambiente.", read(daPlatform::getToolTipText));
        importar("meu-script", GRAVA, List.of());
        JButton local = row("meu-script").actions().get(0);
        assertTrue(read(local::isEnabled), "o que so existe neste computador e da propria pessoa");
    }

    // ------------------------------------------------------------------ nada comeca sozinho

    @Test
    void importarSelecionarETrocarDeAmbienteNaoExecutamNada() throws Exception {
        enter(a, fin);
        importar("meu-script", GRAVA, List.of());
        assertTrue(runner.current().isEmpty(), "importar nao executa");
        edt(() -> window.contextView().workspaceBox().setSelectedItem(fis));
        answer();
        edt(() -> window.contextView().workspaceBox().setSelectedItem(fin));
        answer();
        assertTrue(runner.current().isEmpty(), "trocar de ambiente nao executa");
        assertFalse(Files.exists(tmp.resolve("dados").resolve("execucoes")) && Files.list(tmp.resolve("dados").resolve("execucoes")).findAny().isPresent(),
                "nenhum registro de execucao");
    }

    @Test
    void preparacaoExigeTudoEAprovarNaoInicia() throws Exception {
        enter(a, fin);
        importar("meu-script", GRAVA, List.of());
        RunForm f = abrir("meu-script");
        assertTrue(read(f::scopeText).contains("Nada é enviado à Platform") && read(f::scopeText).contains("Nada começa até você clicar em Iniciar execução"));
        assertTrue(read(f::packageText).startsWith("meu-script · versão do pacote 1.0.0 · entrada main.py"), read(f::packageText));
        assertFalse(read(() -> f.startButton().isEnabled()), "abrir a preparacao nao habilita o inicio");
        assertTrue(runner.current().isEmpty());
        evidence("sakura-dark-preparar-execucao", read(() -> (Container) window.actions().runForm()));

        // aprovacao explicita: o clique da pessoa; nao inicia nada
        assertTrue(read(f::approvalText).contains("Aprovar não inicia nada"));
        edt(() -> f.approveButton().doClick(0));
        answer();
        assertEquals("Este conteúdo exato foi aprovado por você neste computador.", read(f::approvalText));
        assertTrue(runner.current().isEmpty(), "aprovar nao inicia");
        assertFalse(read(() -> f.startButton().isEnabled()), "falta Python e pasta de saida");

        // interpretador invalido: recusado com explicacao
        Path naoPython = Files.writeString(tmp.resolve("python.exe"), "nao sou um interpretador");
        edt(() -> window.actions().setPython(naoPython.toString()));
        answer();
        assertFalse(read(f::pythonText).isBlank());
        assertFalse(read(f::pythonText).startsWith("Python "), read(f::pythonText));
        assertFalse(read(() -> f.startButton().isEnabled()));
        // pasta de saida inexistente
        edt(() -> window.actions().setOutput(tmp.resolve("nao-existe").toString()));
        answer();
        assertEquals("A pasta de saída não existe. Escolha uma pasta existente.", read(f::outputText));
        assertFalse(read(() -> f.startButton().isEnabled()));

        // preparacao cancelada: fechar nao deixa nada rodando nem registrado
        edt(() -> f.closeButton().doClick(0));
        assertNull(read(() -> window.actions().runForm()));
        assertTrue(runner.current().isEmpty());
        assertTrue(runner.running().isEmpty());
    }

    @Test
    void aprovacaoValeParaOConteudoExatoENaoParaOutroPacote() throws Exception {
        enter(a, fin);
        importar("primeiro", GRAVA, List.of());
        importar("segundo", GRAVA + "# outro conteudo\n", List.of());
        RunForm f = abrir("primeiro");
        edt(() -> f.approveButton().doClick(0));
        answer();
        edt(() -> f.closeButton().doClick(0));
        RunForm g = abrir("segundo");
        assertTrue(read(g::approvalText).contains("Aprovar não inicia nada"), "o segundo pacote nao herda a aprovacao do primeiro");
        assertTrue(read(() -> g.approveButton().isVisible()));
        edt(() -> g.closeButton().doClick(0));
        RunForm h = abrir("primeiro");
        assertEquals("Este conteúdo exato foi aprovado por você neste computador.", read(h::approvalText), "a aprovacao do primeiro continua");
    }

    // ------------------------------------------------------------------ execucao real (precisa de Python)

    private RunForm preparado(String name, String script) throws Exception {
        importar(name, script, List.of());
        RunForm f = abrir(name);
        edt(() -> f.approveButton().doClick(0));
        answer();
        edt(() -> window.actions().setPython(PYTHON));
        await("python conferido", () -> {
            try {
                return !read(f::pythonText).isBlank();
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(read(f::pythonText).startsWith("Python "), read(f::pythonText));
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        edt(() -> window.actions().setOutput(saida.toString()));
        answer();
        assertTrue(read(() -> f.startButton().isEnabled()), "tudo conferido: pode iniciar");
        return f;
    }

    @Test
    void execucaoLocalRealGravaNaSaidaENaoFalaComAPlatform() throws Exception {
        assumeTrue(PYTHON != null && !PYTHON.isBlank(), SKIP);
        enter(a, fin);
        RunForm f = preparado("grava", GRAVA);
        int chamadas = session.directory.calls.size();
        assertTrue(runner.current().isEmpty(), "ate aqui nada foi lancado");
        edt(() -> f.startButton().doClick(0));
        await("execucao terminada", () -> runner.current().map(Runner.Status::finished).orElse(false));
        Runner.Status s = runner.current().orElseThrow();
        assertTrue(s.succeeded(), s.state() + " " + s.detail());
        assertEquals(a.id(), s.organizationId(), "o contexto do inicio fica no registro");
        assertEquals(fin.id(), s.workspaceId());
        assertEquals("grava", s.name());
        assertEquals("ok", Files.readString(Path.of(s.outputFolder()).resolve("feito.txt")), "o script rodou e gravou na pasta de saida da execucao");
        await("resultado na tela", () -> {
            try {
                return read(f::statusText).startsWith("Concluída com sucesso");
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(read(f::statusText).contains("Registro só neste computador."));
        assertEquals(chamadas, session.directory.calls.size(), "nenhuma chamada a Platform durante a execucao: sem registro, monitoramento ou envio");
        evidence("sakura-dark-execucao-concluida", read(() -> (Container) window.actions().runForm()));
        // o registro local guarda o contexto e nao existe fila de envio
        Path registros = tmp.resolve("dados").resolve("execucoes");
        String estado = Files.readString(registros.resolve(s.id()).resolve("estado.json"));
        assertTrue(estado.contains(a.id().toString()) && estado.contains(fin.id().toString()), "organizacao e ambiente do inicio no registro");
        assertFalse(Files.exists(tmp.resolve("dados").resolve("plataforma")), "nenhuma pasta de integracao");
    }

    @Test
    void dependenciaAusenteNoPythonEscolhidoNaoIniciaNada() throws Exception {
        assumeTrue(PYTHON != null && !PYTHON.isBlank(), SKIP);
        enter(a, fin);
        importar("com-dependencia", GRAVA, List.of("pacote-que-nao-existe-nekkora==9.9.9"));
        RunForm f = abrir("com-dependencia");
        edt(() -> f.approveButton().doClick(0));
        answer();
        edt(() -> window.actions().setPython(PYTHON));
        await("python conferido", () -> {
            try {
                return !read(f::pythonText).isBlank();
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(read(f::pythonText).contains("AGT-EXT-022"), read(f::pythonText));
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        edt(() -> window.actions().setOutput(saida.toString()));
        answer();
        assertFalse(read(() -> f.startButton().isEnabled()), "ambiente incompativel: nao inicia (e nada e instalado)");
        assertTrue(runner.current().isEmpty());
    }

    @Test
    void comExecucaoEmAndamentoTrocarContextoETerminarSessaoSaoBloqueadosESairPergunta() throws Exception {
        assumeTrue(PYTHON != null && !PYTHON.isBlank(), SKIP);
        ContextView v = window.contextView();
        enter(a, fin);
        RunForm f = preparado("lento", LENTO);
        edt(() -> f.startButton().doClick(0));
        await("em andamento", () -> runner.running().isPresent());
        answer();
        assertTrue(read(() -> f.cancelButton().isVisible()));
        evidence("sakura-dark-execucao-em-andamento", read(() -> (Container) window.actions().runForm()));

        // trocar de ambiente: bloqueado, o seletor volta, a lista nao muda
        edt(() -> v.workspaceBox().setSelectedItem(fis));
        answer();
        assertEquals(List.of(NavText.RUNNING_BLOCKS_CONTEXT), blocked);
        assertEquals(fin, read(() -> v.workspaceBox().getSelectedItem()), "continua no ambiente da execucao");
        // voltar para as organizacoes: bloqueado
        edt(() -> v.backButton().doClick(0));
        assertEquals(ClientWindow.CONTEXT, read(window::showing));
        assertEquals(2, blocked.size());
        // terminar sessao: bloqueado, a sessao continua
        edt(window::requestSignOut);
        assertEquals(NavText.RUNNING_BLOCKS_SIGN_OUT, blocked.get(2));
        assertEquals(Phase.SIGNED_IN, flow.state().phase());
        assertEquals(0, session.ended);
        // segunda execucao: recusada enquanto a primeira corre
        JButton outra = row("lento").actions().get(0);
        edt(() -> outra.doClick(0));
        answer();
        assertEquals(NavText.RUN_BUSY, read(v::noticeText));
        // sair: pergunta; sem confirmar, nada muda e o processo segue
        confirmExit = false;
        edt(window::requestExit);
        assertEquals(1, exitQuestions.size());
        assertTrue(exitQuestions.get(0).contains("Sair encerra o processo dela") && exitQuestions.get(0).startsWith("Sair do Nekkora Agent?"));
        assertEquals(0, closes.get());
        assertTrue(runner.running().isPresent(), "sem confirmacao, a execucao nao e tocada");
        // fechar a janela da execucao nao cancela
        edt(() -> f.closeButton().doClick(0));
        assertTrue(runner.running().isPresent());
        // a sessao vence (15 min): a tela volta para a entrada, a execucao NAO e morta e continua no contexto dela
        edt(session::expire);
        assertEquals(ClientWindow.SIGN_IN, read(window::showing));
        Runner.Status ainda = runner.running().orElseThrow();
        assertEquals(a.id(), ainda.organizationId());
        assertEquals(fin.id(), ainda.workspaceId());
        // sair confirmando: so entao o aplicativo pede o encerramento
        confirmExit = true;
        edt(window::requestExit);
        assertEquals(1, closes.get(), "confirmado: o aplicativo encerra (e o encerramento cancela a execucao)");
        runner.close();
        Runner.Status fim = runner.current().orElseThrow();
        assertTrue(fim.finished());
        assertEquals("Cancelada", fim.state());
        assertEquals(a.id(), fim.organizationId(), "cancelada no contexto em que comecou, sem reatribuicao");
    }

    @Test
    void cancelarPelaTelaEncerraOProcessoERegistraComoCancelada() throws Exception {
        assumeTrue(PYTHON != null && !PYTHON.isBlank(), SKIP);
        enter(a, fin);
        RunForm f = preparado("lento", LENTO);
        edt(() -> f.startButton().doClick(0));
        await("em andamento", () -> runner.running().isPresent());
        answer();
        edt(() -> f.cancelButton().doClick(0));
        await("cancelada", () -> runner.current().map(Runner.Status::finished).orElse(false));
        assertEquals("Cancelada", runner.current().orElseThrow().state());
        // o ultimo aviso do executor chega a tela logo depois de o registro fechar
        await("resultado na tela", () -> {
            try {
                return read(f::statusText).startsWith("Cancelada");
            } catch (Exception e) {
                return false;
            }
        });
        // depois do fim, trocar de contexto volta a ser permitido
        edt(() -> window.contextView().workspaceBox().setSelectedItem(fis));
        answer();
        assertEquals(List.of(), blocked);
        assertEquals(fis, read(() -> window.contextView().workspaceBox().getSelectedItem()));
    }
}
