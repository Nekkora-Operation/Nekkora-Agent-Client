package com.nekkora.client.ui;

import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

import com.nekkora.client.theme.ClientTheme;

/**
 * "Editar" de uma automacao da Platform: duas operacoes DISTINTAS, uma sobre a outra.
 * <ol>
 * <li>o nome cadastrado na Platform ({@link RenameForm}, so com a permissao de renomear; sem ela, a secao diz por que);</li>
 * <li>o pacote que ESTE computador usa ({@link PackageForm}).</li>
 * </ol>
 * Nenhuma das duas altera o conteudo de uma versao publicada.
 */
final class EditForm extends JPanel {

    private static final long serialVersionUID = 1L;

    private final RenameForm rename;
    private final WrappedText renameDenied = new WrappedText(420);
    private final PackageForm pack;

    /** {@code rename} nulo: a pessoa nao pode renomear esta automacao; a secao mostra a justificativa. */
    EditForm(RenameForm rename, PackageForm pack) {
        this.rename = rename;
        this.pack = pack;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);
        if (rename != null) {
            rename.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(rename);
        } else {
            renameDenied.setText(NavText.EDIT_DENIED);
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setOpaque(false);
            p.setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_5, ClientTheme.SPACE_5, ClientTheme.SPACE_3, ClientTheme.SPACE_5));
            p.add(renameDenied);
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(p);
        }
        pack.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(pack);
        Themes.addListener(t -> restyle());
        restyle();
    }

    RenameForm renameForm() {
        return rename;
    }

    String renameDeniedText() {
        return rename == null ? renameDenied.getText() : "";
    }

    PackageForm packageForm() {
        return pack;
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        renameDenied.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        renameDenied.setForeground(Themes.color(t.textSecondary()));
    }
}
