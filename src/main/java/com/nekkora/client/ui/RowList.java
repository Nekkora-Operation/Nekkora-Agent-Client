package com.nekkora.client.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/** Pilha vertical de {@link AutomationRow}, da largura da area visivel; rola so na vertical. */
final class RowList extends JPanel implements Scrollable {

    private static final long serialVersionUID = 1L;

    RowList() {
        setOpaque(false);
        setLayout(new LayoutManager() {
            @Override
            public void addLayoutComponent(String name, Component comp) {
            }

            @Override
            public void removeLayoutComponent(Component comp) {
            }

            @Override
            public Dimension preferredLayoutSize(Container parent) {
                int w = parent.getWidth() > 0 ? parent.getWidth() : 420;
                int h = 0;
                for (Component c : parent.getComponents()) {
                    h += ((AutomationRow) c).heightFor(w);
                }
                return new Dimension(w, h);
            }

            @Override
            public Dimension minimumLayoutSize(Container parent) {
                return preferredLayoutSize(parent);
            }

            @Override
            public void layoutContainer(Container parent) {
                int w = parent.getWidth();
                int y = 0;
                for (Component c : parent.getComponents()) {
                    int h = ((AutomationRow) c).heightFor(w);
                    c.setBounds(0, y, w, h);
                    y += h;
                }
            }
        });
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

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
