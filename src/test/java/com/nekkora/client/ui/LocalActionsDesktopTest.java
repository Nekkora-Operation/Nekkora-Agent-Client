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

    /** Guarda um pacote local no contexto (preparo do teste; na tela, isso acontece em Editar) e recarrega a lista. */
    private void seed(Path zip) throws Exception {
        workshop.importZip(a.id(), fin.id(), zip.toString());
        edt(() -> window.reloadLocal());
        answer();
    }

    // ------------------------------------------------------------------ disponibilidade

    @Test
    void acoesLocaisValemSoComAmbienteEscolhidoEExecutarAindaNaoExiste() throws Exception {
        ContextView v = window.contextView();
        edt(() -> window.organizationsView().cards().get(0).choose());
        answer();
        assertTrue(read(() -> v.generateButton().isEnabled()), "gerar um .zip nao depende de ambiente");
        edt(() -> v.workspaceBox().setSelectedItem(fin));
        answer();
        assertTrue(read(() -> v.generateButton().isEnabled()));
        JButton executar = read(() -> v.rows().get(0).actions().get(0));
        assertFalse(read(executar::isEnabled));
        assertEquals("Indisponível nesta versão de teste.", read(executar::getToolTipText));
        assertEquals("Executar ainda não está disponível nesta versão de teste.", read(v::unavailableNote));
        // metadados da Platform nao sao o codigo: sem pacote local, a linha diz isso
        assertEquals(List.of("Versão publicada 3 · versão do pacote 1.2.0", "Pacote não está neste computador"), read(() -> v.rows().get(0).details()));
    }

    // ------------------------------------------------------------------ renomear

    @Test
    void renomearAutomacaoDaPlatformComPermissaoSalvaNoServidor() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        JButton editar = read(() -> v.rows().get(0).actions().get(1));
        assertTrue(read(editar::isEnabled));
        edt(() -> editar.doClick(0));
        RenameForm f = read(() -> window.actions().renameFormShown());
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
        // Editar abre (o pacote desta maquina e escolhido la); a secao de renomear e que diz a falta de permissao
        JButton editar = read(() -> v.rows().get(0).actions().get(1));
        assertTrue(read(editar::isEnabled));
        edt(() -> editar.doClick(0));
        EditForm semRenomear = (EditForm) read(() -> window.actions().form());
        assertNull(semRenomear.renameForm(), "sem a permissao, nao ha campo de nome");
        assertTrue(read(semRenomear::renameDeniedText).contains("não tem permissão para renomear"), read(semRenomear::renameDeniedText));
        edt(() -> window.actions().close());
        // mesmo forcando a acao (como faria um Client alterado), quem decide e o servidor
        session.directory.renameFailure = Failure.DENIED;
        ContextView.Entry entrada = read(() -> (ContextView.Entry) v.rows().get(0).getClientProperty("entry"));
        edt(() -> window.actions().rename(entrada));
        RenameForm f = read(() -> window.actions().renameFormShown());
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

    /**
     * AGENT-CLIENT-NAVEGACAO-002 T01: atualizar reconcilia a lista da Platform com o que ja esta neste computador. O
     * pacote local nao e apagado, baixado de novo nem trocado, e um formulario aberto no mesmo contexto continua aberto.
     */
    @Test
    void atualizarMantemOsPacotesLocaisEOFormularioAberto() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "folha-local", "0.3.0", "main.py");
        seed(zip);
        List<Workshop.LocalItem> antes = workshop.list(a.id(), fin.id());
        assertEquals(1, antes.size());
        assertEquals(List.of("Conciliação bancária", "folha-local"), names());
        // formulario de renomear aberto sobre o item local
        JButton editar = row("folha-local").actions().get(1);
        edt(() -> editar.doClick(0));
        Object aberto = read(() -> window.actions().form());
        assertTrue(aberto instanceof RenameForm);

        // na Platform: versao nova publicada e automacao nova
        session.directory.automations.put(fin.id(), FakeDirectory.listing(true, true, FakeDirectory.automation(1, "Conciliação bancária", 4, "1.3.0"),
                FakeDirectory.automation(6, "Nova na Platform", 1, "0.1.0")));
        session.directory.calls.clear();
        edt(() -> v.refresh().button().doClick(0));
        answer();
        assertEquals("Dados atualizados.", read(() -> v.refresh().noteText()));
        assertEquals(List.of("Conciliação bancária", "Nova na Platform", "folha-local"), names(), "Platform na ordem dela; depois, o que so existe aqui");
        assertEquals("Versão publicada 4 · versão do pacote 1.3.0", read(() -> v.rows().get(0).details().get(0)));
        assertEquals("Pacote não está neste computador", read(() -> v.rows().get(0).details().get(1)), "a versao nova nao trouxe pacote: nada foi baixado");
        assertEquals(antes, workshop.list(a.id(), fin.id()), "o pacote local e o mesmo: nem apagado, nem reimportado, nem trocado");
        assertTrue(read(() -> window.actions().form()) == aberto, "o formulario aberto no mesmo contexto continua aberto");
        assertTrue(session.directory.renames.isEmpty());
        assertFalse(session.directory.calls.stream().anyMatch(c -> c.startsWith("rename")), "atualizar nao renomeia");
    }

    @Test
    void renomearItemLocalMudaSoONomeNesteComputador() throws Exception {
        ContextView v = window.contextView();
        enter(a, fin);
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "folha-local", "0.3.0", "main.py");
        seed(zip);
        String fingerprint = workshop.list(a.id(), fin.id()).get(0).fingerprint();
        JButton editar = row("folha-local").actions().get(1);
        assertTrue(read(editar::isEnabled), "item so local: a pessoa pode dar um nome");
        edt(() -> editar.doClick(0));
        RenameForm f = read(() -> window.actions().renameFormShown());
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
