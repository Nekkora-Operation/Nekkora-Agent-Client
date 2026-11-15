package com.nekkora.client.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JButton;

import com.nekkora.client.theme.ClientTheme;

/**
 * Botao discreto, com os tokens do {@code .nk-button--ghost} (tamanho sm) da lib: sem fundo, rotulo text-secondary;
 * no hover, fundo surface-hover e rotulo text; anel de foco de 3 px. Dois usos: "Cancelar" (texto) e a troca de tema
 * (so o glifo de meio circulo, com nome acessivel e dica).
 */
public final class GhostButton extends JButton {

    private static final long serialVersionUID = 1L;

    private static final int RING = ClientTheme.FOCUS_RING_WIDTH;
    private final boolean glyph;

    private GhostButton(String text, boolean glyph) {
        super(text);
        this.glyph = glyph;
        restyle();
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public static GhostButton text(String text) {
        return new GhostButton(text, false);
    }

    /** So o glifo de contraste; {@code accessibleName} diz o que o botao faz. */
    public static GhostButton themeGlyph(String accessibleName) {
        GhostButton b = new GhostButton("", true);
        b.describe(accessibleName);
        return b;
    }

    public void describe(String text) {
        getAccessibleContext().setAccessibleName(text);
        setToolTipText(text);
    }

    private void restyle() {
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        restyle();
    }

    @Override
    public Dimension getPreferredSize() {
        if (glyph) {
            return new Dimension(ClientTheme.CONTROL_SM + 2 * RING, ClientTheme.CONTROL_SM - 4 + 2 * RING);
        }
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.stringWidth(getText()) + 2 * ClientTheme.SPACE_3 + 2 * RING, ClientTheme.CONTROL_SM + 2 * RING);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        ClientTheme t = Themes.current();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int w = getWidth();
            int h = getHeight();
            float arc = 12f; // radius-sm (6)
            boolean hot = getModel().isRollover() || getModel().isPressed();
            if (isFocusOwner()) {
                g2.setColor(Themes.color(t.focusRing()));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc + 2 * RING, arc + 2 * RING));
                g2.setColor(Themes.color(t.bg()));
                g2.fill(new RoundRectangle2D.Float(RING, RING, w - 2 * RING, h - 2 * RING, arc, arc));
            }
            if (hot) {
                g2.setColor(Themes.color(t.surfaceHover()));
                g2.fill(new RoundRectangle2D.Float(RING, RING, w - 2 * RING, h - 2 * RING, arc, arc));
            }
            g2.setColor(Themes.color(hot ? t.text() : t.textSecondary()));
            if (glyph) {
                // meio circulo cheio: o simbolo usual de claro/escuro
                float d = 14f;
                float x = (w - d) / 2f;
                float y = (h - d) / 2f;
                g2.fill(new Arc2D.Float(x, y, d, d, 90, 180, Arc2D.PIE));
                g2.setStroke(new java.awt.BasicStroke(1.5f));
                g2.draw(new Ellipse2D.Float(x, y, d, d));
            } else {
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            }
        } finally {
            g2.dispose();
        }
    }
}
