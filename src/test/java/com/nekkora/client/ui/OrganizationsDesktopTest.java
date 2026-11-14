package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Directory.Failure;
import com.nekkora.client.nav.FakeDirectory;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Profile;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.signin.SignInState;
import com.nekkora.client.signin.SignInState.Notice;
import com.nekkora.client.signin.SignInState.Phase;
import com.nekkora.client.theme.ClientTheme;
import com.nekkora.client.theme.Rgb;

/**
 * Seletor de organizacoes, avatar e menu (AGENT-CLIENT-NAVEGACAO-001 T02), na janela real, com a entrada e a Platform
 * SIMULADAS ({@link FakeSignIn}, {@link FakeDirectory}). Os eventos ficam na propria janela de teste: nenhuma tecla ou
 * clique vai para o sistema.
 */
@Tag("desktop")
class OrganizationsDesktopTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");

    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private FakeSession session;
    private final AtomicInteger closes = new AtomicInteger();

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
            window = new ClientWindow(flow, t -> { }, closes::incrementAndGet, BuildInfo.load(), background);
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        settle();
    }

    @AfterEach
    void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            window.avatar().closeMenu();
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

    /** Entra (entrada simulada) com as organizacoes dadas; as consultas ficam pendentes ate {@link #answer()}. */
    private void signIn(List<Organization> organizations) throws Exception {
        session = new FakeSession();
        session.directory.organizations = organizations;
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
    }

    /** A Platform simulada responde o que esta pendente. */
    private void answer() throws Exception {
        background.runAll();
        settle();
        edt(window::validate);
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

    private Rectangle in(Component c, Component reference) throws Exception {
        return read(() -> SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), reference));
    }

    private static String theme(ClientTheme t) {
        return t.libId().equals("light") ? "nekkora-light" : "sakura-dark";
    }

    // ------------------------------------------------------------------ estados da consulta

    @Test
    void carregandoFalhaVazioEListaSaoEstadosDiferentes() throws Exception {
        OrganizationsView v = window.organizationsView();
        signIn(List.of());
        assertEquals(ClientWindow.ORGANIZATIONS, read(window::showing));
        assertEquals("Selecione sua organização", read(() -> v.title().getText()));
        assertEquals(OrganizationsView.LOADING, read(v::showing), "enquanto carrega, NAO mostra o estado vazio");
        assertEquals("", read(v::emptyText));
        evidence("sakura-dark-organizacoes-carregando");

        // falha: mensagem propria e Tentar novamente; nunca "voce nao participa de nenhuma organizacao"
        for (Failure f : new Failure[] {Failure.NETWORK, Failure.RATE_LIMITED, Failure.SERVER, Failure.TIMEOUT, Failure.TLS, Failure.DENIED, Failure.INVALID}) {
            session.directory.organizationsFailure = f;
            answer();
            assertEquals(OrganizationsView.FAILED, read(v::showing), f.toString());
            assertEquals(NavText.of(f), read(v::failureText));
            assertEquals("", read(v::emptyText), f + ": falha nao e lista vazia");
            assertEquals(0, read(() -> v.cards().size()));
            assertTrue(read(() -> v.retryButton().isShowing() && v.retryButton().isEnabled()));
            assertEquals("Tentar novamente", read(() -> v.retryButton().getText()));
            assertFalse(NavText.of(f).matches("(?s).*(Exception|https?://|\\d{3}|token|JSON).*"), "sem detalhe tecnico: " + NavText.of(f));
            if (f == Failure.SERVER) {
                evidence("sakura-dark-organizacoes-erro");
            }
            edt(() -> v.retryButton().doClick(0));
            assertEquals(OrganizationsView.LOADING, read(v::showing), "tentar novamente volta a carregar");
        }
        // vazio legitimo
        session.directory.organizationsFailure = null;
        answer();
        assertEquals(OrganizationsView.EMPTY, read(v::showing));
        assertEquals("Você ainda não participa de nenhuma organização.", read(v::emptyText));
        assertFalse(read(() -> v.retryButton().isShowing()));
        evidence("sakura-dark-organizacoes-nenhuma");
        assertTrue(read(() -> v.footer().isShowing()), "rodape presente tambem sem organizacoes");
    }

    @Test
    void tentarNovamenteRepetidoFazUmaConsultaSo() throws Exception {
        OrganizationsView v = window.organizationsView();
        signIn(List.of());
        session.directory.organizationsFailure = Failure.NETWORK;
        answer();
        int antes = session.directory.organizationCalls.get();
        edt(() -> {
            for (int i = 0; i < 8; i++) {
                v.retryButton().doClick(0);
            }
        });
        answer();
        assertEquals(antes + 1, session.directory.organizationCalls.get());
    }

    // ------------------------------------------------------------------ grade

    @Test
    void umaOrganizacaoFicaCentralizadaEEsperaAEscolha() throws Exception {
        OrganizationsView v = window.organizationsView();
        signIn(FakeDirectory.orgs(1));
        answer();
        assertEquals(OrganizationsView.LIST, read(v::showing));
        assertEquals(ClientWindow.ORGANIZATIONS, read(window::showing), "uma organizacao so NAO entra sozinha");
        assertNull(read(() -> window.contextView().organization()));
        OrganizationCard card = read(() -> v.cards().get(0));
        Rectangle b = in(card, v);
        assertEquals(read(v::getWidth) / 2.0, b.getCenterX(), 1.5, "cartao unico centralizado");
        evidence("sakura-dark-organizacoes-1");
        edt(card::choose);
        assertEquals(ClientWindow.CONTEXT, read(window::showing));
        assertEquals(FakeDirectory.id(1), read(() -> window.contextView().organization().id()));
    }

    @Test
    void duasColunasPorLinhasDaEsquerdaParaADireita() throws Exception {
        OrganizationsView v = window.organizationsView();
        for (ClientTheme theme : ClientTheme.values()) {
            edt(() -> Themes.apply(theme));
            for (int n : new int[] {2, 3, 9}) {
                if (read(window::showing).equals(ClientWindow.ORGANIZATIONS)) {
                    edt(() -> flow.signOut());
                }
                signIn(FakeDirectory.orgs(n));
                answer();
                List<OrganizationCard> cards = read(v::cards);
                assertEquals(n, cards.size());
                List<Rectangle> rs = new ArrayList<>();
                for (OrganizationCard c : cards) {
                    rs.add(in(c, v.grid()));
                }
                for (int i = 0; i < n; i++) {
                    assertEquals(FakeDirectory.id(i + 1), cards.get(i).organization().id(), "ordem da Platform preservada");
                    Rectangle r = rs.get(i);
                    if (i % 2 == 1) {
                        assertEquals(rs.get(i - 1).y, r.y, "par " + i + ": mesma linha do anterior");
                        assertTrue(r.x >= rs.get(i - 1).x + rs.get(i - 1).width, "o segundo fica a direita");
                    } else if (i > 0) {
                        assertTrue(r.y >= rs.get(i - 1).y + rs.get(i - 1).height, "linha nova abaixo da anterior");
                        assertEquals(rs.get(0).x, r.x, "comeca na coluna da esquerda");
                    }
                    for (int j = 0; j < i; j++) {
                        assertFalse(r.intersects(rs.get(j)), "cartoes " + j + " e " + i + " nao se sobrepoem");
                    }
                    assertTrue(r.x >= 0 && r.x + r.width <= read(() -> v.grid().getWidth()), "cartao inteiro na largura");
                }
                // bloco das duas colunas centralizado
                assertEquals(read(() -> v.grid().getWidth()) / 2.0, (rs.get(0).x + rs.get(1).x + rs.get(1).width) / 2.0, 1.5);
                evidence(theme(theme) + "-organizacoes-" + n);
            }
            edt(() -> flow.signOut());
        }
    }

    @Test
    void muitasOrganizacoesRolamSoOConteudoEORodapeFica() throws Exception {
        OrganizationsView v = window.organizationsView();
        signIn(FakeDirectory.orgs(40));
        answer();
        assertTrue(read(() -> v.grid().getHeight() > v.scroll().getViewport().getHeight()), "ha mais conteudo que a area visivel");
        Rectangle rodape = in(v.footer(), v);
        Rectangle titulo = in(v.title(), v);
        edt(() -> v.scroll().getVerticalScrollBar().setValue(v.scroll().getVerticalScrollBar().getMaximum()));
        assertTrue(read(() -> v.scroll().getVerticalScrollBar().getValue() > 0), "rolou");
        assertEquals(rodape, in(v.footer(), v), "o rodape nao rola");
        assertEquals(titulo, in(v.title(), v), "o titulo nao rola");
        assertTrue(read(() -> v.footer().isShowing()));
        Rectangle area = in(v.scroll(), v);
        assertTrue(area.y >= titulo.y + titulo.height && area.y + area.height <= rodape.y, "a area rolavel fica entre o titulo e o rodape");
        evidence("sakura-dark-organizacoes-40-rolado");
        // o ultimo cartao e alcancavel e, com foco, entra na area visivel
        edt(() -> v.scroll().getVerticalScrollBar().setValue(0));
        OrganizationCard ultimo = read(() -> v.cards().get(39));
        edt(ultimo::requestFocusInWindow);
        Rectangle visivel = read(() -> v.scroll().getViewport().getViewRect());
        assertTrue(visivel.intersects(read(ultimo::getBounds)), "o cartao com foco e levado para a area visivel");
    }

    @Test
    void nomesLongosEHomonimasSemCorte() throws Exception {
        OrganizationsView v = window.organizationsView();
        String longo = "Companhia Brasileira de Automação de Processos Administrativos e Financeiros do Nordeste Sociedade Anônima";
        String semEspaco = "Organizacaocomumnomeenormesemnenhumespacoparaquebrarnomeiodalinha";
        List<Organization> orgs = List.of(new Organization(FakeDirectory.id(1), "Acme", "acme", false), new Organization(FakeDirectory.id(2), "Acme", "acme-filial", false),
                new Organization(FakeDirectory.id(3), longo, "cbapaf", true), new Organization(FakeDirectory.id(4), semEspaco, null, false));
        signIn(orgs);
        answer();
        List<OrganizationCard> cards = read(v::cards);
        // homonimas: mesmo nome, identificadores visiveis diferentes
        assertEquals("Acme, identificador acme", read(() -> cards.get(0).getAccessibleContext().getAccessibleName()));
        assertEquals("Acme, identificador acme-filial", read(() -> cards.get(1).getAccessibleContext().getAccessibleName()));
        assertNotEquals(read(() -> cards.get(0).labelLines(cards.get(0).getWidth())), read(() -> cards.get(1).labelLines(cards.get(1).getWidth())));
        // nome longo: todas as palavras, em varias linhas, cada linha dentro do cartao
        OrganizationCard c = cards.get(2);
        List<String> linhas = read(() -> c.nameLines(c.getWidth()));
        assertEquals(longo, String.join(" ", linhas), "nenhuma palavra cortada");
        assertTrue(linhas.size() > 2);
        int larguraDoTexto = read(c::getWidth) - 2 * OrganizationCard.PAD - OrganizationCard.TILE - ClientTheme.SPACE_3;
        for (String l : linhas) {
            assertTrue(read(() -> c.getFontMetrics(Themes.font(ClientTheme.FONT_SIZE_MD, true)).stringWidth(l)) <= larguraDoTexto, "linha cabe: " + l);
        }
        assertEquals(read(() -> c.heightFor(c.getWidth())), read(c::getHeight), 0, "o cartao tem a altura que o nome pede");
        assertTrue(read(() -> c.getAccessibleContext().getAccessibleName()).endsWith(", aguardando ativação"));
        assertEquals(NavText.AWAITING_ACTIVATION_HINT, read(c::getToolTipText));
        // palavra maior que a linha e partida, sem sair do cartao
        OrganizationCard d = cards.get(3);
        assertEquals(semEspaco, String.join("", read(() -> d.nameLines(d.getWidth()))));
        assertEquals("00000000", read(() -> d.labelLines(d.getWidth()).get(0)), "sem handle: comeco do identificador");
        evidence("sakura-dark-organizacoes-nomes-longos");
    }

    @Test
    void janelaEstreitaUsaUmaColunaNaMesmaOrdemSemCortar() throws Exception {
        OrganizationsView v = window.organizationsView();
        signIn(FakeDirectory.orgs(5));
        answer();
        Dimension min = read(window::getMinimumSize);
        edt(() -> window.setSize(min));
        edt(window::validate);
        int w = read(() -> v.grid().getWidth());
        assertEquals(1, CardGrid.columns(w, 5), "largura minima da janela: uma coluna");
        List<OrganizationCard> cards = read(v::cards);
        int y = -1;
        for (int i = 0; i < cards.size(); i++) {
            Rectangle r = in(cards.get(i), v.grid());
            assertEquals(FakeDirectory.id(i + 1), cards.get(i).organization().id());
            assertTrue(r.y > y, "um abaixo do outro, na mesma ordem");
            assertTrue(r.x >= 0 && r.x + r.width <= w, "cartao inteiro na largura");
            y = r.y;
        }
        assertTrue(read(() -> v.footer().isShowing()));
        Rectangle rodape = in(v.footer(), v);
        assertEquals(read(v::getHeight), rodape.y + rodape.height, "rodape no pe");
        evidence("sakura-dark-organizacoes-janela-minima");
        // a regra das colunas, para as larguras de escala alta (a largura logica cai quando a escala sobe)
        assertEquals(2, CardGrid.columns(480, 5));
        assertEquals(1, CardGrid.columns(2 * CardGrid.CARD_MIN + CardGrid.GAP + 2 * CardGrid.PAD - 1, 5));
        assertEquals(1, CardGrid.columns(1200, 1), "um cartao so: uma coluna, centralizada");
    }

    // ------------------------------------------------------------------ teclado e acessibilidade

    @Test
    void cartaoInteiroTemFocoEAcionaPeloTeclado() throws Exception {
        OrganizationsView v = window.organizationsView();
        signIn(FakeDirectory.orgs(3));
        answer();
        List<OrganizationCard> cards = read(v::cards);
        edt(() -> v.focusFirst());
        assertTrue(read(() -> cards.get(0).isFocusOwner()), "o foco vai para o primeiro cartao");
        assertEquals(javax.accessibility.AccessibleRole.PUSH_BUTTON, read(() -> cards.get(0).getAccessibleContext().getAccessibleRole()));
        // Tab segue a ordem dos cartoes (eventos restritos a esta janela)
        edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().focusNextComponent(cards.get(0)));
        assertTrue(read(() -> cards.get(1).isFocusOwner()), "Tab: segundo cartao");
        // anel de foco visivel: o canto do cartao com foco muda de cor
        BufferedImage com = paint(cards.get(1));
        edt(() -> cards.get(2).requestFocusInWindow());
        BufferedImage sem = paint(cards.get(1));
        assertNotEquals(com.getRGB(com.getWidth() / 2, 1), sem.getRGB(sem.getWidth() / 2, 1), "anel de foco pintado");
        // Enter no cartao com foco escolhe AQUELE cartao
        edt(() -> cards.get(2).dispatchEvent(new java.awt.event.KeyEvent(cards.get(2), java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                java.awt.event.KeyEvent.VK_ENTER, '\n')));
        assertEquals(ClientWindow.CONTEXT, read(window::showing));
        assertEquals(FakeDirectory.id(3), read(() -> window.contextView().organization().id()));
        // voltar pelo teclado: o botao de voltar recebe o foco ao entrar e tem nome acessivel
        assertTrue(read(() -> window.contextView().backButton().isFocusOwner()));
        assertEquals("Voltar para as organizações", read(() -> window.contextView().backButton().getAccessibleContext().getAccessibleName()));
        edt(() -> window.contextView().backButton().doClick(0));
        assertEquals(ClientWindow.ORGANIZATIONS, read(window::showing));
        assertNull(read(() -> window.contextView().organization()), "ao voltar, nenhuma organizacao fica escolhida");
        assertEquals(3, read(() -> v.cards().size()), "a lista continua, sem nova consulta");
        assertEquals(1, session.directory.organizationCalls.get());
        // Espaco tambem escolhe
        OrganizationCard primeiro = read(() -> v.cards().get(0));
        edt(() -> primeiro.dispatchEvent(new java.awt.event.KeyEvent(primeiro, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                java.awt.event.KeyEvent.VK_SPACE, ' ')));
        assertEquals(FakeDirectory.id(1), read(() -> window.contextView().organization().id()));
        assertEquals("Organização 01", read(() -> window.contextView().nameLabel().getText()));
    }

    private BufferedImage paint(Component c) throws Exception {
        return read(() -> {
            BufferedImage i = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = i.createGraphics();
            g.setColor(Themes.color(Themes.current().bg()));
            g.fillRect(0, 0, i.getWidth(), i.getHeight());
            c.paint(g);
            g.dispose();
            return i;
        });
    }

    @Test
    void contrasteDosCartoesNosDoisTemas() {
        for (ClientTheme t : ClientTheme.values()) {
            assertTrue(Rgb.contrast(t.text(), t.surface()) >= 7.0, t + " nome sobre o cartao");
            assertTrue(Rgb.contrast(t.textSecondary(), t.surface()) >= 4.5, t + " identificador sobre o cartao");
            assertTrue(Rgb.contrast(t.text(), t.surfaceHover()) >= 4.5, t + " nome sobre o cartao em hover");
            assertTrue(Rgb.contrast(t.accent(), t.accentSoft()) >= 3.0, t + " inicial no quadro (apoio visual)");
            assertTrue(Rgb.contrast(t.text(), t.accentSoft()) >= 4.5, t + " iniciais no avatar");
            assertTrue(Rgb.contrast(t.text(), t.bg()) >= 7.0, t + " titulo");
        }
    }

    // ------------------------------------------------------------------ avatar e menu

    @Test
    void avatarComIniciaisDoNomeOuDesenhoNeutroNuncaDoEmail() throws Exception {
        AvatarButton a = window.avatar();
        assertFalse(read(a::isVisible), "antes de entrar nao ha avatar");
        signIn(FakeDirectory.orgs(2));
        assertTrue(read(a::isVisible));
        assertEquals("", read(a::initials), "perfil ainda carregando: desenho neutro");
        assertEquals("Menu do usuário: Usuário", read(() -> a.getAccessibleContext().getAccessibleName()));
        answer();
        assertEquals("AS", read(a::initials));
        assertEquals("Menu do usuário: Ana Souza", read(() -> a.getAccessibleContext().getAccessibleName()));
        evidence("sakura-dark-avatar-iniciais");

        for (Object[] caso : new Object[][] {{"Ana", "A"}, {"ana maria de souza", "AS"}, {null, ""}, {"   ", ""}}) {
            edt(() -> flow.signOut());
            session = new FakeSession();
            session.directory.profile = new Profile("zeca@empresa.test", (String) caso[0]);
            edt(() -> window.signInView().signInButton().doClick(0));
            edt(() -> fake.last().listener.signedIn(session));
            answer();
            assertEquals(caso[1], read(a::initials), "nome " + caso[0]);
            if ("".equals(caso[1])) {
                assertEquals("Menu do usuário: zeca@empresa.test", read(() -> a.getAccessibleContext().getAccessibleName()), "sem nome: identifica pelo e-mail, sem iniciais");
                evidence("sakura-dark-avatar-neutro");
            }
        }
        // perfil que nao carregou: neutro, e as organizacoes continuam
        edt(() -> flow.signOut());
        session = new FakeSession();
        session.directory.profileFailure = Failure.SERVER;
        session.directory.organizations = FakeDirectory.orgs(1);
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        answer();
        assertEquals("", read(a::initials));
        assertEquals(OrganizationsView.LIST, read(() -> window.organizationsView().showing()));
        // o contrato nao tem foto: o avatar nao tem imagem em nenhum caso
        assertNull(read(a::getIcon));
    }

    @Test
    void menuNaOrdemDefinidaComConfiguracoesEAjudaIndisponiveis() throws Exception {
        signIn(FakeDirectory.orgs(1));
        answer();
        for (ClientTheme theme : ClientTheme.values()) {
            edt(() -> Themes.apply(theme));
            JPopupMenu menu = read(() -> window.avatar().menu());
            List<String> itens = read(() -> {
                List<String> out = new ArrayList<>();
                for (Component c : menu.getComponents()) {
                    if (c instanceof JSeparator) {
                        out.add("---");
                    } else if (c instanceof JMenuItem i) {
                        out.add(i.getText() + (i.isEnabled() ? "" : " (desabilitado)"));
                    } else if (c instanceof JLabel l) {
                        out.add("[" + l.getText() + "]");
                    }
                }
                return out;
            });
            assertEquals(List.of("[Ana Souza]", "---", "Configurações (desabilitado)", "Ajuda (desabilitado)", "---", "Terminar sessão", "---", "Sair"), itens,
                    "nome, divisor, Configuracoes, Ajuda, divisor, Terminar sessao, divisor, Sair; sem Empresa (nao ha campo real)");
            for (Component c : read(menu::getComponents)) {
                if (c instanceof JMenuItem i && !i.isEnabled()) {
                    assertEquals("Indisponível nesta versão", read(() -> i.getAccessibleContext().getAccessibleDescription()));
                    assertEquals("Indisponível nesta versão", read(i::getToolTipText));
                    assertEquals(0, read(() -> i.getActionListeners().length), i.getText() + ": sem acao nem tela");
                }
            }
            // aberto de verdade, ancorado no avatar (o menu e uma janela propria: a captura dele e feita a parte)
            edt(() -> window.avatar().doClick(0));
            settle();
            BufferedImage aberto = read(() -> {
                JPopupMenu m = window.avatar().menu();
                m.setSize(m.getPreferredSize());
                m.doLayout();
                BufferedImage i = new BufferedImage(m.getWidth() * 2, m.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = i.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.scale(2, 2);
                m.paint(g);
                g.dispose();
                return i;
            });
            Files.createDirectories(EVIDENCE);
            ImageIO.write(aberto, "png", EVIDENCE.resolve(theme(theme) + "-menu-do-usuario-200.png").toFile());
            edt(() -> window.avatar().closeMenu());
        }
    }

    @Test
    void terminarSessaoVoltaParaAEntradaEApagaOQueEstavaNaTela() throws Exception {
        signIn(FakeDirectory.orgs(3));
        answer();
        edt(() -> window.organizationsView().cards().get(1).choose());
        assertEquals(ClientWindow.CONTEXT, read(window::showing));
        JPopupMenu menu = read(() -> window.avatar().menu());
        JMenuItem terminar = read(() -> (JMenuItem) menu.getComponent(5));
        assertEquals("Terminar sessão", read(terminar::getText));
        edt(() -> terminar.doClick(0));
        assertEquals(new SignInState(Phase.IDLE, Notice.SIGNED_OUT), flow.state());
        assertEquals(1, session.ended, "token descartado e encerramento pedido a Platform");
        assertEquals(ClientWindow.SIGN_IN, read(window::showing));
        assertEquals("Sessão terminada neste aplicativo.", read(() -> window.signInView().messageText()));
        assertFalse(read(() -> window.avatar().isVisible()), "sem sessao, sem avatar");
        assertEquals(0, read(() -> window.organizationsView().cards().size()), "nenhum cartao da sessao anterior fica na tela");
        assertNull(read(() -> window.contextView().organization()));
        assertEquals("", read(() -> window.contextView().nameLabel().getText()));
        assertEquals(0, closes.get(), "terminar sessao nao fecha o aplicativo");

        // outra pessoa entra em seguida: nada da anterior aparece
        session = new FakeSession();
        session.directory.profile = new Profile("bia@empresa.test", "Bia Lima");
        session.directory.organizations = List.of(FakeDirectory.org(7, "Outra"));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        assertEquals(OrganizationsView.LOADING, read(() -> window.organizationsView().showing()));
        assertEquals(0, read(() -> window.organizationsView().cards().size()));
        assertEquals("", read(() -> window.avatar().initials()));
        answer();
        assertEquals("BL", read(() -> window.avatar().initials()));
        assertEquals(List.of(FakeDirectory.id(7)), read(() -> window.organizationsView().cards().stream().map(c -> c.organization().id()).toList()));
    }

    @Test
    void sairEncerraOAplicativoPeloMesmoCaminhoDeFecharAJanela() throws Exception {
        signIn(FakeDirectory.orgs(1));
        answer();
        JPopupMenu menu = read(() -> window.avatar().menu());
        JMenuItem sair = read(() -> (JMenuItem) menu.getComponent(7));
        assertEquals("Sair", read(sair::getText));
        edt(() -> sair.doClick(0));
        assertEquals(1, closes.get(), "Sair pede o encerramento do aplicativo (nao apenas esconde a janela)");
        assertEquals(Phase.SIGNED_IN, flow.state().phase(), "quem encerra a sessao e o encerramento do aplicativo, que o teste nao executa");
    }

    // ------------------------------------------------------------------ respostas atrasadas e sessao

    @Test
    void respostaDeUmaSessaoAnteriorNaoApareceNaSeguinte() throws Exception {
        signIn(FakeDirectory.orgs(4));
        // as consultas da primeira sessao ficam pendentes; a pessoa termina a sessao e outra entra
        List<Runnable> daPrimeira = new ArrayList<>(background.pending);
        background.pending.clear();
        edt(() -> flow.signOut());
        FakeSession outra = new FakeSession();
        outra.directory.profile = new Profile("bia@empresa.test", "Bia Lima");
        outra.directory.organizations = List.of(FakeDirectory.org(9, "Da Bia"));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(outra));
        // agora a resposta atrasada da primeira sessao chega
        daPrimeira.forEach(Runnable::run);
        settle();
        assertEquals(OrganizationsView.LOADING, read(() -> window.organizationsView().showing()), "a resposta antiga nao preenche a tela nova");
        assertEquals("", read(() -> window.avatar().initials()));
        answer();
        assertEquals(List.of(FakeDirectory.id(9)), read(() -> window.organizationsView().cards().stream().map(c -> c.organization().id()).toList()));
        assertEquals("BL", read(() -> window.avatar().initials()));
    }

    @Test
    void sessaoExpiradaComOSeletorAbertoVoltaParaAEntradaENovaEntradaFunciona() throws Exception {
        signIn(FakeDirectory.orgs(2));
        answer();
        edt(session::expire);
        assertEquals(ClientWindow.SIGN_IN, read(window::showing));
        assertEquals("Sua sessão terminou. Entre novamente.", read(() -> window.signInView().messageText()));
        assertEquals(0, read(() -> window.organizationsView().cards().size()));
        signIn(FakeDirectory.orgs(1));
        answer();
        assertEquals(ClientWindow.ORGANIZATIONS, read(window::showing));
        assertEquals(1, read(() -> window.organizationsView().cards().size()));
    }

    @Test
    void organizacaoEscolhidaMostraONomeNoTopoComVoltarERodape() throws Exception {
        for (ClientTheme theme : ClientTheme.values()) {
            edt(() -> Themes.apply(theme));
            signIn(List.of(new Organization(FakeDirectory.id(1), "Acme Indústria e Comércio", "acme", false), FakeDirectory.org(2, "Beta")));
            answer();
            edt(() -> window.organizationsView().cards().get(0).choose());
            ContextView c = window.contextView();
            assertEquals("Acme Indústria e Comércio", read(() -> c.nameLabel().getText()));
            Rectangle voltar = in(c.backButton(), c);
            Rectangle nome = in(c.nameLabel(), c);
            assertTrue(voltar.x + voltar.width <= nome.x, "a seta de voltar fica a esquerda do nome");
            assertTrue(read(() -> c.footer().isShowing()));
            Rectangle rodape = in(c.footer(), c);
            assertEquals(read(c::getHeight), rodape.y + rodape.height);
            assertTrue(read(() -> window.avatar().isShowing()), "avatar e menu continuam no topo");
            evidence(theme(theme) + "-organizacao-escolhida");
            edt(() -> flow.signOut());
        }
    }
}
