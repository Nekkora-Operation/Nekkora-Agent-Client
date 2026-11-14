package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.signin.SignInState;
import com.nekkora.client.signin.SignInState.Notice;
import com.nekkora.client.signin.SignInState.Phase;
import com.nekkora.client.theme.ClientTheme;
import com.nekkora.client.theme.Rgb;

/**
 * A janela real do Client, com a entrada SIMULADA dos testes ({@link FakeSignIn}; nao existe no aplicativo). Os eventos
 * sao entregues ao proprio componente ({@code doClick}, {@code dispatchEvent}): nenhuma tecla ou clique passa pelo
 * sistema, e nada atinge outras janelas. As imagens de evidencia sao pintadas em memoria ({@code paint}), nas escalas
 * 100%, 150% e 200%, sem captura de tela. Precisa de uma area de trabalho: {@code -Pdesktop}.
 */
@Tag("desktop")
class WindowDesktopTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");

    private FakeSignIn fake;
    private SignInFlow flow;
    private ClientWindow window;
    private final List<ClientTheme> chosen = new ArrayList<>();
    private final AtomicInteger closes = new AtomicInteger();

    @BeforeEach
    void open() throws Exception {
        fake = new FakeSignIn();
        SwingUtilities.invokeAndWait(() -> {
            Themes.apply(ClientTheme.SAKURA_DARK);
            flow = new SignInFlow(fake, r -> {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
            });
            window = new ClientWindow(flow, chosen::add, closes::incrementAndGet);
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        settle();
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

    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                out.add(type.cast(c));
            }
            if (c instanceof Container k) {
                out.addAll(all(k, type));
            }
        }
        return out;
    }

    /** Pinta a janela inteira (conteudo e barra de titulo do tema) numa imagem, na escala pedida. */
    private BufferedImage render(double scale) throws Exception {
        return read(() -> {
            Container root = window.getRootPane();
            BufferedImage img = new BufferedImage((int) Math.ceil(root.getWidth() * scale), (int) Math.ceil(root.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(scale, scale);
            root.paint(g);
            g.dispose();
            return img;
        });
    }

    private void evidence(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        for (double scale : new double[] {1.0, 1.5, 2.0}) {
            ImageIO.write(render(scale), "png", EVIDENCE.resolve(name + "-" + (int) (scale * 100) + ".png").toFile());
        }
    }

    private static Rgb pixel(BufferedImage img, int x, int y) {
        int v = img.getRGB(x, y);
        return new Rgb(v >> 16 & 0xff, v >> 8 & 0xff, v & 0xff);
    }

    // ------------------------------------------------------------------ composicao

    @Test
    void telaInicialTemSoLogoEEntrarSemCamposDeCredencial() throws Exception {
        SignInView v = window.signInView();
        assertEquals("Nekkora Agent", read(window::getTitle));
        assertEquals(ClientWindow.SIGN_IN, read(window::showing));
        assertTrue(read(() -> v.signInButton().isEnabled()));
        assertEquals("Entrar", read(() -> v.signInButton().getText()));
        assertFalse(read(() -> v.spinner().isVisible()));
        assertFalse(read(() -> v.cancelButton().isVisible()));
        assertEquals("", read(v::progressText));
        assertEquals("", read(v::messageText));
        // sem e-mail, senha ou codigo no desktop: nenhum campo de texto na janela
        assertEquals(List.of(), read(() -> all(window.getRootPane(), JTextComponent.class)));
        assertEquals(List.of(), read(() -> all(window.getRootPane(), JTextField.class)));
        // nenhum identificador tecnico nos rotulos
        for (JLabel l : read(() -> all(window.getRootPane(), JLabel.class))) {
            String t = String.valueOf(read(l::getText));
            assertFalse(t.matches("(?s).*(https?://|[0-9a-f]{8}-[0-9a-f]{4}|127\\.0\\.0\\.1).*"), t);
        }
        // os botoes da janela: Entrar, Cancelar (oculto) e a troca de tema, alem dos controles da barra de titulo
        List<String> proprios = read(() -> all(window.getRootPane(), JButton.class).stream()
                .filter(b -> b instanceof PrimaryButton || b instanceof GhostButton).map(b -> b.getClass().getSimpleName()).sorted().toList());
        assertEquals(List.of("GhostButton", "GhostButton", "PrimaryButton"), proprios);
        assertEquals(7, read(() -> window.getIconImages().size()), "icone oficial em todos os tamanhos");
        evidence("sakura-dark-inicial");
    }

    @Test
    void logoMantemAProporcaoEFicaCentralizadoAcimaDoBotao() throws Exception {
        SignInView v = window.signInView();
        LogoView logo = read(() -> all(v, LogoView.class).get(0));
        Rectangle l = read(() -> SwingUtilities.convertRectangle(logo.getParent(), logo.getBounds(), window.getContentPane()));
        Rectangle b = read(() -> SwingUtilities.convertRectangle(v.signInButton().getParent(), v.signInButton().getBounds(), window.getContentPane()));
        int largura = read(() -> window.getContentPane().getWidth());
        assertEquals(SignInView.LOGO_HEIGHT, l.height);
        assertEquals(852.0 / 128.0, l.width / (double) l.height, 0.02, "proporcao do lockup oficial");
        assertEquals(largura / 2.0, l.getCenterX(), 1.5, "logo centralizado");
        assertEquals(largura / 2.0, b.getCenterX(), 1.5, "Entrar centralizado");
        assertTrue(b.y > l.y + l.height, "Entrar abaixo do logo");
        // arquivo certo por tema e por escala: -dark no tema escuro, -light no claro; arquivo maior em telas com escala
        assertTrue(BrandAssets.lockupResource(ClientTheme.SAKURA_DARK, 128).contains("lockup-dark-"));
        assertTrue(BrandAssets.lockupResource(ClientTheme.NEKKORA_LIGHT, 128).contains("lockup-light-"));
        assertEquals(128, BrandAssets.lockup(ClientTheme.SAKURA_DARK, 44).getHeight());
        assertEquals(128, BrandAssets.lockup(ClientTheme.SAKURA_DARK, 88).getHeight());
        assertEquals(256, BrandAssets.lockup(ClientTheme.SAKURA_DARK, 132).getHeight());
        assertEquals(852.0 / 128.0, BrandAssets.lockup(ClientTheme.NEKKORA_LIGHT, 44).getWidth() / 128.0, 0.01);
    }

    // ------------------------------------------------------------------ rodape

    /** O rodape esta inteiro dentro da tela, no pe dela, abaixo de tudo o que a entrada mostra, sem encostar em nada. */
    private void assertFooterClear(String quando) throws Exception {
        SignInView v = window.signInView();
        SignInFooter f = read(v::footer);
        assertTrue(read(f::isShowing), quando + ": rodape visivel");
        Rectangle fb = read(() -> SwingUtilities.convertRectangle(f.getParent(), f.getBounds(), v));
        int h = read(v::getHeight);
        int w = read(v::getWidth);
        assertEquals(h, fb.y + fb.height, quando + ": rodape no pe da tela");
        assertTrue(fb.x >= 0 && fb.x + fb.width <= w && fb.y >= 0, quando + ": rodape inteiro");
        for (JLabel l : read(() -> all(f, JLabel.class))) {
            Rectangle lb = read(() -> SwingUtilities.convertRectangle(l.getParent(), l.getBounds(), v));
            assertTrue(lb.x >= 0 && lb.x + lb.width <= w && lb.y + lb.height <= h, quando + ": linha inteira: " + l.getText());
            assertEquals(read(() -> l.getPreferredSize().width), lb.width, quando + ": texto sem corte: " + l.getText());
            assertEquals(w / 2.0, lb.getCenterX(), 1.5, quando + ": centralizado: " + l.getText());
        }
        List<Component> acima = read(() -> List.<Component>of(all(v, LogoView.class).get(0), v.signInButton(), v.spinner(), v.cancelButton(), v.message()));
        for (Component c : acima) {
            if (!read(c::isShowing)) {
                continue;
            }
            Rectangle b = read(() -> SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), v));
            assertTrue(b.y + b.height <= fb.y, quando + ": " + c.getClass().getSimpleName() + " acima do rodape (" + (b.y + b.height) + " <= " + fb.y + ")");
        }
    }

    @Test
    void rodapeMostraAVersaoDoBuildEmTodosOsEstadosDaEntrada() throws Exception {
        SignInView v = window.signInView();
        String esperado = "Versão " + com.nekkora.client.BuildInfoTest.pomVersion();
        for (ClientTheme theme : ClientTheme.values()) {
            edt(() -> Themes.apply(theme));
            String p = theme.libId().equals("light") ? "nekkora-light" : "sakura-dark";
            JLabel versao = read(() -> v.footer().versionLabel());
            assertEquals(esperado, read(versao::getText));
            assertFalse(read(versao::getText).matches("(?s).*([0-9a-f]{7,}|https?://|localhost|laborat|dev\\b).*"), "sem commit nem ambiente no rodape");
            assertEquals(ClientTheme.FONT_SIZE_XS, read(() -> versao.getFont().getSize()), "fonte do rodape da lib, sem diminuir alem dela");
            assertEquals(Themes.color(theme.textSecondary()), read(versao::getForeground));
            assertTrue(Rgb.contrast(theme.textSecondary(), theme.bg()) >= 4.5, theme + ": contraste do rodape");
            // direitos: abaixo da versao, mesma fonte e cor, com o ano dos metadados do build
            JLabel direitos = read(() -> v.footer().rightsLabel());
            int ano = com.nekkora.client.BuildInfo.load().year().orElseThrow();
            assertEquals("© " + ano + " Nekkora. Todos os direitos reservados.", read(direitos::getText));
            assertEquals(List.of(versao, direitos), read(() -> all(v.footer(), JLabel.class)), "so as duas linhas, nessa ordem");
            assertTrue(read(() -> direitos.getY() >= versao.getY() + versao.getHeight()), "direitos abaixo da versao");
            assertEquals(ClientTheme.FONT_SIZE_XS, read(() -> direitos.getFont().getSize()));
            assertEquals(Themes.color(theme.textSecondary()), read(direitos::getForeground));
            assertFalse(read(direitos::isFocusable));
            assertFalse(read(versao::isFocusable) || read(() -> v.footer().isFocusable()), "o rodape nao entra na ordem do teclado");
            assertFooterClear(p + " inicial");
            evidence(p + "-rodape-inicial");

            edt(() -> v.signInButton().doClick(0));
            edt(() -> fake.last().listener.browserOpened());
            assertFooterClear(p + " aguardando");
            assertEquals(esperado, read(versao::getText));
            evidence(p + "-rodape-aguardando");

            edt(() -> fake.last().listener.failed(Notice.BROWSER_FAILED)); // a mensagem mais longa
            assertFooterClear(p + " erro");
            evidence(p + "-rodape-erro");

            Dimension antes = read(window::getSize);
            edt(() -> window.setSize(window.getMinimumSize()));
            edt(window::validate);
            assertFooterClear(p + " erro, janela minima");
            evidence(p + "-rodape-erro-janela-minima");
            edt(() -> window.setSize(antes));
            edt(window::validate);
        }
        // depois de entrar, a tela inicial continua vazia: o rodape e so da entrada
        edt(() -> v.signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(new FakeSession()));
        assertEquals(ClientWindow.HOME, read(window::showing));
        assertFalse(read(() -> v.footer().isShowing()));
        assertEquals(0, read(() -> all(window.getContentPane(), HomeView.class).get(0).getComponentCount()));
    }

    @Test
    void semMetadadosDoBuildORodapeDizDesenvolvimento() throws Exception {
        AtomicReference<ClientWindow> outra = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SignInFlow f = new SignInFlow(new FakeSignIn(), Runnable::run);
            ClientWindow w = new ClientWindow(f, t -> { }, () -> { }, com.nekkora.client.BuildInfo.DEVELOPMENT);
            w.setLocation(60, 60);
            w.setVisible(true);
            outra.set(w);
        });
        settle();
        try {
            assertEquals("Versão de desenvolvimento", read(() -> outra.get().signInView().footer().versionLabel().getText()));
            assertEquals("© " + java.time.Year.now(java.time.ZoneOffset.UTC).getValue() + " Nekkora. Todos os direitos reservados.",
                    read(() -> outra.get().signInView().footer().rightsLabel().getText()));
        } finally {
            SwingUtilities.invokeAndWait(() -> outra.get().dispose());
        }
    }

    // ------------------------------------------------------------------ estados

    @Test
    void estadosDaEntradaNosDoisTemas() throws Exception {
        SignInView v = window.signInView();
        for (ClientTheme theme : ClientTheme.values()) {
            edt(() -> Themes.apply(theme));
            String p = theme.libId().equals("light") ? "nekkora-light" : "sakura-dark";

            edt(() -> v.signInButton().doClick(0));
            assertEquals(Phase.OPENING_BROWSER, flow.state().phase());
            assertFalse(read(() -> v.signInButton().isEnabled()), "Entrar desabilitado ao abrir o navegador");
            assertEquals(SignInText.OPENING, read(v::progressText));
            assertFalse(read(() -> v.cancelButton().isVisible()));
            evidence(p + "-abrindo-navegador");

            edt(() -> fake.last().listener.browserOpened());
            assertEquals("Aguardando login no navegador...", read(v::progressText));
            assertTrue(read(() -> v.spinner().isVisible() && v.spinner().spinning()), "spinner girando");
            assertTrue(read(() -> v.cancelButton().isVisible()));
            assertFalse(read(() -> v.signInButton().isEnabled()));
            // o andamento fica ABAIXO do botao
            Rectangle botao = read(() -> SwingUtilities.convertRectangle(v.signInButton().getParent(), v.signInButton().getBounds(), v));
            Rectangle giro = read(() -> SwingUtilities.convertRectangle(v.spinner().getParent(), v.spinner().getBounds(), v));
            assertTrue(giro.y >= botao.y + botao.height, "spinner abaixo de Entrar");
            evidence(p + "-aguardando");

            edt(() -> v.cancelButton().doClick(0));
            assertEquals(new SignInState(Phase.IDLE, Notice.CANCELLED), flow.state());
            assertEquals("Entrada cancelada.", read(v::messageText), "cancelar no aplicativo: aviso neutro");
            assertEquals(Themes.color(theme.textSecondary()), read(() -> v.message().getForeground()), "cancelamento nao usa a cor de erro");
            assertEquals("Aviso da entrada", read(() -> v.message().getAccessibleContext().getAccessibleDescription()));
            assertTrue(read(() -> v.signInButton().isEnabled()));
            assertFalse(read(() -> v.spinner().spinning()), "spinner parado fora da espera");
            assertFalse(read(() -> v.cancelButton().isVisible()));
            evidence(p + "-cancelled");

            Rectangle logo = read(() -> SwingUtilities.convertRectangle(all(v, LogoView.class).get(0).getParent(), all(v, LogoView.class).get(0).getBounds(), v));
            for (Notice n : new Notice[] {Notice.DECLINED, Notice.ACCESS_DENIED, Notice.SERVER, Notice.NETWORK, Notice.EXPIRED, Notice.INVALID_RETURN, Notice.TLS,
                    Notice.BROWSER_FAILED, Notice.TIMEOUT, Notice.RATE_LIMITED, Notice.UNAVAILABLE, Notice.RETURN_UNAVAILABLE}) {
                edt(() -> v.signInButton().doClick(0));
                assertEquals("", read(v::messageText), n + ": a nova tentativa limpa a mensagem anterior");
                edt(() -> fake.last().listener.browserOpened());
                assertEquals("Aguardando login no navegador...", read(() -> v.getAccessibleContext().getAccessibleDescription()));
                edt(() -> fake.last().listener.failed(n));
                // resultado terminal: mensagem certa, loading parado, Entrar liberado, continua na entrada
                assertEquals(SignInText.of(n), read(v::messageText));
                assertEquals(ClientWindow.SIGN_IN, read(window::showing), n + ": nao abre a tela inicial");
                assertTrue(read(() -> v.signInButton().isEnabled()), n + ": pode tentar de novo");
                assertFalse(read(() -> v.spinner().isVisible() || v.spinner().spinning()), n + ": loading encerrado");
                assertEquals("", read(v::progressText), n + ": sem \"aguardando\"");
                assertFalse(read(() -> v.cancelButton().isVisible()), n + ": sem Cancelar");
                assertEquals(1, fake.last().cancelled, n + ": recursos da tentativa liberados");
                assertFalse(SignInText.of(n).matches("(?s).*(Exception|\\bat \\w+\\.|https?://|\\d{3}).*"), "sem detalhe tecnico: " + SignInText.of(n));
                // estado identificado sem depender da cor
                boolean neutro = SignInText.neutral(n);
                assertEquals(neutro ? "Aviso da entrada" : "Erro na entrada", read(() -> v.message().getAccessibleContext().getAccessibleDescription()));
                assertEquals(SignInText.of(n), read(() -> v.message().getAccessibleContext().getAccessibleName()));
                assertEquals(SignInText.of(n), read(() -> v.getAccessibleContext().getAccessibleDescription()));
                assertEquals(Themes.color(neutro ? theme.textSecondary() : theme.dangerText()), read(() -> v.message().getForeground()));
                // a mensagem fica inteira, abaixo do botao, sem encostar no logo nem sair da area dela
                WrappedText msg = read(v::message);
                assertEquals(SignInText.of(n), String.join(" ", read(msg::lines)), n + ": nenhuma palavra cortada");
                Rectangle mb = read(() -> SwingUtilities.convertRectangle(msg.getParent(), msg.getBounds(), v));
                Rectangle bb = read(() -> SwingUtilities.convertRectangle(v.signInButton().getParent(), v.signInButton().getBounds(), v));
                Rectangle area = read(() -> SwingUtilities.convertRectangle(msg.getParent().getParent(), msg.getParent().getBounds(), v));
                assertTrue(mb.y >= bb.y + bb.height, n + ": mensagem abaixo de Entrar");
                assertTrue(mb.y + mb.height <= area.y + area.height, n + ": mensagem dentro da area reservada (" + mb.height + " de " + area.height + ")");
                assertFalse(mb.intersects(logo) || mb.intersects(bb), n + ": sem sobreposicao");
                evidence(p + "-" + n.name().toLowerCase().replace('_', '-'));
            }

            // sucesso confirmado: tela inicial VAZIA
            edt(() -> v.signInButton().doClick(0));
            FakeSession session = new FakeSession();
            edt(() -> fake.last().listener.signedIn(session));
            assertEquals(ClientWindow.HOME, read(window::showing));
            HomeView home = read(() -> all(window.getContentPane(), HomeView.class).get(0));
            assertEquals(0, read(home::getComponentCount), "tela inicial sem nenhum componente");
            assertTrue(read(home::isShowing));
            evidence(p + "-inicio-vazio");

            // sessao encerrada: volta para a entrada, com aviso
            edt(session::expire);
            assertEquals(ClientWindow.SIGN_IN, read(window::showing));
            assertEquals("Sua sessão terminou. Entre novamente.", read(v::messageText));
            evidence(p + "-sessao-terminou");
        }
    }

    @Test
    void cliquesRepetidosGeramUmaUnicaTentativa() throws Exception {
        SignInView v = window.signInView();
        edt(() -> {
            for (int i = 0; i < 12; i++) {
                v.signInButton().doClick(0);
            }
        });
        assertEquals(1, fake.attempts.size());
        edt(() -> fake.last().listener.browserOpened());
        edt(() -> {
            for (int i = 0; i < 12; i++) {
                v.signInButton().doClick(0);
            }
        });
        assertEquals(1, fake.attempts.size());
    }

    @Test
    void respostaAtrasadaNaoAbreATelaInicial() throws Exception {
        SignInView v = window.signInView();
        edt(() -> v.signInButton().doClick(0));
        FakeSignIn.FakeAttempt antiga = fake.last();
        edt(() -> antiga.listener.browserOpened());
        edt(() -> v.cancelButton().doClick(0));
        FakeSession tardia = new FakeSession();
        edt(() -> antiga.listener.signedIn(tardia));
        assertEquals(ClientWindow.SIGN_IN, read(window::showing));
        assertEquals(1, tardia.ended);
    }

    // ------------------------------------------------------------------ teclado e foco

    @Test
    void tecladoEFocoVisivel() throws Exception {
        SignInView v = window.signInView();
        edt(() -> v.signInButton().requestFocusInWindow());
        for (int i = 0; i < 20 && !read(() -> v.signInButton().isFocusOwner()); i++) {
            Thread.sleep(50);
        }
        if (read(() -> v.signInButton().isFocusOwner())) {
            // anel de foco pintado: o canto do componente (fora do botao) muda de cor
            BufferedImage comFoco = render(1.0);
            Rectangle r = read(() -> SwingUtilities.convertRectangle(v.signInButton().getParent(), v.signInButton().getBounds(), window.getRootPane()));
            Rgb anel = pixel(comFoco, r.x + r.width / 2, r.y + 1);
            assertEquals(ClientTheme.SAKURA_DARK.focusRing(), anel, "anel de foco de 3 px com a cor do token");
            assertNotEquals(ClientTheme.SAKURA_DARK.bg(), anel);
        }
        // Espaco no botao focado (evento entregue ao proprio botao, nao ao sistema)
        edt(() -> {
            v.signInButton().dispatchEvent(new KeyEvent(v.signInButton(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' '));
            v.signInButton().dispatchEvent(new KeyEvent(v.signInButton(), KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' '));
        });
        assertEquals(1, fake.attempts.size(), "Espaco aciona Entrar");
        edt(() -> fake.last().listener.browserOpened());
        // ordem de foco na espera: Cancelar e a troca de tema sao alcancaveis por teclado
        assertTrue(read(() -> v.cancelButton().isFocusable() && window.themeToggle().isFocusable()));
        assertEquals(v.signInButton(), read(() -> window.getRootPane().getDefaultButton()), "Enter aciona Entrar na tela de entrada");
        edt(() -> v.cancelButton().dispatchEvent(new KeyEvent(v.cancelButton(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' ')));
        edt(() -> v.cancelButton().dispatchEvent(new KeyEvent(v.cancelButton(), KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' ')));
        assertEquals(new SignInState(Phase.IDLE, Notice.CANCELLED), flow.state(), "Espaco em Cancelar cancela");
        assertTrue(read(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager() != null));
    }

    // ------------------------------------------------------------------ tema

    @Test
    void trocaDeTemaEDiscretaAplicaOsTokensEAvisaParaPersistirSoOTema() throws Exception {
        GhostButton toggle = window.themeToggle();
        assertEquals("Tema: Sakura Dark. Clique para usar Nekkora Light.", read(toggle::getToolTipText));
        assertEquals(ClientTheme.SAKURA_DARK.bg(), pixel(render(1.0), 20, read(() -> window.getRootPane().getHeight()) - 20), "fundo do tema escuro");

        edt(() -> toggle.doClick(0));
        assertEquals(ClientTheme.NEKKORA_LIGHT, Themes.current());
        assertEquals(List.of(ClientTheme.NEKKORA_LIGHT), chosen);
        assertEquals("Tema: Nekkora Light. Clique para usar Sakura Dark.", read(toggle::getToolTipText));
        BufferedImage claro = render(1.0);
        int h = read(() -> window.getRootPane().getHeight());
        assertEquals(ClientTheme.NEKKORA_LIGHT.bg(), pixel(claro, 20, h - 20), "fundo do tema claro");
        // o botao Entrar usa o accent do tema
        SignInView v = window.signInView();
        Rectangle r = read(() -> SwingUtilities.convertRectangle(v.signInButton().getParent(), v.signInButton().getBounds(), window.getRootPane()));
        assertEquals(ClientTheme.NEKKORA_LIGHT.accent(), pixel(claro, r.x + 12, r.y + r.height / 2));
        evidence("nekkora-light-inicial");

        edt(() -> toggle.doClick(0));
        assertEquals(ClientTheme.SAKURA_DARK, Themes.current());
        assertEquals(List.of(ClientTheme.NEKKORA_LIGHT, ClientTheme.SAKURA_DARK), chosen);
        BufferedImage escuro = render(1.0);
        assertEquals(ClientTheme.SAKURA_DARK.accent(), pixel(escuro, r.x + 12, r.y + r.height / 2));
        // a troca durante a espera nao mexe na tentativa
        edt(() -> v.signInButton().doClick(0));
        edt(() -> fake.last().listener.browserOpened());
        edt(() -> toggle.doClick(0));
        assertEquals(Phase.WAITING, flow.state().phase());
        assertEquals(1, fake.attempts.size());
    }

    // ------------------------------------------------------------------ tamanho e escala

    @Test
    void redimensionarMantemOConteudoCentralizadoEInteiro() throws Exception {
        SignInView v = window.signInView();
        edt(() -> v.signInButton().doClick(0));
        edt(() -> fake.last().listener.failed(Notice.BROWSER_FAILED)); // a mensagem mais longa
        Dimension min = read(window::getMinimumSize);
        for (Dimension d : List.of(min, new Dimension(480, 600), new Dimension(900, 700), new Dimension(1400, 900))) {
            edt(() -> window.setSize(d));
            edt(window::validate);
            int w = read(() -> window.getContentPane().getWidth());
            int h = read(() -> window.getContentPane().getHeight());
            for (Component c : read(() -> List.<Component>of(all(v, LogoView.class).get(0), v.signInButton()))) {
                Rectangle b = read(() -> SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), window.getContentPane()));
                assertTrue(b.x >= 0 && b.y >= 0 && b.x + b.width <= w && b.y + b.height <= h, c.getClass().getSimpleName() + " inteiro em " + d);
                assertEquals(w / 2.0, b.getCenterX(), 1.5, c.getClass().getSimpleName() + " centralizado em " + d);
            }
            WrappedText msg = read(v::message);
            assertTrue(read(msg::isVisible));
            assertEquals(String.join(" ", read(msg::lines)), SignInText.of(Notice.BROWSER_FAILED), "nenhuma palavra cortada");
            for (String linha : read(msg::lines)) {
                assertTrue(read(() -> msg.getFontMetrics(msg.getFont()).stringWidth(linha)) <= read(msg::getWidth), "linha cabe: " + linha);
            }
            Rectangle mb = read(() -> SwingUtilities.convertRectangle(msg.getParent(), msg.getBounds(), window.getContentPane()));
            assertTrue(mb.x >= 0 && mb.x + mb.width <= w && mb.y + mb.height <= h, "mensagem inteira em " + d);
        }
        edt(() -> window.setSize(min));
        evidence("sakura-dark-janela-minima");
    }

    @Test
    void fecharAJanelaPedeOEncerramento() throws Exception {
        edt(() -> window.dispatchEvent(new java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING)));
        assertEquals(1, closes.get());
    }
}
