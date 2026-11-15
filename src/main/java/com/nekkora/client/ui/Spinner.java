package com.nekkora.client.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.Timer;

import com.nekkora.client.theme.ClientTheme;

/**
 * Indicador de carregamento do {@code .nk-spinner} da lib: anel de 20 px, traco de 2 px em accent-soft, com um quarto em
 * accent girando uma volta a cada 0,8 s. O temporizador so roda enquanto o componente esta na tela.
 */
public final class Spinner extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int SIZE = ClientTheme.SPINNER_SIZE;
    private static final float STROKE = Math.max(2f, SIZE / 10f);
    private final Timer timer;
    private long startedAt = System.nanoTime();

    public Spinner() {
        setOpaque(false);
        timer = new Timer(33, e -> repaint());
    }

    @Override
    public void addNotify() {
        super.addNotify();
        sync();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        sync();
    }

    private void sync() {
        if (isVisible() && isDisplayable()) {
            if (!timer.isRunning()) {
                startedAt = System.nanoTime();
                timer.start();
            }
        } else {
            timer.stop();
        }
    }

    boolean spinning() {
        return timer.isRunning();
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PROGRESS_BAR;
                }
            };
            accessibleContext.setAccessibleName("Carregando");
        }
        return accessibleContext;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(SIZE, SIZE);
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
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float inset = STROKE / 2f;
            float d = SIZE - STROKE;
            float x = (getWidth() - SIZE) / 2f + inset;
            float y = (getHeight() - SIZE) / 2f + inset;
            g2.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g2.setColor(Themes.color(t.accentSoft()));
            g2.draw(new Ellipse2D.Float(x, y, d, d));
            double turn = ((System.nanoTime() - startedAt) / 1_000_000.0 % ClientTheme.SPINNER_TURN_MS) / ClientTheme.SPINNER_TURN_MS;
            g2.setColor(Themes.color(t.accent()));
            g2.draw(new Arc2D.Double(x, y, d, d, 90 - turn * 360, -90, Arc2D.OPEN));
        } finally {
            g2.dispose();
        }
    }
}
