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
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;
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
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.theme.ClientTheme;

/**
 * "Gerar pacote novo" (AGENT-CLIENT-PACOTES-001 T01) na janela real, com a entrada e a Platform SIMULADAS e a area local de
 * pacotes REAL em pasta temporaria. A pasta do projeto e o destino sao informados direto ao fluxo (o seletor de arquivos do
 * sistema nao e aberto no teste). O que se confere: o que a tela mostra, o arquivo gerado e que NADA mais acontece - nenhuma
 * chamada a Platform, nenhum item novo na lista, nenhuma execucao.
 */
@Tag("desktop")
class GenerateDesktopTest {

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
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");
    private final List<String> substituir = new ArrayList<>();
    private final List<String> abertos = new ArrayList<>();
    private boolean respostaSubstituir;

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
            window.actions().confirmOverwrite = path -> {
                substituir.add(path);
                return respostaSubstituir;
            };
            window.actions().reveal = abertos::add;
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        settle();
        session = new FakeSession();
        FakeDirectory d = session.directory;
        d.organizations = List.of(a);
        d.workspaces.put(a.id(), List.of(fin));
        d.automations.put(fin.id(), FakeDirectory.listing(true, true, FakeDirectory.automation(1, "Conciliação bancária", 3, "1.2.0")));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        answer();
        edt(() -> window.organizationsView().cards().get(0).choose());
        answer();
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

    /** Projeto de exemplo: entrada na raiz e em subpasta, dependencias fixadas, e o que NAO deve entrar no pacote. */
    private Path projeto(String nome) throws Exception {
        Path p = Files.createDirectories(tmp.resolve(nome));
        Files.writeString(p.resolve("main.py"), "print('ola')\n");
        Files.createDirectories(p.resolve("tarefas"));
        Files.writeString(p.resolve("tarefas/relatorio.py"), "def gerar():\n    return 1\n");
        Files.writeString(p.resolve("requirements.txt"), "requests==2.31.0\n");
        Files.writeString(p.resolve(".env"), "SENHA=nao-e-para-entrar\n");
        Files.createDirectories(p.resolve(".git"));
        Files.writeString(p.resolve(".git/config"), "[core]\n");
        Files.createDirectories(p.resolve("__pycache__"));
        Files.writeString(p.resolve("__pycache__/main.cpython-312.pyc"), "cache");
        return p;
    }

