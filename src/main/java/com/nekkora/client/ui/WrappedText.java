package com.nekkora.client.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * Texto curto, centralizado, quebrado por palavras numa largura maxima fixa. A altura acompanha o numero de linhas, e
 * nada e cortado: a mensagem cabe inteira em qualquer tamanho de janela e escala.
 */
public final class WrappedText extends JComponent {

    private static final long serialVersionUID = 1L;

    private final int maxWidth;
    private String text = "";

    public WrappedText(int maxWidth) {
        this.maxWidth = maxWidth;
        setOpaque(false);
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
        getAccessibleContext().setAccessibleName(this.text);
        revalidate();
        repaint();
    }

    public String getText() {
        return text;
    }

    /** Linhas que cabem em {@code maxWidth}, quebrando so entre palavras. */
    List<String> lines() {
        FontMetrics fm = getFontMetrics(getFont());
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && fm.stringWidth(candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.LABEL;
                }
            };
        }
        return accessibleContext;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(maxWidth, Math.max(1, lines().size()) * lineHeight(fm));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /** {@code line-height: 1.5} da lib. */
    private static int lineHeight(FontMetrics fm) {
        return (int) Math.ceil(fm.getFont().getSize2D() * 1.5);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.setFont(getFont());
            g2.setColor(getForeground());
            FontMetrics fm = g2.getFontMetrics();
            int lh = lineHeight(fm);
            int y = (lh - fm.getHeight()) / 2 + fm.getAscent();
            for (String line : lines()) {
                g2.drawString(line, (getWidth() - fm.stringWidth(line)) / 2, y);
                y += lh;
            }
        } finally {
            g2.dispose();
        }
    }
}
