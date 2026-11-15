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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Automation;
import com.nekkora.client.nav.FakeDirectory;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Workspace;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.theme.ClientTheme;
import com.nekkora.client.theme.Rgb;

/**
 * "Gerar pacote novo" (antes "Nova automacao") e "Importar (.zip)" como botoes de verdade (AGENT-CLIENT-NAVEGACAO-002 T02), na janela real, com a
 * entrada e a Platform SIMULADAS. O que se confere e o problema relatado: presenca visual (superficie pintada, diferente
 * do fundo), dimensoes, sobreposicao e acesso - sem fixar pixels. Eventos restritos a janela de teste.
 */
@Tag("desktop")
class ActionButtonsDesktopTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");

    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private FakeSession session;
    private final Organization a = FakeDirectory.org(1, "Acme Indústria e Comércio de Componentes Eletrônicos do Brasil");
    private final Workspace fin = FakeDirectory.ws(1, "Financeiro");
    private final Workspace sem = FakeDirectory.ws(2, "Somente leitura");
    private final AtomicInteger created = new AtomicInteger();

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
        d.organizations = List.of(a);
        d.workspaces.put(a.id(), List.of(fin, sem));
        Automation[] muitas = new Automation[40];
        for (int i = 0; i < muitas.length; i++) {
            muitas[i] = FakeDirectory.automation(100 + i,
                    i == 0 ? "Conciliação bancária mensal das contas a pagar e a receber de todas as filiais do grupo" : String.format("Automação %02d", i + 1),
                    i + 1, "1.0." + i);
        }
        d.automations.put(fin.id(), FakeDirectory.listing(true, true, muitas));
        d.automations.put(sem.id(), FakeDirectory.listing(false, false, FakeDirectory.automation(9, "Folha", 2, "0.9.0")));
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(session));
        answer();
        edt(() -> window.contextView().setActions(actions(true)));
        edt(() -> window.organizationsView().cards().get(0).choose());
        answer();
    }

    @AfterEach
    void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            flow.close();
            window.dispose();
        });
    }

    /** As mesmas funcoes de antes, contadas; {@code available} falso: as acoes locais nao existem (versao sem T04). */
    private ContextView.Actions actions(boolean available) {
        return new ContextView.Actions() {
            @Override
            public Consumer<ContextView.Entry> run() {
                return null;
            }

            @Override
            public Consumer<ContextView.Entry> edit() {
                return null;
            }

            @Override
            public Runnable generate() {
                return available ? created::incrementAndGet : null;
            }
        };
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

    private void pick(Object item) throws Exception {
        edt(() -> window.contextView().workspaceBox().setSelectedItem(item));
        answer();
    }

    private Rectangle in(Component c) throws Exception {
        ContextView v = window.contextView();
        return read(() -> SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), v));
    }

    private BufferedImage shot(double scale) throws Exception {
        return read(() -> {
            Container root = window.getRootPane();
            BufferedImage i = new BufferedImage((int) Math.ceil(root.getWidth() * scale), (int) Math.ceil(root.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = i.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(scale, scale);
            root.paint(g);
            g.dispose();
            return i;
        });
    }

    private void evidence(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        for (double scale : new double[] {1.0, 1.5, 2.0}) {
            ImageIO.write(shot(scale), "png", EVIDENCE.resolve(name + "-" + (int) (scale * 100) + ".png").toFile());
        }
    }

    /** Cor pintada num ponto do botao (coordenadas do botao), na imagem da janela em 100%. */
    private Rgb painted(JButton b, int x, int y) throws Exception {
        BufferedImage img = shot(1.0);
        java.awt.Point p = read(() -> SwingUtilities.convertPoint(b, x, y, window.getRootPane()));
        int rgb = img.getRGB(p.x, p.y);
        return Rgb.hex(String.format("#%06x", rgb & 0xffffff));
    }

    private static double distance(Rgb x, Rgb y) {
        return Math.abs(x.r() - y.r()) + Math.abs(x.g() - y.g()) + Math.abs(x.b() - y.b());
    }

    // ------------------------------------------------------------------ aparencia de botao, nos dois temas

    @Test
    void gerarPacoteNovoEBotaoPrimarioComIconeERotuloNosDoisTemasESemImportarNaLista() throws Exception {
        ContextView v = window.contextView();
        pick(fin);
        ActionButton nova = read(v::generateButton);
        assertEquals(ActionButton.Kind.PRIMARY, nova.kind());
        assertEquals(ActionButton.Glyph.ADD, nova.glyph());
        assertEquals("Gerar pacote novo", read(nova::getText));
        assertEquals("Gerar pacote novo", read(() -> nova.getAccessibleContext().getAccessibleName()));
        // (AGENT-CLIENT-PACOTES-001) nao ha mais botao de importar na lista: so o de gerar
        List<String> acoes = read(() -> java.util.Arrays.stream(v.actionsBar().getComponents()).map(c -> ((JButton) c).getText()).toList());
        assertEquals(List.of("Gerar pacote novo"), acoes);
        for (ClientTheme t : ClientTheme.values()) {
            edt(() -> Themes.apply(t));
            edt(window::validate);
            String p = t.libId();
            Rectangle n = read(nova::getBounds);
            Rgb cor = painted(nova, 8, n.height / 2);
            assertTrue(distance(cor, t.accent()) <= 6, p + ": preenchido com a cor de acao (" + cor.toHex() + ")");
            assertTrue(Rgb.contrast(t.accent(), t.bg()) >= 3.0, p + ": superficie distinta do fundo: nao parece link");
            assertTrue(Rgb.contrast(nova.foreground(), nova.background()) >= 4.5, p + ": rotulo legivel");
            evidence(p + "-acoes-botoes");
        }
    }

    @Test
    void dimensoesAreaPropriaESemSobreposicao() throws Exception {
        ContextView v = window.contextView();
        pick(fin);
        for (Dimension d : List.of(new Dimension(900, 700), new Dimension(640, 520), read(window::getMinimumSize))) {
            edt(() -> window.setSize(d));
            edt(window::validate);
            ActionButton nova = read(v::generateButton);
            Rectangle n = in(nova);
            Rectangle barra = in(v.actionsBar());
            Rectangle lista = in(v.scroll());
            Rectangle rodape = in(v.footer());
            assertTrue(n.height >= ClientTheme.CONTROL_MD, "altura de controle em " + d);
            assertTrue(n.width >= read(() -> nova.getPreferredSize().width), "rotulo sem corte em " + d);
            assertTrue(barra.contains(n), "dentro da area das acoes em " + d);
            assertTrue(n.x >= ClientTheme.SPACE_4 && n.x + n.width <= read(v::getWidth) - ClientTheme.SPACE_4, "respiro lateral em " + d);
            assertTrue(lista.y + lista.height <= barra.y, "a lista termina antes das acoes em " + d);
            assertTrue(n.y + n.height <= rodape.y, "acao acima do rodape em " + d);
            assertTrue(read(() -> v.footer().isShowing()) && rodape.y + rodape.height <= read(v::getHeight), "versao e direitos visiveis em " + d);
            assertTrue(read(() -> v.rowList().getHeight() > v.scroll().getViewport().getHeight()), "lista rolavel em " + d);
            edt(() -> v.scroll().getVerticalScrollBar().setValue(v.scroll().getVerticalScrollBar().getMaximum()));
            assertEquals(n, in(nova), "o botao nao rola em " + d);
            edt(() -> v.scroll().getVerticalScrollBar().setValue(0));
        }
        evidence("sakura-dark-acoes-janela-minima");
        edt(() -> Themes.apply(ClientTheme.NEKKORA_LIGHT));
        edt(window::validate);
        evidence("light-acoes-janela-minima");
    }

    /**
     * Largura menor que a dos dois botoes lado a lado (o que acontece com fonte maior ou painel estreito; a janela minima
     * ainda comporta os dois): a faixa empilha, na ordem, com a mesma largura, sem cortar rotulo, e pede mais altura.
     */
    @Test
    void faixaEstreitaEmpilhaNaOrdemSemCortar() throws Exception {
        ActionButton nova = read(() -> new ActionButton("Gerar pacote novo", ActionButton.Kind.PRIMARY, ActionButton.Glyph.ADD));
        ActionButton importar = read(() -> new ActionButton("Importar (.zip)", ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT));
        ButtonRow faixa = read(() -> {
            ButtonRow r = new ButtonRow();
            r.add(nova);
            r.add(importar);
            return r;
        });
        Dimension lado = read(faixa::getPreferredSize);
        int largos = read(() -> nova.getPreferredSize().width + importar.getPreferredSize().width + ButtonRow.GAP);
        assertEquals(largos, lado.width, "largura preferida: os dois botoes e o intervalo, nada fixo");
        edt(() -> {
            faixa.setSize(largos, lado.height);
            faixa.doLayout();
        });
        assertEquals(read(nova::getY), read(importar::getY), "cabendo, lado a lado");
        assertEquals(ButtonRow.GAP, read(() -> importar.getX() - nova.getX() - nova.getWidth()));
        int estreita = largos - 1;
        edt(() -> {
            faixa.setSize(estreita, lado.height);
            faixa.doLayout();
        });
        assertFalse(read(() -> faixa.sideBySide(estreita)));
        Rectangle n = read(nova::getBounds);
        Rectangle i = read(importar::getBounds);
        assertTrue(n.y + n.height + ButtonRow.STACK_GAP == i.y, "Nova automacao acima, Importar abaixo, com intervalo");
        assertEquals(n.width, i.width, "mesma largura");
        assertTrue(n.width >= read(() -> nova.getPreferredSize().width) && i.width >= read(() -> importar.getPreferredSize().width), "sem cortar rotulo");
        assertTrue(n.x >= 0 && n.x + n.width <= estreita, "dentro da faixa");
        Dimension pilha = read(faixa::getPreferredSize);
        assertEquals(n.height + ButtonRow.STACK_GAP + i.height, pilha.height, "a faixa pede a altura dos dois empilhados");
    }

    // ------------------------------------------------------------------ estados

    @Test
    void estadosNormalHoverPressionadoFocoEDesabilitado() throws Exception {
        ContextView v = window.contextView();
        pick(fin);
        for (ClientTheme t : ClientTheme.values()) {
            edt(() -> Themes.apply(t));
            for (ActionButton b : List.of(read(v::generateButton))) {
                String p = t.libId() + " " + b.getText();
                Rgb normal = read(b::background);
                edt(() -> b.getModel().setRollover(true));
                Rgb hover = read(b::background);
                assertNotEquals(normal.toHex(), hover.toHex(), p + ": hover muda a superficie");
                assertTrue(Rgb.contrast(b.foreground(), hover) >= 4.5, p + ": rotulo legivel no hover");
                edt(() -> {
                    b.getModel().setArmed(true);
                    b.getModel().setPressed(true);
                });
                Rgb pressed = read(b::background);
                assertNotEquals(normal.toHex(), pressed.toHex(), p + ": pressionado muda a superficie");
                edt(() -> {
                    b.getModel().setArmed(false); // desarma antes de soltar: nao dispara a acao
                    b.getModel().setPressed(false);
                    b.getModel().setRollover(false);
                });
                // foco: anel pintado na borda do botao, diferente do fundo
                edt(b::requestFocusInWindow);
                assertTrue(read(b::isFocusOwner), p + ": recebe o foco");
                Rectangle r = read(b::getBounds);
                Rgb anel = painted(b, r.width / 2, 1);
                assertTrue(distance(anel, t.bg()) >= 12, p + ": anel de foco visivel (" + anel.toHex() + ")");
                assertTrue(distance(anel, t.focusRing()) <= 6, p + ": anel na cor de foco do tema");
            }
        }
        assertEquals(0, created.get(), "conferir estados nao aciona nada");
    }

    @Test
    void desabilitadoContinuaBotaoEExplicaOMotivo() throws Exception {
        ContextView v = window.contextView();
        ActionButton b = read(v::generateButton);
        assertTrue(read(b::isEnabled), "gerar um .zip nao depende de ambiente escolhido");
        // acao que nao existe nesta versao: indisponivel, nunca "sem permissao"; continua com cara de botao
        edt(() -> v.setActions(actions(false)));
        for (ClientTheme t : ClientTheme.values()) {
            edt(() -> Themes.apply(t));
            assertFalse(read(b::isEnabled));
            assertEquals("Indisponível nesta versão de teste.", read(b::getToolTipText));
            assertEquals("Indisponível nesta versão de teste.", read(() -> b.getAccessibleContext().getAccessibleDescription()));
            Rectangle r = read(b::getBounds);
            Rgb cor = painted(b, 8, r.height / 2);
            assertTrue(distance(cor, t.bg()) >= 6, t.libId() + ": superficie do botao desabilitado ainda aparece (" + cor.toHex() + ")");
            evidence(t.libId() + "-acoes-desabilitadas");
        }
        edt(() -> b.doClick(0));
        assertEquals(0, created.get(), "desabilitado nao aciona");
        edt(() -> v.setActions(actions(true)));
        assertTrue(read(b::isEnabled));
        assertNull(read(b::getToolTipText));
    }

    // ------------------------------------------------------------------ teclado e funcao

    private static void press(Component c, int key) {
        long now = System.currentTimeMillis();
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, now, 0, key, KeyEvent.CHAR_UNDEFINED));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, now, 0, key, KeyEvent.CHAR_UNDEFINED));
    }

    @Test
    void tecladoAlcancaEAcionaOBotao() throws Exception {
        ContextView v = window.contextView();
        pick(fin);
        ActionButton nova = read(v::generateButton);
        assertTrue(read(nova::isFocusable));
        edt(nova::requestFocusInWindow);
        assertTrue(read(nova::isFocusOwner));
        edt(() -> press(nova, KeyEvent.VK_SPACE));
        assertEquals(1, created.get(), "Espaco aciona Gerar pacote novo");
        edt(() -> nova.doClick(0));
        assertEquals(2, created.get());
    }
}