    private static String retrato(Path dir) throws Exception {
        TreeMap<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                m.put(dir.relativize(f).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))) + "|" + Files.getLastModifiedTime(f).toMillis());
            }
        }
        return m.toString();
    }

    private static List<String> nomes(Path zip) throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return z.stream().map(e -> e.getName()).sorted().toList();
        }
    }

    /** O validador REAL de pacotes aceita o arquivo? (importado para um contexto qualquer, fora do da tela) */
    private Workshop.LocalItem validado(Path zip) throws Exception {
        return workshop.importZip(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), zip.toString()).item();
    }

    private GenerateForm abrir() throws Exception {
        ContextView v = window.contextView();
        edt(() -> v.generateButton().doClick(0));
        GenerateForm f = read(() -> window.actions().generateForm());
        assertNotNull(f);
        return f;
    }

    private void preencher(GenerateForm f, Path pasta, String entrada, String nome) throws Exception {
        edt(() -> window.actions().setProjectFolder(pasta.toString()));
        answer();
        edt(() -> f.entryBox().setSelectedItem(entrada));
        edt(() -> f.nameField().setText(nome));
        if (read(() -> f.pythonField().getText()).isBlank()) {
            edt(() -> f.pythonField().setText(">=3.10,<3.14"));
        }
        if (read(() -> f.confirmedBox().isVisible())) {
            edt(() -> f.confirmedBox().setSelected(true));
        }
        edt(() -> window.actions().refreshSummary());
        answer();
    }

    // ------------------------------------------------------------------ a tela

    @Test
    void botaoPrimarioAbreOFormularioComTresCamposEOTextoDeApoio() throws Exception {
        ContextView v = window.contextView();
        ActionButton bt = read(v::generateButton);
        assertEquals("Gerar pacote novo", read(bt::getText));
        assertEquals(ActionButton.Kind.PRIMARY, bt.kind());
        assertEquals(ActionButton.Glyph.ADD, bt.glyph());
        assertEquals("Gerar pacote novo", read(() -> bt.getAccessibleContext().getAccessibleName()));
        assertTrue(read(() -> bt.isShowing() && bt.isEnabled() && bt.isFocusable()), "nao depende de ambiente escolhido: so gera um arquivo");
        assertFalse(read(() -> v.rows().stream().anyMatch(r -> r.name().contains("Nova automação"))));

        GenerateForm f = abrir();
        assertEquals("Gere um pacote .zip do seu projeto. Para cadastrar ou publicar a automação, envie o pacote pela Platform.", read(f::supportText));
        assertEquals(GenerateForm.FOLDER_PLACEHOLDER, read(() -> f.folderField().getText()));
        assertFalse(read(() -> f.folderField().isEditable()), "a pasta vem do seletor, nao de digitacao");
        assertEquals("Procurar pasta", read(() -> f.browseButton().getText()));
        assertEquals("Pasta do projeto", read(() -> f.folderField().getAccessibleContext().getAccessibleName()));
        assertEquals("Arquivo de entrada", read(() -> f.entryBox().getAccessibleContext().getAccessibleName()));
        assertEquals("Nome da automação", read(() -> f.nameField().getAccessibleContext().getAccessibleName()));
        assertFalse(read(f::advancedOpen), "versao, Python, dependencias e hashes ficam em opcoes avancadas");
        assertFalse(read(() -> f.entryBox().isEnabled() || f.nameField().isEnabled() || f.generateButton().isEnabled()), "sem pasta, nada a preencher");
        evidence("sakura-dark-gerar-pacote-vazio", (Container) read(() -> window.actions().form()));
        edt(() -> f.cancelButton().doClick(0));
        assertNull(read(() -> window.actions().form()));
        assertTrue(session.directory.calls.stream().noneMatch(c -> c.startsWith("rename")));
    }

    // ------------------------------------------------------------------ gerar

    @Test
    void pastaValidaComEntradaEmSubpastaGeraOZipESoIsso() throws Exception {
        Path pasta = projeto("projeto-relatorio");
        String antes = retrato(pasta);
        int chamadas = session.directory.calls.size();
        int itens = read(() -> window.contextView().rows().size());
        GenerateForm f = abrir();
        edt(() -> window.actions().setProjectFolder(pasta.toString()));
        answer();
        assertEquals(pasta.toRealPath().toString(), Path.of(read(() -> f.folderField().getText())).toRealPath().toString());
        assertEquals("", read(f::blockersText));
        List<String> opcoes = read(() -> {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < f.entryBox().getItemCount(); i++) {
                l.add(f.entryBox().getItemAt(i));
            }
            return l;
        });
        assertEquals(List.of(GenerateForm.ENTRY_PLACEHOLDER, "main.py", "tarefas/relatorio.py"), opcoes, "so .py de dentro da pasta, com caminho relativo, inclusive subpastas");
        assertEquals(GenerateForm.ENTRY_PLACEHOLDER, read(() -> f.entryBox().getSelectedItem()), "a entrada e sempre escolhida pela pessoa");
        assertFalse(read(() -> f.generateButton().isEnabled()));

        preencher(f, pasta, "tarefas/relatorio.py", "relatorio-mensal");
        String resumo = read(f::summaryText);
        assertTrue(resumo.contains("nome relatorio-mensal") && resumo.contains("entrada tarefas/relatorio.py"), resumo);
        assertTrue(resumo.contains("versão declarada no pacote 1.0.0 (não reservada nem publicada na Platform)"), resumo);
        assertTrue(resumo.contains("requests==2.31.0"), "dependencia lida do projeto, sem executar nada: " + resumo);
        String comp = read(f::compositionText);
        assertTrue(comp.contains("main.py") && comp.contains("tarefas/relatorio.py") && comp.contains("Impressão digital: "), comp);
        assertTrue(comp.contains(".env") && comp.contains("credencial"), "o .env fica fora, com o motivo: " + comp);
        assertTrue(comp.contains(".git/") && comp.contains("__pycache__/"), comp);
        evidence("sakura-dark-gerar-pacote-resumo", (Container) read(() -> window.actions().form()));
        edt(() -> Themes.apply(ClientTheme.NEKKORA_LIGHT));
        evidence("light-gerar-pacote-resumo", (Container) read(() -> window.actions().form()));
        edt(() -> Themes.apply(ClientTheme.SAKURA_DARK));

        Path destino = Files.createDirectories(tmp.resolve("saidas")).resolve("relatorio-mensal-1.0.0.zip");
        edt(() -> window.actions().generateTo(destino.toString()));
        assertFalse(read(() -> f.generateButton().isEnabled()), "gerando: nao aceita outro pedido");
        answer();
        assertTrue(Files.isRegularFile(destino));
        assertEquals("Pacote gerado. Envie este arquivo pela Platform para cadastrar ou publicar a automação. " + destino.toRealPath(),
                read(f::successText).replace(destino.toString(), destino.toRealPath().toString()));
        assertTrue(read(() -> f.revealButton().isVisible()));
        assertEquals("Fechar", read(() -> f.cancelButton().getText()));
        evidence("sakura-dark-gerar-pacote-gerado", (Container) read(() -> window.actions().form()));
        // o pacote: manifesto na raiz, entrada e arquivos do projeto; nada de credencial, cache ou controle de versao
        List<String> dentro = nomes(destino);
        assertTrue(dentro.containsAll(List.of("nekkora-external.json", "main.py", "tarefas/relatorio.py", "requirements.txt")), dentro.toString());
        assertTrue(dentro.stream().noneMatch(n -> n.startsWith(".env") || n.startsWith(".git") || n.contains("__pycache__")), dentro.toString());
        // e o validador real o aceita como pacote, com os dados do resumo
        Workshop.LocalItem pacote = validado(destino);
        assertEquals("relatorio-mensal|1.0.0", pacote.declaredName() + "|" + pacote.packageVersion());
        assertTrue(comp.contains(pacote.fingerprint().replace("sha256:", "")), "a impressao digital do arquivo e a do resumo");

        // SO isso: a pasta intacta, nada na lista, nada no ambiente, nenhuma chamada a Platform, nenhuma execucao
        assertEquals(antes, retrato(pasta), "a pasta do projeto nao foi alterada (nem manifesto gravado nela)");
        assertFalse(Files.exists(pasta.resolve("nekkora-external.json")));
        assertEquals(itens, read(() -> window.contextView().rows().size()), "gerar nao acrescenta automacao a lista");
        assertEquals(0, workshop.list(a.id(), fin.id()).size(), "gerar nao importa o pacote para o ambiente");
        assertEquals(chamadas, session.directory.calls.size(), "nenhuma consulta nem escrita na Platform");
        assertNull(read(() -> window.actions().runForm()), "gerar nao prepara nem inicia execucao");
        // abrir a pasta do arquivo e pedido da pessoa; nada e enviado
        edt(() -> f.revealButton().doClick(0));
        assertEquals(1, abertos.size());
        assertTrue(abertos.get(0).endsWith("relatorio-mensal-1.0.0.zip"));
        edt(() -> f.cancelButton().doClick(0));
        assertNull(read(() -> window.actions().form()));
    }

    @Test
    void trocarAPastaApagaAEntradaEscolhida() throws Exception {
        Path um = projeto("projeto-um");
        Path dois = Files.createDirectories(tmp.resolve("projeto-dois"));
        Files.writeString(dois.resolve("outro.py"), "print(2)\n");
        GenerateForm f = abrir();
        preencher(f, um, "tarefas/relatorio.py", "primeiro");
        assertFalse(read(f::summaryText).isBlank());
        edt(() -> window.actions().setProjectFolder(dois.toString()));
        answer();
        assertEquals(GenerateForm.ENTRY_PLACEHOLDER, read(() -> f.entryBox().getSelectedItem()), "a entrada da pasta anterior nao fica escolhida");
        assertEquals(2, read(() -> f.entryBox().getItemCount()), "so os arquivos da pasta nova");
        assertEquals("outro.py", read(() -> f.entryBox().getItemAt(1)));
        assertEquals("", read(f::summaryText));
        assertFalse(read(() -> f.generateButton().isEnabled()));
        // um arquivo que so existia na outra pasta nao pode ser escolhido aqui
        edt(() -> f.entryBox().setSelectedItem("tarefas/relatorio.py"));
        assertEquals(GenerateForm.ENTRY_PLACEHOLDER, read(() -> f.entryBox().getSelectedItem()));
    }

    @Test
    void destinoExistenteSoESubstituidoComConfirmacao() throws Exception {
        Path pasta = projeto("projeto-destino");
        GenerateForm f = abrir();
        preencher(f, pasta, "main.py", "destino");
        Path destino = tmp.resolve("ja-existe.zip");
        Files.writeString(destino, "arquivo da pessoa");
        respostaSubstituir = false;
        edt(() -> window.actions().generateTo(destino.toString()));
        answer();
        assertEquals(1, substituir.size(), "perguntou antes de substituir");
        assertEquals("arquivo da pessoa", Files.readString(destino), "sem o sim, o arquivo fica como estava");
        assertEquals("O arquivo que já existia não foi alterado. Escolha outro nome ou confirme a substituição.", read(f::errorText));
        assertEquals("", read(f::successText));
        respostaSubstituir = true;
        edt(() -> window.actions().generateTo(destino.toString()));
        answer();
        assertEquals(2, substituir.size());
        assertTrue(nomes(destino).contains("nekkora-external.json"), "com o sim, substituido pelo pacote");
        assertFalse(read(f::successText).isBlank());
    }

    @Test
    void destinoDentroDaPastaNaoSeIncluiEFalhaNaoDeixaZip() throws Exception {
        Path pasta = projeto("projeto-dentro");
        GenerateForm f = abrir();
        preencher(f, pasta, "main.py", "dentro");
        Path destino = pasta.resolve("dentro-1.0.0.zip");
        edt(() -> window.actions().generateTo(destino.toString()));
        answer();
        assertTrue(Files.isRegularFile(destino));
        assertTrue(nomes(destino).stream().noneMatch(n -> n.endsWith(".zip")), "o pacote nao se inclui: " + nomes(destino));
        edt(() -> f.cancelButton().doClick(0));

        // arquivo do projeto alterado depois de aberto: recusa, sem deixar ZIP no destino
        GenerateForm g = abrir();
        preencher(g, pasta, "main.py", "dentro");
        Files.writeString(pasta.resolve("main.py"), "print('mudou depois de abrir a pasta')\n");
        Path outro = tmp.resolve("nao-deve-existir.zip");
        edt(() -> window.actions().generateTo(outro.toString()));
        answer();
        assertTrue(read(g::errorText).contains("mudaram depois que ela foi aberta"), read(g::errorText));
        assertFalse(Files.exists(outro), "falha nao deixa um ZIP com cara de valido");
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().contains("nao-deve-existir")), "nem temporario");
        }
        assertEquals("", read(g::successText));
    }

    @Test
    void pastaQueNaoServeExplicaENaoHabilitaAGeracao() throws Exception {
        GenerateForm f = abrir();
        // pasta sem nenhum .py
        Path vazia = Files.createDirectories(tmp.resolve("sem-python"));
        Files.writeString(vazia.resolve("leia-me.txt"), "nada aqui");
        edt(() -> window.actions().setProjectFolder(vazia.toString()));
        answer();
        assertFalse((read(f::blockersText) + read(f::errorText)).isBlank(), "explica por que nao pode");
        assertFalse(read(() -> f.generateButton().isEnabled()));
        // pasta que nao existe
        edt(() -> window.actions().setProjectFolder(tmp.resolve("nao-existe").toString()));
        answer();
        assertTrue(read(f::blockersText).contains("não existe ou não pôde ser aberta"), read(f::blockersText));
        assertEquals(GenerateForm.FOLDER_PLACEHOLDER, read(() -> f.folderField().getText()));
        assertFalse(read(() -> f.generateButton().isEnabled()));
        // pacote do Studio (manifest.json): outro formato, nada e misturado
        Path studio = Files.createDirectories(tmp.resolve("do-studio"));
        Files.writeString(studio.resolve("manifest.json"), "{\"schema\":\"nekkora-studio/codegen-manifest\"}");
        Files.writeString(studio.resolve("main.py"), "print(1)\n");
        edt(() -> window.actions().setProjectFolder(studio.toString()));
        answer();
        assertFalse(read(f::blockersText).isBlank());
        assertFalse(read(() -> f.generateButton().isEnabled()));
        assertFalse(Files.exists(studio.resolve("nekkora-external.json")), "nada e gravado na pasta");
    }

    @Test
    void manifestoExistenteEAproveitadoEAVersaoNaoSobeSozinha() throws Exception {
        // gera um pacote, extrai para uma pasta (vira um projeto que ja tem manifesto valido) e gera de novo
        Path pasta = projeto("projeto-base");
        GenerateForm f = abrir();
        preencher(f, pasta, "main.py", "com-manifesto");
        edt(() -> f.versionField().setText("2.3.0"));
        edt(() -> window.actions().refreshSummary());
        answer();
        Path primeiro = tmp.resolve("com-manifesto-2.3.0.zip");
        edt(() -> window.actions().generateTo(primeiro.toString()));
        answer();
        assertTrue(Files.isRegularFile(primeiro), read(f::errorText));
        edt(() -> f.cancelButton().doClick(0));
        Path extraido = Files.createDirectories(tmp.resolve("extraido"));
        try (ZipFile z = new ZipFile(primeiro.toFile())) {
            for (var e : z.stream().toList()) {
                Path alvo = extraido.resolve(e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(alvo);
                } else {
                    Files.createDirectories(alvo.getParent());
                    Files.write(alvo, z.getInputStream(e).readAllBytes());
                }
            }
        }
        String antes = retrato(extraido);
        GenerateForm g = abrir();
        edt(() -> window.actions().setProjectFolder(extraido.toString()));
        answer();
        assertTrue(read(g::notesText).contains("já tem um manifesto de pacote"), read(g::notesText));
        assertEquals("com-manifesto", read(() -> g.nameField().getText()));
        assertEquals("2.3.0", read(() -> g.versionField().getText()), "a versao do manifesto, sem aumento");
        assertEquals("main.py", read(() -> g.entryBox().getSelectedItem()));
        assertTrue(read(g::summaryText).contains("versão declarada no pacote 2.3.0"), read(g::summaryText));
        Path segundo = tmp.resolve("de-novo.zip");
        edt(() -> window.actions().generateTo(segundo.toString()));
        answer();
        assertTrue(Files.isRegularFile(segundo), read(g::errorText));
        assertEquals(validado(primeiro).fingerprint(), validado(segundo).fingerprint(),
                "reempacotar o mesmo conteudo da a mesma impressao digital");
        assertEquals(antes, retrato(extraido), "o manifesto da pasta nao e regravado");
    }

    @Test
    void fecharOFormularioNoMeioNaoDeixaNadaParaTras() throws Exception {
        Path pasta = projeto("projeto-cancelado");
        GenerateForm f = abrir();
        preencher(f, pasta, "main.py", "cancelado");
        Path destino = tmp.resolve("cancelado.zip");
        edt(() -> window.actions().generateTo(destino.toString()));
        // a pessoa cancela antes de a geracao rodar
        edt(() -> f.cancelButton().doClick(0));
        answer();
        assertNull(read(() -> window.actions().form()));
        assertFalse(Files.exists(destino), "cancelado: nenhum arquivo final");
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith("cancelado") && !Files.isDirectory(p)), "nem temporario ao lado do destino");
        }
        assertEquals("", read(() -> window.contextView().noticeText()));
    }
}
