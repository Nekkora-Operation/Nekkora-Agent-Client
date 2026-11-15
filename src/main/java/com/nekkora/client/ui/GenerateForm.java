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
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.nekkora.client.pack.Workshop;
import com.nekkora.client.theme.ClientTheme;

/**
 * "Gerar pacote novo": escolhe a PASTA do projeto, o arquivo de entrada e o nome, mostra o resumo do que sera gerado e, ao
 * concluir, grava UM arquivo .zip onde a pessoa escolher. Nada alem disso: gerar nao cria automacao, rascunho nem versao
 * na Platform, nao executa o codigo, nao instala dependencias e nao acrescenta nada a lista.
 * <p>
 * OBRIGATORIOS: a pasta, o arquivo de entrada e o nome. O resto fica em "Opcoes avancadas", recolhido: a versao declarada
 * (ja preenchida: 1.0.0 num projeto novo, ou a do manifesto da pasta), o requisito de Python - OPCIONAL: em branco, o pacote
 * sai "sem restricao de Python declarada" -, as dependencias e a composicao. A secao abre sozinha so quando um campo dela
 * precisa de correcao.
 * <p>
 * Mensagens (AGENT-CLIENT-PREPARACAO-002): cada problema aparece junto do campo a que se refere, com a gravidade escrita
 * ({@link Notice}); o codigo de suporte fica numa linha de detalhe, fora da frase. Nada fica vermelho antes de a pessoa
 * mexer no campo ou pedir para gerar. A geracao tem tres estados visiveis e exclusivos: "Gerando pacote…", o erro (com os
 * campos preservados) ou "Pacote gerado", com o caminho e o botao para abrir a pasta.
 */
