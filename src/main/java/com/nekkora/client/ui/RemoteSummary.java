package com.nekkora.client.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.nekkora.client.remote.RemoteMachine;
import com.nekkora.client.remote.RemoteMachine.Status;
import com.nekkora.client.remote.RemoteMachine.View;
import com.nekkora.client.theme.ClientTheme;

/**
 * O resumo da execucao remota na tela principal (depois da entrada): o VINCULO deste computador com a Platform, a
 * organizacao, o posto na Platform, a COMUNICACAO e a atividade de agora, em tres linhas curtas, com o caminho para os detalhes.
 * <p>
 * Vinculado e conectado sao coisas diferentes e aparecem separadas: "vinculado" e ter a credencial da maquina guardada;
 * "conectado" so quando a Platform aceitou o ultimo contato. Ter a credencial nao comprova contato de agora.
 * <p>
 * So le o estado da maquina; nao habilita, nao desabilita e nao usa a sessao de quem entrou (o nome da organizacao vem da
 * lista de quem entrou, quando ela esta nela).
 */
final class RemoteSummary extends JPanel {

    private static final long serialVersionUID = 1L;

    private final transient RemoteMachine machine;
    private final transient Function<String, String> organizationName;
    private final JLabel state = new JLabel();
    private final JLabel where = new JLabel();
    private final JLabel activity = new JLabel();
    private final GhostButton details = GhostButton.text(RemoteText.SUMMARY_DETAILS);

    RemoteSummary(RemoteMachine machine, Function<String, String> organizationName, Runnable onDetails) {
        this.machine = machine;
        this.organizationName = organizationName;
        setLayout(new BorderLayout(ClientTheme.SPACE_2, 0));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_2, ClientTheme.SPACE_4, ClientTheme.SPACE_2, ClientTheme.SPACE_4));
        setOpaque(true);
        JPanel lines = new JPanel();
        lines.setOpaque(false);
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        for (JLabel l : new JLabel[] {state, where, activity}) {
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            l.setFocusable(false);
            lines.add(l);
        }
        add(lines, BorderLayout.CENTER);
        details.getAccessibleContext().setAccessibleName(RemoteText.SUMMARY_DETAILS_ACCESSIBLE);
        details.addActionListener(e -> onDetails.run());
        add(details, BorderLayout.EAST);
        machine.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        Themes.addListener(t -> restyle());
        restyle();
        refresh();
    }

    /** Releitura do estado da maquina (e do nome da organizacao, que depende da lista de quem entrou). */
    void refresh() {
        View v = machine.view();
        Status s = v.status();
        boolean linked = s == Status.RECEIVING || s == Status.PAUSED;
        String org = linked ? machine.organizationId().map(organizationName).orElse("") : "";
        state.setText(RemoteText.summaryState(s, v.inContact()));
        where.setText(RemoteText.summaryWhere(linked, org, machine.seatName().orElse("")));
        where.setVisible(!where.getText().isEmpty());
        activity.setText(RemoteText.summaryActivity(machine.activity()));
        // o texto inteiro fica acessivel mesmo quando a janela estreita o corta
        state.setToolTipText(state.getText());
        where.setToolTipText(where.getText());
        activity.setToolTipText(activity.getText());
        getAccessibleContext().setAccessibleName(state.getText() + ". " + where.getText() + (where.getText().isEmpty() ? "" : ". ") + activity.getText());
        revalidate();
        repaint();
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        state.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
        state.setForeground(Themes.color(t.text()));
        for (JLabel l : new JLabel[] {where, activity}) {
            l.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
            l.setForeground(Themes.color(t.textSecondary()));
        }
    }

    // ------------------------------------------------------------------ para os testes de tela

    String stateText() {
        return state.getText();
    }

    String whereText() {
        return where.isVisible() ? where.getText() : "";
    }

    String activityText() {
        return activity.getText();
    }

    GhostButton detailsButton() {
        return details;
    }
}
