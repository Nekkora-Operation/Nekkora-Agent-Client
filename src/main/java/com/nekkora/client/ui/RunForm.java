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
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.nekkora.client.run.Runner;
import com.nekkora.client.theme.ClientTheme;

/**
 * Preparacao e acompanhamento de UMA execucao local (AGENT-CLIENT-PREPARACAO-002: versao compacta). Nada comeca ao abrir:
 * "Iniciar execucao" so habilita com o pacote conferido, um ambiente Python escolhido e totalmente compativel, a pasta de
 * saida e a aprovacao explicita deste conteudo.
 *
 * <p>Na tela principal, em ordem: o titulo; uma frase curta (execucao local, registros neste computador); a AUTOMACAO (nome
 * e versao); o LOCAL DA AUTOMACAO (a pasta do pacote de origem, com "Abrir pasta" - o local de execucao so existe depois do
 * inicio, e a tela diz isso em vez de inventar um caminho); o AMBIENTE PYTHON, numa caixa de selecao, com "Localizar ambiente
 * virtual" e "Buscar novamente"; a PASTA DE SAIDA; a aprovacao e "Iniciar execucao". Nome completo, entrada, requisitos,
 * impressao digital e o caminho do interpretador ficam em "Detalhes tecnicos", recolhido.
 *
 * <p>Tres caminhos diferentes, tres campos diferentes: onde esta o codigo, qual interpretador o executa e onde a saida e
 * gravada. Escolher um nao muda os outros. Caminhos ficam em campos proprios, numa linha, alinhados a esquerda, selecionaveis
 * e com o texto inteiro na dica.
 *
 * <p>O resultado da ULTIMA execucao tem uma area propria, identificada e separada da preparacao: a frase e os caminhos dela
 * nao se misturam com os da proxima execucao. As mensagens dizem a gravidade em palavras ({@link Notice}); so um impedimento
 * de verdade e erro. O formulario so apresenta: quem busca, confere e decide a selecao e {@link LocalActions}.
 */
