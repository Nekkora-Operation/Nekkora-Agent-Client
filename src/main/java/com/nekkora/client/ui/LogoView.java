package com.nekkora.client.ui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * Logo completa (simbolo + nome) do tema atual, numa altura logica fixa e com a proporcao do arquivo. Em telas com
 * escala (150%, 200%), usa o arquivo maior para nao perder nitidez.
 */
public final class LogoView extends JComponent {

    private static final long serialVersionUID = 1L;

    /** Proporcao do lockup oficial (852 x 128). */
    static final double ASPECT = 852.0 / 128.0;

    private final int height;

    public LogoView(int logicalHeight) {
        this.height = logicalHeight;
        setOpaque(false);
        Themes.addListener(t -> repaint());
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.ICON;
                }
            };
            accessibleContext.setAccessibleName("Nekkora");
        }
        return accessibleContext;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension((int) Math.round(height * ASPECT), height);
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
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            double scale = Math.max(1.0, g2.getTransform().getScaleY());
            BufferedImage img = BrandAssets.lockup(Themes.current(), (int) Math.ceil(height * scale));
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Dimension d = getPreferredSize();
            // a largura vem da proporcao do proprio arquivo: nunca distorce
            int w = (int) Math.round(d.height * (img.getWidth() / (double) img.getHeight()));
            g2.drawImage(img, (getWidth() - w) / 2, (getHeight() - d.height) / 2, w, d.height, null);
        } finally {
            g2.dispose();
        }
    }
}
