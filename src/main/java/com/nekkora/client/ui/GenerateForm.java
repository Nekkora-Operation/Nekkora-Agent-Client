package com.nekkora.client.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

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
 * "Gerar pacote novo" (AGENT-CLIENT-PACOTES-001 T01): escolhe a PASTA do projeto, o arquivo de entrada e o nome, mostra o
 * resumo do que sera gerado e, ao concluir, grava UM arquivo .zip onde a pessoa escolher. Nada alem disso: gerar nao cria
 * automacao, rascunho nem versao na Platform, nao executa o codigo, nao instala dependencias e nao acrescenta nada a lista.
 * <p>
 * O formulario principal tem so os tres campos e o resumo. Versao declarada, Python, dependencias, composicao (o que entra
 * e o que fica fora, com o motivo) e a impressao digital ficam em "Avancado", que abre sozinho apenas quando falta uma
 * informacao obrigatoria. Trocar a pasta apaga a entrada escolhida antes.
 */
final class GenerateForm extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String ENTRY_PLACEHOLDER = "Selecione o arquivo de entrada";
    static final String FOLDER_PLACEHOLDER = "Nenhuma pasta escolhida";

    /** O que a tela pede a quem a abriu. Nenhum destes executa codigo nem fala com a Platform. */
    interface Events {
        void browse();

        /** Entrada, nome ou um campo avancado mudou: o resumo precisa ser refeito. */
        void changed();

        void generate();

        void cancel();

        /** Abrir a pasta do arquivo gerado. */
        void reveal();
    }

    /** O que a pessoa informou. Versao em branco: a do manifesto existente ou, sem ele, a inicial. */
    record Values(String name, String version, String entry, String python, List<String> dependencies, boolean dependenciesConfirmed) {
    }

    private final JLabel title = new JLabel(NavText.GENERATE_TITLE);
    private final WrappedText support = new WrappedText(400);
    private final JTextField folder = new JTextField(FOLDER_PLACEHOLDER, 26);
    private final ActionButton browse = ActionButton.compact(NavText.GENERATE_BROWSE, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final WrappedText blockers = new WrappedText(400);
    private final WrappedText notes = new WrappedText(400);
    private final JComboBox<String> entry = new JComboBox<>();
    private final JTextField name = new JTextField(26);
    private final WrappedText summary = new WrappedText(400);
    private final GhostButton advancedToggle = GhostButton.text(NavText.GENERATE_ADVANCED_SHOW);
    private final JPanel advanced = new JPanel();
    private final JTextField version = new JTextField(10);
    private final JTextField python = new JTextField(16);
    private final JTextArea dependencies = new JTextArea(3, 26);
    private final JCheckBox confirmed = new JCheckBox(NavText.GENERATE_DEPENDENCIES_CONFIRM);
    private final JTextArea composition = new JTextArea(7, 26);
    private final WrappedText error = new WrappedText(400);
    private final WrappedText success = new WrappedText(400);
    private final PrimaryButton generate = PrimaryButton.compact(NavText.GENERATE_BUTTON);
    private final GhostButton cancel = GhostButton.text(NavText.CANCEL);
    private final ActionButton reveal = ActionButton.compact(NavText.GENERATE_REVEAL, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final List<JLabel> labels = new ArrayList<>();
    private final transient Events events;
    private boolean filling;
    private boolean usable;
    private boolean needsConfirmation;
    private boolean busy;
    private boolean done;

    GenerateForm(Events events) {
        this.events = events;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_5, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5));
        setOpaque(true);
        support.setText(NavText.GENERATE_SUPPORT);

        folder.setEditable(false);
        folder.getAccessibleContext().setAccessibleName(NavText.GENERATE_FOLDER);
        browse.addActionListener(e -> events.browse());
        JPanel pick = new JPanel(new BorderLayout(ClientTheme.SPACE_2, 0));
        pick.setOpaque(false);
        pick.add(folder, BorderLayout.CENTER);
        pick.add(browse, BorderLayout.EAST);

        entry.setModel(new DefaultComboBoxModel<>(new String[] {ENTRY_PLACEHOLDER}));
        entry.addActionListener(e -> fire());
        name.addActionListener(e -> fire());
        name.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                fire();
            }
        });
        for (JTextField f : List.of(version, python)) {
            f.addActionListener(e -> fire());
            f.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    fire();
                }
            });
        }
        dependencies.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                fire();
            }
        });
        confirmed.addActionListener(e -> fire());
        confirmed.setOpaque(false);

        advancedToggle.addActionListener(e -> setAdvancedOpen(!advanced.isVisible()));
        advanced.setLayout(new BoxLayout(advanced, BoxLayout.Y_AXIS));
        advanced.setOpaque(false);
        advanced.setVisible(false);
        composition.setEditable(false);
        composition.setLineWrap(false);
        composition.getAccessibleContext().setAccessibleName(NavText.GENERATE_COMPOSITION);

        generate.addActionListener(e -> {
            if (generate.isEnabled()) {
                events.generate();
            }
        });
        cancel.addActionListener(e -> events.cancel());
        reveal.addActionListener(e -> events.reveal());
        reveal.setVisible(false);
        blockers.setVisible(false);
        notes.setVisible(false);
        error.setVisible(false);
        success.setVisible(false);
        summary.setVisible(false);
        blockers.getAccessibleContext().setAccessibleDescription("Erro");
        error.getAccessibleContext().setAccessibleDescription("Erro");

        add(left(title));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(support));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        field(this, NavText.GENERATE_FOLDER, pick, folder);
        add(left(blockers));
        add(left(notes));
        field(this, NavText.GENERATE_ENTRY, entry, entry);
        field(this, NavText.GENERATE_NAME, name, name);
        add(left(summary));
        add(left(advancedToggle));
        field(advanced, NavText.GENERATE_VERSION, version, version);
        field(advanced, NavText.GENERATE_PYTHON, python, python);
        JScrollPane deps = new JScrollPane(dependencies);
        field(advanced, NavText.GENERATE_DEPENDENCIES, deps, dependencies);
        advanced.add(left(confirmed));
        advanced.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        JScrollPane comp = new JScrollPane(composition);
        field(advanced, NavText.GENERATE_COMPOSITION, comp, composition);
        add(left(advanced));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(error));
        add(left(success));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        buttons.setOpaque(false);
        buttons.add(reveal);
        buttons.add(cancel);
        buttons.add(generate);
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        add(left(buttons));
        Themes.addListener(t -> restyle());
        restyle();
        refresh();
    }

    private static <T extends javax.swing.JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private void field(JPanel where, String text, javax.swing.JComponent shown, javax.swing.JComponent input) {
        JLabel l = new JLabel(text);
        l.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(text);
        labels.add(l);
        where.add(left(l));
        where.add(Box.createVerticalStrut(4));
        where.add(left(shown));
        where.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
    }

    private void fire() {
        if (!filling && usable && !busy && !done) {
            events.changed();
        }
    }

    /** A pasta esta sendo lida: nada e editavel ate o retrato chegar. */
    void showReading(String folderPath) {
        filling = true;
        try {
            folder.setText(folderPath);
            entry.setModel(new DefaultComboBoxModel<>(new String[] {ENTRY_PLACEHOLDER}));
            usable = false;
            done = false;
            summary.setText("");
            summary.setVisible(false);
            composition.setText("");
            setError("");
            success.setVisible(false);
            reveal.setVisible(false);
            blockers.setVisible(false);
            notes.setText(NavText.WORKING);
            notes.setVisible(true);
        } finally {
            filling = false;
        }
        refresh();
    }

    /** A pasta nao pode ser aberta: a frase diz por que; nada fica escolhido. */
    void showFolderRefused(String message) {
        filling = true;
        try {
            folder.setText(FOLDER_PLACEHOLDER);
            usable = false;
            notes.setVisible(false);
            blockers.setText(message);
            blockers.setVisible(true);
        } finally {
            filling = false;
        }
        refresh();
    }

    /**
     * Uma pasta foi aberta. Os campos voltam ao que ESTA pasta declara: a entrada escolhida para a pasta anterior some (a
     * sugestao do projeto fica so na dica), e o nome, a versao, o Python e as dependencias vem do manifesto ou da leitura
     * estatica do projeto, se houver.
     */
    void showFolder(Workshop.Draft draft) {
        filling = true;
        try {
            folder.setText(draft.sourceLabel());
            List<String> why = draft.blockers();
            usable = why.isEmpty();
            blockers.setText(String.join(" ", why));
            blockers.setVisible(!usable);
            List<String> info = new ArrayList<>(draft.notes());
            draft.existingManifest().ifPresent(m -> info.add(0, NavText.GENERATE_MANIFEST_FOUND));
            notes.setText(String.join(" ", info));
            notes.setVisible(!info.isEmpty());
            DefaultComboBoxModel<String> files = new DefaultComboBoxModel<>();
            files.addElement(ENTRY_PLACEHOLDER);
            draft.pythonFiles().forEach(files::addElement);
            entry.setModel(files);
            entry.setToolTipText(draft.suggestedEntry().map(s -> "Sugestão do projeto: " + s).orElse(null));
            Workshop.Manifest m = draft.existingManifest().orElse(null);
            if (m != null && draft.pythonFiles().contains(m.entry())) {
                entry.setSelectedItem(m.entry()); // a entrada que o manifesto da pasta ja declara
            }
            name.setText(m == null ? "" : m.name());
            version.setText(m == null ? "" : m.version());
            python.setText(m != null && m.python() != null ? m.python() : draft.python().orElse(""));
            dependencies.setText(String.join("\n", m == null ? draft.dependencies() : m.dependencies()));
            needsConfirmation = m == null && draft.dependenciesNeedConfirmation();
            confirmed.setSelected(false);
            confirmed.setVisible(needsConfirmation);
            done = false;
            summary.setText("");
            summary.setVisible(false);
            composition.setText("");
            setError("");
            success.setVisible(false);
            reveal.setVisible(false);
            if (needsConfirmation || !draft.unsupportedDependencies().isEmpty()) {
                setAdvancedOpen(true);
            }
        } finally {
            filling = false;
        }
        refresh();
    }

    /** O resumo do que sera gerado com as escolhas atuais (ou a frase de por que ainda nao pode). */
    void showSummary(Workshop.PackageSummary s) {
        summary.setText(NavText.generateSummary(s));
        summary.setVisible(true);
        StringBuilder b = new StringBuilder();
        b.append("Impressão digital: ").append(s.fingerprint()).append('\n');
        b.append(s.manifestReused() ? "Manifesto da pasta reaproveitado como está.\n" : "Manifesto novo (nekkora-external.json), gravado só no pacote.\n");
        b.append("\nIncluídos (").append(s.included().size()).append("):\n");
        s.included().forEach(f -> b.append("  ").append(f).append(s.warned().contains(f) ? "   — confira: o nome sugere credencial" : "").append('\n'));
        b.append("\nFora do pacote (").append(s.excluded().size()).append("):\n");
        s.excluded().forEach(x -> b.append("  ").append(x.path()).append("   — ").append(x.reason()).append('\n'));
        composition.setText(b.toString());
        composition.setCaretPosition(0);
        setError("");
        refresh();
    }

    void clearSummary() {
        summary.setText("");
        summary.setVisible(false);
        composition.setText("");
        refresh();
    }

    void setBusy(boolean busy) {
        this.busy = busy;
        generate.setLoading(busy);
        if (busy) {
            setError("");
        }
        refresh();
    }

    void setError(String message) {
        error.setText(message == null ? "" : message);
        error.setVisible(message != null && !message.isEmpty());
        revalidate();
        repaint();
    }

    /** O arquivo foi gravado: a mensagem do que fazer com ele e o botao para abrir a pasta. Nada foi enviado. */
    void showGenerated(String path) {
        done = true;
        busy = false;
        generate.setLoading(false);
        setError("");
        success.setText(NavText.GENERATED + " " + path);
        success.setVisible(true);
        reveal.setVisible(true);
        cancel.setText(NavText.CLOSE);
        refresh();
    }

    void setAdvancedOpen(boolean open) {
        advanced.setVisible(open);
        advancedToggle.setText(open ? NavText.GENERATE_ADVANCED_HIDE : NavText.GENERATE_ADVANCED_SHOW);
        revalidate();
        repaint();
        java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (w != null) {
            w.pack();
        }
    }

    private void refresh() {
        boolean edit = usable && !busy && !done;
        for (Component c : new Component[] {entry, name, version, python, dependencies, confirmed}) {
            c.setEnabled(edit);
        }
        browse.setEnabled(!busy);
        generate.setEnabled(edit && !entryValue().isEmpty() && !name.getText().isBlank());
        generate.setVisible(!done);
        generate.setToolTipText(generate.isEnabled() || done ? null : NavText.GENERATE_NEEDS);
        revalidate();
        repaint();
    }

    private String entryValue() {
        Object chosen = entry.getSelectedItem();
        return chosen == null || ENTRY_PLACEHOLDER.equals(chosen) ? "" : chosen.toString();
    }

    Values values() {
        List<String> deps = new ArrayList<>();
        for (String line : dependencies.getText().split("\\R")) {
            if (!line.isBlank()) {
                deps.add(line.trim());
            }
        }
        return new Values(name.getText().trim(), version.getText().trim(), entryValue(), python.getText().trim(), deps, !needsConfirmation || confirmed.isSelected());
    }

    boolean usable() {
        return usable;
    }

    boolean done() {
        return done;
    }

    JTextField folderField() {
        return folder;
    }

    ActionButton browseButton() {
        return browse;
    }

    JComboBox<String> entryBox() {
        return entry;
    }

    JTextField nameField() {
        return name;
    }

    JTextField versionField() {
        return version;
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

    PrimaryButton generateButton() {
        return generate;
    }

    GhostButton cancelButton() {
        return cancel;
    }

    ActionButton revealButton() {
        return reveal;
    }

    GhostButton advancedToggle() {
        return advancedToggle;
    }

    boolean advancedOpen() {
        return advanced.isVisible();
    }

    String supportText() {
        return support.getText();
    }

    String blockersText() {
        return blockers.isVisible() ? blockers.getText() : "";
    }

    String notesText() {
        return notes.isVisible() ? notes.getText() : "";
    }

    String summaryText() {
        return summary.isVisible() ? summary.getText() : "";
    }

    String compositionText() {
        return composition.getText();
    }

    String errorText() {
        return error.isVisible() ? error.getText() : "";
    }

    String successText() {
        return success.isVisible() ? success.getText() : "";
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        title.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        title.setForeground(Themes.color(t.text()));
        for (WrappedText w : List.of(support, notes)) {
            w.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
            w.setForeground(Themes.color(t.textSecondary()));
        }
        for (WrappedText w : List.of(summary, success)) {
            w.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
            w.setForeground(Themes.color(t.text()));
        }
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
        composition.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
    }
}
