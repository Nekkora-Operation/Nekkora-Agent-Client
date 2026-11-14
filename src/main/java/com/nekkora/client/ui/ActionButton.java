package com.nekkora.client.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JButton;

import com.nekkora.client.theme.ClientTheme;
import com.nekkora.client.theme.Rgb;

/**
 * Botao de acao com icone E rotulo, nos tokens do {@code .nk-button} da Nekkora-lib (altura control-md, raio radius-md,
 * padding space-4, intervalo space-2 entre icone e rotulo, fonte de 14 px peso medio, borda de 1 px, anel de foco de 3 px,
 * deslocamento de 1 px ao pressionar e opacidade 0,45 desabilitado). Duas variantes da lib:
 * <ul>
 * <li>{@link Kind#PRIMARY}: {@code .nk-button--primary}, preenchido com a cor de acao do tema;</li>
 * <li>{@link Kind#SECONDARY}: {@code .nk-button--secondary}, superficie e contorno proprios, visiveis nos dois temas.</li>
 * </ul>
 * Desabilitado, continua com a superficie e o contorno (so esmaecido): segue reconhecivel como botao. O icone e desenhado
 * aqui (nenhum arquivo de imagem), na cor do rotulo, e e so complemento: o nome acessivel e o texto.
 * <p>
 * A largura preferida e a do conteudo (icone, intervalo, rotulo e padding), medida na fonte atual; nenhuma largura fixa.
 */
public final class ActionButton extends JButton {

    private static final long serialVersionUID = 1L;
    private static final int RING = ClientTheme.FOCUS_RING_WIDTH;
    /** Lado do icone, em px logicos (o tamanho de icone do botao md da lib). */
    static final int ICON = 16;

    /** Variante da lib. */
    public enum Kind { PRIMARY, SECONDARY }

    /** Icone desenhado ao lado do rotulo. */
    public enum Glyph { ADD, IMPORT, REFRESH }

    private final Kind kind;
    private final Glyph glyph;
    private boolean loading;

    public ActionButton(String text, Kind kind, Glyph glyph) {
        super(text);
        this.kind = kind;
        this.glyph = glyph;
        restyle();
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    Kind kind() {
        return kind;
    }

    Glyph glyph() {
        return glyph;
    }

    /** Desabilitado por estar trabalhando (opacidade de carregando), e nao por indisponibilidade. */
    public void setLoading(boolean loading) {
        this.loading = loading;
        setEnabled(!loading);
        setCursor(Cursor.getPredefinedCursor(loading ? Cursor.WAIT_CURSOR : Cursor.HAND_CURSOR));
        repaint();
    }

    boolean loading() {
        return loading;
    }

    private void restyle() {
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setFont(Themes.font(ClientTheme.FONT_SIZE_MD, true));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        restyle();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int w = ICON + ClientTheme.SPACE_2 + fm.stringWidth(getText()) + 2 * ClientTheme.SPACE_4;
        return new Dimension(w + 2 * RING, ClientTheme.CONTROL_MD + 2 * RING);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    /** Cor da superficie no estado atual (para a pintura e para os testes de contraste). */
    Rgb background() {
        ClientTheme t = Themes.current();
        boolean live = isEnabled();
        boolean pressed = live && getModel().isPressed();
        boolean hover = live && getModel().isRollover();
        if (kind == Kind.PRIMARY) {
            return pressed ? t.accentActive() : hover ? t.accentHover() : t.accent();
        }
        return pressed || hover ? t.buttonSecondaryBgHover() : t.buttonSecondaryBg();
    }

    /** Cor do rotulo e do icone. */
    Rgb foreground() {
        ClientTheme t = Themes.current();
        return kind == Kind.PRIMARY ? t.textInverse() : t.buttonSecondaryFg();
    }

    /** Cor do contorno (a primaria nao tem: a borda da lib e transparente). */
    Rgb outline() {
        return kind == Kind.PRIMARY ? null : Themes.current().buttonSecondaryBorder();
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
            Rgb line = outline();
            if (line != null) {
                g2.setColor(Themes.color(line));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(RING + 0.5f, RING + 0.5f, w - 2 * RING - 1, h - 2 * RING - 1, arc, arc));
            }
            g2.setColor(Themes.color(foreground()));
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int content = ICON + ClientTheme.SPACE_2 + fm.stringWidth(getText());
            int down = isEnabled() && getModel().isPressed() ? 1 : 0;
            int x = (w - content) / 2;
            paintGlyph(g2, glyph, x, (h - ICON) / 2f + down);
            g2.drawString(getText(), x + ICON + ClientTheme.SPACE_2, (h - fm.getHeight()) / 2 + fm.getAscent() + down);
        } finally {
            g2.dispose();
        }
    }

    /** O icone, numa caixa de {@link #ICON} px a partir de (x, y), na cor atual. Traco de 1,75 px, pontas redondas. */
    static void paintGlyph(Graphics2D g2, Glyph glyph, float x, float y) {
        g2.setStroke(new BasicStroke(1.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float s = ICON;
        float c = s / 2f;
        switch (glyph) {
            case ADD -> {
                g2.draw(new Line2D.Float(x + c, y + 3, x + c, y + s - 3));
                g2.draw(new Line2D.Float(x + 3, y + c, x + s - 3, y + c));
            }
            case IMPORT -> {
                // seta para dentro de uma bandeja
                g2.draw(new Line2D.Float(x + c, y + 2, x + c, y + 10));
                Path2D.Float head = new Path2D.Float();
                head.moveTo(x + c - 3.5f, y + 6.5f);
                head.lineTo(x + c, y + 10);
                head.lineTo(x + c + 3.5f, y + 6.5f);
                g2.draw(head);
                Path2D.Float tray = new Path2D.Float();
                tray.moveTo(x + 2.5f, y + 10.5f);
                tray.lineTo(x + 2.5f, y + 13.5f);
                tray.lineTo(x + s - 2.5f, y + 13.5f);
                tray.lineTo(x + s - 2.5f, y + 10.5f);
                g2.draw(tray);
            }
            case REFRESH -> {
                // arco aberto com a ponta de seta: o simbolo usual de atualizar
                g2.draw(new Arc2D.Float(x + 2.5f, y + 2.5f, s - 5, s - 5, 60, 280, Arc2D.OPEN));
                Path2D.Float head = new Path2D.Float();
                head.moveTo(x + 9.2f, y + 1.2f);
                head.lineTo(x + 12.4f, y + 3.6f);
                head.lineTo(x + 9.0f, y + 5.9f);
                g2.draw(head);
            }
            default -> throw new IllegalStateException(String.valueOf(glyph));
        }
    }
}
