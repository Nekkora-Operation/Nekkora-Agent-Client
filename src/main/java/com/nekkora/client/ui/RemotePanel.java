package com.nekkora.client.ui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.concurrent.Executor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.nekkora.client.remote.MachineChannel.Problem;
import com.nekkora.client.remote.MachineChannel.Refused;
import com.nekkora.client.remote.RemoteMachine;
import com.nekkora.client.remote.RemoteMachine.Status;
import com.nekkora.client.remote.RemoteMachine.View;
import com.nekkora.client.theme.ClientTheme;

/**
 * A habilitacao deste computador para execucao remota: o estado em palavras e, conforme o estado, habilitar (codigo +
 * confirmacao explicita), pausar ou retomar o recebimento, desabilitar (com um segundo passo de confirmacao) ou remover
 * um vinculo encerrado. Cada clique e uma tentativa; enquanto ela corre, o formulario fica ocupado. O codigo digitado e
 * apagado do campo assim que a tentativa termina, com sucesso ou nao. Nada aqui usa a sessao de quem entrou.
 */
final class RemotePanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int WIDTH = 520;

    private interface Call {
        View run() throws Refused;
    }

    private final transient RemoteMachine machine;
    private final transient Executor background;

    private final JLabel title = new JLabel(RemoteText.TITLE);
    private final Notice status = new Notice(WIDTH);
    private final Notice contact = new Notice(WIDTH);
    private final WrappedText what = new WrappedText(WIDTH).alignLeft();
    private final WrappedText local = new WrappedText(WIDTH).alignLeft();
    private final Notice notYet = new Notice(WIDTH);
    private final WrappedText validity = new WrappedText(WIDTH).alignLeft();
    private final JLabel codeLabel = new JLabel(RemoteText.CODE_FIELD);
    private final JTextField code = new JTextField(20);
    private final JCheckBox confirm = new JCheckBox(RemoteText.CONFIRM);
    private final PrimaryButton enable = PrimaryButton.compact(RemoteText.ENABLE);
    private final PrimaryButton toggle = PrimaryButton.compact(RemoteText.PAUSE);
    private final GhostButton disable = GhostButton.text(RemoteText.DISABLE);
    private final Notice disableWarning = new Notice(WIDTH);
    private final PrimaryButton disableConfirm = PrimaryButton.compact(RemoteText.DISABLE_CONFIRM);
    private final GhostButton keep = GhostButton.text(RemoteText.KEEP);
    private final Notice result = new Notice(WIDTH);
    private final GhostButton close = GhostButton.text(RemoteText.CLOSE);
    private final JPanel enableSection = column();
    private final JPanel enabledSection = column();
    private final JPanel disableSection = column();

    private boolean busy;
    private boolean confirmingDisable;
    private boolean disposed;

    RemotePanel(RemoteMachine machine, Executor background, Runnable onClose) {
        this.machine = machine;
        this.background = background;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_5, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5));
        setOpaque(true);
        what.setText(RemoteText.WHAT);
        local.setText(RemoteText.LOCAL_STAYS_LOCAL);
        codeLabel.setLabelFor(code);
        code.getAccessibleContext().setAccessibleName(RemoteText.CODE_FIELD);
        confirm.setOpaque(false);
        confirm.getAccessibleContext().setAccessibleName(RemoteText.CONFIRM);

        // o codigo e longo e nao precisa caber inteiro: o campo tem a largura do texto da tela
        code.setMaximumSize(new java.awt.Dimension(WIDTH, code.getPreferredSize().height + 8));
        code.setPreferredSize(new java.awt.Dimension(WIDTH, code.getPreferredSize().height + 8));
        for (JComponent c : new JComponent[] {codeLabel, code, confirm}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        enableSection.add(codeLabel);
        enableSection.add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        enableSection.add(code);
        enableSection.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        enableSection.add(confirm);
        enableSection.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        enableSection.add(row(enable));

        enabledSection.add(row(toggle, disable));

        disableSection.add(disableWarning);
        disableSection.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        disableSection.add(row(disableConfirm, keep));

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        footer.setOpaque(false);
        footer.add(close);

        JComponent[] all = {title, status, contact, what, local, notYet, validity, enableSection, enabledSection, disableSection, result, footer};
        int[] gaps = {ClientTheme.SPACE_3, ClientTheme.SPACE_2, ClientTheme.SPACE_3, ClientTheme.SPACE_2, ClientTheme.SPACE_3, ClientTheme.SPACE_2,
                ClientTheme.SPACE_4, ClientTheme.SPACE_2, ClientTheme.SPACE_2, ClientTheme.SPACE_3, ClientTheme.SPACE_4, 0};
        for (int k = 0; k < all.length; k++) {
            all[k].setAlignmentX(Component.LEFT_ALIGNMENT);
            add(all[k]);
            add(Box.createVerticalStrut(gaps[k]));
        }

        code.addActionListener(e -> submitEnable());
        enable.addActionListener(e -> submitEnable());
        toggle.addActionListener(e -> {
            boolean paused = machine.view().status() == Status.PAUSED;
            attempt(toggle, paused ? machine::resume : machine::pause, null);
        });
        disable.addActionListener(e -> {
            if (machine.view().status() == Status.ENDED) {
                // vinculo ja encerrado: remover e so local, nao ha o que confirmar com a Platform
                attempt(null, machine::disable, RemoteText.REMOVED_NOW);
            } else {
                confirmingDisable = true;
                result.clear();
                render();
            }
        });
        keep.addActionListener(e -> {
            confirmingDisable = false;
            render();
        });
        disableConfirm.addActionListener(e -> attempt(disableConfirm, machine::disable, RemoteText.DISABLED_NOW));
        close.addActionListener(e -> onClose.run());
        machine.addListener(() -> SwingUtilities.invokeLater(() -> {
            if (!disposed && !busy) {
                render();
            }
        }));
        Themes.addListener(t -> {
            restyle();
            if (!disposed) {
                relayout();
            }
        });
        restyle();
        render();
    }

    /** A janela fechou: respostas que chegarem depois nao mexem mais na tela. */
    void dispose() {
        disposed = true;
        code.setText("");
    }

    private void submitEnable() {
        if (busy || !enable.isEnabled()) {
            return;
        }
        String typed = code.getText().strip();
        if (typed.isEmpty()) {
            result.show(Notice.Kind.ERROR, RemoteText.CODE_REQUIRED);
            relayout();
            return;
        }
        if (!confirm.isSelected()) {
            result.show(Notice.Kind.ERROR, RemoteText.CONFIRM_REQUIRED);
            relayout();
            return;
        }
        attempt(enable, () -> machine.enable(typed), RemoteText.ENABLED_NOW);
    }

    private void attempt(PrimaryButton source, Call call, String done) {
        if (busy) {
            return;
        }
        busy = true;
        result.clear();
        if (source != null) {
            source.setLoading(true);
        }
        render();
        background.execute(() -> {
            Problem problem = null;
            try {
                call.run();
            } catch (Refused e) {
                problem = e.problem();
            } catch (RuntimeException | LinkageError e) {
                problem = Problem.UNEXPECTED;
            }
            Problem why = problem;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                if (source != null) {
                    source.setLoading(false);
                }
                // o codigo nao fica na tela depois da tentativa, com sucesso ou nao
                code.setText("");
                confirm.setSelected(false);
                confirmingDisable = false;
                if (disposed) {
                    return;
                }
                if (why != null) {
                    result.show(Notice.Kind.ERROR, RemoteText.problem(why));
                } else if (done != null) {
                    result.show(Notice.Kind.SUCCESS, done);
                }
                render();
            });
        });
    }

    private void render() {
        View v = machine.view();
        Status s = v.status();
        boolean linked = s == Status.RECEIVING || s == Status.PAUSED;
        status.show(switch (s) {
            case RECEIVING -> Notice.Kind.SUCCESS;
            case PAUSED, ENDED -> Notice.Kind.WARNING;
            case UNAVAILABLE -> Notice.Kind.ERROR;
            default -> Notice.Kind.INFO;
        }, RemoteText.status(s));
        if (linked && !v.inContact()) {
            contact.show(Notice.Kind.WARNING, RemoteText.NO_CONTACT);
        } else {
            contact.clear();
        }
        if (linked) {
            notYet.show(Notice.Kind.INFO, RemoteText.NOT_YET);
        } else {
            notYet.clear();
        }
        String until = linked ? v.credentialExpiresAt().map(RemotePanel::day).orElse("") : "";
        validity.setText(until.isEmpty() ? "" : RemoteText.validUntil(until));
        validity.setVisible(!until.isEmpty());
        what.setVisible(s == Status.NOT_ENABLED);
        enableSection.setVisible(s == Status.NOT_ENABLED);
        enabledSection.setVisible((linked || s == Status.ENDED) && !confirmingDisable);
        disableSection.setVisible(linked && confirmingDisable);
        toggle.setVisible(linked);
        toggle.setText(s == Status.PAUSED ? RemoteText.RESUME : RemoteText.PAUSE);
        toggle.getAccessibleContext().setAccessibleName(toggle.getText());
        disable.setText(s == Status.ENDED ? RemoteText.REMOVE : RemoteText.DISABLE);
        disable.getAccessibleContext().setAccessibleName(disable.getText());
        disableWarning.show(Notice.Kind.WARNING, RemoteText.DISABLE_WARNING);
        for (JComponent c : new JComponent[] {code, confirm, enable, toggle, disable, disableConfirm, keep}) {
            c.setEnabled(!busy);
        }
        relayout();
    }

    private void relayout() {
        revalidate();
        repaint();
        // a janela acompanha a altura do conteudo (mesmo aviso dos demais formularios)
        firePropertyChange("conteudo", null, Boolean.TRUE);
    }

    /** A data de validade no fuso deste computador, como dia/mês/ano; vazio se não for uma data. */
    private static String day(String instant) {
        try {
            return java.time.OffsetDateTime.parse(instant).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (java.time.DateTimeException e) {
            return "";
        }
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private static JPanel row(JComponent... items) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setOpaque(false);
        for (int k = 0; k < items.length; k++) {
            if (k > 0) {
                p.add(Box.createHorizontalStrut(ClientTheme.SPACE_2));
            }
            p.add(items[k]);
        }
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        title.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        title.setForeground(Themes.color(t.text()));
        for (WrappedText w : new WrappedText[] {what, local, validity}) {
            w.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
            w.setForeground(Themes.color(t.textSecondary()));
        }
        codeLabel.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
        codeLabel.setForeground(Themes.color(t.text()));
        code.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        confirm.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        confirm.setForeground(Themes.color(t.text()));
    }

    // ------------------------------------------------------------------ para os testes de tela

    JTextField codeField() {
        return code;
    }

    JCheckBox confirmBox() {
        return confirm;
    }

    PrimaryButton enableButton() {
        return enable;
    }

    PrimaryButton toggleButton() {
        return toggle;
    }

    GhostButton disableButton() {
        return disable;
    }

    PrimaryButton disableConfirmButton() {
        return disableConfirm;
    }

    GhostButton keepButton() {
        return keep;
    }

    GhostButton closeButton() {
        return close;
    }

    Notice statusNotice() {
        return status;
    }

    Notice contactNotice() {
        return contact;
    }

    Notice resultNotice() {
        return result;
    }

    String validityText() {
        return validity.isVisible() ? validity.getText() : "";
    }

    Notice notYetNotice() {
        return notYet;
    }

    boolean busy() {
        return busy;
    }
}
