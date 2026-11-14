package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
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
import com.nekkora.client.signin.SignInState.Phase;
import com.nekkora.client.theme.ClientTheme;

/**
 * O botao "Atualizar" nas duas telas (AGENT-CLIENT-NAVEGACAO-002 T01), na janela real, com a entrada e a Platform
 * SIMULADAS. Eventos restritos a janela de teste. A prova com a API e o banco reais fica no laboratorio.
 */
@Tag("desktop")
class RefreshDesktopTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");

    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private FakeSession session;
    private FakeDirectory d;
    private final Organization a = FakeDirectory.org(1, "Acme Indústria");
    private final Organization b = FakeDirectory.org(2, "Beta Serviços");
    private final Organization nova = FakeDirectory.org(3, "Criada depois do login");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");
    private final Workspace fis = FakeDirectory.ws(2, "Fiscal");
    private final Workspace novoAmbiente = FakeDirectory.ws(7, "Criado depois");
    private final Workspace deB = FakeDirectory.ws(4, "Financeiro");
    private final Automation conc = FakeDirectory.automation(1, "Conciliação bancária", 3, "1.2.0");

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
        d = session.directory;
        d.organizations = List.of(a, b);
        d.workspaces.put(a.id(), List.of(fin, fis));
        d.workspaces.put(b.id(), List.of(deB));
        d.automations.put(fin.id(), FakeDirectory.listing(true, true, conc, FakeDirectory.automation(2, "Emissão de boletos", 1, null)));
        d.automations.put(fis.id(), FakeDirectory.listing(false, true, FakeDirectory.automation(4, "Apuração", 7, "2.0.1")));
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
        answer();
    }

    private void pick(Object item) throws Exception {
        edt(() -> window.contextView().workspaceBox().setSelectedItem(item));
        answer();
    }

    private Rectangle in(Component c, Component reference) throws Exception {
        return read(() -> SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), reference));
    }

    private List<String> cards() throws Exception {
        return read(() -> window.organizationsView().cards().stream().map(c -> c.organization().name()).toList());
    }

    private List<String> rows() throws Exception {
        return read(() -> window.contextView().rows().stream().map(AutomationRow::name).toList());
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

    // ------------------------------------------------------------------ seletor de organizacoes

    @Test
    void noSeletorOBotaoTemIconeERotuloEFicaNoCabecalho() throws Exception {
        OrganizationsView v = window.organizationsView();
        ActionButton bt = read(() -> v.refresh().button());
        assertEquals("Atualizar", read(bt::getText), "rotulo por extenso, nao so o icone");
        assertEquals(ActionButton.Glyph.REFRESH, bt.glyph());
        assertEquals("Atualizar", read(() -> bt.getAccessibleContext().getAccessibleName()));
        assertTrue(read(() -> bt.isShowing() && bt.isEnabled() && bt.isFocusable()));
        assertFalse(read(bt::getToolTipText).toLowerCase().contains("vers"), "atualiza dados, nao o programa");
        // no cabecalho: abaixo do titulo, acima da area rolavel, e nao rola com a lista
        d.organizations = FakeDirectory.orgs(30);
        edt(() -> bt.doClick(0));
        answer();
        Rectangle botao = in(bt, v);
        Rectangle titulo = in(v.title(), v);
        Rectangle lista = in(v.scroll(), v);
        assertTrue(titulo.y + titulo.height <= botao.y && botao.y + botao.height <= lista.y, "titulo, Atualizar, lista");
        assertTrue(read(() -> v.grid().getHeight() > v.scroll().getViewport().getHeight()), "lista longa, rolavel");
        edt(() -> v.scroll().getVerticalScrollBar().setValue(v.scroll().getVerticalScrollBar().getMaximum()));
        assertEquals(botao, in(bt, v), "o botao nao rola: acessivel sem ir ao fim da lista");
        assertTrue(read(() -> v.footer().isShowing()));
    }

    @Test
    void organizacaoNovaApareceSemNovoLoginComProgressoESemCliqueDuplo() throws Exception {
        OrganizationsView v = window.organizationsView();
        ActionButton bt = read(() -> v.refresh().button());
        assertEquals(List.of("Acme Indústria", "Beta Serviços"), cards());
        assertEquals("", read(() -> v.refresh().noteText()), "sem atualizacao pedida, nenhuma mensagem");
        int antes = d.organizationCalls.get();
        int entradas = fake.attempts.size();

        d.organizations = List.of(a, b, nova);
        edt(() -> bt.doClick(0));
        // em curso: progresso visivel, botao desabilitado, a lista de antes continua na tela
        assertEquals("Atualizando…", read(bt::getText));
        assertFalse(read(bt::isEnabled));
        assertTrue(bt.loading());
        assertEquals("Atualizando…", read(() -> bt.getAccessibleContext().getAccessibleDescription()));
        assertEquals(OrganizationsView.LIST, read(v::showing), "a tela nao some enquanto atualiza");
        assertEquals(List.of("Acme Indústria", "Beta Serviços"), cards());
        assertEquals("", read(() -> v.refresh().noteText()), "nenhum 'atualizado' antes de terminar");
        evidence("sakura-dark-atualizar-organizacoes-em-curso");
        edt(() -> bt.doClick(0));
        edt(() -> bt.doClick(0));
        assertEquals(1, background.size(), "cliques repetidos nao acumulam consultas");

        answer();
        assertEquals(List.of("Acme Indústria", "Beta Serviços", "Criada depois do login"), cards(), "a organizacao nova aparece");
        assertEquals("Dados atualizados.", read(() -> v.refresh().noteText()));
        assertFalse(v.refresh().error());
        assertEquals("Atualizar", read(bt::getText));
        assertTrue(read(bt::isEnabled));
        assertEquals(antes + 1, d.organizationCalls.get());
        assertEquals(entradas, fake.attempts.size(), "nenhuma nova entrada foi pedida");
        assertEquals(Phase.SIGNED_IN, flow.state().phase());
        assertEquals(ClientWindow.ORGANIZATIONS, read(window::showing), "nada foi escolhido sozinho");
        evidence("sakura-dark-atualizar-organizacoes-atualizado");
        edt(() -> Themes.apply(ClientTheme.NEKKORA_LIGHT));
        edt(window::validate);
        evidence("light-atualizar-organizacoes-atualizado");
        // escolher uma organizacao tira a mensagem da tela seguinte
        d.workspaces.put(nova.id(), List.of());
        choose(nova);
        assertEquals("", read(() -> window.contextView().refresh().noteText()));
    }

    @Test
    void falhaNoSeletorNaoViraVazioNemSucessoENovaTentativaFunciona() throws Exception {
        OrganizationsView v = window.organizationsView();
        ActionButton bt = read(() -> v.refresh().button());
        Object[][] casos = {
                {Failure.NETWORK, "Não foi possível atualizar. Tente novamente. Verifique sua conexão. Os dados na tela são os de antes e não foram atualizados."},
                {Failure.SERVER, "Não foi possível atualizar. Tente novamente. A Platform encontrou um problema; aguarde alguns instantes. Os dados na tela são os de antes e não foram atualizados."},
                {Failure.RATE_LIMITED, "Não foi possível atualizar. Tente novamente. Muitas consultas seguidas: aguarde um pouco. Os dados na tela são os de antes e não foram atualizados."},
                {Failure.TIMEOUT, "Não foi possível atualizar. Tente novamente. A Platform não respondeu a tempo. Os dados na tela são os de antes e não foram atualizados."},
                {Failure.DENIED, "Não foi possível atualizar. Tente novamente. Sua conta não tem permissão para esta consulta. Os dados na tela são os de antes e não foram atualizados."}};
        for (Object[] c : casos) {
            Failure f = (Failure) c[0];
            d.organizationsFailure = f;
            edt(() -> bt.doClick(0));
            answer();
            assertEquals(c[1], read(() -> v.refresh().noteText()), f.toString());
            assertTrue(v.refresh().error(), f + ": em cor de erro");
            assertFalse(read(() -> v.refresh().noteText()).contains("Dados atualizados"), f.toString());
            assertEquals(OrganizationsView.LIST, read(v::showing), f + ": nao vira 'nenhuma organizacao' nem tela de erro");
            assertEquals(List.of("Acme Indústria", "Beta Serviços"), cards(), f + ": os dados de antes continuam");
            assertEquals("", read(v::emptyText));
            assertTrue(read(bt::isEnabled), f + ": pode tentar de novo");
            if (f == Failure.RATE_LIMITED) {
                evidence("sakura-dark-atualizar-organizacoes-falha");
            }
            d.organizationsFailure = null;
            edt(() -> bt.doClick(0));
            answer();
            assertEquals("Dados atualizados.", read(() -> v.refresh().noteText()), f + ": a nova tentativa funciona");
            assertFalse(v.refresh().error());
        }
    }

    @Test
    void plataformaAntigaNaoEDadaComoAtualizada() throws Exception {
        OrganizationsView v = window.organizationsView();
        d.organizationsCurrent = false;
        edt(() -> v.refresh().button().doClick(0));
        answer();
        assertEquals("Lista consultada, mas esta versão da Platform só mostra organizações novas depois de entrar de novo.", read(() -> v.refresh().noteText()));
        assertFalse(read(() -> v.refresh().noteText()).contains("Dados atualizados"));
    }

    @Test
    void sessaoExpiradaNaAtualizacaoVoltaParaAEntradaSemRenovar() throws Exception {
        OrganizationsView v = window.organizationsView();
        edt(() -> v.refresh().button().doClick(0));
        // a Platform responde 401: quem trata e a sessao; a tela volta para a entrada e a resposta nao repovoa nada
        edt(session::expire);
        d.organizations = List.of(a, b, nova);
        answer();
        assertEquals(ClientWindow.SIGN_IN, read(window::showing));
        assertEquals(0, read(() -> v.cards().size()), "nada da sessao anterior fica na tela");
        assertEquals("", read(() -> v.refresh().noteText()));
        assertFalse(flow.state().phase() == Phase.SIGNED_IN, "atualizar nao renova a sessao");
    }

    // ------------------------------------------------------------------ organizacao, ambiente e automacoes

    @Test
    void naTelaDaOrganizacaoOBotaoFicaNoCabecalhoEPreservaOResto() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        ActionButton bt = read(() -> v.refresh().button());
        assertEquals("Atualizar", read(bt::getText));
        assertEquals(ActionButton.Glyph.REFRESH, bt.glyph());
        assertEquals("Atualizar", read(() -> bt.getAccessibleContext().getAccessibleName()));
        assertTrue(read(() -> bt.isShowing() && bt.isEnabled() && bt.isFocusable()));
        for (java.awt.Dimension size : List.of(new java.awt.Dimension(480, 600), read(window::getMinimumSize), new java.awt.Dimension(900, 700))) {
            edt(() -> window.setSize(size));
            edt(window::validate);
            Rectangle botao = in(bt, v);
            Rectangle voltar = in(v.backButton(), v);
            Rectangle combo = in(v.workspaceBox(), v);
            Rectangle lista = in(v.scroll(), v);
            assertTrue(read(() -> v.backButton().isShowing() && v.workspaceBox().isShowing() && v.nameLabel().isShowing()), "voltar, organizacao e ambiente continuam em " + size);
            assertTrue(voltar.x + voltar.width <= botao.x, "voltar a esquerda, Atualizar a direita em " + size);
            assertFalse(botao.intersects(voltar) || botao.intersects(combo), "sem sobreposicao em " + size);
            assertTrue(botao.x + botao.width <= read(v::getWidth), "inteiro dentro da janela em " + size);
            assertTrue(botao.width >= read(() -> bt.getPreferredSize().width), "rotulo sem corte em " + size);
            assertTrue(botao.y + botao.height <= combo.y && combo.y + combo.height <= lista.y, "cabecalho, ambiente, lista em " + size);
        }
    }

    @Test
    void semAmbienteAtualizaAListaSemEscolherNenhum() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        d.workspaces.put(a.id(), List.of(novoAmbiente, fin, fis));
        edt(() -> v.refresh().button().doClick(0));
        assertEquals("Atualizando…", read(() -> v.refresh().button().getText()));
        answer();
        assertEquals(4, read(() -> v.workspaceBox().getItemCount()), "orientacao e os tres ambientes");
        assertEquals(NavText.WORKSPACE_PLACEHOLDER, read(() -> v.workspaceBox().getSelectedItem()), "nenhum ambiente e escolhido sozinho");
        assertEquals(0, read(() -> v.rows().size()));
        assertEquals("Dados atualizados.", read(() -> v.refresh().noteText()));
        assertFalse(d.calls.stream().anyMatch(c -> c.startsWith("automations")));
    }

    @Test
    void comAmbienteMantemASelecaoETrazAutomacaoEVersaoNovas() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        pick(fin);
        assertEquals(List.of("Conciliação bancária", "Emissão de boletos"), rows());
        assertEquals("Versão publicada 3 · versão do pacote 1.2.0", read(() -> v.rows().get(0).details().get(0)));
        // na Platform, depois de a tela aberta: ambiente novo, automacao nova e versao nova publicada
        d.workspaces.put(a.id(), List.of(novoAmbiente, fin, fis));
        d.automations.put(fin.id(), FakeDirectory.listing(true, true, FakeDirectory.automation(8, "Automação criada depois", null, null),
                FakeDirectory.automation(1, "Conciliação bancária", 4, "1.3.0"), FakeDirectory.automation(2, "Emissão de boletos", 1, null)));
        edt(() -> v.refresh().button().doClick(0));
        assertEquals(List.of("Conciliação bancária", "Emissão de boletos"), rows(), "enquanto atualiza, a lista de antes continua");
        assertEquals(ContextView.LIST, read(v::showing));
        evidence("sakura-dark-atualizar-contexto-em-curso");
        answer();
        assertEquals(fin, read(() -> v.workspaceBox().getSelectedItem()), "o mesmo ambiente continua escolhido");
        assertEquals(4, read(() -> v.workspaceBox().getItemCount()), "o ambiente novo esta no seletor");
        assertEquals(List.of("Automação criada depois", "Conciliação bancária", "Emissão de boletos"), rows(), "na ordem da Platform, sem duplicar");
        assertEquals("Versão publicada 4 · versão do pacote 1.3.0", read(() -> v.rows().get(1).details().get(0)), "a versao publicada nova aparece");
        assertEquals("Pacote não está neste computador", read(() -> v.rows().get(1).details().get(1)), "nenhum pacote e baixado");
        assertEquals("Dados atualizados.", read(() -> v.refresh().noteText()));
        assertEquals(ClientWindow.CONTEXT, read(window::showing));
        assertEquals("Acme Indústria", read(() -> v.nameLabel().getText()));
        evidence("sakura-dark-atualizar-contexto-atualizado");
        edt(() -> Themes.apply(ClientTheme.NEKKORA_LIGHT));
        edt(window::validate);
        evidence("light-atualizar-contexto-atualizado");
        // repetir: nada duplica
        edt(() -> v.refresh().button().doClick(0));
        answer();
        assertEquals(List.of("Automação criada depois", "Conciliação bancária", "Emissão de boletos"), rows());
        // trocar de ambiente tira a mensagem
        pick(fis);
        assertEquals("", read(() -> v.refresh().noteText()));
    }

    @Test
    void falhaComAmbienteMantemOsDadosDeAntesEAvisa() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        pick(fin);
        for (Failure f : new Failure[] {Failure.RATE_LIMITED, Failure.NETWORK, Failure.SERVER}) {
            d.automationsFailure = f;
            d.workspaces.put(a.id(), List.of(novoAmbiente, fin, fis)); // a parte que chegaria nao entra sozinha
            edt(() -> v.refresh().button().doClick(0));
            answer();
            String nota = read(() -> v.refresh().noteText());
            assertTrue(nota.startsWith("Não foi possível atualizar. Tente novamente."), f + ": " + nota);
            assertTrue(nota.endsWith("Os dados na tela são os de antes e não foram atualizados."), f + ": " + nota);
            assertTrue(v.refresh().error());
            assertEquals(ContextView.LIST, read(v::showing), f + ": a lista nao vira erro nem vazio");
            assertEquals(List.of("Conciliação bancária", "Emissão de boletos"), rows(), f.toString());
            assertEquals(3, read(() -> v.workspaceBox().getItemCount()), f + ": atualizacao parcial nao entra");
            assertEquals(fin, read(() -> v.workspaceBox().getSelectedItem()), f + ": indisponibilidade nao tira o ambiente");
            if (f == Failure.NETWORK) {
                evidence("sakura-dark-atualizar-contexto-falha");
                edt(() -> Themes.apply(ClientTheme.NEKKORA_LIGHT));
                edt(window::validate);
                evidence("light-atualizar-contexto-falha");
                edt(() -> Themes.apply(ClientTheme.SAKURA_DARK));
            }
            d.automationsFailure = null;
        }
        edt(() -> v.refresh().button().doClick(0));
        answer();
        assertEquals("Dados atualizados.", read(() -> v.refresh().noteText()));
        assertEquals(4, read(() -> v.workspaceBox().getItemCount()));
    }

    @Test
    void ambienteRevogadoSaiDaTelaComMensagemESemEscolherOutro() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        pick(fin);
        d.workspaces.put(a.id(), List.of(fis));
        edt(() -> v.refresh().button().doClick(0));
        answer();
        assertEquals(ClientWindow.CONTEXT, read(window::showing), "continua na organizacao");
        assertEquals(NavText.WORKSPACE_PLACEHOLDER, read(() -> v.workspaceBox().getSelectedItem()), "o ambiente que sobrou nao e escolhido no lugar");
        assertEquals(0, read(() -> v.rows().size()), "as automacoes do ambiente perdido saem da tela");
        assertEquals("Você não tem mais acesso ao ambiente “Financeiro”. Selecione outro ambiente.", read(() -> v.refresh().noteText()));
        evidence("sakura-dark-atualizar-ambiente-revogado");
    }

    @Test
    void organizacaoRevogadaVoltaAoSeletorComMensagem() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        pick(fin);
        d.organizations = List.of(b);
        edt(() -> v.refresh().button().doClick(0));
        answer();
        assertEquals(ClientWindow.ORGANIZATIONS, read(window::showing));
        assertEquals(List.of("Beta Serviços"), cards(), "a outra organizacao nao e aberta sozinha");
        assertEquals("Você não tem mais acesso à organização “Acme Indústria”. Ela saiu da lista.", read(() -> window.organizationsView().refresh().noteText()));
        assertEquals(0, read(() -> v.rows().size()), "nada da organizacao perdida fica na tela dela");
        assertEquals("", read(() -> v.nameLabel().getText()));
        evidence("sakura-dark-atualizar-organizacao-revogada");
    }

    @Test
    void trocaRapidaDeOrganizacaoComRespostaAtrasadaNaoMisturaContextos() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        pick(fin);
        d.automations.put(fin.id(), FakeDirectory.listing(true, true, FakeDirectory.automation(8, "Só existe na Acme", 1, null)));
        edt(() -> v.refresh().button().doClick(0));
        Runnable atrasada = background.take(0);
        // a pessoa volta e entra na outra organizacao antes de a resposta chegar
        edt(() -> v.backButton().doClick(0));
        choose(b);
        pick(deB);
        assertEquals(List.of("Folha de pagamento"), rows());
        edt(atrasada::run);
        settle();
        assertEquals("Beta Serviços", read(() -> v.nameLabel().getText()));
        assertEquals(List.of("Folha de pagamento"), rows(), "a resposta atrasada da Acme nao aparece na Beta");
        assertEquals("", read(() -> v.refresh().noteText()), "nem vira 'Dados atualizados' de outra organizacao");
        assertEquals("Atualizar", read(() -> v.refresh().button().getText()));

        // outra conta: a resposta atrasada da conta anterior nao repovoa nada
        edt(() -> v.refresh().button().doClick(0));
        Runnable daContaAnterior = background.take(0);
        edt(window::requestSignOut);
        assertEquals(ClientWindow.SIGN_IN, read(window::showing));
        FakeSession outra = new FakeSession();
        outra.directory.organizations = List.of(FakeDirectory.org(50, "Da outra conta"));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(outra));
        answer();
        edt(daContaAnterior::run);
        settle();
        assertEquals(List.of("Da outra conta"), cards(), "so os dados da conta atual");
        assertEquals("", read(() -> window.organizationsView().refresh().noteText()));
    }

    @Test
    void atualizarNaoCriaNaoRenomeiaENaoExecuta() throws Exception {
        ContextView v = window.contextView();
        choose(a);
        pick(fin);
        d.calls.clear();
        edt(() -> v.refresh().button().doClick(0));
        answer();
        assertEquals(List.of("workspaces " + a.id(), "automations " + a.id() + " " + fin.id()), d.calls, "so leituras");
        assertTrue(d.renames.isEmpty());
        assertEquals("", read(v::noticeText), "nenhuma acao local foi disparada");
    }
}
