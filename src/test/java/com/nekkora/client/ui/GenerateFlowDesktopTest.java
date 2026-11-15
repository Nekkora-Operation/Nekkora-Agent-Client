package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

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
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.nav.FakeDirectory;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Workspace;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.theme.ClientTheme;

/**
 * AGENT-CLIENT-PREPARACAO-002 T03: "Gerar pacote novo" de ponta a ponta na JANELA, com o dialogo MODAL (como em producao), o
 * clique REAL em "Gerar pacote" e o "Salvar como" REAL, conduzido por dentro do processo. O que era o defeito:
 * <ul>
 *   <li>sem informar Python, o pacote NAO era gerado (AGT-PAC-037), e a versao aparecia em branco;</li>
 *   <li>{@code 3.12} e {@code 3.12.0} eram recusados;</li>
 *   <li>os campos so eram conferidos DEPOIS do "Salvar como": a pessoa escolhia o destino, salvava, e a tela ficava com a
 *       mesma frase de antes - sem pacote e sem nada de novo para ler.</li>
 * </ul>
 * Aqui: o requisito de Python e opcional, a interpretacao aparece antes de gerar, um campo invalido impede a abertura do
 * seletor, e todo caminho termina num estado visivel (gerando, erro com os campos preservados, ou "Pacote gerado").
 */
@Tag("desktop")
class GenerateFlowDesktopTest {

