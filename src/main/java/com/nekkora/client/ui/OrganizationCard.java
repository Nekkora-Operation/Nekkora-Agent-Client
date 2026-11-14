package com.nekkora.client.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Locale;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

import com.nekkora.client.nav.Organization;
import com.nekkora.client.theme.ClientTheme;

/**
 * Cartao de uma organizacao ({@code .nk-card} da lib: superficie, borda de 1 px, raio lg, espaco 5). O cartao INTEIRO e o
 * alvo: clique, Enter ou Espaco escolhem; ele tem foco e anel de foco proprios.
 * <p>
 * O contrato nao tem logotipo: no lugar dele vai um quadro com a inicial do nome, que e so um apoio visual. Quem
 * distingue duas organizacoes de mesmo nome e o identificador abaixo do nome.
 */
final class OrganizationCard extends JComponent {

    private static final long serialVersionUID = 1L;

    static final int PAD = ClientTheme.SPACE_5;
    static final int TILE = 40;
    private static final int RING = ClientTheme.FOCUS_RING_WIDTH;

    private final Organization organization;
    private boolean hover;

    OrganizationCard(Organization organization, Runnable onChoose) {
        this.organization = organization;
        setFocusable(true);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (organization.awaitingActivation()) {
            setToolTipText(NavText.AWAITING_ACTIVATION_HINT);
        }
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    requestFocusInWindow();
                    onChoose.run();
                }
            }
        });
        addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
                scrollRectToVisible(new java.awt.Rectangle(0, 0, getWidth(), getHeight()));
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
        for (int key : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE}) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "escolher");
        }
        getActionMap().put("escolher", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                onChoose.run();
            }
        });
    }

    Organization organization() {
        return organization;
    }

    /** Aciona o cartao como o teclado ou o clique fariam (usado pelos testes, sem eventos do sistema). */
    void choose() {
        getActionMap().get("escolher").actionPerformed(null);
    }

    private java.awt.Font nameFont() {
        return Themes.font(ClientTheme.FONT_SIZE_MD, true);
    }

    private java.awt.Font smallFont() {
        return Themes.font(ClientTheme.FONT_SIZE_XS, false);
    }

    private static int lineHeight(java.awt.Font f) {
        return (int) Math.ceil(f.getSize2D() * 1.5);
    }

    private int textWidth(int width) {
        return Math.max(40, width - 2 * PAD - TILE - ClientTheme.SPACE_3);
    }

    List<String> nameLines(int width) {
        return TextLines.wrap(getFontMetrics(nameFont()), organization.name(), textWidth(width));
    }

    List<String> labelLines(int width) {
        return TextLines.wrap(getFontMetrics(smallFont()), organization.label(), textWidth(width));
    }

    /** Altura que o cartao precisa nessa largura: o nome quebra em quantas linhas forem necessarias, sem corte. */
    int heightFor(int width) {
        int text = nameLines(width).size() * lineHeight(nameFont()) + labelLines(width).size() * lineHeight(smallFont())
                + (organization.awaitingActivation() ? ClientTheme.SPACE_2 + lineHeight(smallFont()) : 0);
        return 2 * PAD + Math.max(TILE, text);
    }

    @Override
    public Dimension getPreferredSize() {
        int w = getWidth() > 0 ? getWidth() : 280;
        return new Dimension(w, heightFor(w));
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PUSH_BUTTON;
                }
            };
            accessibleContext.setAccessibleName(NavText.card(organization));
            if (organization.awaitingActivation()) {
                accessibleContext.setAccessibleDescription(NavText.AWAITING_ACTIVATION_HINT);
            }
        }
        return accessibleContext;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            ClientTheme t = Themes.current();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth();
            int h = getHeight();
            float r = ClientTheme.RADIUS_LG;
            if (isFocusOwner()) {
                g2.setColor(Themes.color(t.focusRing()));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r + RING, r + RING));
            }
            RoundRectangle2D body = new RoundRectangle2D.Float(RING, RING, w - 2 * RING, h - 2 * RING, r, r);
            g2.setColor(Themes.color(hover ? t.surfaceHover() : t.surface()));
            g2.fill(body);
            g2.setColor(Themes.color(isFocusOwner() ? t.accent() : t.border()));
            g2.draw(new RoundRectangle2D.Float(RING + 0.5f, RING + 0.5f, w - 2 * RING - 1, h - 2 * RING - 1, r, r));

            // apoio visual no lugar do logotipo: a inicial do nome
            int tx = PAD;
            int ty = PAD;
            g2.setColor(Themes.color(t.accentSoft()));
            g2.fill(new RoundRectangle2D.Float(tx, ty, TILE, TILE, ClientTheme.RADIUS_MD, ClientTheme.RADIUS_MD));
            String initial = initial(organization.name());
            g2.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
            g2.setColor(Themes.color(t.accent()));
            FontMetrics tf = g2.getFontMetrics();
            g2.drawString(initial, tx + (TILE - tf.stringWidth(initial)) / 2, ty + (TILE - tf.getHeight()) / 2 + tf.getAscent());

            int x = PAD + TILE + ClientTheme.SPACE_3;
            int y = PAD;
            g2.setFont(nameFont());
            g2.setColor(Themes.color(t.text()));
            y = draw(g2, nameLines(w), x, y, lineHeight(nameFont()));
            g2.setFont(smallFont());
            g2.setColor(Themes.color(t.textSecondary()));
            y = draw(g2, labelLines(w), x, y, lineHeight(smallFont()));
            if (organization.awaitingActivation()) {
                draw(g2, List.of(NavText.AWAITING_ACTIVATION), x, y + ClientTheme.SPACE_2, lineHeight(smallFont()));
            }
        } finally {
            g2.dispose();
        }
    }

    private static int draw(Graphics2D g2, List<String> lines, int x, int y, int lh) {
        FontMetrics fm = g2.getFontMetrics();
        for (String line : lines) {
            g2.drawString(line, x, y + (lh - fm.getHeight()) / 2 + fm.getAscent());
            y += lh;
        }
        return y;
    }

    static String initial(String name) {
        for (int i = 0; i < name.length();) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
            }
            i += Character.charCount(cp);
        }
        return "";
    }
}
