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
 * confirmacao explicita), desabilitar (com um segundo passo de confirmacao) ou remover um vinculo encerrado. Pausar e
 * o recebimento NAO e oferecido nesta tela (pedido do aceite de 17/11); o mecanismo continua em {@link RemoteMachine#pause()}.
 * Um computador que JA esteja pausado mostra o estado e, so nele, "Voltar a receber trabalhos": a recuperacao explicita, que
 * preserva o vinculo e as autorizacoes. Nada e retomado sozinho. Cada clique e uma tentativa; enquanto ela corre, o formulario fica ocupado. O codigo digitado e
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
    private final WrappedText link = new WrappedText(WIDTH).alignLeft();
    private final WrappedText what = new WrappedText(WIDTH).alignLeft();
    private final WrappedText local = new WrappedText(WIDTH).alignLeft();
    private final Notice notYet = new Notice(WIDTH);
    private final WrappedText validity = new WrappedText(WIDTH).alignLeft();
    private final WrappedText reportsPython = new WrappedText(WIDTH).alignLeft();
    private final WrappedText pythonReported = new WrappedText(WIDTH).alignLeft();
    private final Notice held = new Notice(WIDTH);
    private final Notice heldLogs = new Notice(WIDTH);
    private final Notice activity = new Notice(WIDTH);
    private final WrappedText organization = new WrappedText(WIDTH).alignLeft();
    private final WrappedText seeOnPlatform = new WrappedText(WIDTH).alignLeft();
    private final WrappedText pythonHere = new WrappedText(WIDTH).alignLeft();
    private final java.util.function.Function<String, String> organizationName;
    private final Notice lost = new Notice(WIDTH);
    private final GhostButton refreshPython = GhostButton.text(RemoteText.REFRESH_PYTHON);
    private final JLabel codeLabel = new JLabel(RemoteText.CODE_FIELD);
    private final JTextField code = new JTextField(20);
    private final JCheckBox confirm = new JCheckBox(RemoteText.CONFIRM);
    private final PrimaryButton enable = PrimaryButton.compact(RemoteText.ENABLE);
    /** So para o computador JA pausado: a recuperacao explicita do estado, sem desfazer o vinculo. Pausar nao e oferecido. */
    private final PrimaryButton resume = PrimaryButton.compact(RemoteText.RESUME);
    private final GhostButton disable = GhostButton.text(RemoteText.DISABLE);
    private final Notice disableWarning = new Notice(WIDTH);
    private final PrimaryButton disableConfirm = PrimaryButton.compact(RemoteText.DISABLE_CONFIRM);
    private final GhostButton keep = GhostButton.text(RemoteText.KEEP);
    private final Notice result = new Notice(WIDTH);
    private final GhostButton close = GhostButton.text(RemoteText.CLOSE);
    // o que e extenso fica recolhido: explicacoes, credencial e ambientes Python (a parte principal mostra organizacao, posto,
    // comunicacao e a atividade de agora)
    private final GhostButton detailsToggle = GhostButton.text(RemoteText.DETAILS_SHOW);
    private final JPanel detailsSection = column();
    private final WrappedText seatExplained = new WrappedText(WIDTH).alignLeft();
    private boolean detailsOpen;
    private final JPanel enableSection = column();
    private final JPanel enabledSection = column();
    private final JPanel disableSection = column();

    private boolean busy;
    private boolean confirmingDisable;
    private boolean disposed;

    RemotePanel(RemoteMachine machine, Executor background, Runnable onClose) {
        this(machine, background, onClose, id -> "");
    }

    /** {@code organizationName}: o nome da organizacao de um identificador, pela lista de quem entrou; vazio se ela nao esta na lista. */
    RemotePanel(RemoteMachine machine, Executor background, Runnable onClose, java.util.function.Function<String, String> organizationName) {
        this.organizationName = organizationName;
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

        enabledSection.add(row(resume, disable));
        reportsPython.setText(RemoteText.REPORTS_PYTHON);
        seatExplained.setText(RemoteText.SEAT_EXPLAINED);

        disableSection.add(disableWarning);
        disableSection.add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        disableSection.add(row(disableConfirm, keep));

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        footer.setOpaque(false);
        footer.add(close);

        seeOnPlatform.setText(RemoteText.SEE_ON_PLATFORM);
        // detalhes tecnicos, recolhidos por padrao: o que o posto significa, o que este aplicativo executa, o que fica local, onde
        // ver o conteudo, a validade da credencial e os ambientes Python informados
        JComponent refreshRow = row(refreshPython);
        for (JComponent c : new JComponent[] {seatExplained, notYet, local, seeOnPlatform, validity, reportsPython, pythonReported, pythonHere, refreshRow}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            detailsSection.add(c);
            detailsSection.add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        }
        detailsSection.getAccessibleContext().setAccessibleName(RemoteText.DETAILS_TITLE);
        detailsSection.setVisible(false);
        detailsToggle.addActionListener(e -> setDetailsOpen(!detailsOpen));
        JComponent toggleRow = row(detailsToggle);
        // parte principal: estado, vinculo, organizacao e posto, comunicacao, atividade, pendencias (so quando ha) e as acoes
        JComponent[] all = {title, status, link, organization, contact, activity, what, held, heldLogs, lost, enableSection, enabledSection, disableSection,
                result, toggleRow, detailsSection, footer};
        int[] gaps = {ClientTheme.SPACE_3, ClientTheme.SPACE_2, ClientTheme.SPACE_2, ClientTheme.SPACE_2, ClientTheme.SPACE_2, ClientTheme.SPACE_3, ClientTheme.SPACE_3,
                ClientTheme.SPACE_2, ClientTheme.SPACE_2, ClientTheme.SPACE_3, ClientTheme.SPACE_2, ClientTheme.SPACE_2, ClientTheme.SPACE_3, ClientTheme.SPACE_3,
                ClientTheme.SPACE_2, ClientTheme.SPACE_3, 0};
        for (int k = 0; k < all.length; k++) {
            all[k].setAlignmentX(Component.LEFT_ALIGNMENT);
            add(all[k]);
            add(Box.createVerticalStrut(gaps[k]));
        }

        code.addActionListener(e -> submitEnable());
        // a confirmacao desmarcada desabilita o botao (a validacao na acao continua existindo)
        confirm.addActionListener(e -> enable.setEnabled(!busy && confirm.isSelected()));
        enable.addActionListener(e -> submitEnable());
        // ato explicito de quem esta diante do computador: nada e retomado sozinho
        resume.addActionListener(e -> {
            if (machine.view().status() == Status.PAUSED) {
                attempt(resume, machine::resume, RemoteText.RESUMED_NOW);
            }
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
        refreshPython.addActionListener(e -> attempt(null, machine::publishInventory, RemoteText.PYTHON_REFRESHED));
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

    /** Abre ou recolhe os detalhes tecnicos; a janela acompanha a altura. */
    void setDetailsOpen(boolean open) {
        detailsOpen = open;
        detailsSection.setVisible(open);
        detailsToggle.setText(open ? RemoteText.DETAILS_HIDE : RemoteText.DETAILS_SHOW);
        detailsToggle.getAccessibleContext().setAccessibleName(detailsToggle.getText());
        relayout();
    }

    /** A janela fechou: respostas que chegarem depois nao mexem mais na tela. */
    void dispose() {
        disposed = true;
        code.setText("");
    }

    /**
     * O botao so fica habilitado com a confirmacao marcada; a confirmacao e conferida AQUI de novo (Enter no campo do codigo
     * chega por este caminho): sem ela, nada sai deste computador.
     */
    private void submitEnable() {
        if (busy) {
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
        // vinculo e comunicacao em linhas separadas: a credencial guardada nao comprova contato de agora
        link.setText(RemoteText.link(s));
        link.setVisible(linked);
        if (linked) {
            String last = machine.lastContactEpochSecond().isPresent() ? time(machine.lastContactEpochSecond().getAsLong()) : "";
            // conectado: texto de apoio, sem rotulo ("Concluido" nao descreve uma comunicacao); sem contato: aviso
            contact.show(v.inContact() ? Notice.Kind.PLAIN : Notice.Kind.WARNING, RemoteText.connection(v.inContact(), last));
        } else {
            contact.clear();
        }
        if (linked) {
            notYet.show(Notice.Kind.INFO, machine.executes() ? RemoteText.RUNS_HERE : RemoteText.NOT_YET);
        } else {
            notYet.clear();
        }
        // T06 - o estado operacional: para qual organizacao, o que esta rodando agora e de quem e, e onde ver o que foi produzido
        String org = linked ? machine.organizationId().map(id -> RemoteText.organization(organizationName.apply(id))).orElse("") : "";
        // o posto desta maquina na Platform, pelo nome que ela informou na presenca (ate ela chegar, so a organizacao)
        String seat = linked ? machine.seatName().map(RemoteText::seat).orElse("") : "";
        org = org.isEmpty() || seat.isEmpty() ? org : org + " " + seat;
        organization.setText(org);
        organization.setVisible(!org.isEmpty());
        seatExplained.setVisible(!seat.isEmpty());
        RemoteMachine.Activity now = machine.activity();
        String keptHere = RemoteText.kept(machine.kept());
        // PLATFORM-LICENCIAMENTO-001: a Platform recusou a ultima execucao antes do inicio (por exemplo, sem licenca) - dito aqui
        String declinedHere = linked ? machine.lastDeclined().map(d -> "\n" + RemoteText.declined(d.reason())).orElse("") : "";
        if (linked || now != RemoteMachine.Activity.IDLE || !keptHere.isEmpty()) {
            activity.show(!declinedHere.isEmpty() || !keptHere.isEmpty() ? Notice.Kind.WARNING : now == RemoteMachine.Activity.IDLE ? Notice.Kind.PLAIN : Notice.Kind.INFO,
                    RemoteText.activity(now) + keptHere + declinedHere);
        } else {
            activity.clear();
        }
        seeOnPlatform.setVisible(linked);
        java.util.List<RemoteMachine.PythonHere> envs = linked ? machine.pythonHere() : java.util.List.of();
        StringBuilder list = new StringBuilder();
        for (RemoteMachine.PythonHere e : envs) {
            list.append('\n').append(RemoteText.pythonHere(e.code(), e.version(), e.virtual(), e.where()));
        }
        pythonHere.setText(envs.isEmpty() ? "" : RemoteText.PYTHON_HERE_TITLE + list);
        pythonHere.setVisible(!envs.isEmpty());
        String until = linked ? v.credentialExpiresAt().map(RemotePanel::day).orElse("") : "";
        validity.setText(until.isEmpty() ? "" : RemoteText.validUntil(until));
        validity.setVisible(!until.isEmpty());
        String reported = linked ? v.pythonEnvironments().map(RemoteText::pythonReported).orElse("") : "";
        pythonReported.setText(reported);
        pythonReported.setVisible(!reported.isEmpty());
        refreshPython.setVisible(linked);
        // pendencia visivel em qualquer estado da habilitacao: o que ficou neste computador sem confirmacao de recebimento
        // a data e o motivo da remocao sao ditos ANTES de ela acontecer: o prazo de guarda vigente e o dia em que ele vence
        int keepDays = machine.retentionDays();
        java.util.List<RemoteMachine.Held> pending = machine.heldResults();
        if (pending.isEmpty()) {
            held.clear();
        } else {
            long first = pending.stream().mapToLong(RemoteMachine.Held::removeAtEpochSecond).min().orElse(0);
            held.show(Notice.Kind.WARNING, RemoteText.held(pending.size(), pending.stream().mapToInt(RemoteMachine.Held::files).sum(),
                    pending.stream().mapToLong(RemoteMachine.Held::bytes).sum(),
                    pending.stream().map(RemoteMachine.Held::reason).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)), day(first),
                    RemoteMachine.heldFolder(pending), keepDays));
        }
        java.util.List<RemoteMachine.HeldLogs> queues = machine.heldLogs();
        if (queues.isEmpty()) {
            heldLogs.clear();
        } else {
            heldLogs.show(Notice.Kind.WARNING, RemoteText.heldLogs(queues.size(),
                    queues.stream().map(RemoteMachine.HeldLogs::reason).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)),
                    day(queues.stream().mapToLong(RemoteMachine.HeldLogs::removeAtEpochSecond).min().orElse(0)), keepDays));
        }
        // o que ja foi removido pelo prazo nunca aparece como entregue: fica dito aqui, com o que a Platform sabe disso
        java.util.List<RemoteMachine.Lost> gone = machine.lost();
        if (gone.isEmpty()) {
            lost.clear();
        } else {
            java.util.List<RemoteMachine.Lost> files = gone.stream().filter(l -> !l.logs()).toList();
            java.util.List<RemoteMachine.Lost> lines = gone.stream().filter(RemoteMachine.Lost::logs).toList();
            int notice = gone.stream().anyMatch(l -> "PENDING".equals(l.notice())) ? 1 : gone.stream().anyMatch(l -> "IMPOSSIBLE".equals(l.notice())) ? 2 : 0;
            lost.show(Notice.Kind.ERROR, RemoteText.lost(files.size(), files.stream().mapToInt(RemoteMachine.Lost::count).sum(), lines.size(),
                    lines.stream().mapToInt(RemoteMachine.Lost::count).sum(),
                    day(gone.stream().mapToLong(RemoteMachine.Lost::lostAtEpochSecond).max().orElse(0)), notice));
        }
        what.setVisible(s == Status.NOT_ENABLED);
        enableSection.setVisible(s == Status.NOT_ENABLED);
        enabledSection.setVisible((linked || s == Status.ENDED) && !confirmingDisable);
        disableSection.setVisible(linked && confirmingDisable);
        resume.setVisible(s == Status.PAUSED);
        disable.setText(s == Status.ENDED ? RemoteText.REMOVE : RemoteText.DISABLE);
        disable.getAccessibleContext().setAccessibleName(disable.getText());
        // desabilitar impede de vez o envio do que ainda nao foi confirmado: dito ANTES de a pessoa confirmar
        boolean sending = pending.stream().anyMatch(h -> "SENDING".equals(h.reason())) || queues.stream().anyMatch(q -> "SENDING".equals(q.reason()));
        disableWarning.show(Notice.Kind.WARNING, sending ? RemoteText.DISABLE_WARNING_SENDING : RemoteText.DISABLE_WARNING);
        for (JComponent c : new JComponent[] {code, confirm, enable, resume, disable, disableConfirm, keep, refreshPython}) {
            c.setEnabled(!busy);
        }
        enable.setEnabled(!busy && confirm.isSelected());
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

    /** A hora, no fuso deste computador, como HH:mm:ss. */
    private static String time(long epochSecond) {
        return java.time.Instant.ofEpochSecond(epochSecond).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
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
        for (WrappedText w : new WrappedText[] {what, local, validity, reportsPython, pythonReported, organization, seeOnPlatform, pythonHere, link, seatExplained}) {
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

    PrimaryButton resumeButton() {
        return resume;
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

    GhostButton detailsToggle() {
        return detailsToggle;
    }

    boolean detailsOpen() {
        return detailsSection.isVisible();
    }

    /** O que esta de fato na tela agora dentro dos detalhes tecnicos (vazio com eles recolhidos). */
    boolean detailsShowing() {
        return detailsSection.isShowing();
    }

    String linkText() {
        return link.isVisible() ? link.getText() : "";
    }

    Notice resultNotice() {
        return result;
    }

    Notice activityNotice() {
        return activity;
    }

    String organizationText() {
        return organization.isVisible() ? organization.getText() : "";
    }

    String seeOnPlatformText() {
        return seeOnPlatform.isVisible() ? seeOnPlatform.getText() : "";
    }

    String pythonHereText() {
        return pythonHere.isVisible() ? pythonHere.getText() : "";
    }

    Notice heldLogsNotice() {
        return heldLogs;
    }

    Notice disableWarningNotice() {
        return disableWarning;
    }

    Notice lostNotice() {
        return lost;
    }

    private static String day(long epochSecond) {
        return java.time.Instant.ofEpochSecond(epochSecond).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    Notice heldNotice() {
        return held;
    }

    String pythonReportedText() {
        return pythonReported.isVisible() ? pythonReported.getText() : "";
    }

    String reportsPythonText() {
        return reportsPython.getText();
    }

    GhostButton refreshPythonButton() {
        return refreshPython;
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