final class GenerateForm extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String ENTRY_PLACEHOLDER = "Selecione o arquivo de entrada";
    static final String FOLDER_PLACEHOLDER = "Nenhuma pasta escolhida";
    private static final int WIDTH = 440;

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

    /**
     * O que a pessoa informou. {@code version} em branco: a do manifesto existente ou, sem ele, a inicial. {@code python}: o
     * texto do campo como esta (em branco = sem restricao declarada).
     */
    record Values(String name, String version, String entry, String python, List<String> dependencies, boolean dependenciesConfirmed) {
    }

    private final JLabel title = new JLabel(NavText.GENERATE_TITLE);
    private final Notice support = new Notice(WIDTH);
    private final JTextField folder = new JTextField(FOLDER_PLACEHOLDER, 26);
    private final ActionButton browse = ActionButton.compact(NavText.GENERATE_BROWSE, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final Notice blockers = new Notice(WIDTH);
    private final Notice notes = new Notice(WIDTH);
    private final JComboBox<String> entry = new JComboBox<>();
    private final Notice entryNotice = new Notice(WIDTH);
    private final JTextField name = new JTextField(26);
    private final Notice nameNotice = new Notice(WIDTH);
    private final Notice summary = new Notice(WIDTH);
    private final GhostButton advancedToggle = GhostButton.text(NavText.GENERATE_ADVANCED_SHOW);
    private final JPanel advanced = new JPanel();
    private final JTextField version = new JTextField(10);
    private final Notice versionNotice = new Notice(WIDTH);
    private final JTextField python = new JTextField(16);
    private final Notice pythonOrigin = new Notice(WIDTH);
    private final Notice pythonNotice = new Notice(WIDTH);
    private final JTextArea dependencies = new JTextArea(3, 26);
    private final Notice dependenciesNotice = new Notice(WIDTH);
    private final JTextArea composition = new JTextArea(7, 26);
    private final Notice progress = new Notice(WIDTH);
    private final Notice error = new Notice(WIDTH);
    private final Notice success = new Notice(WIDTH);
    private final JTextField generatedPath = new JTextField(26);
    private final Notice nextStep = new Notice(WIDTH);
    private final PrimaryButton generate = PrimaryButton.compact(NavText.GENERATE_BUTTON);
    private final GhostButton cancel = GhostButton.text(NavText.CANCEL);
    private final ActionButton reveal = ActionButton.compact(NavText.GENERATE_REVEAL, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final List<JLabel> labels = new ArrayList<>();
    private final transient Events events;
    private boolean filling;
    private boolean usable;
    private boolean busy;
    private boolean done;
    /** A pessoa ja saiu do campo de Python (ou pediu para gerar): so a partir dai um texto invalido e mostrado como erro. */
    private boolean pythonCommitted;

    GenerateForm(Events events) {
        this.events = events;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_5, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5));
        setOpaque(true);
        support.show(Notice.Kind.PLAIN, NavText.GENERATE_SUPPORT);

        folder.setEditable(false);
        folder.getAccessibleContext().setAccessibleName(NavText.GENERATE_FOLDER);
        browse.addActionListener(e -> events.browse());
        JPanel pick = new JPanel(new BorderLayout(ClientTheme.SPACE_2, 0));
        pick.setOpaque(false);
        pick.add(folder, BorderLayout.CENTER);
        pick.add(browse, BorderLayout.EAST);

        entry.setModel(new DefaultComboBoxModel<>(new String[] {ENTRY_PLACEHOLDER}));
        entry.addActionListener(e -> {
            entryNotice.clear();
            fire();
        });
        onCommit(name, () -> {
            nameNotice.clear();
            fire();
        });
        onCommit(version, () -> {
            versionNotice.clear();
            fire();
        });
        onCommit(python, () -> {
            pythonCommitted = true;
            showPythonReading();
            fire();
        });
        python.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                showPythonReading();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                showPythonReading();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                showPythonReading();
            }
        });
        dependencies.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                dependenciesNotice.clear();
                fire();
            }
        });

        advancedToggle.addActionListener(e -> setAdvancedOpen(!advanced.isVisible()));
        advanced.setLayout(new BoxLayout(advanced, BoxLayout.Y_AXIS));
        advanced.setOpaque(false);
        advanced.setVisible(false);
        composition.setEditable(false);
        composition.setLineWrap(false);
        composition.getAccessibleContext().setAccessibleName(NavText.GENERATE_COMPOSITION);
        generatedPath.setEditable(false);
        generatedPath.getAccessibleContext().setAccessibleName(NavText.GENERATED_PATH);
        generatedPath.setVisible(false);

        generate.addActionListener(e -> {
            if (generate.isEnabled()) {
                events.generate();
            }
        });
        cancel.addActionListener(e -> events.cancel());
        reveal.addActionListener(e -> events.reveal());
        reveal.setVisible(false);

        add(left(title));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(support);
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        field(this, NavText.GENERATE_FOLDER, pick, folder, blockers, notes);
        field(this, NavText.GENERATE_ENTRY, entry, entry, entryNotice);
        field(this, NavText.GENERATE_NAME, name, name, nameNotice);
        add(summary);
        add(left(advancedToggle));
        field(advanced, NavText.GENERATE_VERSION, version, version, versionNotice);
        field(advanced, NavText.GENERATE_PYTHON, python, python, pythonOrigin, pythonNotice);
        JScrollPane deps = new JScrollPane(dependencies);
        field(advanced, NavText.GENERATE_DEPENDENCIES, deps, dependencies, dependenciesNotice);
        JScrollPane comp = new JScrollPane(composition);
        field(advanced, NavText.GENERATE_COMPOSITION, comp, composition);
        add(left(advanced));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        // os tres estados da geracao, no mesmo lugar e nunca juntos: andamento, erro ou conclusao
        add(progress);
        add(error);
        add(success);
        add(Box.createVerticalStrut(4));
        add(left(generatedPath));
        add(nextStep);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        buttons.setOpaque(false);
        buttons.add(reveal);
        buttons.add(cancel);
        buttons.add(generate);
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        add(left(buttons));
        Themes.addListener(t -> restyle());
        restyle();
        showPythonReading();
        refresh();
    }

    private static <T extends javax.swing.JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private void onCommit(JTextField f, Runnable then) {
        f.addActionListener(e -> then.run());
        f.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                then.run();
            }
        });
    }

    /** Um campo com o rotulo acima e as mensagens DELE logo abaixo. */
    private void field(JPanel where, String text, javax.swing.JComponent shown, javax.swing.JComponent input, Notice... below) {
        JLabel l = new JLabel(text);
        l.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(text);
        labels.add(l);
        where.add(left(l));
        where.add(Box.createVerticalStrut(4));
        where.add(left(shown));
        for (Notice n : below) {
            where.add(n);
        }
        where.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
    }

    private void fire() {
        if (!filling && usable && !busy && !done) {
            events.changed();
        }
    }

    /** O que o campo de Python quer dizer, escrito abaixo dele. Texto invalido so vira erro depois de a pessoa sair do campo. */
    private void showPythonReading() {
        Workshop.PythonReading r = Workshop.readPython(python.getText());
        if (!r.valid()) {
            if (pythonCommitted) {
                pythonNotice.show(Notice.Kind.ERROR, NavText.GENERATE_PYTHON_INVALID);
            } else {
                pythonNotice.show(Notice.Kind.PLAIN, NavText.GENERATE_PYTHON_EXAMPLES);
            }
        } else if (!r.declared()) {
            pythonNotice.show(Notice.Kind.PLAIN, NavText.GENERATE_PYTHON_NONE);
        } else {
            pythonNotice.show(Notice.Kind.PLAIN, NavText.generatePythonReading(r));
        }
        fit();
    }

    /** O campo de Python tem um texto que nao e um requisito? (a geracao nao comeca; o campo mostra o erro) */
    boolean pythonInvalid() {
        return !Workshop.readPython(python.getText()).valid();
    }

    /** A pessoa pediu para gerar: o que estiver digitado passa a valer como confirmado (um texto invalido vira erro). */
    void commit() {
        pythonCommitted = true;
        showPythonReading();
        if (pythonInvalid()) {
            setAdvancedOpen(true);
        }
    }

    /** A pasta esta sendo lida: nada e editavel ate o retrato chegar. */
    void showReading(String folderPath) {
        filling = true;
        try {
            folder.setText(folderPath);
            folder.setToolTipText(folderPath);
            entry.setModel(new DefaultComboBoxModel<>(new String[] {ENTRY_PLACEHOLDER}));
            usable = false;
            done = false;
            summary.clear();
            composition.setText("");
            clearProblems();
            clearResult();
            blockers.clear();
            notes.show(Notice.Kind.PLAIN, NavText.WORKING);
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
            folder.setToolTipText(null);
            usable = false;
            notes.clear();
            blockers.show(Notice.Kind.ERROR, message);
        } finally {
            filling = false;
        }
        refresh();
    }

    /**
     * Uma pasta foi aberta. Os campos voltam ao que ESTA pasta declara: a entrada escolhida para a pasta anterior some (a
     * sugestao do projeto fica so na dica); nome, versao, requisito de Python e dependencias vem do manifesto da pasta ou da
     * leitura estatica do projeto, se houver. Num projeto novo a versao comeca em 1.0.0 e o requisito de Python em branco: a
     * versao de Python instalada NESTE computador nunca e posta como requisito do projeto.
     */
    void showFolder(Workshop.Draft draft) {
        filling = true;
        try {
            folder.setText(draft.sourceLabel());
            folder.setToolTipText(draft.sourceLabel());
            List<String> why = draft.blockers();
            usable = why.isEmpty();
            blockers.show(Notice.Kind.ERROR, String.join(" ", why));
            List<String> info = new ArrayList<>(draft.notes());
            draft.existingManifest().ifPresent(m -> info.add(0, NavText.GENERATE_MANIFEST_FOUND));
            notes.show(Notice.Kind.INFO, String.join(" ", info));
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
            version.setText(m == null ? Workshop.FIRST_VERSION : m.version());
            pythonCommitted = false;
            // o requisito so vem preenchido quando a PASTA o declara; a origem fica escrita, e a pessoa pode revisar
            String origin = null;
            if (m != null) {
                python.setText(m.python() == null ? "" : m.python());
                origin = m.python() == null ? null : NavText.GENERATE_PYTHON_FROM_MANIFEST;
            } else {
                python.setText(draft.python().orElse(""));
                origin = draft.python().isPresent() ? NavText.generatePythonFrom(draft.pythonSource().orElse("os metadados do projeto")) : null;
            }
            if (origin == null && draft.pythonUnsupported().isPresent()) {
                pythonOrigin.show(Notice.Kind.WARNING, NavText.generatePythonUnsupported(draft.pythonUnsupported().get()));
            } else {
                pythonOrigin.show(Notice.Kind.INFO, origin);
            }
            dependencies.setText(String.join("\n", m == null ? draft.dependencies() : m.dependencies()));
            done = false;
            summary.clear();
            composition.setText("");
            clearProblems();
            clearResult();
            showPythonReading();
            if (pythonOrigin.kind() == Notice.Kind.WARNING && pythonOrigin.isVisible()) {
                setAdvancedOpen(true); // ha uma declaracao do projeto que o manifesto nao representa: a pessoa revisa
            }
        } finally {
            filling = false;
        }
        refresh();
    }

    /** O resumo do que sera gerado com as escolhas atuais. */
    void showSummary(Workshop.PackageSummary s) {
        summary.show(Notice.Kind.PLAIN, NavText.generateSummary(s));
        StringBuilder b = new StringBuilder();
        b.append("Impressão digital: ").append(s.fingerprint()).append('\n');
        b.append(s.manifestReused() ? "Manifesto da pasta reaproveitado como está.\n" : "Manifesto novo (nekkora-external.json), gravado só no pacote.\n");
        b.append("\nIncluídos (").append(s.included().size()).append("):\n");
        s.included().forEach(f -> b.append("  ").append(f).append(s.warned().contains(f) ? "   — confira: o nome sugere credencial" : "").append('\n'));
        b.append("\nFora do pacote (").append(s.excluded().size()).append("):\n");
        s.excluded().forEach(x -> b.append("  ").append(x.path()).append("   — ").append(x.reason()).append('\n'));
        composition.setText(b.toString());
        composition.setCaretPosition(0);
        clearProblems();
        refresh();
    }

    void clearSummary() {
        summary.clear();
        composition.setText("");
        refresh();
    }

    /**
     * Ocupado: {@code what} diz com o que ("Conferindo…", "Gerando pacote…"). Enquanto isso nada e editavel e o botao nao
     * aceita outro clique. Fim do trabalho: {@code setBusy(false, null)}.
     */
    void setBusy(boolean busy, String what) {
        this.busy = busy;
        generate.setLoading(busy);
        if (busy) {
            clearProblems();
            progress.show(Notice.Kind.INFO, what);
        } else {
            progress.clear();
        }
        refresh();
    }

    void setBusy(boolean busy) {
        setBusy(busy, busy ? NavText.GENERATING : null);
    }

    boolean busy() {
        return busy;
    }

    /** Algo que aconteceu e NAO e um problema (a pessoa nao quis substituir o arquivo, por exemplo): informacao, nunca erro. */
    void setInfo(String message) {
        error.clear();
        progress.show(Notice.Kind.INFO, message);
        fit();
    }

    String infoText() {
        return progress.text();
    }

    /** Um problema sem campo proprio (ou so a frase): aparece na area de resultado, como erro. */
    void setError(String message) {
        error.show(Notice.Kind.ERROR, message);
        fit();
    }

    /**
     * A recusa do nucleo, junto do campo a que ela se refere; o codigo de suporte vai para a linha de detalhe. Os campos
     * ficam como a pessoa os deixou. Um campo da secao avancada abre a secao.
     */
    void showProblem(Workshop.Refused r) {
        clearProblems();
        String text = r.plain();
        Notice where = switch (r.field()) {
            case FOLDER -> blockers;
            case ENTRY -> entryNotice;
            case NAME -> nameNotice;
            case VERSION -> versionNotice;
            case PYTHON -> pythonNotice;
            case DEPENDENCIES -> dependenciesNotice;
            default -> error;
        };
        if (where == pythonNotice) {
            pythonCommitted = true;
        }
        where.show(Notice.Kind.ERROR, text, r.code() == null ? null : NavText.supportCode(r.code()));
        if (where == versionNotice || where == pythonNotice || where == dependenciesNotice) {
            setAdvancedOpen(true);
        }
        fit();
    }

    private void clearProblems() {
        error.clear();
        entryNotice.clear();
        nameNotice.clear();
        versionNotice.clear();
        dependenciesNotice.clear();
        if (pythonNotice.isError() && !pythonInvalid()) {
            showPythonReading();
        }
    }

    private void clearResult() {
        success.clear();
        nextStep.clear();
        generatedPath.setVisible(false);
        reveal.setVisible(false);
        progress.clear();
    }

    /** O arquivo foi gravado e conferido: "Pacote gerado", o caminho, o botao da pasta e o que fazer com ele. Nada foi enviado. */
    void showGenerated(String path) {
        done = true;
        busy = false;
        generate.setLoading(false);
        clearProblems();
        progress.clear();
        success.show(Notice.Kind.SUCCESS, NavText.GENERATED);
        generatedPath.setText(path);
        generatedPath.setCaretPosition(0);
        generatedPath.setToolTipText(path);
        generatedPath.setVisible(true);
        nextStep.show(Notice.Kind.PLAIN, NavText.GENERATED_NEXT);
        reveal.setVisible(true);
        cancel.setText(NavText.CLOSE);
        refresh();
        fit();
    }

    void setAdvancedOpen(boolean open) {
        advanced.setVisible(open);
        advancedToggle.setText(open ? NavText.GENERATE_ADVANCED_HIDE : NavText.GENERATE_ADVANCED_SHOW);
        fit();
    }

    /** O conteudo mudou de altura: a janela acompanha (quem a abriu limita a altura a area util da tela). */
    private void fit() {
        revalidate();
        repaint();
        firePropertyChange("conteudo", false, true);
    }

    private void refresh() {
        boolean edit = usable && !busy && !done;
        for (Component c : new Component[] {entry, name, version, python, dependencies}) {
            c.setEnabled(edit);
        }
        browse.setEnabled(!busy);
        generate.setEnabled(edit && !entryValue().isEmpty() && !name.getText().isBlank());
        generate.setVisible(!done);
        generate.setToolTipText(generate.isEnabled() || done ? null : NavText.GENERATE_NEEDS);
        fit();
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
        return new Values(name.getText().trim(), version.getText().trim(), entryValue(), python.getText().trim(), deps, true);
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
        return support.text();
    }

    String blockersText() {
        return blockers.text();
    }

    String notesText() {
        return notes.text();
    }

    String summaryText() {
        return summary.text();
    }

    String compositionText() {
        return composition.getText();
    }

    /** A frase de erro na tela, de qualquer campo (vazio se nao ha erro). */
    String errorText() {
        for (Notice n : List.of(error, blockers, entryNotice, nameNotice, versionNotice, pythonNotice, dependenciesNotice)) {
            if (n.isError()) {
                return n.text();
            }
        }
        return "";
    }

    Notice pythonNotice() {
        return pythonNotice;
    }

    Notice pythonOriginNotice() {
        return pythonOrigin;
    }

    Notice versionNotice() {
        return versionNotice;
    }

    Notice nameNotice() {
        return nameNotice;
    }

    Notice generalError() {
        return error;
    }

    Notice progressNotice() {
        return progress;
    }

    Notice successNotice() {
        return success;
    }

    JTextField generatedPathField() {
        return generatedPath;
    }

    String successText() {
        return success.isVisible() ? success.text() + " " + generatedPath.getText() : "";
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        title.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        title.setForeground(Themes.color(t.text()));
        for (JLabel l : labels) {
            l.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
            l.setForeground(Themes.color(t.text()));
        }
        composition.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
        generatedPath.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
    }
}
