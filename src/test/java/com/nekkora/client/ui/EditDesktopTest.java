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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.swing.JButton;
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
 * "Editar" de uma automacao da Platform (AGENT-CLIENT-PACOTES-001 T02): o nome e o PACOTE NESTA MAQUINA - pacote local ou
 * download de uma versao publicada. Janela real; entrada e Platform SIMULADAS; area local de pacotes REAL em pasta
 * temporaria, com pacotes gerados pelo empacotador real. A prova do download contra a API real fica no laboratorio.
 */
@Tag("desktop")
class EditDesktopTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");

    @TempDir
    Path tmp;
    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private Workshop workshop;
    private FakeSession session;
    private FakeDirectory d;
    private final Organization a = FakeDirectory.org(1, "Acme Indústria");
    private final Organization b = FakeDirectory.org(2, "Beta Serviços");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");
    private final Workspace fis = FakeDirectory.ws(2, "Fiscal");
    private final Workspace deB = FakeDirectory.ws(4, "Financeiro");
    private final UUID auto = FakeDirectory.id(2001);
    private final UUID v1 = FakeDirectory.id(3101);
    private final UUID v2 = FakeDirectory.id(3102);
    private final UUID rascunho = FakeDirectory.id(3103);
    private Path zip1;
    private Path zip2;
    private String fp1;
    private String fp2;

    @BeforeEach
    void open() throws Exception {
        fake = new FakeSignIn();
        background = new FakeDirectory.Manual();
        workshop = new Workshop(tmp.resolve("dados"));
        zip1 = PackFixtures.packageZip(tmp.resolve("zips"), "conciliacao", Map.of("main.py", "print('v1')\n"), "main.py", List.of());
        zip2 = PackFixtures.packageZip(tmp.resolve("zips"), "conciliacao", Map.of("main.py", "print('v2')\n", "util.py", "X = 2\n"), "main.py", List.of());
        fp1 = hex(workshop.inspectLocal(zip1.toString()).fingerprint());
        fp2 = hex(workshop.inspectLocal(zip2.toString()).fingerprint());
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
            window.actions().modal = false;
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        settle();
        session = new FakeSession();
        d = session.directory;
        d.organizations = List.of(a, b);
        d.workspaces.put(a.id(), List.of(fin, fis));
        d.workspaces.put(b.id(), List.of(deB));
        // a ultima publicada e a versao 2; a 1 tambem esta publicada; ha um rascunho
        publicar(true, true, true);
        d.automations.put(fis.id(), FakeDirectory.listing(false, false, FakeDirectory.automation(4, "Apuração", 7, "2.0.1")));
        d.automations.put(deB.id(), FakeDirectory.listing(true, true, new Automation(auto, "Conciliação bancária", null)));
        d.packages.put(v1, Files.readAllBytes(zip1));
        d.packages.put(v2, Files.readAllBytes(zip2));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        answer();
    }

    /** O que a "Platform" lista para a automacao. {@code contrato3} falso: Platform anterior, sem sha256 nem tamanho. */
    private void publicar(boolean rename, boolean download, boolean contrato3) throws Exception {
        long s1 = Files.size(zip1);
        long s2 = Files.size(zip2);
        d.automations.put(fin.id(), new Automation.Listing(List.of(new Automation(auto, "Conciliação bancária",
                new Automation.Published(v2, 2, "conciliacao", "1.0.0", fp2, contrato3 ? sha(zip2) : null, contrato3 ? s2 : -1))),
                new Automation.Capabilities(rename, true, download), false));
        d.versions.put(auto, List.of(new Automation.Version(rascunho, 3, false, "conciliacao", "1.0.0", "ab".repeat(32), contrato3 ? "cd".repeat(32) : null, contrato3 ? 10 : -1),
                new Automation.Version(v2, 2, true, "conciliacao", "1.0.0", fp2, contrato3 ? sha(zip2) : null, contrato3 ? s2 : -1),
                new Automation.Version(v1, 1, true, "conciliacao", "1.0.0", fp1, contrato3 ? sha(zip1) : null, contrato3 ? s1 : -1)));
    }

    @AfterEach
    void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            window.actions().close();
            flow.close();
            window.dispose();
        });
        background.runAll();
    }

    private static String hex(String fingerprint) {
        return fingerprint.startsWith("sha256:") ? fingerprint.substring(7) : fingerprint;
    }

    private static String sha(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
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
        edt(window::validate);
    }

    private void enter(Organization o, Workspace w) throws Exception {
        edt(() -> window.organizationsView().cards().stream().filter(c -> c.organization().id().equals(o.id())).findFirst().orElseThrow().choose());
        answer();
        edt(() -> window.contextView().workspaceBox().setSelectedItem(w));
        answer();
    }

    private PackageForm editar() throws Exception {
        JButton editar = read(() -> window.contextView().rows().get(0).actions().get(1));
        assertTrue(read(editar::isEnabled));
        edt(() -> editar.doClick(0));
        answer();
        PackageForm f = read(() -> window.actions().packageForm());
        assertNotNull(f);
        return f;
    }

    private void escolher(PackageForm f, UUID version) throws Exception {
        edt(() -> {
            for (int i = 0; i < f.versionsBox().getItemCount(); i++) {
                if (f.versionsBox().getItemAt(i) instanceof Automation.Version v && v.versionId().equals(version)) {
                    f.versionsBox().setSelectedIndex(i);
                }
            }
        });
    }

    private List<String> detalhes() throws Exception {
        return read(() -> window.contextView().rows().get(0).details());
    }

    private List<String> nomes() throws Exception {
        return read(() -> window.contextView().rows().stream().map(AutomationRow::name).toList());
    }

    /** Nada pela metade fora da area de parciais, e nada "pronto" que nao tenha sido conferido. */
    private List<String> baixados() throws Exception {
        Path root = tmp.resolve("dados");
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.contains("baixados/")) {
                    out.add(rel);
                }
            }
        }
        return out;
    }

    /** O mesmo conteudo num ZIP diferente (ordem e compressao trocadas): outros bytes, mesmo pacote. */
    private Path recompactado(Path zip) throws Exception {
        Path out = tmp.resolve("recompactado-" + System.nanoTime() + ".zip");
        try (ZipFile in = new ZipFile(zip.toFile()); ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out))) {
            z.setLevel(Deflater.NO_COMPRESSION);
            List<? extends ZipEntry> entries = new ArrayList<>(in.stream().toList());
            java.util.Collections.reverse(entries);
            for (ZipEntry e : entries) {
                if (!e.isDirectory()) {
                    z.putNextEntry(new ZipEntry(e.getName()));
                    z.write(in.getInputStream(e).readAllBytes());
                    z.closeEntry();
                }
            }
        }
        assertFalse(sha(out).equals(sha(zip)), "os bytes do ZIP sao outros");
        return out;
    }

    private void evidence(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        Container root = (Container) read(() -> window.actions().form());
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

    // ------------------------------------------------------------------ a tela

    @Test
    void editarTemONomeEOPacoteNestaMaquinaComAsDuasOrigens() throws Exception {
        enter(a, fin);
        assertEquals("Pacote não está neste computador", detalhes().get(1));
        PackageForm f = editar();
        EditForm e = (EditForm) read(() -> window.actions().form());
        assertNotNull(e.renameForm(), "com a permissao, o nome cadastrado pode ser alterado aqui");
        assertEquals("Renomear automação na Platform", read(() -> e.renameForm().titleText()));
        assertEquals("Nenhum pacote desta automação neste computador.", read(f::statusText));
        assertEquals("Escolher arquivo .zip", read(() -> f.pickZipButton().getText()));
        assertEquals("Escolher pasta", read(() -> f.pickFolderButton().getText()));
        // so versoes PUBLICADAS com pacote; nenhuma vem escolhida; o rascunho nao aparece
        List<String> versoes = read(f::versionLabels);
        assertEquals(3, versoes.size(), versoes.toString());
        assertEquals("Selecione uma versão publicada", versoes.get(0));
        assertTrue(versoes.get(1).startsWith("Versão 2 · pacote 1.0.0 · ") && versoes.get(2).startsWith("Versão 1 · pacote 1.0.0 · "), versoes.toString());
        assertNull(read(f::chosenVersion));
        assertFalse(read(() -> f.downloadButton().isEnabled()), "sem versao escolhida, nada a baixar");
        assertEquals(List.of("versions " + a.id() + " " + auto), d.calls.stream().filter(c -> c.startsWith("versions") || c.startsWith("download")).toList(),
                "abrir Editar consulta so os metadados das versoes; nada e baixado sozinho");
        evidence("sakura-dark-editar-pacote");
        edt(() -> Themes.apply(ClientTheme.NEKKORA_LIGHT));
        evidence("light-editar-pacote");
        edt(() -> Themes.apply(ClientTheme.SAKURA_DARK));
        edt(() -> f.closeButton().doClick(0));
        assertNull(read(() -> window.actions().form()));
    }

    // ------------------------------------------------------------------ baixar da Platform

    @Test
    void baixarVersaoPublicadaConfereInstalaEDeixaDisponivelSemExecutar() throws Exception {
        enter(a, fin);
        PackageForm f = editar();
        escolher(f, v2);
        assertTrue(read(() -> f.downloadButton().isEnabled()));
        // progresso: a primeira parte chega e a tela mostra quanto ja veio
        List<String> progresso = new ArrayList<>();
        d.afterChunk = () -> {
        };
        edt(() -> f.downloadButton().doClick(0));
        assertTrue(read(() -> f.progressBar().isVisible() && f.cancelDownloadButton().isVisible()), "baixando: progresso e cancelar a vista");
        assertFalse(read(() -> f.downloadButton().isEnabled() || f.pickZipButton().isEnabled()), "um download por vez");
        progresso.add(read(() -> f.progressBar().getString()));
        answer();
        assertEquals("Versão 2 baixada e conferida. Pacote disponível nesta máquina. Nada foi executado nem instalado.", read(f::downloadText));
        assertFalse(read(f::downloadIsError));
        assertTrue(progresso.get(0).contains(" de "), "tamanho conhecido: 'x de y' (" + progresso + ")");
        // o pacote esta no contexto, ligado a automacao, com a origem e a versao da Platform
        List<Workshop.LocalItem> itens = workshop.list(a.id(), fin.id());
        assertEquals(1, itens.size());
        assertEquals(fp2, hex(itens.get(0).fingerprint()));
        assertEquals(auto, itens.get(0).linkedAutomationId());
        assertEquals("Baixado da Platform", itens.get(0).originLabel());
        assertEquals(2, itens.get(0).versionNumber());
        assertEquals(v2, itens.get(0).automationVersionId());
        assertTrue(read(f::statusText).startsWith("Pacote disponível nesta máquina: conciliacao, versão do pacote 1.0.0 · origem: Baixado da Platform"), read(f::statusText));
        assertTrue(read(f::statusText).contains("versão 2 da Platform"));
        assertEquals("Pacote disponível neste computador: conciliacao 1.0.0", detalhes().get(1), "a lista passa a dizer que o pacote esta aqui");
        // a copia conferida fica na area de baixados, separada por origem, organizacao, automacao e versao; nenhum parcial
        List<String> arquivos = baixados();
        assertTrue(arquivos.stream().anyMatch(p -> p.contains(a.id() + "/" + auto + "/" + v2 + "/pacote.zip")), arquivos.toString());
        assertTrue(arquivos.stream().noneMatch(p -> p.contains(".parcial/")), "nenhum parcial sobra: " + arquivos);
        // baixar NAO executa, nao prepara, nao renomeia; so leu versoes e baixou uma vez
        assertNull(read(() -> window.actions().runForm()));
        assertTrue(d.renames.isEmpty());
        assertEquals(1, d.calls.stream().filter(c -> c.startsWith("download")).count());
        evidence("sakura-dark-editar-pacote-baixado");

        // outra versao da mesma automacao: as duas convivem; a 2 nao e substituida
        escolher(f, v1);
        edt(() -> f.downloadButton().doClick(0));
        answer();
        assertEquals("Versão 1 baixada e conferida. Pacote disponível nesta máquina. Nada foi executado nem instalado.", read(f::downloadText));
        assertEquals(2, workshop.list(a.id(), fin.id()).size(), "duas versoes guardadas, cada uma na sua pasta");
        assertTrue(baixados().stream().anyMatch(p -> p.contains("/" + v1 + "/pacote.zip")) && baixados().stream().anyMatch(p -> p.contains("/" + v2 + "/pacote.zip")));
        assertEquals("Pacote disponível neste computador: conciliacao 1.0.0", detalhes().get(1), "a linha continua com o pacote da versao publicada");
        assertEquals(List.of("Conciliação bancária"), nomes(), "a outra versao nao vira item solto na lista");
        // isolamento: nada disto aparece em outro ambiente nem em outra organizacao
        assertEquals(0, workshop.list(a.id(), fis.id()).size());
        assertEquals(0, workshop.list(b.id(), deB.id()).size());
    }

    @Test
    void semPermissaoOuPlatformAnteriorODownloadFicaIndisponivelComOMotivo() throws Exception {
        // sem a capacidade de baixar (so leitura): a tela diz, e nada e pedido
        publicar(false, false, true);
        enter(a, fin);
        PackageForm f = editar();
        EditForm e = (EditForm) read(() -> window.actions().form());
        assertNull(e.renameForm(), "sem permissao de renomear, a secao do nome so explica");
        assertTrue(read(e::renameDeniedText).contains("não tem permissão para renomear"));
        escolher(f, v2);
        assertFalse(read(() -> f.downloadButton().isEnabled()));
        assertEquals(NavText.PKG_DOWNLOAD_DENIED, read(() -> f.downloadButton().getToolTipText()));
        assertEquals(NavText.PKG_DOWNLOAD_DENIED, read(f::downloadText));
        edt(() -> window.actions().downloadChosen());
        answer();
        assertEquals(0, d.calls.stream().filter(c -> c.startsWith("download")).count(), "sem a capacidade, o aplicativo nem pede");
        assertTrue(read(() -> f.pickZipButton().isEnabled()), "o pacote local continua possivel");
        edt(() -> window.actions().close());

        // Platform anterior ao contrato de download: sem sha256/tamanho, nada e baixavel, e a tela diz que e a Platform
        publicar(true, false, false);
        edt(() -> window.contextView().refresh().button().doClick(0));
        answer();
        PackageForm g = editar();
        assertEquals(List.of(NavText.PKG_VERSIONS_NONE), read(g::versionLabels));
        assertEquals(NavText.PKG_DOWNLOAD_UNSUPPORTED, read(g::downloadText));
        assertFalse(read(() -> g.downloadButton().isEnabled()));
    }

    @Test
    void downloadRecusadoInterrompidoCanceladoOuAdulteradoNaoDeixaNadaPronto() throws Exception {
        enter(a, fin);
        PackageForm f = editar();
        Object[][] casos = {{Failure.DENIED, NavText.PKG_DOWNLOAD_FORBIDDEN}, {Failure.NOT_FOUND, NavText.PKG_DOWNLOAD_GONE},
                {Failure.CONFLICT, NavText.PKG_DOWNLOAD_UNAVAILABLE}, {Failure.SERVER, NavText.of(Failure.SERVER) + " Nada foi instalado."},
                {Failure.RATE_LIMITED, NavText.of(Failure.RATE_LIMITED) + " Nada foi instalado."}};
        for (Object[] c : casos) {
            d.downloadFailure = (Failure) c[0];
            escolher(f, v2);
            edt(() -> f.downloadButton().doClick(0));
            answer();
            assertEquals(c[1], read(f::downloadText), c[0].toString());
            assertTrue(read(f::downloadIsError));
            assertEquals(0, workshop.list(a.id(), fin.id()).size(), c[0] + ": nada instalado");
            assertTrue(baixados().isEmpty(), c[0] + ": " + baixados());
        }
        d.downloadFailure = null;

        // interrompido no meio: o que chegou e descartado
        d.downloadCutAt = 300;
        escolher(f, v2);
        edt(() -> f.downloadButton().doClick(0));
        answer();
        assertEquals(NavText.PKG_DOWNLOAD_INTERRUPTED, read(f::downloadText));
        assertEquals(0, workshop.list(a.id(), fin.id()).size());
        assertTrue(baixados().isEmpty(), "bytes parciais nao ficam: " + baixados());
        d.downloadCutAt = -1;

        // cancelado pela pessoa no meio
        d.afterChunk = () -> SwingUtilities.invokeLater(() -> f.cancelDownloadButton().doClick(0));
        escolher(f, v2);
        edt(() -> f.downloadButton().doClick(0));
        Runnable baixar = background.take(0);
        Thread t = new Thread(baixar);
        t.start();
        t.join(10_000);
        answer();
        d.afterChunk = null;
        assertTrue(read(f::downloadText).startsWith("Download cancelado."), read(f::downloadText));
        assertTrue(read(f::downloadIsError));
        assertEquals(0, workshop.list(a.id(), fin.id()).size());
        assertTrue(baixados().isEmpty(), "cancelado: nenhum parcial fica: " + baixados());

        // adulterado: mesmo tamanho, outro conteudo - o SHA-256 informado pela Platform nao confere
        byte[] original = d.packages.get(v2);
        byte[] adulterado = original.clone();
        adulterado[adulterado.length / 2] ^= 0x55;
        d.packages.put(v2, adulterado);
        escolher(f, v2);
        edt(() -> f.downloadButton().doClick(0));
        answer();
        assertTrue(read(f::downloadText).contains("não confere com o que a Platform informou"), read(f::downloadText));
        assertTrue(read(f::downloadIsError));
        assertEquals(0, workshop.list(a.id(), fin.id()).size(), "adulterado: nada instalado");
        assertTrue(baixados().isEmpty(), baixados().toString());
        assertEquals("Pacote não está neste computador", detalhes().get(1), "a lista nunca diz que ha pacote sem ele ter sido conferido");

        // identidade errada: a Platform entrega o pacote de OUTRA versao (bytes validos, sha e impressao que nao sao os esperados)
        d.packages.put(v2, d.packages.get(v1));
        long s2 = Files.size(zip2);
        if (d.packages.get(v1).length == s2) {
            escolher(f, v2);
            edt(() -> f.downloadButton().doClick(0));
            answer();
            assertTrue(read(f::downloadIsError));
            assertEquals(0, workshop.list(a.id(), fin.id()).size());
        }
        // e, com o arquivo certo de volta, o download funciona
        d.packages.put(v2, original);
        escolher(f, v2);
        edt(() -> f.downloadButton().doClick(0));
        answer();
        assertFalse(read(f::downloadIsError), read(f::downloadText));
        assertEquals(1, workshop.list(a.id(), fin.id()).size());
    }

    @Test
    void trocarDeContextoOuSairDuranteODownloadDescartaOParcial() throws Exception {
        enter(a, fin);
        PackageForm f = editar();
        escolher(f, v2);
        edt(() -> f.downloadButton().doClick(0));
        Runnable baixar = background.take(0);
        // a pessoa troca de ambiente antes de os bytes chegarem: o formulario fecha e o download e interrompido
        edt(() -> window.contextView().workspaceBox().setSelectedItem(fis));
        assertNull(read(() -> window.actions().form()));
        baixar.run();
        answer();
        assertEquals(0, workshop.list(a.id(), fin.id()).size(), "nada foi instalado no contexto anterior");
        assertEquals(0, workshop.list(a.id(), fis.id()).size(), "nem no novo");
        assertTrue(baixados().isEmpty(), baixados().toString());
        assertEquals("", read(() -> window.contextView().noticeText()));
    }

    // ------------------------------------------------------------------ projeto ou pacote local

    @Test
    void pacoteLocalIgualAoPublicadoEReconhecidoPeloConteudoMesmoRecompactado() throws Exception {
        enter(a, fin);
        PackageForm f = editar();
        Path outroZip = recompactado(zip2);
        edt(() -> window.actions().chooseLocal(outroZip.toString()));
        answer();
        String texto = read(f::localText);
        assertTrue(texto.startsWith("Pacote conferido e disponível nesta máquina: é o mesmo conteúdo da versão publicada 2."), texto);
        assertTrue(texto.contains("Origem: Arquivo .zip") && texto.contains("versão do pacote 1.0.0") && texto.contains("Nada foi executado"), texto);
        assertFalse(read(f::localIsError));
        assertFalse(read(() -> f.useSeparateButton().isVisible()));
        List<Workshop.LocalItem> itens = workshop.list(a.id(), fin.id());
        assertEquals(1, itens.size());
        assertEquals(auto, itens.get(0).linkedAutomationId(), "mesmo conteudo: ligado a automacao");
        assertEquals("Pacote disponível neste computador: conciliacao 1.0.0", detalhes().get(1));
        assertTrue(read(f::statusText).contains("mesmo conteúdo da versão publicada 2"), read(f::statusText));
        assertEquals(0, d.calls.stream().filter(c -> c.startsWith("download") || c.startsWith("rename")).count(), "usar pacote local nao fala com a Platform");
        // pasta de pacote (o mesmo conteudo extraido): tambem reconhecida
        Path pasta = Files.createDirectories(tmp.resolve("pacote-em-pasta"));
        try (ZipFile z = new ZipFile(zip2.toFile())) {
            for (ZipEntry e : z.stream().toList()) {
                if (!e.isDirectory()) {
                    Path alvo = pasta.resolve(e.getName());
                    Files.createDirectories(alvo.getParent());
                    Files.write(alvo, z.getInputStream(e).readAllBytes());
                }
            }
        }
        edt(() -> window.actions().chooseLocal(pasta.toString()));
        answer();
        assertTrue(read(f::localText).contains("mesmo conteúdo da versão publicada 2") && read(f::localText).contains("Origem: Pasta de pacote"), read(f::localText));
        assertEquals(1, workshop.list(a.id(), fin.id()).size(), "o mesmo conteudo nao duplica");
    }

    @Test
    void conteudoDiferenteNaoEAssociadoEExigeEscolhaExplicita() throws Exception {
        enter(a, fin);
        PackageForm f = editar();
        Path diferente = PackFixtures.packageZip(tmp.resolve("zips"), "conciliacao", Map.of("main.py", "print('outro conteudo, mesmo nome')\n"), "main.py", List.of());
        edt(() -> window.actions().chooseLocal(diferente.toString()));
        answer();
        String texto = read(f::localText);
        assertTrue(texto.startsWith("Projeto local diferente da versão publicada."), texto);
        assertTrue(texto.contains("não é o da versão publicada 2 e não será apresentado como ela"), texto);
        assertTrue(read(() -> f.useSeparateButton().isVisible()), "a escolha e pedida");
        assertEquals(0, workshop.list(a.id(), fin.id()).size(), "mesmo NOME de pacote, outro conteudo: nada e guardado sem a decisao");
        assertEquals("Pacote não está neste computador", detalhes().get(1), "a automacao continua sem pacote: nenhuma associacao falsa");
        evidence("sakura-dark-editar-pacote-diferente");
        // a decisao: projeto local separado - guardado, SEM ligacao com a automacao
        edt(() -> f.useSeparateButton().doClick(0));
        answer();
        assertTrue(read(f::localText).startsWith("Guardado como projeto local separado (conciliacao, versão do pacote 1.0.0), sem ligação com esta automação."),
                read(f::localText));
        List<Workshop.LocalItem> itens = workshop.list(a.id(), fin.id());
        assertEquals(1, itens.size());
        assertNull(itens.get(0).linkedAutomationId());
        assertEquals("Pacote não está neste computador", detalhes().get(1), "nao e apresentado como a versao publicada");
        assertEquals(List.of("Conciliação bancária", "conciliacao"), nomes(), "aparece como item so deste computador");
        assertEquals(NavText.PKG_NONE, read(f::statusText));
        // escolher o pacote correto continua possivel
        edt(() -> window.actions().chooseLocal(zip2.toString()));
        answer();
        assertTrue(read(f::localText).contains("mesmo conteúdo da versão publicada 2"));
        assertEquals("Pacote disponível neste computador: conciliacao 1.0.0", detalhes().get(1));
    }

    @Test
    void pastaDeCodigoOfereceGerarPacoteEOQueNaoEPacoteERecusado() throws Exception {
        enter(a, fin);
        PackageForm f = editar();
        Path codigo = Files.createDirectories(tmp.resolve("so-codigo"));
        Files.writeString(codigo.resolve("main.py"), "print('sem manifesto')\n");
        edt(() -> window.actions().chooseLocal(codigo.toString()));
        answer();
        assertTrue(read(f::localText).contains("ainda não é um pacote") && read(f::localText).contains("Gerar pacote novo"), read(f::localText));
        assertTrue(read(f::localIsError));
        assertTrue(read(() -> f.generateButton().isVisible()), "oferece gerar o pacote");
        assertFalse(read(() -> f.useSeparateButton().isVisible()));
        assertEquals(0, workshop.list(a.id(), fin.id()).size(), "nada e associado nem guardado");
        // arquivo que nao e pacote
        Path ruim = PackFixtures.notAZip(tmp.resolve("zips"));
        edt(() -> window.actions().chooseLocal(ruim.toString()));
        answer();
        assertTrue(read(f::localIsError));
        assertFalse(read(f::localText).isBlank());
        assertFalse(read(() -> f.generateButton().isVisible()));
        assertEquals(0, workshop.list(a.id(), fin.id()).size());
        // "Gerar pacote novo" a partir da pasta de codigo abre o formulario de geracao com ela
        edt(() -> window.actions().chooseLocal(codigo.toString()));
        answer();
        edt(() -> f.generateButton().doClick(0));
        answer();
        GenerateForm g = read(() -> window.actions().generateForm());
        assertNotNull(g);
        assertTrue(read(() -> g.folderField().getText()).endsWith("so-codigo"));
        assertEquals(0, workshop.list(a.id(), fin.id()).size(), "gerar e outra operacao: nada foi associado a automacao");
    }

    @Test
    void renomearEEscolherPacoteSaoOperacoesDistintas() throws Exception {
        enter(a, fin);
        PackageForm f = editar();
        edt(() -> window.actions().chooseLocal(zip2.toString()));
        answer();
        assertTrue(d.renames.isEmpty(), "escolher o pacote nao renomeia");
        String antes = sha(zip2);
        EditForm e = (EditForm) read(() -> window.actions().form());
        edt(() -> e.renameForm().field().setText("Conciliação diária"));
        edt(() -> e.renameForm().saveButton().doClick(0));
        answer();
        assertEquals(List.of(auto + "=Conciliação diária"), d.renames, "uma chamada de renomear, com o nome");
        assertEquals(antes, sha(zip2), "o arquivo da pessoa nao e tocado");
        assertEquals(1, workshop.list(a.id(), fin.id()).size(), "renomear nao mexe no pacote desta maquina");
        assertEquals(fp2, hex(workshop.list(a.id(), fin.id()).get(0).fingerprint()));
        assertNotNull(f);
    }
}
