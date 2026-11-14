package com.nekkora.client.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

import com.nekkora.client.theme.ClientTheme;

/**
 * Grade dos cartoes de organizacao: DUAS colunas, preenchidas por linhas da esquerda para a direita, na ordem recebida.
 * Com um cartao so, ele fica centralizado. Quando a largura nao comporta duas colunas (janela estreita ou escala alta),
 * vira UMA coluna, na mesma ordem, sem cortar cartao. A altura de cada linha e a do cartao mais alto dela.
 */
final class CardGrid extends JPanel implements Scrollable {

    private static final long serialVersionUID = 1L;

    static final int GAP = ClientTheme.SPACE_4;
    static final int PAD = ClientTheme.SPACE_6;
    static final int CARD_MIN = 180;
    static final int CARD_MAX = 300;

    CardGrid() {
        setOpaque(false);
        setLayout(new Rows());
    }

    /** Quantas colunas a grade usa nessa largura, com essa quantidade de cartoes. */
    static int columns(int width, int cards) {
        return cards > 1 && width - 2 * PAD >= 2 * CARD_MIN + GAP ? 2 : 1;
    }

    static int cardWidth(int width, int columns) {
        int free = width - 2 * PAD - (columns - 1) * GAP;
        return Math.max(120, Math.min(CARD_MAX, free / columns));
    }

    private final class Rows implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            int width = parent.getWidth() > 0 ? parent.getWidth() : 480;
            return new Dimension(width, arrange(parent, width, false));
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            arrange(parent, parent.getWidth(), true);
        }

        private int arrange(Container parent, int width, boolean apply) {
            int n = parent.getComponentCount();
            if (n == 0) {
                return 2 * PAD;
            }
            int cols = columns(width, n);
            int cw = cardWidth(width, cols);
            int block = cols * cw + (cols - 1) * GAP;
            int left = Math.max(0, (width - block) / 2);
            int y = PAD;
            for (int i = 0; i < n; i += cols) {
                int rowHeight = 0;
                for (int c = 0; c < cols && i + c < n; c++) {
                    rowHeight = Math.max(rowHeight, ((OrganizationCard) parent.getComponent(i + c)).heightFor(cw));
                }
                if (apply) {
                    for (int c = 0; c < cols && i + c < n; c++) {
                        parent.getComponent(i + c).setBounds(left + c * (cw + GAP), y, cw, rowHeight);
                    }
                }
                y += rowHeight + GAP;
            }
            return y - GAP + PAD;
        }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? Math.max(24, visibleRect.height - 24) : 24;
    }

    /** A grade acompanha a largura da area visivel: rola so na vertical. */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
