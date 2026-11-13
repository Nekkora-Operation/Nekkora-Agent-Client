package com.nekkora.client.ui;

import java.awt.AlphaComposite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JButton;

import com.nekkora.client.theme.ClientTheme;
import com.nekkora.client.theme.Rgb;

/**
 * Botao principal, com os tokens do {@code .nk-button--primary} da Nekkora-lib: fundo accent (hover e active proprios),
 * rotulo text-inverse, altura control-md (40), raio radius-md (8), fonte de 14 px peso medio, anel de foco de 3 px e
 * opacidade 0,45 desabilitado (0,75 quando esta carregando). E um {@link JButton}: foco por Tab, Espaco e Enter.
 */
public final class PrimaryButton extends JButton {

    private static final long serialVersionUID = 1L;

    private static final int RING = ClientTheme.FOCUS_RING_WIDTH;
    private boolean loading;

    public PrimaryButton(String text) {
        super(text);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setFont(Themes.font(ClientTheme.FONT_SIZE_MD, true));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** Desabilitado por estar trabalhando (opacidade de carregando), e nao por indisponibilidade. */
    public void setLoading(boolean loading) {
        this.loading = loading;
        setEnabled(!loading);
        setCursor(Cursor.getPredefinedCursor(loading ? Cursor.WAIT_CURSOR : Cursor.HAND_CURSOR));
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // o tema troca o look and feel: a aparencia propria e reposta
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Themes.font(ClientTheme.FONT_SIZE_MD, true));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int w = Math.max(220, fm.stringWidth(getText()) + 2 * ClientTheme.SPACE_4);
        return new Dimension(w + 2 * RING, ClientTheme.CONTROL_MD + 2 * RING);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /** Cor de fundo do estado atual (para a pintura e para os testes de contraste). */
    Rgb background() {
        ClientTheme t = Themes.current();
        if (isEnabled() && getModel().isPressed()) {
            return t.accentActive();
        }
        if (isEnabled() && getModel().isRollover()) {
            return t.accentHover();
        }
        return t.accent();
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
            float arc = 2f * ClientTheme.RADIUS_MD;
            if (isFocusOwner() && isEnabled()) {
                g2.setColor(Themes.color(t.focusRing()));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc + 2 * RING, arc + 2 * RING));
            }
            if (!isEnabled()) {
                g2.setComposite(AlphaComposite.SrcOver.derive(loading ? ClientTheme.LOADING_OPACITY : ClientTheme.DISABLED_OPACITY));
            }
            g2.setColor(Themes.color(background()));
            g2.fill(new RoundRectangle2D.Float(RING, RING, w - 2 * RING, h - 2 * RING, arc, arc));
            g2.setColor(Themes.color(t.textInverse()));
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(getText())) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent() + (isEnabled() && getModel().isPressed() ? 1 : 0);
            g2.drawString(getText(), tx, ty);
        } finally {
            g2.dispose();
        }
    }
}
