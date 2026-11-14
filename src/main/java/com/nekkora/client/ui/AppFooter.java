package com.nekkora.client.ui;

import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.theme.ClientTheme;

/**
 * Rodape das telas: versao e, abaixo dela, a linha de direitos da Nekkora. Identificacao discreta, sempre visivel nos estados da entrada (inicial,
 * aguardando e resultado) e nas telas de depois dela. Nao e botao nem link.
 * <p>
 * Estilo do rodape de autenticacao da lib ({@code .nk-auth__footer}): {@code font-size-xs} e {@code text-secondary}.
 */
final class AppFooter extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel version = new JLabel();
    private final JLabel rights = new JLabel();

    AppFooter(BuildInfo build) {
        setOpaque(false);
        setFocusable(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_2, ClientTheme.SPACE_4, ClientTheme.SPACE_4, ClientTheme.SPACE_4));
        version.setText(SignInText.version(build));
        version.setAlignmentX(Component.CENTER_ALIGNMENT);
        version.setFocusable(false);
        add(version);
        rights.setText(SignInText.rights(build));
        rights.setAlignmentX(Component.CENTER_ALIGNMENT);
        rights.setFocusable(false);
        add(rights);
        Themes.addListener(t -> restyle());
        restyle();
    }

    JLabel versionLabel() {
        return version;
    }

    JLabel rightsLabel() {
        return rights;
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        for (Component c : getComponents()) {
            c.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
            c.setForeground(Themes.color(t.textSecondary()));
        }
    }
}
