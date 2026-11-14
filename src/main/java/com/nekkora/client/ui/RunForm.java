package com.nekkora.client.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.nekkora.client.run.Runner;
import com.nekkora.client.theme.ClientTheme;

/**
 * Preparacao e acompanhamento de UMA execucao local. Nada comeca ao abrir: "Iniciar execucao" so habilita com o pacote
 * conferido, um Python escolhido pela pessoa e compativel, a pasta de saida e a aprovacao explicita deste conteudo.
 * O formulario diz, no alto, que a execucao e so local e que nada vai para a Platform.
 */
final class RunForm extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel title = new JLabel(NavText.RUN_TITLE);
    private final WrappedText scope = new WrappedText(400);
    private final WrappedText packageLine = new WrappedText(400);
    private final JTextField python = new JTextField(26);
    private final GhostButton choosePython = GhostButton.text(NavText.CHOOSE);
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

    private boolean packageOk;
    private boolean pythonOk;
    private boolean outputOk;
    private boolean approved;
    private boolean busy;
    private boolean running;

    RunForm(Runnable onChoosePython, Runnable onChooseOutput, Runnable onApprove, Runnable onStart, Runnable onCancel, Runnable onClose) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_5, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5));
        setOpaque(true);
        scope.setText(NavText.RUN_SCOPE);
        packageLine.setText(NavText.RUN_CHECKING);
        approvalText.setText(NavText.RUN_APPROVAL_TEXT);
        python.setEditable(false);
        output.setEditable(false);
        python.getAccessibleContext().setAccessibleName("Interpretador Python");
        output.getAccessibleContext().setAccessibleName("Pasta de saída");
        choosePython.getAccessibleContext().setAccessibleName("Escolher o interpretador Python");
        chooseOutput.getAccessibleContext().setAccessibleName("Escolher a pasta de saída");
        choosePython.addActionListener(e -> onChoosePython.run());
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
        section("Python (escolhido por você; nada é instalado)");
        add(left(row(python, choosePython)));
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
            packageLine.setText(checked.name() + " · versão do pacote " + checked.packageVersion() + " · entrada " + checked.entry() + " · Python " + checked.pythonRequirement()
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

    void showPython(String path, Runner.Python found, String problem) {
        python.setText(path == null ? "" : path);
        pythonOk = found != null;
        pythonLine.setText(found != null ? "Python " + found.version() + ": compatível com o pacote, dependências conferidas." : problem == null ? "" : problem);
        pythonLine.setVisible(!pythonLine.getText().isEmpty());
        mark(pythonLine, found == null);
        refresh();
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
        w.setForeground(Themes.color(error ? Themes.current().dangerText() : Themes.current().textSecondary()));
        w.getAccessibleContext().setAccessibleDescription(error ? "Erro" : "");
        revalidate();
        repaint();
    }

    private void refresh() {
        boolean idle = !busy && !running;
        choosePython.setEnabled(idle && packageOk);
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
        return packageOk && pythonOk && outputOk && approved;
    }

    String pythonPath() {
        return python.getText();
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

    GhostButton choosePythonButton() {
        return choosePython;
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
        for (WrappedText w : List.of(scope, packageLine, pythonLine, outputLine, approvalText, statusLine)) {
            w.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
            w.setForeground(Themes.color(t.textSecondary()));
        }
        for (JLabel l : labels) {
            l.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
            l.setForeground(Themes.color(t.text()));
        }
        python.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        output.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
    }
}
