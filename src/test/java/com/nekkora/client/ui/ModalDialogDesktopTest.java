package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Automation;
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
 * Os formularios de "Editar" e "Gerar pacote novo" como o aplicativo os abre de verdade: em dialogo MODAL, com o clique
 * nos botoes e o seletor de arquivos REAL (conduzido por dentro do processo, sem teclado nem mouse do sistema).
 *
 * <p>Reproduz os tres defeitos do aceite manual, que os outros testes de tela nao viam porque abriam o dialogo nao-modal
 * e chamavam as acoes direto, sem passar pelo botao e pelo seletor: a consulta de versoes presa em "Consultando", a
 * escolha de .zip/pasta sem resultado e a pasta do projeto que nao aparecia. Causa: o estado que liga o formulario as
 * acoes era atribuido DEPOIS de abrir o dialogo, e abrir um dialogo modal so retorna quando ele fecha.
 */
@Tag("desktop")
class ModalDialogDesktopTest {

    @TempDir
    Path tmp;
    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private Workshop workshop;
    private FakeDirectory d;
    private final Organization a = FakeDirectory.org(1, "Acme Indústria");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");
    private final UUID auto = FakeDirectory.id(2001);
    private final UUID v1 = FakeDirectory.id(3101);
    private Path zip1;

    @BeforeEach
    void open() throws Exception {
        fake = new FakeSignIn();
        background = new FakeDirectory.Manual();
        workshop = new Workshop(tmp.resolve("dados"));
        zip1 = PackFixtures.packageZip(tmp.resolve("zips"), "conciliacao", Map.of("main.py", "print('v1')\n"), "main.py", List.of());
        String fp1 = workshop.inspectLocal(zip1.toString()).fingerprint();
        fp1 = fp1.startsWith("sha256:") ? fp1.substring(7) : fp1;
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip1)));
        long size = Files.size(zip1);
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
            window.setPlatformOrigin("https://platform.teste");
            // window.actions().modal fica como em producao: verdadeiro
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        assertTrue(read(() -> window.actions().modal), "o teste so vale com o dialogo modal, como em producao");
        settle();
        FakeSession session = new FakeSession();
        d = session.directory;
        d.organizations = List.of(a);
        d.workspaces.put(a.id(), List.of(fin));
        d.automations.put(fin.id(), new Automation.Listing(List.of(new Automation(auto, "Conciliação bancária",
                new Automation.Published(v1, 1, "conciliacao", "1.0.0", fp1, sha, size))), new Automation.Capabilities(true, true, true), false));
        d.versions.put(auto, List.of(new Automation.Version(v1, 1, true, "conciliacao", "1.0.0", fp1, sha, size)));
        d.packages.put(v1, Files.readAllBytes(zip1));
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
        // um seletor que tenha ficado aberto por uma falha do teste nao pode prender a fila de eventos
        for (int i = 0; i < 3; i++) {
            JFileChooser open = read(ModalDialogDesktopTest::chooserOnScreen);
            if (open == null) {
                break;
            }
            edt(open::cancelSelection);
        }
        edt(() -> window.actions().close());
        SwingUtilities.invokeAndWait(() -> {
            flow.close();
            window.dispose();
        });
        background.runAll();
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
        for (int i = 0; i < 8; i++) {
            background.runAll();
            settle();
        }
    }

    /**
     * Clique de verdade em algo que abre uma janela modal: o clique so "retorna" quando a janela fecha, entao ele e
     * postado na fila e o teste segue enquanto a janela esta aberta (ela mantem a fila de eventos andando).
     */
    private void clickOpening(JButton button) throws Exception {
        assertTrue(read(() -> button.isShowing() && button.isEnabled()), "botao na tela e habilitado: " + read(button::getText));
        SwingUtilities.invokeLater(() -> button.doClick(0));
        settle();
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

    private JFileChooser chooser() throws Exception {
        long limit = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < limit) {
            JFileChooser f = read(ModalDialogDesktopTest::chooserOnScreen);
            if (f != null) {
                return f;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("o seletor de arquivos nao abriu");
    }

    /** A pessoa escolhe {@code path} no seletor e confirma. */
    private void confirm(Path path) throws Exception {
        JFileChooser c = chooser();
        edt(() -> {
            c.setSelectedFile(path.toFile());
            c.approveSelection();
        });
        assertFalse(read(c::isShowing), "o seletor fecha ao confirmar");
        answer();
    }

    /** A pessoa desiste no seletor. */
    private void cancel() throws Exception {
        JFileChooser c = chooser();
        edt(c::cancelSelection);
        assertFalse(read(c::isShowing));
        answer();
    }

    private PackageForm editar() throws Exception {
        JButton editar = read(() -> window.contextView().rows().get(0).actions().get(1));
        clickOpening(editar);
        answer();
        PackageForm f = read(() -> window.actions().packageForm());
        assertNotNull(f, "com o dialogo ABERTO, o formulario ja tem de estar ligado as acoes");
        assertTrue(read(f::isShowing));
        return f;
    }

    private GenerateForm gerar() throws Exception {
        clickOpening(read(() -> window.contextView().generateButton()));
        GenerateForm f = read(() -> window.actions().generateForm());
        assertNotNull(f, "com o dialogo ABERTO, o formulario ja tem de estar ligado as acoes");
        assertTrue(read(f::isShowing));
        return f;
    }

    private static List<String> items(javax.swing.JComboBox<?> box) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < box.getItemCount(); i++) {
            Object o = box.getItemAt(i);
            out.add(o instanceof Automation.Version v ? "versao " + v.number() : String.valueOf(o));
        }
        return out;
    }

    private Path projeto(String nome) throws Exception {
        Path p = tmp.resolve(nome);
        Files.createDirectories(p.resolve("tarefas"));
        Files.writeString(p.resolve("main.py"), "print('oi')\n");
        Files.writeString(p.resolve("tarefas").resolve("relatorio.py"), "print('relatorio')\n");
        return p;
    }

    // ------------------------------------------------------------------ defeito 1: versoes presas em "Consultando"

    @Test
    void editarModalTerminaAConsultaDeVersoesEHabilitaBaixar() throws Exception {
        PackageForm f = editar();

        List<String> lista = read(() -> items(f.versionsBox()));
        assertFalse(lista.contains(NavText.PKG_VERSIONS_LOADING), "a consulta termina: " + lista);
        assertTrue(lista.contains("versao 1"), lista.toString());
        assertFalse(read(() -> f.retryVersionsButton().isVisible()));

        edt(() -> f.versionsBox().setSelectedIndex(1));
        assertTrue(read(() -> f.downloadButton().isEnabled()), "versao escolhida: Baixar habilitado");
        edt(() -> f.downloadButton().doClick(0));
        answer();
        assertTrue(read(f::downloadText).contains("baixada e conferida"), read(f::downloadText));
    }

    @Test
    void consultaDeVersoesQueFalhaMostraOErroEPermiteTentarDeNovo() throws Exception {
        d.versionsFailure = Failure.NETWORK;
        PackageForm f = editar();

        List<String> falhou = read(() -> items(f.versionsBox()));
        assertEquals(List.of(NavText.PKG_VERSIONS_FAILED), falhou, "a falha encerra o carregamento");
        assertFalse(read(f::downloadText).isBlank(), "a frase diz o que houve");
        assertTrue(read(() -> f.retryVersionsButton().isShowing() && f.retryVersionsButton().isEnabled()));
        assertFalse(read(() -> f.downloadButton().isEnabled()));

        d.versionsFailure = null;
        edt(() -> f.retryVersionsButton().doClick(0));
        assertEquals(List.of(NavText.PKG_VERSIONS_LOADING), read(() -> items(f.versionsBox())), "tentando de novo");
        assertFalse(read(() -> f.retryVersionsButton().isVisible()));
        answer();
        assertTrue(read(() -> items(f.versionsBox())).contains("versao 1"));
        assertFalse(read(() -> f.retryVersionsButton().isVisible()));
        assertEquals("", read(f::downloadText), "a frase da falha saiu");
    }

    // ------------------------------------------------------------------ defeito 2: .zip / pasta sem resultado

    @Test
    void escolherZipPeloSeletorValidaEMostraOResultado() throws Exception {
        PackageForm f = editar();
        assertEquals("", read(f::localText));

        clickOpening(read(f::pickZipButton));
        cancel();
        assertEquals("", read(f::localText), "desistir no seletor preserva o estado");
        assertTrue(read(f::isShowing));

        clickOpening(read(f::pickZipButton));
        confirm(zip1);
        assertTrue(read(f::localText).contains("mesmo conteúdo da versão publicada"), read(f::localText));
        assertTrue(read(f::isShowing), "o Editar continua aberto");
        assertTrue(read(() -> f.pickZipButton().isEnabled()), "o processamento terminou");
    }

    @Test
    void escolherZipInvalidoPeloSeletorMostraErroCompreensivel() throws Exception {
        Path ruim = tmp.resolve("ruim.zip");
        Files.writeString(ruim, "isto nao e um zip");
        PackageForm f = editar();

        clickOpening(read(f::pickZipButton));
        confirm(ruim);
        assertFalse(read(f::localText).isBlank(), "o erro aparece na tela");
        assertTrue(read(() -> f.pickZipButton().isEnabled()), "a falha encerra o processamento e permite nova tentativa");

        clickOpening(read(f::pickZipButton));
        confirm(zip1);
        assertTrue(read(f::localText).contains("mesmo conteúdo da versão publicada"), read(f::localText));
    }

    @Test
    void escolherPastaDeCodigoPeloSeletorOfereceGerarEAbreOFormularioComAPasta() throws Exception {
        Path codigo = projeto("codigo");
        PackageForm f = editar();

        clickOpening(read(f::pickFolderButton));
        confirm(codigo);
        assertFalse(read(f::localText).isBlank());
        assertTrue(read(() -> f.generateButton().isShowing()), "pasta de codigo sem manifesto: oferece Gerar pacote novo");

        clickOpening(read(f::generateButton));
        answer();
        GenerateForm g = read(() -> window.actions().generateForm());
        assertNotNull(g);
        assertTrue(read(g::isShowing));
        assertEquals(codigo.toString(), read(() -> g.folderField().getText()), "a pasta escolhida em Editar chega ao formulario");
        assertTrue(read(() -> items(g.entryBox())).contains("main.py"));
    }

    // ------------------------------------------------------------------ defeito 3: pasta do projeto nao aparecia

    @Test
    void procurarPastaPeloSeletorMostraAPastaECarregaOsArquivosDeEntrada() throws Exception {
        Path pasta = projeto("projeto");
        GenerateForm f = gerar();
        String vazio = read(() -> f.folderField().getText());
        List<String> semEntradas = read(() -> items(f.entryBox()));

        clickOpening(read(f::browseButton));
        cancel();
        assertEquals(vazio, read(() -> f.folderField().getText()), "desistir no seletor preserva o estado");
        assertEquals(semEntradas, read(() -> items(f.entryBox())));

        clickOpening(read(f::browseButton));
        confirm(pasta);
        assertEquals(pasta.toString(), read(() -> f.folderField().getText()));
        List<String> entradas = read(() -> items(f.entryBox()));
        assertTrue(entradas.contains("main.py") && entradas.contains("tarefas/relatorio.py"), entradas.toString());

        // desistir de uma segunda escolha mantem a pasta ja carregada
        clickOpening(read(f::browseButton));
        cancel();
        assertEquals(pasta.toString(), read(() -> f.folderField().getText()));
        assertTrue(read(() -> items(f.entryBox())).contains("main.py"));
    }

    @Test
    void pastaRecusadaMostraOMotivoEPermiteEscolherOutra() throws Exception {
        Path vazia = Files.createDirectories(tmp.resolve("vazia"));
        Path pasta = projeto("projeto");
        GenerateForm f = gerar();

        clickOpening(read(f::browseButton));
        confirm(vazia);
        assertFalse(read(() -> items(f.entryBox())).contains("main.py"));
        assertTrue(read(() -> f.browseButton().isEnabled()), "a recusa encerra a leitura");

        clickOpening(read(f::browseButton));
        confirm(pasta);
        assertEquals(pasta.toString(), read(() -> f.folderField().getText()));
        assertTrue(read(() -> items(f.entryBox())).contains("main.py"));
    }

    @Test
    void fecharOFormularioDesligaOEstadoENaoDeixaConsultaPendurada() throws Exception {
        PackageForm f = editar();
        edt(() -> f.closeButton().doClick(0));
        answer();
        assertFalse(read(f::isShowing));
        assertEquals(null, read(() -> window.actions().packageForm()), "fechado: nenhum formulario fica ligado as acoes");

        GenerateForm g = gerar();
        edt(() -> g.cancelButton().doClick(0));
        answer();
        assertEquals(null, read(() -> window.actions().generateForm()));
    }
}
