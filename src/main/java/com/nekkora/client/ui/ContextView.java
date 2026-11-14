package com.nekkora.client.ui;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.theme.ClientTheme;

/**
 * Tela da organizacao escolhida: no alto, a seta para voltar ao seletor e o nome da organizacao; no pe, o rodape. O
 * conteudo (ambiente e automacoes) fica na area central.
 */
final class ContextView extends JPanel {

    private static final long serialVersionUID = 1L;

    private final GhostButton back = GhostButton.text("←");
    private final JLabel name = new JLabel();
    private final JPanel content = new JPanel(new BorderLayout());
    private final AppFooter footer;
    private Organization organization;

    ContextView(Runnable onBack, BuildInfo build) {
        super(new BorderLayout());
        setOpaque(true);
        back.describe(NavText.BACK);
        back.addActionListener(e -> onBack.run());

        JPanel header = new JPanel(new BorderLayout(ClientTheme.SPACE_2, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_3, ClientTheme.SPACE_4, ClientTheme.SPACE_3, ClientTheme.SPACE_4));
        header.add(back, BorderLayout.WEST);
        header.add(name, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        content.setOpaque(false);
        add(content, BorderLayout.CENTER);

        footer = new AppFooter(build);
        add(footer, BorderLayout.SOUTH);

        Themes.addListener(t -> restyle());
        restyle();
    }

    /** A organizacao da tela. O nome longo e encurtado so na pintura; o nome inteiro fica na dica e no nome acessivel. */
    void show(Organization organization) {
        this.organization = organization;
        name.setText(organization == null ? "" : organization.name());
        name.setToolTipText(organization == null ? null : organization.name() + " (" + organization.label() + ")");
        getAccessibleContext().setAccessibleName(organization == null ? "" : "Organização " + organization.name());
    }

    Organization organization() {
        return organization;
    }

    GhostButton backButton() {
        return back;
    }

    JLabel nameLabel() {
        return name;
    }

    JPanel content() {
        return content;
    }

    AppFooter footer() {
        return footer;
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        name.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        name.setForeground(Themes.color(t.text()));
        back.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, false));
    }
}
