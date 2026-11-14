package com.nekkora.client.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JPanel;

import com.nekkora.client.theme.ClientTheme;

/**
 * Faixa de botoes de acao: lado a lado, centralizados, quando a largura comporta todos no tamanho preferido; empilhados,
 * NA MESMA ORDEM e com a mesma largura, quando nao comporta (janela estreita, fonte maior). Nenhum botao e encolhido abaixo
 * do proprio rotulo enquanto a faixa tiver essa largura; nenhum tamanho e fixo. A altura preferida acompanha a largura
 * disponivel, e a faixa pede novo layout quando ela muda.
 */
final class ButtonRow extends JPanel {

    private static final long serialVersionUID = 1L;
    static final int GAP = ClientTheme.SPACE_3;
    static final int STACK_GAP = ClientTheme.SPACE_2;

    private int laidOutFor = -1;

    ButtonRow() {
        super(null);
        setOpaque(false);
        setLayout(new Flow());
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (getWidth() != laidOutFor) {
                    laidOutFor = getWidth();
                    revalidate();
                }
            }
        });
    }

    /** Os botoes cabem lado a lado nesta largura? */
    boolean sideBySide(int width) {
        Insets in = getInsets();
        return rowWidth() <= width - in.left - in.right;
    }

    private int rowWidth() {
        int w = 0;
        int n = 0;
        for (Component c : getComponents()) {
            if (c.isVisible()) {
                w += c.getPreferredSize().width;
                n++;
            }
        }
        return w + Math.max(0, n - 1) * GAP;
    }

    private int availableWidth() {
        int w = getWidth();
        if (w <= 0 && getParent() != null) {
            w = getParent().getWidth();
        }
        return w;
    }

    private final class Flow implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component comp) {
            // sem restricoes
        }

        @Override
        public void removeLayoutComponent(Component comp) {
            // sem restricoes
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int width = availableWidth();
            boolean row = width <= 0 || sideBySide(width);
            int w = 0;
            int h = 0;
            int n = 0;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = c.getPreferredSize();
                w = row ? w + d.width : Math.max(w, d.width);
                h = row ? Math.max(h, d.height) : h + d.height;
                n++;
            }
            int gaps = Math.max(0, n - 1);
            return new Dimension((row ? w + gaps * GAP : w) + in.left + in.right, (row ? h : h + gaps * STACK_GAP) + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int inner = parent.getWidth() - in.left - in.right;
            if (sideBySide(parent.getWidth())) {
                int x = in.left + (inner - rowWidth()) / 2;
                for (Component c : parent.getComponents()) {
                    if (c.isVisible()) {
                        Dimension d = c.getPreferredSize();
                        c.setBounds(x, in.top, d.width, d.height);
                        x += d.width + GAP;
                    }
                }
                return;
            }
            int widest = 0;
            for (Component c : parent.getComponents()) {
                if (c.isVisible()) {
                    widest = Math.max(widest, c.getPreferredSize().width);
                }
            }
            int w = Math.min(widest, Math.max(0, inner));
            int y = in.top;
            for (Component c : parent.getComponents()) {
                if (c.isVisible()) {
                    int h = c.getPreferredSize().height;
                    c.setBounds(in.left + (inner - w) / 2, y, w, h);
                    y += h + STACK_GAP;
                }
            }
        }
    }
}