    @TempDir
    Path tmp;

    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private Workshop workshop;
    private final Organization a = FakeDirectory.org(1, "Acme Indústria");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");

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
            window.actions().confirmOverwrite = path -> true;
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        assertTrue(read(() -> window.actions().modal), "o teste so vale com o dialogo modal, como em producao");
        settle();
        FakeSession session = new FakeSession();
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
        for (int i = 0; i < 3; i++) {
            JFileChooser open = read(GenerateFlowDesktopTest::chooserOnScreen);
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

    /** Clique de verdade em algo que pode abrir uma janela modal: e postado na fila, e o teste segue com a janela aberta. */
    private void click(JButton button) throws Exception {
        assertTrue(read(() -> button.isShowing() && button.isEnabled()), "botao na tela e habilitado: " + read(button::getText));
        SwingUtilities.invokeLater(() -> button.doClick(0));
        settle();
        answer();
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
            JFileChooser f = read(GenerateFlowDesktopTest::chooserOnScreen);
            if (f != null) {
                return f;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("o seletor de arquivos nao abriu");
    }

    private void confirm(Path path) throws Exception {
        JFileChooser c = chooser();
        edt(() -> {
            c.setSelectedFile(path.toFile());
            c.approveSelection();
        });
        assertFalse(read(c::isShowing), "o seletor fecha ao confirmar");
        answer();
    }

    private void cancelChooser() throws Exception {
        JFileChooser c = chooser();
        edt(c::cancelSelection);
        assertFalse(read(c::isShowing));
        answer();
    }

    /** Projeto novo, sem manifesto e sem nenhuma declaracao: main.py na raiz e um modulo em subpasta. */
    private Path projeto(String nome) throws Exception {
        Path p = tmp.resolve(nome);
        Files.createDirectories(p.resolve("tarefas"));
        Files.writeString(p.resolve("main.py"), "from tarefas import relatorio\nprint(relatorio.gerar())\n");
        Files.writeString(p.resolve("tarefas").resolve("__init__.py"), "");
        Files.writeString(p.resolve("tarefas").resolve("relatorio.py"), "def gerar():\n    return 1\n");
        return p;
    }

    /** Abre o formulario pelo botao, escolhe a pasta pelo seletor e preenche SO os tres campos obrigatorios. */
    private GenerateForm preenchido(Path pasta, String nome) throws Exception {
        click(read(() -> window.contextView().generateButton()));
        GenerateForm f = read(() -> window.actions().generateForm());
        assertNotNull(f);
        click(read(f::browseButton));
        confirm(pasta);
        edt(() -> f.entryBox().setSelectedItem("main.py"));
        edt(() -> f.nameField().setText(nome));
        edt(() -> window.actions().refreshSummary());
        answer();
        return f;
    }

    private static String manifesto(Path zip) throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return new String(z.getInputStream(z.getEntry("nekkora-external.json")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<String> sobras(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ------------------------------------------------------------------ Python opcional

    @Test
    void semInformarPythonOPacoteEGeradoEATelaTerminaEmPacoteGerado() throws Exception {
        Path pasta = projeto("projeto-novo");
        GenerateForm f = preenchido(pasta, "relatorio-mensal");
        assertFalse(read(f::advancedOpen), "os tres campos bastam: nada pede para abrir as opcoes avancadas");
        assertEquals("1.0.0", read(() -> f.versionField().getText()), "a versao declarada ja vem preenchida num projeto novo");
        assertEquals("", read(() -> f.pythonField().getText()), "o Python instalado neste computador nao vira requisito do projeto");
        assertEquals(NavText.GENERATE_PYTHON_NONE, read(() -> f.pythonNotice().text()));
        assertEquals("", read(f::errorText), "nada em vermelho antes de a pessoa pedir para gerar");
        assertTrue(read(f::summaryText).contains("Python: Sem restrição de Python declarada"), read(f::summaryText));

        Path destino = Files.createDirectories(tmp.resolve("saída com espaços")).resolve("relatório mensal.zip");
        click(read(f::generateButton));
        confirm(destino);

        assertTrue(Files.isRegularFile(destino), "o pacote existe no destino escolhido: " + read(f::errorText));
        assertEquals("Concluído", read(() -> f.successNotice().label()));
        assertEquals("Pacote gerado", read(() -> f.successNotice().text()));
        assertEquals(destino.toString(), read(() -> f.generatedPathField().getText()));
        assertTrue(read(() -> f.revealButton().isShowing()), "Abrir pasta");
        assertEquals("", read(() -> f.progressNotice().text()), "o andamento termina");
        assertEquals("", read(f::errorText));
        assertEquals(List.of("relatório mensal.zip"), sobras(destino.getParent()), "nenhum arquivo parcial");

        String m = manifesto(destino);
        assertFalse(m.contains("\"python\""), "sem requisito: a chave nao existe no manifesto (nem faixa arbitraria, nem versao ficticia)\n" + m);
        assertTrue(m.contains("\"version\": \"1.1.0\""), m);
        assertTrue(m.contains("\"packageVersion\": \"1.0.0\""), m);
        assertTrue(m.contains("\"tarefas/relatorio.py\""), "a subpasta continua entrando");
        // o validador real de pacotes aceita o arquivo
        Workshop.LocalItem item = workshop.importZip(UUID.randomUUID(), UUID.randomUUID(), destino.toString()).item();
        assertEquals("relatorio-mensal", item.declaredName());
    }

    @Test
    void tresPontoDozeEAFamiliaETresPontoDozePontoZeroEAVersaoExata() throws Exception {
        Path pasta = projeto("projeto-familia");
        GenerateForm f = preenchido(pasta, "familia");
        edt(() -> f.advancedToggle().doClick(0));

        edt(() -> f.pythonField().setText("3.12"));
        assertEquals("Interpretação: qualquer Python 3.12 (3.12.x). No manifesto: ==3.12.*", read(() -> f.pythonNotice().text()),
                "a interpretacao aparece ANTES de gerar");
        assertFalse(read(() -> f.pythonNotice().isError()));
        Path familia = tmp.resolve("familia.zip");
        click(read(f::generateButton));
        confirm(familia);
        assertTrue(Files.isRegularFile(familia), read(f::errorText));
        assertTrue(manifesto(familia).contains("\"python\": \"==3.12.*\""), manifesto(familia));
        assertTrue(manifesto(familia).contains("\"version\": \"1.0.0\""), "requisito da 1.0: o manifesto continua 1.0.0");
        edt(() -> f.cancelButton().doClick(0));

        GenerateForm g = preenchido(pasta, "exata");
        edt(() -> g.advancedToggle().doClick(0));
        edt(() -> g.pythonField().setText("3.12.0"));
        assertEquals("Interpretação: exatamente o Python 3.12.0. No manifesto: ==3.12.0", read(() -> g.pythonNotice().text()));
        Path exata = tmp.resolve("exata.zip");
        click(read(g::generateButton));
        confirm(exata);
        assertTrue(Files.isRegularFile(exata), read(g::errorText));
        assertTrue(manifesto(exata).contains("\"python\": \"==3.12.0\""), manifesto(exata));
        assertTrue(manifesto(exata).contains("\"version\": \"1.1.0\""), "versao exata so existe no manifesto 1.1");
        edt(() -> g.cancelButton().doClick(0));

        GenerateForm h = preenchido(pasta, "faixa");
        edt(() -> h.advancedToggle().doClick(0));
        edt(() -> h.pythonField().setText(">= 3.10, < 3.14"));
        assertEquals("Interpretação: Python 3.10 ou mais novo, abaixo do 3.14. No manifesto: >=3.10,<3.14", read(() -> h.pythonNotice().text()));
        Path faixa = tmp.resolve("faixa.zip");
        click(read(h::generateButton));
        confirm(faixa);
        assertTrue(manifesto(faixa).contains("\"python\": \">=3.10,<3.14\""), manifesto(faixa));
        for (Path zip : List.of(familia, exata, faixa)) {
            workshop.importZip(UUID.randomUUID(), UUID.randomUUID(), zip.toString()); // o validador real aceita
        }
    }

    @Test
    void requisitoInvalidoNaoAbreOSalvarComoEApagarOCampoPermiteGerar() throws Exception {
        Path pasta = projeto("projeto-invalido");
        GenerateForm f = preenchido(pasta, "invalido");
        edt(() -> f.advancedToggle().doClick(0));
        edt(() -> f.pythonField().setText("python novo"));
        assertFalse(read(() -> f.pythonNotice().isError()), "enquanto a pessoa digita, nada fica vermelho");

        click(read(f::generateButton));
        assertNull(read(GenerateFlowDesktopTest::chooserOnScreen), "campo invalido: o seletor de destino NAO abre");
        assertTrue(read(() -> f.pythonNotice().isError()), "o erro fica junto do campo");
        assertEquals("Erro", read(() -> f.pythonNotice().label()));
        assertTrue(read(() -> f.pythonNotice().text()).startsWith("Requisito não entendido."), read(() -> f.pythonNotice().text()));
        assertTrue(read(f::advancedOpen), "a secao do campo fica aberta");
        assertEquals("python novo", read(() -> f.pythonField().getText()), "nada e trocado por um padrao escondido");
        assertEquals("invalido", read(() -> f.nameField().getText()));
        assertEquals("", read(() -> f.successNotice().text()));
        assertTrue(read(() -> f.generateButton().isEnabled()), "nada ficou preso: a pessoa corrige e tenta de novo");

        // desistir no "Salvar como" nao mostra sucesso nem erro, e o formulario fica como estava
        edt(() -> f.pythonField().setText(""));
        click(read(f::generateButton));
        cancelChooser();
        assertEquals("", read(f::errorText));
        assertEquals("", read(() -> f.successNotice().text()));
        assertEquals("", read(() -> f.progressNotice().text()));
        assertEquals("invalido", read(() -> f.nameField().getText()));

        Path destino = tmp.resolve("sem-restricao.zip");
        click(read(f::generateButton));
        confirm(destino);
        assertTrue(Files.isRegularFile(destino), read(f::errorText));
        assertFalse(manifesto(destino).contains("\"python\""));
    }

    @Test
    void versaoInvalidaEDitaJuntoDoCampoAntesDoSeletor() throws Exception {
        Path pasta = projeto("projeto-versao");
        GenerateForm f = preenchido(pasta, "versao");
        edt(() -> f.advancedToggle().doClick(0));
        edt(() -> f.versionField().setText("um"));
        click(read(f::generateButton));
        assertNull(read(GenerateFlowDesktopTest::chooserOnScreen), "o seletor de destino nao abre");
        assertTrue(read(() -> f.versionNotice().isError()));
        assertEquals("A versão precisa ter o formato x.y.z, só com números (por exemplo, 1.0.0).", read(() -> f.versionNotice().text()));
        assertEquals("Código de suporte: AGT-PAC-036", read(() -> f.versionNotice().detail()), "o codigo fica no detalhe, fora da frase");
        assertFalse(read(() -> f.pythonNotice().isError()), "so o campo com problema");
    }

    // ------------------------------------------------------------------ conclusao confiavel

    @Test
    void falhaDeGravacaoTerminaOAndamentoMantemOsCamposEDizOMotivo() throws Exception {
        Path pasta = projeto("projeto-falha");
        GenerateForm f = preenchido(pasta, "falha");
        Path destino = tmp.resolve("ocupado.zip");
        Files.writeString(destino, "arquivo que ja existia");
        // outro programa com o arquivo aberto: no Windows ele nao pode ser substituido
        java.nio.channels.FileChannel preso = java.nio.channels.FileChannel.open(destino, java.nio.file.StandardOpenOption.READ);
        try {
            click(read(f::generateButton));
            confirm(destino);
            if (read(() -> f.successNotice().text()).isEmpty()) {
                assertTrue(read(() -> f.generalError().isError()), "o motivo aparece como erro");
                assertFalse(read(() -> f.generalError().text()).isBlank());
                assertFalse(read(() -> f.generalError().detail()).isBlank(), "com o codigo de suporte no detalhe");
                assertEquals("arquivo que ja existia", Files.readString(destino), "o arquivo que existia fica intacto");
            }
        } finally {
            preso.close();
        }
        assertEquals("", read(() -> f.progressNotice().text()), "o andamento sempre termina");
        assertFalse(read(f::busy));
        assertEquals("falha", read(() -> f.nameField().getText()), "os campos ficam");
        assertEquals("main.py", read(() -> f.entryBox().getSelectedItem()));
        assertFalse(sobras(tmp).stream().anyMatch(n -> n.endsWith(".parcial")), "nenhum arquivo parcial: " + sobras(tmp));
    }

    @Test
    void destinoSemPermissaoDeEscritaEUmErroVisivelENaoUmSilencio() throws Exception {
        Path pasta = projeto("projeto-negado");
        GenerateForm f = preenchido(pasta, "negado");
        // a "pasta" de destino deixou de existir entre a escolha e a gravacao: a gravacao falha de verdade
        Path some = Files.createDirectories(tmp.resolve("some"));
        Path destino = some.resolve("pacote.zip");
        edt(() -> window.actions().generateTo(destino.toString()));
        Files.delete(some);
        answer();
        assertFalse(Files.exists(destino));
        assertTrue(read(() -> f.generalError().isError()), "falha de escrita: erro na area de resultado");
        assertEquals("", read(() -> f.successNotice().text()));
        assertEquals("", read(() -> f.progressNotice().text()));
        assertTrue(read(() -> f.generateButton().isEnabled()), "a pessoa pode escolher outro destino");
    }

    // ------------------------------------------------------------------ T04: arquivo de dependencias

    @Test
    void requirementsDaRaizApareceComoSelecionadoEAsDependenciasVaoParaOManifesto() throws Exception {
        Path pasta = projeto("projeto-com-arquivo");
        Files.writeString(pasta.resolve("requirements.txt"), "# robo\nrequests==2.31.0\nopenpyxl[lxml]==3.1.5\n");
        GenerateForm f = preenchido(pasta, "com-arquivo");
        assertFalse(read(f::advancedOpen), "arquivo sem problema: nada pede para abrir as opcoes avancadas");
        edt(() -> f.advancedToggle().doClick(0));
        assertEquals(pasta.resolve("requirements.txt").toString(), read(() -> f.requirementsPathField().getText()));
        assertEquals("Informação", read(() -> f.requirementsNotice().label()));
        assertTrue(read(() -> f.requirementsNotice().text()).startsWith("Encontrado na raiz do projeto e selecionado: requirements.txt."),
                "a deteccao e dita na tela: " + read(() -> f.requirementsNotice().text()));
        assertEquals("2 dependências com versão exata: requests==2.31.0, openpyxl[lxml]==3.1.5.", read(() -> f.requirementsSummaryNotice().text()));
        assertEquals("", read(() -> f.requirementsProblemsNotice().text()));
        assertEquals("Arquivo de dependências (2 dependências)", String.valueOf(read(() -> f.sourceBox().getSelectedItem())));
        assertEquals("requests==2.31.0\nopenpyxl[lxml]==3.1.5", read(() -> f.dependenciesArea().getText()));
        assertFalse(read(() -> f.dependenciesArea().isEditable()), "a lista do arquivo nao e editada aqui: uma so origem vale");
        assertFalse(read(() -> f.copyRequirementsBox().isVisible()), "ja e um arquivo do projeto");
        assertTrue(read(f::summaryText).contains("dependências: requests==2.31.0, openpyxl[lxml]==3.1.5 (do arquivo de dependências)"), read(f::summaryText));

        Path destino = tmp.resolve("com-arquivo.zip");
        click(read(f::generateButton));
        confirm(destino);
        assertTrue(Files.isRegularFile(destino), read(f::errorText));
        String m = manifesto(destino);
        assertTrue(m.contains("\"requests==2.31.0\"") && m.contains("\"openpyxl[lxml]==3.1.5\""), m);
        assertFalse(m.contains(tmp.getFileName().toString()), "nenhum caminho deste computador no manifesto");
    }

    @Test
    void arquivoEscolhidoPeloSeletorComLinhasNaoRepresentaveisAvisaPorLinhaEImpedeGerarPorEle() throws Exception {
        Path pasta = projeto("projeto-escolhe");
        Path fora = Files.createDirectories(tmp.resolve("fora do projeto"));
        Path req = fora.resolve("requisitos.txt");
        Files.writeString(req, "requests>=2.0\nopenpyxl==3.1.5\n-r base.txt\n");
        GenerateForm f = preenchido(pasta, "escolhe");
        edt(() -> f.advancedToggle().doClick(0));
        assertEquals(NavText.REQUIREMENTS_NONE, read(() -> f.requirementsPathField().getText()));
        assertEquals("Edição manual", String.valueOf(read(() -> f.sourceBox().getSelectedItem())));
        assertEquals(NavText.DEPENDENCIES_NONE, read(() -> f.dependenciesNotice().text()),
                "sem arquivo: 'nenhuma dependencia declarada' - que nao e 'o projeto nao usa bibliotecas'");
        assertFalse(read(() -> f.clearRequirementsButton().isVisible()));

        // desistir no seletor preserva tudo
        click(read(f::selectRequirementsButton));
        JFileChooser c = chooser();
        assertEquals(JFileChooser.FILES_ONLY, read(c::getFileSelectionMode), "seleciona um ARQUIVO");
        cancelChooser();
        assertEquals(NavText.REQUIREMENTS_NONE, read(() -> f.requirementsPathField().getText()));

        click(read(f::selectRequirementsButton));
        confirm(req);
        assertEquals(req.toString(), read(() -> f.requirementsPathField().getText()));
        assertTrue(read(() -> f.requirementsNotice().text()).startsWith("Arquivo de fora da pasta do projeto."), read(() -> f.requirementsNotice().text()));
        assertEquals("Aviso", read(() -> f.requirementsProblemsNotice().label()), "linha que o pacote nao representa: aviso, com o motivo, por linha");
        String problemas = read(() -> f.requirementsProblemsNotice().text());
        assertTrue(problemas.contains("Linha 1 (requests>=2.0): faixa de versões") && problemas.contains("Linha 3 (-r base.txt): inclui outro arquivo"), problemas);
        assertTrue(read(f::requirementsDetailsText).contains("ok        linha 2: openpyxl==3.1.5"), read(f::requirementsDetailsText));
        assertEquals("Arquivo de dependências (1 dependência)", String.valueOf(read(() -> f.sourceBox().getSelectedItem())),
                "o arquivo passa a ser a origem (a pessoa nao tinha escolhido outra)");
        assertTrue(read(() -> f.copyRequirementsBox().isVisible()));
        assertEquals("Copiar este arquivo para o pacote como requirements.txt", read(() -> f.copyRequirementsBox().getText()));
        assertFalse(read(() -> f.copyRequirementsBox().isSelected()), "copiar e uma acao explicita");

        // gerar por um arquivo com problema: o seletor de destino NAO abre, e o erro fica nas dependencias
        click(read(f::generateButton));
        assertNull(read(GenerateFlowDesktopTest::chooserOnScreen));
        assertTrue(read(() -> f.dependenciesNotice().isError()));
        assertTrue(read(() -> f.dependenciesNotice().text()).contains("linha 1: requests>=2.0"), read(() -> f.dependenciesNotice().text()));
        assertEquals("Código de suporte: AGT-PAC-047", read(() -> f.dependenciesNotice().detail()));

        // a pessoa corrige o arquivo e o seleciona de novo
        Files.writeString(req, "requests==2.31.0\nopenpyxl==3.1.5\n");
        click(read(f::selectRequirementsButton));
        confirm(req);
        assertEquals("", read(() -> f.requirementsProblemsNotice().text()));
        assertEquals("", read(f::errorText));
        edt(() -> f.copyRequirementsBox().doClick(0));
        answer();
        assertTrue(read(f::summaryText).contains("o arquivo de dependências entra no pacote como requirements.txt"), read(f::summaryText));
        Path destino = tmp.resolve("escolhe.zip");
        click(read(f::generateButton));
        confirm(destino);
        assertTrue(Files.isRegularFile(destino), read(f::errorText));
        assertTrue(manifesto(destino).contains("\"requests==2.31.0\""));
        try (ZipFile z = new ZipFile(destino.toFile())) {
            assertNotNull(z.getEntry("requirements.txt"), "o arquivo escolhido entrou, e so ele");
            assertNull(z.getEntry("requisitos.txt"));
        }
        assertEquals(List.of("requisitos.txt"), sobras(fora), "a pasta do arquivo fica como estava");
    }

    @Test
    void removerASelecaoEEdicaoManualSaoEscolhasExplicitas() throws Exception {
        Path pasta = projeto("projeto-remove");
        Files.writeString(pasta.resolve("requirements.txt"), "requests>=2.0\n");
        GenerateForm f = preenchido(pasta, "remove");
        assertTrue(read(f::advancedOpen), "o arquivo tem uma linha que so a pessoa resolve: a secao abre");
        assertTrue(read(() -> f.dependenciesNotice().isError()), "com o arquivo como origem, o resumo nao pode ser feito");

        // edicao manual: a pessoa digita a lista
        edt(() -> f.chooseSource(Workshop.DependencySource.MANUAL));
        answer();
        assertTrue(read(() -> f.dependenciesArea().isEditable()));
        edt(() -> f.dependenciesArea().setText("requests==2.31.0"));
        edt(() -> window.actions().refreshSummary());
        answer();
        assertEquals("", read(f::errorText));
        assertTrue(read(f::summaryText).contains("requests==2.31.0 (edição manual)"), read(f::summaryText));

        // remover a selecao: o arquivo deixa de ser origem; a edicao manual da pessoa fica
        edt(() -> f.clearRequirementsButton().doClick(0));
        answer();
        assertEquals(NavText.REQUIREMENTS_NONE, read(() -> f.requirementsPathField().getText()));
        assertEquals("", read(() -> f.requirementsProblemsNotice().text()));
        assertEquals("Edição manual", String.valueOf(read(() -> f.sourceBox().getSelectedItem())));
        assertEquals("requests==2.31.0", read(() -> f.dependenciesArea().getText()));
        Path destino = tmp.resolve("remove.zip");
        click(read(f::generateButton));
        confirm(destino);
        assertTrue(Files.isRegularFile(destino), read(f::errorText));
        assertTrue(manifesto(destino).contains("\"requests==2.31.0\""));
        assertEquals("requests>=2.0\n", Files.readString(pasta.resolve("requirements.txt")), "o arquivo do projeto nao e alterado");
    }

    @Test
    void enquantoGeraOBotaoNaoAceitaOutroPedido() throws Exception {
        Path pasta = projeto("projeto-duplo");
        GenerateForm f = preenchido(pasta, "duplo");
        Path destino = tmp.resolve("duplo.zip");
        edt(() -> window.actions().generateTo(destino.toString()));
        assertEquals("Gerando pacote…", read(() -> f.progressNotice().text()), "andamento visivel");
        assertEquals("Informação", read(() -> f.progressNotice().label()));
        assertFalse(read(() -> f.generateButton().isEnabled()));
        int pendentes = background.size();
        edt(() -> window.actions().generateTo(tmp.resolve("outro.zip").toString()));
        edt(() -> window.actions().requestGenerate());
        assertEquals(pendentes, background.size(), "segundo pedido ignorado enquanto o primeiro corre");
        answer();
        assertTrue(Files.isRegularFile(destino));
        assertFalse(Files.exists(tmp.resolve("outro.zip")));
        assertEquals("Pacote gerado", read(() -> f.successNotice().text()));
    }
}
