package com.nekkora.client.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JPanel;

import com.nekkora.client.theme.ClientTheme;

/**
 * Uma linha da lista: a esquerda o nome e, abaixo dele, as linhas de detalhe (versao, pacote local); a direita, os botoes
 * de acao. Quando a largura nao comporta texto e botoes lado a lado (janela estreita, escala alta), os botoes descem
 * para a linha seguinte, alinhados a esquerda. O nome quebra em quantas linhas precisar; nada e cortado nem sobreposto.
 */
final class AutomationRow extends JPanel {

    private static final long serialVersionUID = 1L;

    static final int PAD_X = ClientTheme.SPACE_4;
    static final int PAD_Y = ClientTheme.SPACE_3;
    static final int GAP = ClientTheme.SPACE_2;
    /** Largura minima do texto para os botoes ficarem ao lado dele. */
    static final int TEXT_MIN = 150;

    private final String name;
    private final List<String> details;
    private final List<JButton> actions = new ArrayList<>();

    AutomationRow(String name, List<String> details, JButton... buttons) {
        this.name = name;
        this.details = List.copyOf(details);
        setOpaque(false);
        setLayout(new Flow());
        for (JButton b : buttons) {
            actions.add(b);
            add(b);
        }
        StringBuilder accessible = new StringBuilder(name);
        details.forEach(d -> accessible.append(", ").append(d));
        getAccessibleContext().setAccessibleName(accessible.toString());
    }

    String name() {
        return name;
    }

    List<String> details() {
        return details;
    }

    List<JButton> actions() {
        return actions;
    }

    private java.awt.Font nameFont() {
        return Themes.font(ClientTheme.FONT_SIZE_MD, true);
    }

    private java.awt.Font detailFont() {
        return Themes.font(ClientTheme.FONT_SIZE_XS, false);
    }

    private static int lh(java.awt.Font f) {
        return (int) Math.ceil(f.getSize2D() * 1.5);
    }

    private int buttonsWidth() {
        int w = 0;
        for (JButton b : actions) {
            w += b.getPreferredSize().width + GAP;
        }
        return Math.max(0, w - GAP);
    }

    private int buttonsHeight() {
        int h = 0;
        for (JButton b : actions) {
            h = Math.max(h, b.getPreferredSize().height);
        }
        return h;
    }

    /** Os botoes ficam ao lado do texto nessa largura? */
    boolean sideBySide(int width) {
        return width - 2 * PAD_X - buttonsWidth() - ClientTheme.SPACE_4 >= TEXT_MIN;
    }

    int textWidth(int width) {
        return Math.max(60, sideBySide(width) ? width - 2 * PAD_X - buttonsWidth() - ClientTheme.SPACE_4 : width - 2 * PAD_X);
    }

    List<String> nameLines(int width) {
        return TextLines.wrap(getFontMetrics(nameFont()), name, textWidth(width));
    }

    List<List<String>> detailLines(int width) {
        List<List<String>> out = new ArrayList<>();
        for (String d : details) {
            out.add(TextLines.wrap(getFontMetrics(detailFont()), d, textWidth(width)));
        }
        return out;
    }

    int textHeight(int width) {
        int h = nameLines(width).size() * lh(nameFont());
        for (List<String> d : detailLines(width)) {
            h += d.size() * lh(detailFont());
        }
        return h;
    }

    int heightFor(int width) {
        int text = textHeight(width);
        return 2 * PAD_Y + (sideBySide(width) ? Math.max(text, buttonsHeight()) : text + GAP + buttonsHeight());
    }

    @Override
    public Dimension getPreferredSize() {
        int w = getWidth() > 0 ? getWidth() : 420;
        return new Dimension(w, heightFor(w));
    }

    private final class Flow implements LayoutManager {
        @Override
        public void addLayoutComponent(String n, Component c) {
        }

        @Override
        public void removeLayoutComponent(Component c) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return getPreferredSize();
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return getPreferredSize();
        }

        @Override
        public void layoutContainer(Container parent) {
            int w = parent.getWidth();
            int bh = buttonsHeight();
            if (sideBySide(w)) {
                int x = w - PAD_X - buttonsWidth();
                int y = PAD_Y + Math.max(0, (Math.max(textHeight(w), bh) - bh) / 2);
                for (JButton b : actions) {
                    Dimension d = b.getPreferredSize();
                    b.setBounds(x, y + (bh - d.height) / 2, d.width, d.height);
                    x += d.width + GAP;
                }
            } else {
                int x = PAD_X;
                int y = PAD_Y + textHeight(w) + GAP;
                for (JButton b : actions) {
                    Dimension d = b.getPreferredSize();
                    b.setBounds(x, y + (bh - d.height) / 2, d.width, d.height);
                    x += d.width + GAP;
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            ClientTheme t = Themes.current();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth();
            int y = PAD_Y;
            g2.setFont(nameFont());
            g2.setColor(Themes.color(t.text()));
            y = draw(g2, nameLines(w), y, lh(nameFont()));
            g2.setFont(detailFont());
            g2.setColor(Themes.color(t.textSecondary()));
            for (List<String> d : detailLines(w)) {
                y = draw(g2, d, y, lh(detailFont()));
            }
            // divisor entre as linhas da lista
            g2.setColor(Themes.color(t.border()));
            g2.fillRect(PAD_X, getHeight() - 1, w - 2 * PAD_X, 1);
        } finally {
            g2.dispose();
        }
    }

    private static int draw(Graphics2D g2, List<String> lines, int y, int lh) {
        FontMetrics fm = g2.getFontMetrics();
        for (String line : lines) {
            g2.drawString(line, PAD_X, y + (lh - fm.getHeight()) / 2 + fm.getAscent());
            y += lh;
        }
        return y;
    }
}
