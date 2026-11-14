package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Directory.Failure;
import com.nekkora.client.nav.FakeDirectory;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Workspace;
import com.nekkora.client.pack.PackFixtures;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.theme.ClientTheme;

/**
 * Importar, nova automacao e renomear (AGENT-CLIENT-NAVEGACAO-001 T04), na janela real: area local de pacotes REAL em
 * pasta temporaria, entrada e Platform SIMULADAS. Os formularios abrem sem modal para o teste nao travar a fila de
 * eventos; nenhum evento vai para o sistema.
 */
@Tag("desktop")
class LocalActionsDesktopTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");

    @TempDir
    Path tmp;
    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private Workshop workshop;
    private FakeSession session;
    private final Organization a = FakeDirectory.org(1, "Acme Indústria");
    private final Organization b = FakeDirectory.org(2, "Beta Serviços");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");
    private final Workspace fis = FakeDirectory.ws(2, "Fiscal");
    private final Workspace deB = FakeDirectory.ws(4, "Financeiro");

    @BeforeEach
    void open() throws Exception {
        fake = new FakeSignIn();
        background = new FakeDirectory.Manual();
        workshop = new Workshop(tmp.resolve("dados"));
        SwingUtilities.invokeAndWait(() -> {
            Themes.apply(ClientTheme.SAKURA_DARK);
            flow = new SignInFlow(fake, r -> {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
            });
            window = new ClientWindow(flow, t -> { }, () -> { }, BuildInfo.load(), background, workshop);
            window.actions().modal = false;
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
        d.automations.put(fis.id(), FakeDirectory.listing(false, true, FakeDirectory.automation(4, "Apuração", 7, "2.0.1")));
        d.automations.put(deB.id(), FakeDirectory.listing(false, false));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        answer();
    }

    @AfterEach
    void close() throws Exception {
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

    /** Roda o que esta pendente (consultas simuladas e disco), ate nao sobrar nada. */
    private void answer() throws Exception {
        for (int i = 0; i < 6; i++) {
            background.runAll();
            settle();
        }
        edt(window::validate);
    }

    private void enter(Organization o, Workspace w) throws Exception {
        edt(() -> window.organizationsView().cards().stream().filter(c -> c.organization().id().equals(o.id())).findFirst().orElseThrow().choose());
        answer();
        edt(() -> window.contextView().workspaceBox().setSelectedItem(w));
        answer();
    }

    private List<String> names() throws Exception {
        return read(() -> window.contextView().rows().stream().map(AutomationRow::name).toList());
    }

    private AutomationRow row(String name) throws Exception {
        return read(() -> window.contextView().rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow());
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

    // ------------------------------------------------------------------ disponibilidade

    @Test
    void acoesLocaisValemSoComAmbienteEscolhidoEExecutarAindaNaoExiste() throws Exception {
        ContextView v = window.contextView();
        edt(() -> window.organizationsView().cards().get(0).choose());
        answer();
        assertFalse(read(() -> v.createButton().isEnabled() || v.importButton().isEnabled()), "sem ambiente escolhido, nada de importar ou preparar");
        assertEquals("Selecione um ambiente primeiro.", read(() -> v.importButton().getToolTipText()));
        edt(() -> v.workspaceBox().setSelectedItem(fin));
        answer();
        assertTrue(read(() -> v.createButton().isEnabled() && v.importButton().isEnabled()));
        assertEquals("Importar (.zip)", read(() -> v.importButton().getText()), "o texto diz que e .zip");
        JButton executar = read(() -> v.rows().get(0).actions().get(0));
        assertFalse(read(executar::isEnabled));
        assertEquals("Indisponível nesta versão de teste.", read(executar::getToolTipText));
        assertEquals("Executar ainda não está disponível nesta versão de teste.", read(v::unavailableNote));
        // metadados da Platform nao sao o codigo: sem pacote local, a linha diz isso
        assertEquals(List.of("Versão publicada 3 · versão do pacote 1.2.0", "Pacote não está neste computador"), read(() -> v.rows().get(0).details()));
    }

    // ------------------------------------------------------------------ importar

    @Test
    void importarZipValidoApareceSoNoAmbienteEmQueFoiImportado() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "folha-local", "0.3.0", "main.py");
        edt(() -> window.actions().importZip(zip.toString()));
        assertEquals("Trabalhando...", read(v::noticeText));
        answer();
        assertEquals("Pacote importado: folha-local (versão do pacote 0.3.0). Ele fica só neste computador, neste ambiente; nada foi executado.", read(v::noticeText));
        assertEquals(List.of("Conciliação bancária", "folha-local"), names(), "automacoes da Platform primeiro; depois o item so local");
        assertEquals(List.of("Somente neste computador (não está na Platform)", "Versão do pacote 0.3.0"), row("folha-local").details());
        evidence("sakura-dark-importado", window.getRootPane());
        // de novo: nao duplica
        edt(() -> window.actions().importZip(zip.toString()));
        answer();
        assertEquals("Este pacote já estava importado neste ambiente: folha-local.", read(v::noticeText));
        assertEquals(2, names().size());
        // outro ambiente da mesma organizacao: o pacote NAO aparece, e o aviso do anterior some
        edt(() -> v.workspaceBox().setSelectedItem(fis));
        answer();
        assertEquals(List.of("Apuração"), names());
        assertEquals("", read(v::noticeText));
        // outra organizacao, ambiente homonimo: tambem nao
        edt(() -> v.backButton().doClick(0));
        enter(b, deB);
        assertEquals(List.of(), names());
        assertEquals(ContextView.EMPTY, read(v::showing));
        // de volta ao ambiente original, ele continua la (em disco, por contexto)
        edt(() -> v.backButton().doClick(0));
        enter(a, fin);
        assertEquals(List.of("Conciliação bancária", "folha-local"), names());
        assertEquals(1, workshop.list(a.id(), fin.id()).size());
        assertEquals(0, workshop.list(a.id(), fis.id()).size());
        assertEquals(0, workshop.list(b.id(), deB.id()).size());
    }

    @Test
    void zipInvalidoERecusadoComExplicacaoENadaFicaGuardado() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        for (Path ruim : List.of(PackFixtures.notAZip(tmp.resolve("zips")), PackFixtures.codeZip(tmp.resolve("zips"), "minimo"), tmp.resolve("nao-existe.zip"))) {
            edt(() -> window.actions().importZip(ruim.toString()));
            answer();
            String aviso = read(v::noticeText);
            assertFalse(aviso.isBlank() || aviso.startsWith("Pacote importado"), ruim.getFileName() + ": " + aviso);
            assertEquals(List.of("Conciliação bancária"), names());
        }
        Path semExtensao = Files.copy(PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "x", "1.0.0", "main.py"), tmp.resolve("pacote.bin"));
        edt(() -> window.actions().importZip(semExtensao.toString()));
        answer();
        assertEquals("Escolha um arquivo .zip.", read(v::noticeText));
        assertEquals(0, workshop.list(a.id(), fin.id()).size());
    }

    @Test
    void trocarDeContextoDuranteAImportacaoNaoLevaOResultadoParaONovo() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "tardio", "1.0.0", "main.py");
        edt(() -> window.actions().importZip(zip.toString()));
        Runnable gravacao = background.take(0); // a importacao fica presa
        edt(() -> v.backButton().doClick(0));
        enter(b, deB);
        gravacao.run(); // termina agora, com a pessoa em outra organizacao
        answer();
        assertEquals("", read(v::noticeText), "o aviso da importacao nao aparece no contexto novo");
        assertEquals(List.of(), names(), "nem o pacote");
        assertEquals(0, workshop.list(b.id(), deB.id()).size());
        assertEquals(1, workshop.list(a.id(), fin.id()).size(), "o pacote foi para o contexto em que a importacao NASCEU");
    }

    @Test
    void pacoteIgualAoPublicadoEReconhecidoPelaImpressaoDigital() throws Exception {
        ContextView v = window.contextView();
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "conciliacao", "1.2.0", "main.py");
        String fingerprint = new Workshop(tmp.resolve("outra")).importZip(a.id(), fin.id(), zip.toString()).item().fingerprint();
        session.directory.automations.put(fin.id(), new com.nekkora.client.nav.Automation.Listing(List.of(new com.nekkora.client.nav.Automation(FakeDirectory.id(2001),
                "Conciliação bancária", new com.nekkora.client.nav.Automation.Published(FakeDirectory.id(3001), 3, "conciliacao", "1.2.0", fingerprint))),
                new com.nekkora.client.nav.Automation.Capabilities(true, true), false));
        enter(a, fin);
        assertEquals("Pacote não está neste computador", read(() -> v.rows().get(0).details().get(1)));
        edt(() -> window.actions().importZip(zip.toString()));
        answer();
        assertEquals(List.of("Conciliação bancária"), names(), "o pacote identico ao publicado nao vira outra linha");
        assertEquals("Pacote disponível neste computador: conciliacao 1.2.0", read(() -> v.rows().get(0).details().get(1)));
    }

    // ------------------------------------------------------------------ nova automacao

    @Test
    void novaAutomacaoPreparaPacoteLocalSemCriarNadaNaPlatform() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        int chamadas = session.directory.calls.size();
        Path codigo = PackFixtures.codeZip(tmp.resolve("zips"), "declarado");
        edt(() -> window.actions().newAutomation(codigo.toString()));
        answer();
        NewPackageForm f = (NewPackageForm) read(() -> window.actions().form());
        assertNotNull(f);
        assertTrue(read(f::scopeText).contains("Nada é criado na Platform"));
        assertEquals("", read(f::blockersText));
        assertEquals(NewPackageForm.ENTRY_PLACEHOLDER, read(() -> f.entryBox().getSelectedItem()), "o arquivo de entrada nunca vem escolhido");
        assertEquals("1.0.0", read(() -> f.versionField().getText()));
        evidence("sakura-dark-nova-automacao", (Container) read(() -> window.actions().form()));
        // sem escolher a entrada: nao prepara
        edt(() -> f.nameField().setText("relatorio-mensal"));
        edt(() -> f.prepareButton().doClick(0));
        assertEquals("Escolha o arquivo de entrada.", read(f::errorText));
        assertEquals(0, workshop.list(a.id(), fin.id()).size());
        edt(() -> f.entryBox().setSelectedItem("app.py"));
        if (read(() -> f.pythonField().getText()).isBlank()) {
            edt(() -> f.pythonField().setText(">=3.10,<3.14"));
        }
        edt(() -> f.confirmedBox().setSelected(true));
        edt(() -> f.prepareButton().doClick(0));
        answer();
        assertNull(read(() -> window.actions().form()), "preparou: o formulario fecha");
        assertEquals("Pacote preparado: relatorio-mensal (versão do pacote 1.0.0). Ele fica só neste computador; nada foi criado na Platform.", read(v::noticeText));
        assertEquals(List.of("Conciliação bancária", "relatorio-mensal"), names());
        assertFalse(session.directory.calls.subList(chamadas, session.directory.calls.size()).stream().anyMatch(c -> c.startsWith("rename")),
                "nenhuma escrita na Platform");
        // dados invalidos: o formulario fica aberto com a recusa, e nada e gravado
        edt(() -> window.actions().newAutomation(codigo.toString()));
        answer();
        NewPackageForm g = (NewPackageForm) read(() -> window.actions().form());
        edt(() -> {
            g.nameField().setText("outro");
            g.versionField().setText("versao um");
            g.entryBox().setSelectedItem("app.py");
            g.pythonField().setText(">=3.10,<3.14");
            g.confirmedBox().setSelected(true);
        });
        edt(() -> g.prepareButton().doClick(0));
        answer();
        assertFalse(read(g::errorText).isBlank());
        assertEquals(1, workshop.list(a.id(), fin.id()).size());
    }

    @Test
    void zipQueJaEPacoteNaoViraNovaAutomacao() throws Exception {
        enter(a, fin);
        Path pronto = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "pronto", "1.0.0", "main.py");
        edt(() -> window.actions().newAutomation(pronto.toString()));
        answer();
        NewPackageForm f = (NewPackageForm) read(() -> window.actions().form());
        assertEquals("Este ZIP já é um pacote pronto. Use Importar (.zip) para trazê-lo como está.", read(f::blockersText));
        assertFalse(read(() -> f.prepareButton().isEnabled()));
    }

    // ------------------------------------------------------------------ renomear

    @Test
    void renomearAutomacaoDaPlatformComPermissaoSalvaNoServidor() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        JButton editar = read(() -> v.rows().get(0).actions().get(1));
        assertTrue(read(editar::isEnabled));
        edt(() -> editar.doClick(0));
        RenameForm f = (RenameForm) read(() -> window.actions().form());
        assertEquals("Renomear automação na Platform", read(f::titleText));
        assertTrue(read(f::scopeText).contains("vale na Platform") && read(f::scopeText).contains("pacotes e os manifestos não mudam"), "o alcance e dito antes do campo");
        assertEquals("Conciliação bancária", read(() -> f.field().getText()));
        evidence("sakura-dark-renomear-na-platform", (Container) read(() -> window.actions().form()));
        // vazio nao e enviado
        edt(() -> f.field().setText("   "));
        edt(() -> f.saveButton().doClick(0));
        assertEquals("Informe um nome.", read(f::errorText));
        assertEquals(List.of(), session.directory.renames);
        edt(() -> f.field().setText("Conciliação diária"));
        edt(() -> f.saveButton().doClick(0));
        assertFalse(read(() -> f.saveButton().isEnabled()), "ocupado: nao envia duas vezes");
        edt(() -> f.saveButton().doClick(0));
        answer();
        assertEquals(List.of(FakeDirectory.id(2001) + "=Conciliação diária"), session.directory.renames, "uma chamada ao servidor");
        assertNull(read(() -> window.actions().form()));
        assertEquals("Automação renomeada na Platform.", read(v::noticeText));
        assertEquals(List.of("Conciliação diária"), names(), "o nome mostrado e o que a Platform devolveu na nova consulta");
        assertEquals("Versão publicada 3 · versão do pacote 1.2.0", read(() -> v.rows().get(0).details().get(0)), "versao e pacote nao mudam");
    }

    @Test
    void semPermissaoOBotaoFicaDesabilitadoEAChamadaDiretaERecusadaPeloServidor() throws Exception {
        ContextView v = window.contextView();
        enter(a, fis); // capacidades do ambiente: rename=false
        JButton editar = read(() -> v.rows().get(0).actions().get(1));
        assertFalse(read(editar::isEnabled));
        assertEquals("Você não tem permissão para editar automações neste ambiente.", read(editar::getToolTipText));
        // mesmo forcando a acao (como faria um Client alterado), quem decide e o servidor
        session.directory.renameFailure = Failure.DENIED;
        ContextView.Entry entrada = read(() -> (ContextView.Entry) v.rows().get(0).getClientProperty("entry"));
        edt(() -> window.actions().rename(entrada));
        RenameForm f = (RenameForm) read(() -> window.actions().form());
        edt(() -> f.field().setText("Nome forçado"));
        edt(() -> f.saveButton().doClick(0));
        answer();
        assertEquals("Você não tem permissão para renomear esta automação. Nada foi alterado.", read(f::errorText));
        assertEquals(List.of("Apuração"), names(), "a tela nao mostra o nome novo");
        assertEquals(List.of(), session.directory.renames);
        // as outras recusas do servidor tambem ficam no formulario, cada uma com o seu texto
        for (Failure x : new Failure[] {Failure.NOT_FOUND, Failure.CONFLICT, Failure.REJECTED, Failure.NETWORK, Failure.RATE_LIMITED, Failure.SERVER}) {
            session.directory.renameFailure = x;
            edt(() -> f.field().setText("Tentativa " + x));
            edt(() -> f.saveButton().doClick(0));
            answer();
            assertEquals(NavText.of(x), read(f::errorText), x.toString());
            assertTrue(read(() -> f.saveButton().isEnabled()), x + ": pode corrigir e tentar de novo");
        }
        assertEquals(List.of("Apuração"), names());
    }

    @Test
    void renomearItemLocalMudaSoONomeNesteComputador() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "folha-local", "0.3.0", "main.py");
        edt(() -> window.actions().importZip(zip.toString()));
        answer();
        String fingerprint = workshop.list(a.id(), fin.id()).get(0).fingerprint();
        JButton editar = row("folha-local").actions().get(1);
        assertTrue(read(editar::isEnabled), "item so local: a pessoa pode dar um nome");
        edt(() -> editar.doClick(0));
        RenameForm f = (RenameForm) read(() -> window.actions().form());
        assertEquals("Renomear neste computador", read(f::titleText));
        assertTrue(read(f::scopeText).contains("só o nome mostrado neste computador") && read(f::scopeText).contains("nada é alterado na Platform"));
        evidence("sakura-dark-renomear-local", (Container) read(() -> window.actions().form()));
        int chamadas = session.directory.calls.size();
        edt(() -> f.field().setText("Folha do financeiro"));
        edt(() -> f.saveButton().doClick(0));
        answer();
        assertEquals("Nome alterado só neste computador.", read(v::noticeText));
        assertEquals(List.of("Conciliação bancária", "Folha do financeiro"), names());
        assertEquals(chamadas, session.directory.calls.size(), "nenhuma chamada a Platform");
        Workshop.LocalItem item = workshop.list(a.id(), fin.id()).get(0);
        assertEquals(fingerprint, item.fingerprint(), "pacote intacto");
        assertEquals("folha-local", item.declaredName());
    }

    @Test
    void formularioAbertoFechaAoTrocarDeContextoOuTerminarASessao() throws Exception {
        enter(a, fin);
        edt(() -> window.contextView().rows().get(0).actions().get(1).doClick(0));
        assertNotNull(read(() -> window.actions().form()));
        edt(() -> window.contextView().workspaceBox().setSelectedItem(fis));
        assertNull(read(() -> window.actions().form()), "trocou de ambiente: o formulario do anterior fecha");
        answer();
        edt(() -> window.actions().rename(new ContextView.Entry(FakeDirectory.automation(4, "Apuração", 7, "2.0.1"), null)));
        assertNotNull(read(() -> window.actions().form()));
        edt(() -> flow.signOut());
        assertNull(read(() -> window.actions().form()), "sessao terminada: nenhum formulario fica aberto");
        JPanel nada = read(() -> window.actions().form());
        assertNull(nada);
    }
}
