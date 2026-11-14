package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Automation;
import com.nekkora.client.nav.Directory.Failure;
import com.nekkora.client.nav.FakeDirectory;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Workspace;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.theme.ClientTheme;

/**
 * Tela da organizacao: ambiente e lista de automacoes (AGENT-CLIENT-NAVEGACAO-001 T03), na janela real, com a entrada e a
 * Platform SIMULADAS. Eventos restritos a janela de teste.
 */
@Tag("desktop")
class ContextDesktopTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");

    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private FakeSession session;
    private final Organization a = FakeDirectory.org(1, "Acme Indústria");
    private final Organization b = FakeDirectory.org(2, "Beta Serviços");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");
    private final Workspace fis = FakeDirectory.ws(2, "Fiscal");
    private final Workspace vazio = FakeDirectory.ws(3, "Recursos Humanos");
    private final Workspace deB = FakeDirectory.ws(4, "Financeiro");

    @BeforeEach
    void open() throws Exception {
        fake = new FakeSignIn();
        background = new FakeDirectory.Manual();
        SwingUtilities.invokeAndWait(() -> {
            Themes.apply(ClientTheme.SAKURA_DARK);
            flow = new SignInFlow(fake, r -> {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
            });
            window = new ClientWindow(flow, t -> { }, () -> { }, BuildInfo.load(), background);
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        settle();
        session = new FakeSession();
        FakeDirectory d = session.directory;
        d.organizations = List.of(a, b);
        d.workspaces.put(a.id(), List.of(fin, fis, vazio));
        d.workspaces.put(b.id(), List.of(deB));
        d.automations.put(fin.id(), FakeDirectory.listing(true, true, FakeDirectory.automation(1, "Conciliação bancária", 3, "1.2.0"),
                FakeDirectory.automation(2, "Emissão de boletos", 1, null), FakeDirectory.automation(3, "Rascunho ainda sem publicação", null, null)));
        d.automations.put(fis.id(), FakeDirectory.listing(false, true, FakeDirectory.automation(4, "Apuração", 7, "2.0.1")));
        d.automations.put(vazio.id(), FakeDirectory.listing(true, true));
        d.automations.put(deB.id(), FakeDirectory.listing(false, false, FakeDirectory.automation(9, "Folha de pagamento", 2, "0.9.0")));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        answer();
    }

    @AfterEach
    void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            flow.close();
            window.dispose();
        });
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
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
        background.runAll();
        settle();
        edt(window::validate);
    }

    private void choose(Organization o) throws Exception {
        edt(() -> window.organizationsView().cards().stream().filter(c -> c.organization().id().equals(o.id())).findFirst().orElseThrow().choose());
    }

    private void pick(Object item) throws Exception {
        edt(() -> window.contextView().workspaceBox().setSelectedItem(item));
    }

    private Rectangle in(Component c, Component reference) throws Exception {
        return read(() -> SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), reference));
    }

    private void evidence(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        for (double scale : new double[] {1.0, 1.5, 2.0}) {
            BufferedImage img = read(() -> {
                Container root = window.getRootPane();
                BufferedImage i = new BufferedImage((int) Math.ceil(root.getWidth() * scale), (int) Math.ceil(root.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = i.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.scale(scale, scale);
                root.paint(g);
                g.dispose();
                return i;
            });
            ImageIO.write(img, "png", EVIDENCE.resolve(name + "-" + (int) (scale * 100) + ".png").toFile());
        }
    }

    private List<String> items() throws Exception {
        return read(() -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < window.contextView().workspaceBox().getItemCount(); i++) {
                Object o = window.contextView().workspaceBox().getItemAt(i);
                out.add(o instanceof Workspace w ? w.name() : String.valueOf(o));
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ ambiente

    @Test
    void seletorDeAmbienteComecaSemEscolhaEListaSoOsDaOrganizacao() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        assertEquals(ClientWindow.CONTEXT, read(window::showing));
        assertEquals("Acme Indústria", read(() -> v.nameLabel().getText()));
        assertEquals("Ambiente", read(() -> v.workspaceLabel().getText()), "rotulo da tela: Ambiente (nao Workspace)");
        assertEquals("Ambiente", read(() -> v.workspaceBox().getAccessibleContext().getAccessibleName()));
        assertEquals(List.of("Carregando ambientes..."), items());
        assertFalse(read(() -> v.workspaceBox().isEnabled()));
        answer();
        assertEquals(List.of("Selecione um ambiente", "Financeiro", "Fiscal", "Recursos Humanos"), items());
        assertEquals("Selecione um ambiente", read(() -> v.workspaceBox().getSelectedItem()), "nenhum ambiente escolhido sozinho");
        assertTrue(read(() -> v.workspaceBox().isEnabled() && v.workspaceBox().isFocusable()));
        assertEquals(ContextView.HINT, read(v::showing));
        assertEquals("Selecione um ambiente para ver as automações.", read(v::hintText));
        assertEquals(0, read(() -> v.rows().size()));
        assertFalse(session.directory.calls.stream().anyMatch(c -> c.startsWith("automations")), "sem ambiente, nenhuma consulta de automacoes");
        assertFalse(read(() -> v.createButton().isEnabled() || v.importButton().isEnabled()), "sem ambiente, sem acoes do ambiente");
        evidence("sakura-dark-ambiente-selecione");
        // nada de "workspace" nos textos visiveis
        for (String t : items()) {
            assertFalse(t.toLowerCase().contains("workspace"));
        }
    }

    @Test
    void semAmbientesMostraAOpcaoDesabilitadaEAOrientacao() throws Exception {
        session.directory.workspaces.put(b.id(), List.of());
        choose(b);
        answer();
        ContextView v = window.contextView();
        assertEquals(List.of("Nenhum ambiente cadastrado"), items());
        assertFalse(read(() -> v.workspaceBox().isEnabled()), "opcao desabilitada");
        assertEquals("Crie um ambiente na Platform para continuar.", read(v::workspaceNote));
        assertEquals("", read(v::hintText));
        assertEquals("", read(v::emptyText), "sem ambientes nao e 'nenhuma automacao'");
        evidence("sakura-dark-ambiente-nenhum");
    }

    @Test
    void falhaAoCarregarAmbientesNaoViraNenhumAmbienteCadastrado() throws Exception {
        ContextView v = window.contextView();
        for (Failure f : new Failure[] {Failure.NETWORK, Failure.RATE_LIMITED, Failure.SERVER, Failure.NOT_FOUND, Failure.DENIED}) {
            session.directory.workspacesFailure = f;
            choose(a);
            answer();
            assertEquals(NavText.of(f), read(v::workspaceNote), f.toString());
            assertFalse(items().contains("Nenhum ambiente cadastrado"), f + ": falha nao e ausencia de ambientes");
            assertFalse(read(() -> v.workspaceBox().isEnabled()));
            assertTrue(read(() -> v.retryWorkspacesButton().isShowing()));
            if (f == Failure.NOT_FOUND) {
                evidence("sakura-dark-ambiente-acesso-negado");
            }
            session.directory.workspacesFailure = null;
            edt(() -> v.retryWorkspacesButton().doClick(0));
            answer();
            assertEquals(4, items().size(), "tentar novamente traz os ambientes");
            assertFalse(read(() -> v.retryWorkspacesButton().isShowing()));
            edt(() -> v.backButton().doClick(0));
        }
    }

    // ------------------------------------------------------------------ automacoes

    @Test
    void listaComNomeVersaoEBotoesNosDoisTemas() throws Exception {
        ContextView v = window.contextView();
        for (ClientTheme theme : ClientTheme.values()) {
            edt(() -> Themes.apply(theme));
            String p = theme.libId().equals("light") ? "nekkora-light" : "sakura-dark";
            choose(a);
            answer();
            pick(fin);
            assertEquals(ContextView.LOADING, read(v::showing), "carregando nao e lista vazia");
            assertEquals("", read(v::emptyText));
            answer();
            assertEquals(ContextView.LIST, read(v::showing));
            List<AutomationRow> rows = read(v::rows);
            assertEquals(List.of("Conciliação bancária", "Emissão de boletos", "Rascunho ainda sem publicação"), rows.stream().map(AutomationRow::name).toList(),
                    "na ordem da Platform");
            // numero da versao NA PLATFORM e versao DECLARADA no pacote, cada uma com o seu nome
            assertEquals(List.of("Versão publicada 3 · versão do pacote 1.2.0"), rows.get(0).details());
            assertEquals(List.of("Versão publicada 1"), rows.get(1).details());
            assertEquals(List.of("Sem versão publicada"), rows.get(2).details(), "rascunho nao e versao publicada");
            for (AutomationRow r : rows) {
                assertFalse(r.details().get(0).toLowerCase().contains("última"), "nao chama de ultima");
                List<JButton> bs = r.actions();
                assertEquals(List.of("Executar", "Editar"), bs.stream().map(JButton::getText).toList());
                Rectangle row = read(r::getBounds);
                Rectangle run = read(() -> bs.get(0).getBounds());
                Rectangle edit = read(() -> bs.get(1).getBounds());
                assertTrue(run.x + run.width <= edit.x, "Executar e Editar lado a lado, sem sobreposicao");
                assertTrue(edit.x + edit.width <= row.width && run.y >= 0 && run.y + run.height <= row.height, "botoes dentro da linha");
                // texto a esquerda, botoes a direita
                assertTrue(read(() -> r.sideBySide(r.getWidth())));
                assertTrue(AutomationRow.PAD_X + read(() -> r.textWidth(r.getWidth())) <= run.x, "o texto termina antes dos botoes");
                assertEquals("Executar: " + r.name(), read(() -> bs.get(0).getAccessibleContext().getAccessibleName()));
                assertEquals("Editar: " + r.name(), read(() -> bs.get(1).getAccessibleContext().getAccessibleName()));
            }
            assertTrue(read(() -> v.footer().isShowing() && v.actionsBar().isShowing()));
            evidence(p + "-automacoes-lista");
            edt(() -> v.backButton().doClick(0));
        }
    }

    @Test
    void ambienteSemAutomacoesMostraOVazioEMantemAsAcoes() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        answer();
        pick(vazio);
        answer();
        assertEquals(ContextView.EMPTY, read(v::showing));
        assertEquals("Nenhuma automação neste ambiente.", read(v::emptyText));
        assertTrue(read(() -> v.createButton().isShowing() && v.importButton().isShowing()), "as acoes do ambiente continuam na tela");
        assertEquals("Nova automação", read(() -> v.createButton().getText()));
        assertEquals("Importar (.zip)", read(() -> v.importButton().getText()));
        evidence("sakura-dark-automacoes-nenhuma");
    }

    @Test
    void falhaAoCarregarAutomacoesNaoViraListaVazia() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        answer();
        for (Failure f : new Failure[] {Failure.NETWORK, Failure.RATE_LIMITED, Failure.SERVER, Failure.TIMEOUT, Failure.NOT_FOUND}) {
            session.directory.automationsFailure = f;
            pick(fin);
            answer();
            assertEquals(ContextView.FAILED, read(v::showing), f.toString());
            assertEquals(NavText.of(f), read(v::failureText));
            assertEquals("", read(v::emptyText), f + ": falha nao e 'nenhuma automacao'");
            assertEquals(0, read(() -> v.rows().size()));
            if (f == Failure.SERVER) {
                evidence("sakura-dark-automacoes-erro");
            }
            session.directory.automationsFailure = null;
            edt(() -> v.retryAutomationsButton().doClick(0));
            answer();
            assertEquals(ContextView.LIST, read(v::showing));
            pick(NavText.WORKSPACE_PLACEHOLDER);
        }
    }

    @Test
    void semPermissaoDeEdicaoOBotaoDizPorQue() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        answer();
        pick(fis); // capacidades: rename=false
        answer();
        JButton edit = read(() -> v.rows().get(0).actions().get(1));
        assertFalse(read(edit::isEnabled));
        // nesta versao a acao ainda nao existe: a indicacao e de indisponibilidade, NUNCA de falta de permissao
        for (JButton bt : read(() -> List.of(v.rows().get(0).actions().get(0), edit, v.createButton(), v.importButton()))) {
            assertFalse(read(bt::isEnabled));
            assertEquals("Indisponível nesta versão de teste.", read(bt::getToolTipText), bt.getText());
            assertEquals("Indisponível nesta versão de teste.", read(() -> bt.getAccessibleContext().getAccessibleDescription()));
            assertFalse(read(bt::getToolTipText).toLowerCase().contains("permiss"));
        }
        assertEquals("Executar, Editar, Nova automação e Importar ainda não estão disponíveis nesta versão de teste.", read(v::unavailableNote));
        assertTrue(read(edit::isShowing), "visivel e desabilitado, com a justificativa");
        String dica = read(edit::getToolTipText);
        assertFalse(dica == null || dica.isBlank());
        assertEquals(read(edit::getToolTipText), read(() -> edit.getAccessibleContext().getAccessibleDescription()));
    }

    // ------------------------------------------------------------------ troca de contexto

    @Test
    void trocarDeAmbienteOuDeOrganizacaoNaoDeixaNadaDoContextoAnterior() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        answer();
        pick(fin);
        answer();
        assertEquals(3, read(() -> v.rows().size()));
        // troca de ambiente: a lista anterior some ANTES de a nova chegar
        pick(fis);
        assertEquals(0, read(() -> v.rows().size()), "nenhuma linha do ambiente anterior enquanto carrega o novo");
        assertEquals(ContextView.LOADING, read(v::showing));
        answer();
        assertEquals(List.of("Apuração"), read(() -> v.rows().stream().map(AutomationRow::name).toList()));
        // voltar e entrar em outra organizacao: nome, ambientes e automacoes sao os da nova
        edt(() -> v.backButton().doClick(0));
        assertEquals(ClientWindow.ORGANIZATIONS, read(window::showing));
        assertNull(read(v::organization));
        assertEquals(0, read(() -> v.rows().size()));
        choose(b);
        assertEquals("Beta Serviços", read(() -> v.nameLabel().getText()));
        assertEquals(List.of("Carregando ambientes..."), items(), "os ambientes da organizacao anterior nao aparecem");
        answer();
        assertEquals(List.of("Selecione um ambiente", "Financeiro"), items());
        assertEquals("Selecione um ambiente", read(() -> v.workspaceBox().getSelectedItem()), "o ambiente escolhido na outra organizacao nao vem junto");
        pick(deB);
        answer();
        assertEquals(List.of("Folha de pagamento"), read(() -> v.rows().stream().map(AutomationRow::name).toList()),
                "ambiente homonimo de outra organizacao: as automacoes sao as dele");
    }

    @Test
    void respostaAtrasadaNaoMostraDadosDoContextoAnterior() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        answer();
        pick(fin);
        Runnable lenta = background.take(0); // automacoes do Financeiro de A, presas
        edt(() -> v.backButton().doClick(0));
        choose(b);
        answer();
        pick(deB);
        answer();
        lenta.run();
        settle();
        assertEquals("Beta Serviços", read(() -> v.nameLabel().getText()));
        assertEquals(List.of("Folha de pagamento"), read(() -> v.rows().stream().map(AutomationRow::name).toList()),
                "a resposta atrasada da organizacao anterior nao entra na lista");
    }

    // ------------------------------------------------------------------ rolagem, tamanho e teclado

    @Test
    void listaLongaRolaEAcoesERodapeFicam() throws Exception {
        Automation[] muitas = new Automation[60];
        for (int i = 0; i < muitas.length; i++) {
            muitas[i] = FakeDirectory.automation(100 + i, String.format("Automação %02d", i + 1), i + 1, "1.0." + i);
        }
        session.directory.automations.put(fin.id(), FakeDirectory.listing(true, true, muitas));
        ContextView v = window.contextView();
        choose(a);
        answer();
        pick(fin);
        answer();
        assertTrue(read(() -> v.rowList().getHeight() > v.scroll().getViewport().getHeight()));
        Rectangle acoes = in(v.actionsBar(), v);
        Rectangle rodape = in(v.footer(), v);
        Rectangle combo = in(v.workspaceBox(), v);
        edt(() -> v.scroll().getVerticalScrollBar().setValue(v.scroll().getVerticalScrollBar().getMaximum()));
        assertTrue(read(() -> v.scroll().getVerticalScrollBar().getValue() > 0), "rolou");
        assertEquals(acoes, in(v.actionsBar(), v), "Nova automacao e Importar nao rolam");
        assertEquals(rodape, in(v.footer(), v), "o rodape nao rola");
        assertEquals(combo, in(v.workspaceBox(), v), "o seletor de ambiente nao rola");
        Rectangle area = in(v.scroll(), v);
        assertTrue(area.y + area.height <= acoes.y && acoes.y + acoes.height <= rodape.y, "lista, depois acoes, depois rodape");
        assertTrue(read(() -> v.createButton().isShowing() && v.importButton().isShowing() && v.footer().isShowing()));
        evidence("sakura-dark-automacoes-60-rolado");
    }

    @Test
    void janelaEstreitaLevaOsBotoesParaALinhaSeguinteSemSobrepor() throws Exception {
        session.directory.automations.put(fin.id(), FakeDirectory.listing(true, true,
                FakeDirectory.automation(1, "Conciliação bancária diária das contas correntes e de investimento do grupo", 12, "10.20.300"),
                FakeDirectory.automation(2, "Curta", 1, null)));
        ContextView v = window.contextView();
        choose(a);
        answer();
        pick(fin);
        answer();
        Dimension min = read(window::getMinimumSize);
        for (Dimension d : List.of(new Dimension(480, 600), min, new Dimension(900, 700))) {
            edt(() -> window.setSize(d));
            edt(window::validate);
            for (AutomationRow r : read(v::rows)) {
                int w = read(r::getWidth);
                assertEquals(r.name(), String.join(" ", read(() -> r.nameLines(w))), "nome inteiro em " + d);
                Rectangle run = read(() -> r.actions().get(0).getBounds());
                Rectangle edit = read(() -> r.actions().get(1).getBounds());
                assertFalse(run.intersects(edit), "botoes nao se sobrepoem em " + d);
                assertTrue(run.x >= 0 && edit.x + edit.width <= w && edit.y + edit.height <= read(r::getHeight), "botoes inteiros em " + d);
                int textBottom = AutomationRow.PAD_Y + read(() -> r.textHeight(w));
                boolean lado = read(() -> r.sideBySide(w));
                if (lado) {
                    assertTrue(AutomationRow.PAD_X + read(() -> r.textWidth(w)) <= run.x, "texto a esquerda dos botoes em " + d);
                } else {
                    assertTrue(run.y >= textBottom, "botoes na linha seguinte, abaixo do texto, em " + d);
                }
                assertEquals(read(() -> r.heightFor(w)), read(r::getHeight), 0);
            }
        }
        // a regra, para larguras de escala alta
        AutomationRow longa = read(() -> v.rows().get(0));
        assertFalse(read(() -> longa.sideBySide(240)), "largura pequena: acoes na linha seguinte");
        assertTrue(read(() -> longa.sideBySide(700)));
        edt(() -> window.setSize(min));
        edt(window::validate);
        evidence("sakura-dark-automacoes-janela-minima");
        assertTrue(read(() -> v.footer().isShowing() && v.createButton().isShowing()));
    }

    @Test
    void tecladoSeletorEBotoes() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        answer();
        assertTrue(read(() -> v.backButton().isFocusOwner()), "ao entrar, o foco esta na seta de voltar");
        edt(() -> v.workspaceBox().requestFocusInWindow());
        assertTrue(read(() -> v.workspaceBox().isFocusOwner()), "o seletor recebe foco pelo teclado");
        // seta para baixo no seletor escolhe o proximo ambiente (evento restrito ao componente)
        edt(() -> v.workspaceBox().setSelectedIndex(1));
        answer();
        assertEquals(fin, read(() -> v.workspaceBox().getSelectedItem()));
        assertEquals(3, read(() -> v.rows().size()));
        for (JButton bt : read(() -> v.rows().get(0).actions())) {
            assertTrue(read(bt::isFocusable));
        }
        assertTrue(read(() -> v.createButton().isFocusable() && v.importButton().isFocusable()));
    }
}
