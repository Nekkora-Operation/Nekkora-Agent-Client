package com.nekkora.client.ui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.nekkora.client.theme.ClientTheme;

/**
 * Formulario de renomear: diz o ALCANCE da alteracao (na Platform, ou so neste computador) antes do campo, para que um
 * apelido local nunca passe por nome atualizado na Platform. Um clique em Salvar, uma tentativa; enquanto ela corre, o
 * formulario fica ocupado.
 */
final class RenameForm extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel title = new JLabel();
    private final WrappedText scope = new WrappedText(340);
    private final JLabel label = new JLabel(NavText.NAME_FIELD);
    private final JTextField field = new JTextField(28);
    private final WrappedText error = new WrappedText(340);
    private final PrimaryButton save = PrimaryButton.compact(NavText.SAVE);
    private final GhostButton cancel = GhostButton.text(NavText.CANCEL);
    private final boolean required;

    /**
     * @param required {@code true}: nome vazio nao e enviado (automacao da Platform); {@code false}: vazio e aceito e quer
     *                 dizer "voltar ao nome do pacote" (item local)
     */
    RenameForm(String titleText, String scopeText, String current, boolean required, Consumer<String> onSave, Runnable onCancel) {
        this.required = required;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_5, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5));
        setOpaque(true);
        title.setText(titleText);
        scope.setText(scopeText);
        label.setLabelFor(field);
        field.setText(current == null ? "" : current);
        field.getAccessibleContext().setAccessibleName(NavText.NAME_FIELD);
        field.getAccessibleContext().setAccessibleDescription(scopeText);
        field.addActionListener(e -> submit(onSave));
        save.addActionListener(e -> submit(onSave));
        cancel.addActionListener(e -> onCancel.run());
        error.setVisible(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(save);
        for (Component c : new Component[] {title, scope, label, field, error, buttons}) {
            ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        add(title);
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(scope);
        add(Box.createVerticalStrut(ClientTheme.SPACE_4));
        add(label);
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(field);
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(error);
        add(Box.createVerticalStrut(ClientTheme.SPACE_4));
        add(buttons);
        restyle();
    }

    private void submit(Consumer<String> onSave) {
        if (!save.isEnabled()) {
            return;
        }
        String value = field.getText().trim();
        if (required && value.isEmpty()) {
            setError(NavText.NAME_REQUIRED);
            return;
        }
        onSave.accept(value);
    }

    /** Ocupado: a alteracao esta sendo gravada; nada pode ser enviado de novo. */
    void setBusy(boolean busy) {
        save.setLoading(busy);
        field.setEnabled(!busy);
        if (busy) {
            setError("");
        }
    }

    void setError(String message) {
        error.setText(message == null ? "" : message);
        error.setVisible(message != null && !message.isEmpty());
        error.getAccessibleContext().setAccessibleDescription("Erro");
        revalidate();
        repaint();
    }

    JTextField field() {
        return field;
    }

    PrimaryButton saveButton() {
        return save;
    }

    GhostButton cancelButton() {
        return cancel;
    }

    String scopeText() {
        return scope.getText();
    }

    String titleText() {
        return title.getText();
    }

    String errorText() {
        return error.isVisible() ? error.getText() : "";
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        title.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        title.setForeground(Themes.color(t.text()));
        scope.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        scope.setForeground(Themes.color(t.textSecondary()));
        label.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
        label.setForeground(Themes.color(t.text()));
        field.setFont(Themes.font(ClientTheme.FONT_SIZE_MD, false));
        error.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        error.setForeground(Themes.color(t.dangerText()));
    }
}