final class RunForm extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int WIDTH = 440;

    /** O que a pessoa faz na preparacao. Nenhum destes inicia a execucao, exceto {@link #start()}. */
    interface Events {
        /** A pessoa escolheu um ambiente na caixa de selecao. */
        void pythonChosen();

        /** "Localizar ambiente virtual": a pessoa aponta a pasta RAIZ de um ambiente virtual. */
        void locateEnvironment();

        void searchAgain();

        void chooseOutput();

        void approve();

        void start();

        void cancel();

        void close();

        /** Abrir, no gerenciador de arquivos, a pasta dita (nada dela e executado). */
        void openFolder(String folder);
    }

    private final JLabel title = new JLabel(NavText.RUN_TITLE);
    private final Notice scope = new Notice(WIDTH);
    private final Notice automation = new Notice(WIDTH);
    private final JTextField code = new JTextField(26);
    private final GhostButton openCode = GhostButton.text(NavText.RUN_OPEN_FOLDER);
    private final Notice codeNote = new Notice(WIDTH);
    private final DefaultComboBoxModel<Runner.PythonOption> pythonModel = new DefaultComboBoxModel<>();
    private final JComboBox<Runner.PythonOption> pythons = new JComboBox<>(pythonModel);
    private final ActionButton locate = ActionButton.compact(NavText.PY_LOCATE, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final GhostButton searchAgain = GhostButton.text(NavText.PY_SEARCH_AGAIN);
    private final Notice pythonNotice = new Notice(WIDTH);
    private final JTextField output = new JTextField(26);
    private final GhostButton chooseOutput = GhostButton.text(NavText.CHOOSE);
    private final Notice outputNotice = new Notice(WIDTH);
    private final Notice approval = new Notice(WIDTH);
    private final GhostButton approve = GhostButton.text(NavText.RUN_APPROVE);
    private final GhostButton detailsToggle = GhostButton.text(NavText.RUN_DETAILS_SHOW);
    private final JTextArea details = new JTextArea(8, 26);
    private final JScrollPane detailsScroll = new JScrollPane(details);
    private final Notice startNotice = new Notice(WIDTH);
    private final PrimaryButton start = PrimaryButton.compact(NavText.RUN_START);
    private final GhostButton cancel = GhostButton.text(NavText.RUN_CANCEL);
    private final GhostButton close = GhostButton.text(NavText.CLOSE);
    // ---- area propria da ultima execucao
    private final JPanel last = new JPanel();
    private final JSeparator lastSeparator = new JSeparator();
    private final JLabel lastTitle = new JLabel(NavText.RUN_LAST_TITLE);
    private final Notice lastState = new Notice(WIDTH);
    private final JLabel lastRunLabel = new JLabel(NavText.RUN_LAST_CODE);
    private final JTextField lastRun = new JTextField(26);
    private final GhostButton openLastRun = GhostButton.text(NavText.RUN_OPEN_FOLDER);
    private final JLabel lastOutputLabel = new JLabel(NavText.RUN_LAST_OUTPUT);
    private final JTextField lastOutput = new JTextField(26);
    private final GhostButton openLastOutput = GhostButton.text(NavText.RUN_OPEN_FOLDER);
    private final JLabel lastTextLabel = new JLabel(NavText.RUN_LAST_TEXT);
    private final JTextField lastText = new JTextField(26);
    private final GhostButton openLastText = GhostButton.text(NavText.RUN_OPEN_TEXT);
    private final JPanel lastRunRow;
    private final JPanel lastOutputRow;
    private final JPanel lastTextRow;
    private final List<JLabel> labels = new ArrayList<>();
    private final List<JTextField> paths = new ArrayList<>();

    private Runner.Checked checked;
    private boolean packageOk;
    private boolean searching;
    private boolean settingPythons;
    private String checkingId;
    private boolean outputOk;
    private boolean approved;
    private boolean busy;
    private boolean running;
    private String placeholder = NavText.PY_PLACEHOLDER_SEARCHING;
    private List<String> failedSources = List.of();

    RunForm(Events events) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_4, ClientTheme.SPACE_5, ClientTheme.SPACE_3, ClientTheme.SPACE_5));
        setOpaque(true);
        scope.show(Notice.Kind.PLAIN, NavText.RUN_SCOPE);
        automation.show(Notice.Kind.PLAIN, NavText.RUN_CHECKING);
        path(code, NavText.RUN_CODE);
        path(output, NavText.RUN_OUTPUT);
        path(lastRun, NavText.RUN_LAST_CODE);
        path(lastOutput, NavText.RUN_LAST_OUTPUT);
        path(lastText, NavText.RUN_LAST_TEXT);
        openCode.getAccessibleContext().setAccessibleName(NavText.RUN_OPEN_CODE);
        openCode.setToolTipText(NavText.RUN_OPEN_HINT);
        chooseOutput.getAccessibleContext().setAccessibleName("Escolher a pasta de saída");
        openLastRun.getAccessibleContext().setAccessibleName(NavText.RUN_OPEN_LAST_CODE);
        openLastOutput.getAccessibleContext().setAccessibleName(NavText.RUN_OPEN_LAST_OUTPUT);
        openLastText.getAccessibleContext().setAccessibleName(NavText.RUN_OPEN_LAST_TEXT);

        pythons.getAccessibleContext().setAccessibleName(NavText.PY_TITLE);
        pythons.getAccessibleContext().setAccessibleDescription(NavText.PY_BOX_HINT);
        pythons.setMaximumRowCount(8);
        pythons.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                Runner.PythonOption o = (Runner.PythonOption) value;
                // sem escolha, a caixa diz o que falta (procurando, escolher, nenhum encontrado): nunca fica em branco
                String text = o == null ? placeholder : o.id().equals(checkingId) ? NavText.pythonChecking(o) : NavText.pythonChoice(o);
                JLabel l = (JLabel) super.getListCellRendererComponent(list, text, index, selected, focus);
                ClientTheme t = Themes.current();
                if (!selected) {
                    l.setForeground(Themes.color(o != null && (o.usable() || o.id().equals(checkingId)) ? t.text() : t.textSecondary()));
                }
                l.setToolTipText(o == null ? null : o.path()); // o caminho do interpretador fica na dica e nos detalhes tecnicos
                return l;
            }
        });
        pythons.addActionListener(e -> {
            if (!settingPythons) {
                events.pythonChosen();
            }
        });
        locate.getAccessibleContext().setAccessibleDescription(NavText.PY_LOCATE_HINT);
        locate.setToolTipText(NavText.PY_LOCATE_HINT);
        searchAgain.setToolTipText(NavText.PY_SEARCH_AGAIN_HINT);
        searchAgain.getAccessibleContext().setAccessibleDescription(NavText.PY_SEARCH_AGAIN_HINT);
        locate.addActionListener(e -> events.locateEnvironment());
        searchAgain.addActionListener(e -> events.searchAgain());
        openCode.addActionListener(e -> events.openFolder(code.getText()));
        chooseOutput.addActionListener(e -> events.chooseOutput());
        approve.addActionListener(e -> events.approve());
        start.addActionListener(e -> {
            if (start.isEnabled()) {
                events.start();
            }
        });
        cancel.addActionListener(e -> events.cancel());
        close.addActionListener(e -> events.close());
        openLastRun.addActionListener(e -> events.openFolder(lastRun.getText()));
        openLastOutput.addActionListener(e -> events.openFolder(lastOutput.getText()));
        openLastText.addActionListener(e -> events.openFolder(lastText.getText()));
        cancel.setVisible(false);
        details.setEditable(false);
        details.setLineWrap(false);
        details.getAccessibleContext().setAccessibleName(NavText.RUN_DETAILS);
        detailsScroll.setVisible(false);
        detailsToggle.addActionListener(e -> setDetailsOpen(!detailsScroll.isVisible()));

        int gap = ClientTheme.SPACE_2 + 2;
        add(left(title));
        add(Box.createVerticalStrut(2));
        add(scope);
        add(Box.createVerticalStrut(gap));
        section(this, NavText.RUN_AUTOMATION, automation);
        add(automation);
        add(Box.createVerticalStrut(gap));
        section(this, NavText.RUN_CODE, code);
        add(left(row(code, openCode)));
        add(codeNote);
        add(Box.createVerticalStrut(gap));
        section(this, NavText.PY_TITLE, pythons);
        add(left(pythons));
        add(Box.createVerticalStrut(4));
        JPanel pythonButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, ClientTheme.SPACE_2, 0));
        pythonButtons.setOpaque(false);
        pythonButtons.add(locate);
        pythonButtons.add(searchAgain);
        add(left(pythonButtons));
        add(pythonNotice);
        add(Box.createVerticalStrut(gap));
        section(this, NavText.RUN_OUTPUT, output);
        add(left(row(output, chooseOutput)));
        add(outputNotice);
        add(Box.createVerticalStrut(gap));
        section(this, NavText.RUN_APPROVAL, approve);
        add(approval);
        add(left(row(approve)));
        add(Box.createVerticalStrut(4));
        add(left(detailsToggle));
        add(left(detailsScroll));
        add(startNotice);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        buttons.setOpaque(false);
        buttons.add(close);
        buttons.add(cancel);
        buttons.add(start);
        add(Box.createVerticalStrut(4));
        add(left(buttons));

        // ---- ultima execucao: area propria, separada da preparacao
        last.setLayout(new BoxLayout(last, BoxLayout.Y_AXIS));
        last.setOpaque(false);
        last.setVisible(false);
        last.getAccessibleContext().setAccessibleName(NavText.RUN_LAST_TITLE);
        last.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        last.add(left(lastSeparator));
        last.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        labels.add(lastTitle);
        last.add(left(lastTitle));
        last.add(Box.createVerticalStrut(4));
        last.add(lastState);
        lastRunRow = lastRow(lastRunLabel, lastRun, openLastRun);
        lastOutputRow = lastRow(lastOutputLabel, lastOutput, openLastOutput);
        lastTextRow = lastRow(lastTextLabel, lastText, openLastText);
        last.add(lastRunRow);
        last.add(lastOutputRow);
        last.add(lastTextRow);
        add(left(last));

        // trocar o tema com a preparacao aberta repinta o formulario inteiro (fundo, textos, caixa e mensagens)
        Themes.addListener(t -> {
            restyle();
            pythons.repaint();
        });
        restyle();
        showApproved(false, null);
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

    private JPanel lastRow(JLabel label, JTextField field, GhostButton open) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        labels.add(label);
        label.setLabelFor(field);
        p.add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        p.add(left(label));
        p.add(Box.createVerticalStrut(4));
        p.add(left(row(field, open)));
        p.setVisible(false);
        return left(p);
    }

    /** Um caminho: campo proprio, de uma linha, so leitura, alinhado a esquerda; o texto inteiro fica na dica e pode ser copiado. */
    private void path(JTextField f, String name) {
        f.setEditable(false);
        f.setHorizontalAlignment(JTextField.LEFT);
        f.getAccessibleContext().setAccessibleName(name);
        paths.add(f);
    }

    private static void setPath(JTextField f, String value) {
        f.setText(value == null ? "" : value);
        f.setCaretPosition(0);
        f.setToolTipText(value == null || value.isEmpty() ? null : value);
    }

    private void section(JPanel where, String text, javax.swing.JComponent of) {
        JLabel l = new JLabel(text);
        l.setLabelFor(of);
        labels.add(l);
        where.add(left(l));
        where.add(Box.createVerticalStrut(4));
    }

    /** O conteudo mudou de altura: a janela acompanha (quem a abriu limita a altura a area util da tela). */
    private void fit() {
        revalidate();
        repaint();
        firePropertyChange("conteudo", false, true);
    }

    /** O pacote conferido (ou por que nao pode ser executado). */
    void showPackage(Runner.Checked ok, String problem) {
        checked = ok;
        packageOk = ok != null;
        if (ok != null) {
            approved = ok.approved();
            automation.show(Notice.Kind.PLAIN, NavText.runAutomation(ok));
            setPath(code, ok.packageDir());
            // o processo roda numa copia criada no inicio: antes disso, o local de execucao nao existe e nao e inventado
            codeNote.show(Notice.Kind.PLAIN, NavText.RUN_CODE_NOTE);
            showApproved(approved, null);
        } else {
            automation.show(Notice.Kind.ERROR, problem);
            setPath(code, "");
            codeNote.clear();
        }
        showDetails();
        refresh();
    }

    /** Uma busca (das instalacoes ou do ambiente apontado) esta em curso: a caixa fica como esta, os botoes esperam. */
    void showPythonSearching(String text) {
        searching = true;
        if (pythonModel.getSize() == 0) {
            placeholder = NavText.PY_PLACEHOLDER_SEARCHING;
        }
        pythonNotice.show(Notice.Kind.INFO, text);
        pythons.repaint();
        refresh();
    }

    /**
     * As opcoes de agora. {@code selectedId}: o ambiente escolhido (nulo = nenhum; a caixa nunca escolhe sozinha).
     * {@code kind} e {@code message}: o que dizer abaixo da caixa, com a gravidade.
     */
    void showPythons(List<Runner.PythonOption> options, String selectedId, Notice.Kind kind, String message, List<String> failed) {
        searching = false;
        checkingId = null;
        failedSources = failed == null ? List.of() : List.copyOf(failed);
        settingPythons = true;
        try {
            pythonModel.removeAllElements();
            Runner.PythonOption selected = null;
            for (Runner.PythonOption o : options) {
                pythonModel.addElement(o);
                if (o.id().equals(selectedId)) {
                    selected = o;
                }
            }
            placeholder = options.isEmpty() ? NavText.PY_PLACEHOLDER_NONE : NavText.PY_PLACEHOLDER_CHOOSE;
            pythons.setSelectedItem(selected);
            if (selected == null) {
                pythons.setSelectedIndex(-1);
            }
        } finally {
            settingPythons = false;
        }
        showPythonMessage(kind, message);
    }

    void showPythonMessage(Notice.Kind kind, String message) {
        pythonNotice.show(kind, message);
        showDetails();
        refresh();
    }

    /** Um item esta sendo conferido de novo (aparece como "verificando" e nao habilita o inicio). */
    void showPythonChecking(String id) {
        checkingId = id;
        pythons.repaint();
        refresh();
    }

    /** O ambiente escolhido na caixa, ou nulo. */
    Runner.PythonOption chosenPython() {
        return (Runner.PythonOption) pythons.getSelectedItem();
    }

    /** O ambiente escolhido esta conferido e totalmente compativel? */
    private boolean pythonOk() {
        Runner.PythonOption o = chosenPython();
        return !searching && o != null && o.usable() && !o.id().equals(checkingId);
    }

    /** A pasta de saida. So aparece uma mensagem quando ha um problema: uma pasta valida nao e marcada. */
    void showOutput(String folder, String problem) {
        setPath(output, folder);
        outputOk = folder != null && problem == null;
        outputNotice.show(Notice.Kind.ERROR, problem);
        refresh();
    }

    void showApproved(boolean isApproved, String problem) {
        approved = isApproved;
        if (problem != null) {
            approval.show(Notice.Kind.ERROR, problem);
        } else if (isApproved) {
            approval.show(Notice.Kind.PLAIN, NavText.RUN_APPROVED); // a frase diz a situacao; nao e um resultado a destacar
        } else {
            approval.show(Notice.Kind.PLAIN, NavText.RUN_APPROVAL_TEXT);
        }
        refresh();
    }

    /**
     * O andamento (ou o resultado) da execucao desta janela, na AREA DA ULTIMA EXECUCAO: a situacao, de onde o processo
     * rodou e onde a saida foi gravada. Nada disto e escrito nos campos da preparacao.
     */
    void showStatus(Runner.Status s) {
        running = s.running();
        startNotice.clear();
        last.setVisible(true);
        String text = s.state() + (s.running() ? " · " + NavText.elapsed(s.elapsedMillis()) : "") + (s.detail().isEmpty() ? "" : " · " + s.detail())
                + (s.finished() ? " · " + NavText.RUN_LOCAL_RECORD : "");
        lastState.show(!s.finished() ? Notice.Kind.INFO : s.succeeded() ? Notice.Kind.SUCCESS : Notice.Kind.ERROR, text);
        lastState.getAccessibleContext().setAccessibleDescription(s.finished() ? "Resultado da última execução" : "Execução em andamento");
        setPath(lastRun, s.runDir());
        lastRunRow.setVisible(!s.runDir().isEmpty());
        setPath(lastOutput, s.outputFolder());
        lastOutputRow.setVisible(!s.outputFolder().isEmpty());
        setPath(lastText, s.outputText());
        lastTextRow.setVisible(!s.outputText().isEmpty());
        refresh();
    }

    /** O inicio foi recusado: erro, junto do botao; os campos ficam como estao. */
    void showStartProblem(String problem) {
        startNotice.show(Notice.Kind.ERROR, problem);
        refresh();
    }

    void setBusy(boolean busy) {
        this.busy = busy;
        refresh();
    }

    void setDetailsOpen(boolean open) {
        detailsScroll.setVisible(open);
        detailsToggle.setText(open ? NavText.RUN_DETAILS_HIDE : NavText.RUN_DETAILS_SHOW);
        fit();
    }

    /** Tudo o que e extenso: nome completo, entrada, requisitos, impressao digital, caminho do interpretador e o motivo de cada situacao. */
    private void showDetails() {
        details.setText(NavText.runDetails(checked, chosenPython(), failedSources));
        details.setCaretPosition(0);
    }

    private void refresh() {
        boolean idle = !busy && !running;
        pythons.setEnabled(idle && packageOk && !searching);
        locate.setEnabled(idle && packageOk && !searching);
        searchAgain.setEnabled(idle && packageOk && !searching);
        chooseOutput.setEnabled(idle && packageOk);
        openCode.setEnabled(!code.getText().isEmpty());
        approve.setEnabled(idle && packageOk && !approved);
        approve.setVisible(!approved);
        start.setEnabled(idle && canStart());
        start.setToolTipText(start.isEnabled() ? null : NavText.runStartNeeds(packageOk, pythonOk(), outputOk, approved));
        cancel.setVisible(running);
        fit();
    }

    /** Tudo o que a execucao exige esta conferido? */
    boolean canStart() {
        return packageOk && pythonOk() && outputOk && approved;
    }

    /** O caminho do interpretador do ambiente escolhido (vazio se nenhum). */
    String pythonPath() {
        Runner.PythonOption o = chosenPython();
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

    JComboBox<Runner.PythonOption> pythonBox() {
        return pythons;
    }

    /** As opcoes da caixa, como a pessoa as le. */
    List<String> pythonLines() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pythonModel.getSize(); i++) {
            Runner.PythonOption o = pythonModel.getElementAt(i);
            out.add(o.id().equals(checkingId) ? NavText.pythonChecking(o) : NavText.pythonChoice(o));
        }
        return out;
    }

    /** O que a caixa mostra fechada: a opcao escolhida ou, sem escolha, o que falta. */
    String pythonShown() {
        Runner.PythonOption o = chosenPython();
        return o == null ? placeholder : o.id().equals(checkingId) ? NavText.pythonChecking(o) : NavText.pythonChoice(o);
    }

    ActionButton locateButton() {
        return locate;
    }

    GhostButton searchAgainButton() {
        return searchAgain;
    }

    Notice pythonNotice() {
        return pythonNotice;
    }

    boolean pythonSearching() {
        return searching;
    }

    String scopeText() {
        return scope.text();
    }

    /** A linha da automacao (ou a frase de por que o pacote nao pode ser executado). */
    String packageText() {
        return automation.text();
    }

    Notice automationNotice() {
        return automation;
    }

    JTextField codeField() {
        return code;
    }

    GhostButton openCodeButton() {
        return openCode;
    }

    String codeNoteText() {
        return codeNote.text();
    }

    String pythonText() {
        return pythonNotice.text();
    }

    JTextField outputField() {
        return output;
    }

    Notice outputNotice() {
        return outputNotice;
    }

    String outputText() {
        return outputNotice.text();
    }

    Notice approvalNotice() {
        return approval;
    }

    String approvalText() {
        return approval.text();
    }

    GhostButton detailsToggle() {
        return detailsToggle;
    }

    boolean detailsOpen() {
        return detailsScroll.isVisible();
    }

    String detailsText() {
        return details.getText();
    }

    Notice startNotice() {
        return startNotice;
    }

    /** A area da ultima execucao esta na tela? */
    boolean lastShown() {
        return last.isVisible();
    }

    Notice lastNotice() {
        return lastState;
    }

    /** A frase da ultima execucao (vazia se nao houve nenhuma nesta janela) ou a do inicio recusado. */
    String statusText() {
        return last.isVisible() && !lastState.text().isEmpty() ? lastState.text() : startNotice.text();
    }

    JTextField lastRunField() {
        return lastRun;
    }

    JTextField lastTextField() {
        return lastText;
    }

    GhostButton openLastTextButton() {
        return openLastText;
    }

    JTextField lastOutputField() {
        return lastOutput;
    }

    GhostButton openLastRunButton() {
        return openLastRun;
    }

    GhostButton openLastOutputButton() {
        return openLastOutput;
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        title.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        title.setForeground(Themes.color(t.text()));
        pythons.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        for (JLabel l : labels) {
            l.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
            l.setForeground(Themes.color(t.text()));
        }
        for (JTextField f : paths) {
            f.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        }
        details.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
        lastSeparator.setForeground(Themes.color(t.border()));
    }
}
