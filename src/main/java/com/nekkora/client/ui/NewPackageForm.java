package com.nekkora.client.ui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.nekkora.client.pack.Workshop;
import com.nekkora.client.theme.ClientTheme;

/**
 * "Nova automacao": os dados do pacote local que sera preparado a partir de um ZIP de codigo ja aberto. O que o projeto
 * declara vem preenchido como sugestao; o arquivo de entrada e SEMPRE escolhido pela pessoa. Preparar nao cria nada na
 * Platform, nao executa o codigo e nao instala dependencias - e o formulario diz isso.
 */
final class NewPackageForm extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String ENTRY_PLACEHOLDER = "Selecione o arquivo de entrada";

    /** O que a pessoa informou. */
    record Values(String name, String version, String entry, String python, List<String> dependencies, boolean dependenciesConfirmed) {
    }

    private final JLabel title = new JLabel(NavText.NEW_TITLE);
    private final WrappedText scope = new WrappedText(380);
    private final WrappedText blockers = new WrappedText(380);
    private final JTextField name = new JTextField(24);
    private final JTextField version = new JTextField("1.0.0", 10);
    private final JComboBox<String> entry = new JComboBox<>();
    private final JTextField python = new JTextField(16);
    private final JTextArea dependencies = new JTextArea(4, 24);
    private final JCheckBox confirmed = new JCheckBox("Conferi as dependências acima");
    private final WrappedText error = new WrappedText(380);
    private final PrimaryButton prepare = PrimaryButton.compact(NavText.NEW_PREPARE);
    private final GhostButton cancel = GhostButton.text(NavText.CANCEL);
    private final List<JLabel> labels = new ArrayList<>();
    private final boolean needsConfirmation;

    NewPackageForm(Workshop.Draft draft, Consumer<Values> onPrepare, Runnable onCancel) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_5, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5));
        setOpaque(true);
        scope.setText(NavText.NEW_SCOPE);
        List<String> why = draft.blockers();
        boolean usable = why.isEmpty();
        blockers.setText(String.join(" ", why));
        blockers.setVisible(!usable);
        blockers.getAccessibleContext().setAccessibleDescription("Erro");

        DefaultComboBoxModel<String> files = new DefaultComboBoxModel<>();
        files.addElement(ENTRY_PLACEHOLDER);
        draft.pythonFiles().forEach(files::addElement);
        entry.setModel(files);
        // a sugestao NAO e escolhida: fica so na dica, e a pessoa escolhe
        draft.suggestedEntry().ifPresent(s -> entry.setToolTipText("Sugestão do projeto: " + s));
        python.setText(draft.python().orElse(""));
        dependencies.setText(String.join("\n", draft.dependencies()));
        needsConfirmation = draft.dependenciesNeedConfirmation();
        confirmed.setVisible(needsConfirmation);
        confirmed.setOpaque(false);
        error.setVisible(false);

        prepare.addActionListener(e -> {
            if (prepare.isEnabled()) {
                onPrepare.accept(values());
            }
        });
        cancel.addActionListener(e -> onCancel.run());
        prepare.setEnabled(usable);
        for (Component c : new Component[] {name, version, entry, python, dependencies, confirmed}) {
            c.setEnabled(usable);
        }

        add(left(title));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(scope));
        add(left(blockers));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        field("Nome do pacote", name);
        field("Versão do pacote (x.y.z)", version);
        field("Arquivo de entrada", entry);
        field("Python exigido (por exemplo >=3.10,<3.14)", python);
        JScrollPane deps = new JScrollPane(dependencies);
        field("Dependências, uma por linha (nome==versão)", deps);
        dependencies.getAccessibleContext().setAccessibleName("Dependências");
        add(left(confirmed));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(error));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(prepare);
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        add(left(buttons));
        restyle();
    }

    private static <T extends javax.swing.JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private void field(String text, javax.swing.JComponent input) {
        JLabel l = new JLabel(text);
        l.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(text);
        labels.add(l);
        add(left(l));
        add(Box.createVerticalStrut(4));
        add(left(input));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
    }

    Values values() {
        List<String> deps = new ArrayList<>();
        for (String line : dependencies.getText().split("\\R")) {
            if (!line.isBlank()) {
                deps.add(line.trim());
            }
        }
        Object chosen = entry.getSelectedItem();
        String file = chosen == null || ENTRY_PLACEHOLDER.equals(chosen) ? "" : chosen.toString();
        return new Values(name.getText().trim(), version.getText().trim(), file, python.getText().trim(), deps, !needsConfirmation || confirmed.isSelected());
    }

    void setBusy(boolean busy) {
        prepare.setLoading(busy);
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

    JTextField nameField() {
        return name;
    }

    JTextField versionField() {
        return version;
    }

    JComboBox<String> entryBox() {
        return entry;
    }

    JTextField pythonField() {
        return python;
    }

    JTextArea dependenciesArea() {
        return dependencies;
    }

    JCheckBox confirmedBox() {
        return confirmed;
    }

    PrimaryButton prepareButton() {
        return prepare;
    }

    String scopeText() {
        return scope.getText();
    }

    String blockersText() {
        return blockers.isVisible() ? blockers.getText() : "";
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
        for (WrappedText w : List.of(blockers, error)) {
            w.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
            w.setForeground(Themes.color(t.dangerText()));
        }
        for (JLabel l : labels) {
            l.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
            l.setForeground(Themes.color(t.text()));
        }
        confirmed.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        confirmed.setForeground(Themes.color(t.text()));
    }
}
