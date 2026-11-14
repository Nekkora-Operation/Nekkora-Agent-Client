package com.nekkora.client.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import com.nekkora.client.run.Runner;
import com.nekkora.client.theme.ClientTheme;

/**
 * Preparacao e acompanhamento de UMA execucao local. Nada comeca ao abrir: "Iniciar execucao" so habilita com o pacote
 * conferido, um ambiente Python escolhido e totalmente compativel, a pasta de saida e a aprovacao explicita deste conteudo.
 * O formulario diz, no alto, que a execucao e so local e que nada vai para a Platform.
 *
 * <p><b>Ambiente Python</b> (AGENT-CLIENT-PYTHON-001): uma LISTA dos ambientes encontrados - a pessoa nao digita nem procura
 * o {@code python.exe}. As instalacoes deste computador vem da descoberta limitada; "Selecionar pasta da automacao" acrescenta
 * os ambientes virtuais daquela pasta, no alto da lista. O caminho de cada um aparece na dica do item e no detalhe do
 * escolhido. O formulario so apresenta: quem busca, confere e decide a selecao e {@link LocalActions}.
 */
final class RunForm extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel title = new JLabel(NavText.RUN_TITLE);
    private final WrappedText scope = new WrappedText(400);
    private final WrappedText packageLine = new WrappedText(400);
    private final WrappedText pythonHint = new WrappedText(400);
    private final DefaultListModel<Runner.PythonOption> pythonModel = new DefaultListModel<>();
    private final JList<Runner.PythonOption> pythons = new JList<>(pythonModel);
    private final JScrollPane pythonScroll = new JScrollPane(pythons);
    private final ActionButton selectFolder = ActionButton.compact(NavText.PY_SELECT_FOLDER, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final GhostButton searchAgain = GhostButton.text(NavText.PY_SEARCH_AGAIN);
    private final WrappedText pythonLine = new WrappedText(400);
    private final JTextField output = new JTextField(26);
    private final GhostButton chooseOutput = GhostButton.text(NavText.CHOOSE);
    private final WrappedText outputLine = new WrappedText(400);
    private final WrappedText approvalText = new WrappedText(400);
    private final GhostButton approve = GhostButton.text(NavText.RUN_APPROVE);
    private final WrappedText statusLine = new WrappedText(400);
    private final PrimaryButton start = PrimaryButton.compact(NavText.RUN_START);
    private final GhostButton cancel = GhostButton.text(NavText.RUN_CANCEL);
    private final GhostButton close = GhostButton.text(NavText.CLOSE);
    private final List<JLabel> labels = new java.util.ArrayList<>();
    /** As frases que estao dizendo um problema: a cor de erro acompanha a troca de tema. */
    private final java.util.Set<WrappedText> errors = new java.util.HashSet<>();

    private boolean packageOk;
    private boolean searching;
    private boolean settingPythons;
    private String checkingId;
    private boolean outputOk;
    private boolean approved;
    private boolean busy;
    private boolean running;

    /** O que a pessoa faz na secao "Ambiente Python". */
    interface PythonEvents {
        /** A pessoa escolheu (ou tirou a escolha de) um item da lista. */
        void chosen();

        void selectFolder();

        void searchAgain();
    }

    RunForm(PythonEvents python, Runnable onChooseOutput, Runnable onApprove, Runnable onStart, Runnable onCancel, Runnable onClose) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_5, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5));
        setOpaque(true);
        scope.setText(NavText.RUN_SCOPE);
        packageLine.setText(NavText.RUN_CHECKING);
        approvalText.setText(NavText.RUN_APPROVAL_TEXT);
        output.setEditable(false);
        output.getAccessibleContext().setAccessibleName("Pasta de saída");
        chooseOutput.getAccessibleContext().setAccessibleName("Escolher a pasta de saída");
        pythonHint.setText(NavText.PY_HINT);
        pythons.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pythons.setVisibleRowCount(2);
        pythons.getAccessibleContext().setAccessibleName(NavText.PY_LIST_NAME);
        pythons.getAccessibleContext().setAccessibleDescription(NavText.PY_HINT);
        pythons.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                Runner.PythonOption o = (Runner.PythonOption) value;
                boolean checking = o.id().equals(checkingId);
                String text = checking ? NavText.pythonKind(o) + " — " + NavText.PY_CHECKING + "…" : NavText.pythonLine(o);
                JLabel l = (JLabel) super.getListCellRendererComponent(list, text, index, selected, focus);
                ClientTheme t = Themes.current();
                // o ambiente do projeto vem no alto e em destaque; o que nao pode ser usado fica esmaecido (e continua selecionavel, para ver o motivo)
                l.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, o.kind() == com.nekkora.client.run.PythonEnvironments.Kind.PROJECT));
                if (!selected) {
                    l.setForeground(Themes.color(o.usable() || checking ? t.text() : t.textSecondary()));
                }
                l.setBorder(BorderFactory.createEmptyBorder(4, ClientTheme.SPACE_2, 4, ClientTheme.SPACE_2));
                l.setToolTipText(o.path());
                return l;
            }
        });
        pythons.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !settingPythons) {
                python.chosen();
            }
        });
        selectFolder.getAccessibleContext().setAccessibleDescription("Abre um seletor de PASTA. Procura só os ambientes virtuais diretamente dentro dessa pasta.");
        searchAgain.setToolTipText(NavText.PY_SEARCH_AGAIN_HINT);
        searchAgain.getAccessibleContext().setAccessibleDescription(NavText.PY_SEARCH_AGAIN_HINT);
        selectFolder.addActionListener(e -> python.selectFolder());
        searchAgain.addActionListener(e -> python.searchAgain());
        chooseOutput.addActionListener(e -> onChooseOutput.run());
        approve.addActionListener(e -> onApprove.run());
        start.addActionListener(e -> {
            if (start.isEnabled()) {
                onStart.run();
            }
        });
        cancel.addActionListener(e -> onCancel.run());
        close.addActionListener(e -> onClose.run());
        cancel.setVisible(false);
        pythonLine.setVisible(false);
        outputLine.setVisible(false);
        statusLine.setVisible(false);

        add(left(title));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(scope));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        section("Pacote");
        add(left(packageLine));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        section(NavText.PY_TITLE);
        add(left(pythonHint));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(pythonScroll));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        JPanel pythonButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, ClientTheme.SPACE_2, 0));
        pythonButtons.setOpaque(false);
        pythonButtons.add(selectFolder);
        pythonButtons.add(searchAgain);
        add(left(pythonButtons));
        add(left(pythonLine));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        section("Pasta de saída");
        add(left(row(output, chooseOutput)));
        add(left(outputLine));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        section("Aprovação");
        add(left(approvalText));
        add(left(row(approve)));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        add(left(statusLine));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        buttons.setOpaque(false);
        buttons.add(close);
        buttons.add(cancel);
        buttons.add(start);
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        add(left(buttons));
        // trocar o tema com a preparacao aberta repinta o formulario inteiro (fundo, textos, lista e frases de erro)
        Themes.addListener(t -> {
            restyle();
            pythons.repaint();
        });
        restyle();
        refresh();
    }

    private static <T extends javax.swing.JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel row(Component... parts) {
        JPanel p = new JPanel(new BorderLayout(ClientTheme.SPACE_2, 0));
        p.setOpaque(false);
        p.add(parts[0], parts.length > 1 ? BorderLayout.CENTER : BorderLayout.WEST);
        if (parts.length > 1) {
            p.add(parts[1], BorderLayout.EAST);
        }
        return p;
    }

    private void section(String text) {
        JLabel l = new JLabel(text);
        labels.add(l);
        add(left(l));
        add(Box.createVerticalStrut(4));
    }

    /** O pacote conferido (ou por que nao pode ser executado). */
    void showPackage(Runner.Checked checked, String problem) {
        packageOk = checked != null;
        if (checked != null) {
            approved = checked.approved();
            packageLine.setText(checked.name() + " · versão do pacote " + checked.packageVersion() + " · entrada " + checked.entry() + " · "
                    + (checked.pythonRequirement() == null ? com.nekkora.client.pack.Workshop.NO_PYTHON_REQUIREMENT : "Python " + checked.pythonRequirement())
                    + (checked.dependencies().isEmpty() ? " · sem dependências" : " · " + checked.dependencies().size() + " dependência(s) exata(s)") + " · impressão digital "
                    + checked.shortFingerprint());
        } else {
            packageLine.setText(problem);
        }
        mark(packageLine, !packageOk);
        if (packageOk) {
            showApproved(approved, null);
        }
        refresh();
    }

    /** Uma busca (das instalacoes ou da pasta) esta em curso: a lista fica como esta, os botoes esperam. */
    void showPythonSearching(String text) {
        searching = true;
        pythonLine.setText(text);
        pythonLine.setVisible(true);
        mark(pythonLine, false);
        refresh();
    }

    /**
     * A lista de agora. {@code selectedId}: o ambiente escolhido (nulo = nenhum; a lista nunca escolhe sozinha).
     * {@code message}: o que dizer abaixo da lista; {@code error}: se e um problema.
     */
    void showPythons(List<Runner.PythonOption> options, String selectedId, String message, boolean error) {
        searching = false;
        checkingId = null;
        settingPythons = true;
        try {
            pythonModel.clear();
            options.forEach(pythonModel::addElement);
            pythons.clearSelection();
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).id().equals(selectedId)) {
                    pythons.setSelectedIndex(i);
                    pythons.ensureIndexIsVisible(i);
                }
            }
            pythons.setVisibleRowCount(Math.max(2, Math.min(6, options.size())));
        } finally {
            settingPythons = false;
        }
        showPythonMessage(message, error);
    }

    void showPythonMessage(String message, boolean error) {
        pythonLine.setText(message == null ? "" : message);
        pythonLine.setVisible(!pythonLine.getText().isEmpty());
        mark(pythonLine, error);
        refresh();
    }

    /** Um item esta sendo conferido de novo (aparece como "Verificando" e nao habilita o inicio). */
    void showPythonChecking(String id) {
        checkingId = id;
        pythons.repaint();
        refresh();
    }

    /** O ambiente escolhido na lista, ou nulo. */
    Runner.PythonOption chosenPython() {
        return pythons.getSelectedValue();
    }

    /** O ambiente escolhido esta conferido e totalmente compativel? */
    private boolean pythonOk() {
        Runner.PythonOption o = pythons.getSelectedValue();
        return !searching && o != null && o.usable() && !o.id().equals(checkingId);
    }

    void showOutput(String folder, String problem) {
        output.setText(folder == null ? "" : folder);
        outputOk = folder != null && problem == null;
        outputLine.setText(problem == null ? NavText.RUN_OUTPUT_HINT : problem);
        outputLine.setVisible(folder != null || problem != null);
        mark(outputLine, problem != null);
        refresh();
    }

    void showApproved(boolean isApproved, String problem) {
        approved = isApproved;
        if (problem != null) {
            approvalText.setText(problem);
            mark(approvalText, true);
        } else {
            approvalText.setText(isApproved ? NavText.RUN_APPROVED : NavText.RUN_APPROVAL_TEXT);
            mark(approvalText, false);
        }
        refresh();
    }

    /** O andamento (ou o resultado) da execucao deste formulario. */
    void showStatus(Runner.Status s) {
        running = s.running();
        String text = s.state() + (s.running() ? " · " + NavText.elapsed(s.elapsedMillis()) : "") + (s.detail().isEmpty() ? "" : " · " + s.detail())
                + (s.finished() && !s.outputFolder().isEmpty() ? " · Saída: " + s.outputFolder() : "") + (s.finished() ? " · " + NavText.RUN_LOCAL_RECORD : "");
        statusLine.setText(text);
        statusLine.setVisible(true);
        mark(statusLine, s.finished() && !s.succeeded());
        statusLine.getAccessibleContext().setAccessibleDescription(s.finished() ? "Resultado da execução" : "Execução em andamento");
        refresh();
    }

    void showStartProblem(String problem) {
        statusLine.setText(problem);
        statusLine.setVisible(true);
        mark(statusLine, true);
        refresh();
    }

    void setBusy(boolean busy) {
        this.busy = busy;
        refresh();
    }

    private void mark(WrappedText w, boolean error) {
        if (error) {
            errors.add(w);
        } else {
            errors.remove(w);
        }
        w.setForeground(Themes.color(error ? Themes.current().dangerText() : Themes.current().textSecondary()));
        w.getAccessibleContext().setAccessibleDescription(error ? "Erro" : "");
        revalidate();
        repaint();
    }

    private void refresh() {
        boolean idle = !busy && !running;
        pythons.setEnabled(idle && packageOk && !searching);
        selectFolder.setEnabled(idle && packageOk && !searching);
        searchAgain.setEnabled(idle && packageOk && !searching);
        chooseOutput.setEnabled(idle && packageOk);
        approve.setEnabled(idle && packageOk && !approved);
        approve.setVisible(!approved);
        start.setEnabled(idle && canStart());
        start.setToolTipText(start.isEnabled() ? null : NavText.RUN_START_NEEDS);
        cancel.setVisible(running);
        revalidate();
        repaint();
    }

    /** Tudo o que a execucao exige esta conferido? */
    boolean canStart() {
        return packageOk && pythonOk() && outputOk && approved;
    }

    /** O caminho do interpretador do ambiente escolhido (vazio se nenhum). */
    String pythonPath() {
        Runner.PythonOption o = pythons.getSelectedValue();
        return o == null ? "" : o.path();
    }

    String outputFolder() {
        return output.getText();
    }

    boolean running() {
        return running;
    }

    PrimaryButton startButton() {
        return start;
    }

    GhostButton approveButton() {
        return approve;
    }

    GhostButton cancelButton() {
        return cancel;
    }

    GhostButton closeButton() {
        return close;
    }

    JList<Runner.PythonOption> pythonList() {
        return pythons;
    }

    /** As linhas da lista, como a pessoa as le. */
    List<String> pythonLines() {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < pythonModel.size(); i++) {
            Runner.PythonOption o = pythonModel.get(i);
            out.add(o.id().equals(checkingId) ? NavText.pythonKind(o) + " — " + NavText.PY_CHECKING + "…" : NavText.pythonLine(o));
        }
        return out;
    }

    ActionButton selectFolderButton() {
        return selectFolder;
    }

    GhostButton searchAgainButton() {
        return searchAgain;
    }

    java.awt.Color pythonMessageColor() {
        return pythonLine.getForeground();
    }

    String pythonHintText() {
        return pythonHint.getText();
    }

    boolean pythonSearching() {
        return searching;
    }

    String scopeText() {
        return scope.getText();
    }

    String packageText() {
        return packageLine.getText();
    }

    String pythonText() {
        return pythonLine.isVisible() ? pythonLine.getText() : "";
    }

    String outputText() {
        return outputLine.isVisible() ? outputLine.getText() : "";
    }

    String approvalText() {
        return approvalText.getText();
    }

    String statusText() {
        return statusLine.isVisible() ? statusLine.getText() : "";
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        title.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        title.setForeground(Themes.color(t.text()));
        pythons.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        pythonScroll.setBorder(BorderFactory.createLineBorder(Themes.color(t.border())));
        for (WrappedText w : List.of(scope, packageLine, pythonHint, pythonLine, outputLine, approvalText, statusLine)) {
            w.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
            w.setForeground(Themes.color(errors.contains(w) ? t.dangerText() : t.textSecondary()));
        }
        for (JLabel l : labels) {
            l.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
            l.setForeground(Themes.color(t.text()));
        }
        output.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
    }
}
