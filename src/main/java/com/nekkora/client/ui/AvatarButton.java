package com.nekkora.client.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import com.nekkora.client.nav.Profile;
import com.nekkora.client.theme.ClientTheme;

/**
 * Avatar da pessoa no topo, e o menu dela. O contrato da Platform nao tem foto: o avatar mostra as INICIAIS DO NOME e,
 * sem nome (ou enquanto o perfil nao carregou), um desenho neutro. Nunca iniciais tiradas do e-mail.
 * <p>
 * Menu, nesta ordem: nome (informativo), divisor, Configuracoes e Ajuda (visiveis e desabilitadas nesta etapa), divisor,
 * Terminar sessao, divisor, Sair. "Empresa" so entraria com um campo real do perfil, que nao existe.
 */
final class AvatarButton extends JButton {

    private static final long serialVersionUID = 1L;

    static final int SIZE = 28;
    private static final int RING = ClientTheme.FOCUS_RING_WIDTH;

    private final Runnable onSignOut;
    private final Runnable onExit;
    private Profile profile;
    private JPopupMenu open;

    AvatarButton(Runnable onSignOut, Runnable onExit) {
        this.onSignOut = onSignOut;
        this.onExit = onExit;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addActionListener(e -> showMenu());
        setProfile(null);
    }

    /** {@code null}: perfil ainda nao carregado ou indisponivel (avatar neutro). */
    void setProfile(Profile profile) {
        this.profile = profile;
        String who = NavText.person(profile);
        getAccessibleContext().setAccessibleName(NavText.MENU + ": " + who);
        setToolTipText(who);
        repaint();
    }

    String initials() {
        return profile == null ? "" : profile.initials();
    }

    /** O menu, montado na hora com o que se sabe da pessoa. */
    JPopupMenu menu() {
        JPopupMenu m = new JPopupMenu();
        m.getAccessibleContext().setAccessibleName(NavText.MENU);
        JLabel name = new JLabel(NavText.person(profile));
        name.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
        name.setForeground(Themes.color(Themes.current().text()));
        name.setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_2, ClientTheme.SPACE_3, ClientTheme.SPACE_2, ClientTheme.SPACE_3));
        name.getAccessibleContext().setAccessibleDescription("Usuário que entrou");
        m.add(name);
        m.addSeparator();
        m.add(unavailable(NavText.MENU_SETTINGS));
        m.add(unavailable(NavText.MENU_HELP));
        m.addSeparator();
        JMenuItem signOut = new JMenuItem(NavText.MENU_SIGN_OUT);
        signOut.addActionListener(e -> onSignOut.run());
        m.add(signOut);
        m.addSeparator();
        JMenuItem exit = new JMenuItem(NavText.MENU_EXIT);
        exit.addActionListener(e -> onExit.run());
        m.add(exit);
        return m;
    }

    private static JMenuItem unavailable(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(false);
        item.setToolTipText(NavText.MENU_UNAVAILABLE);
        item.getAccessibleContext().setAccessibleDescription(NavText.MENU_UNAVAILABLE);
        return item;
    }

    private void showMenu() {
        if (open != null && open.isVisible()) {
            open.setVisible(false);
            return;
        }
        open = menu();
        Dimension d = open.getPreferredSize();
        open.show(this, getWidth() - d.width, getHeight());
    }

    void closeMenu() {
        if (open != null) {
            open.setVisible(false);
            open = null;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(SIZE + 2 * RING, SIZE + 2 * RING);
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
            ClientTheme t = Themes.current();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            float x = (getWidth() - SIZE) / 2f;
            float y = (getHeight() - SIZE) / 2f;
            if (isFocusOwner()) {
                g2.setColor(Themes.color(t.focusRing()));
                g2.fill(new Ellipse2D.Float(x - RING, y - RING, SIZE + 2 * RING, SIZE + 2 * RING));
            }
            g2.setColor(Themes.color(getModel().isRollover() ? t.surfaceHover() : t.accentSoft()));
            g2.fill(new Ellipse2D.Float(x, y, SIZE, SIZE));
            String initials = initials();
            if (!initials.isEmpty()) {
                g2.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, true));
                g2.setColor(Themes.color(t.text()));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(initials, x + (SIZE - fm.stringWidth(initials)) / 2f, y + (SIZE - fm.getHeight()) / 2f + fm.getAscent());
            } else {
                // desenho neutro (cabeca e ombros), recortado no circulo
                g2.setClip(new Ellipse2D.Float(x, y, SIZE, SIZE));
                g2.setColor(Themes.color(t.textSecondary()));
                g2.fill(new Ellipse2D.Float(x + SIZE / 2f - 5, y + 6, 10, 10));
                g2.fill(new Ellipse2D.Float(x + SIZE / 2f - 10, y + 18, 20, 20));
            }
        } finally {
            g2.dispose();
        }
    }
}
