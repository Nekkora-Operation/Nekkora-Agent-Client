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
 * O resumo da execucao remota na tela principal (depois da entrada): o VINCULO deste computador com a Platform e a
 * COMUNICACAO, a organizacao, o posto na Platform e a atividade de agora, uma informacao por linha curta, com o caminho
 * para os detalhes.
 * <p>
 * Vinculado e conectado sao coisas diferentes e aparecem separadas: "vinculado" e ter a credencial da maquina guardada;
 * "conectado" so quando a Platform aceitou o ultimo contato. Ter a credencial nao comprova contato de agora.
 * <p>
 * As linhas usam a largura inteira da janela (o botao fica na ultima, ao lado da atividade): a primeira, que diz a
 * comunicacao, nunca divide espaco com o botao. So le o estado da maquina; nao habilita, nao desabilita e nao usa a sessao
 * de quem entrou (o nome da organizacao vem da lista de quem entrou, quando ela esta nela).
 */
final class RemoteSummary extends JPanel {

    private static final long serialVersionUID = 1L;

    private final transient RemoteMachine machine;
    private final transient Function<String, String> organizationName;
    private final JLabel state = new JLabel();
    private final JLabel organization = new JLabel();
    private final JLabel seat = new JLabel();
    private final JLabel activity = new JLabel();
    private final GhostButton details = GhostButton.text(RemoteText.SUMMARY_DETAILS);

    RemoteSummary(RemoteMachine machine, Function<String, String> organizationName, Runnable onDetails) {
        this.machine = machine;
        this.organizationName = organizationName;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_2, ClientTheme.SPACE_4, ClientTheme.SPACE_2, ClientTheme.SPACE_4));
        setOpaque(true);
        for (JLabel l : new JLabel[] {state, organization, seat}) {
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            l.setFocusable(false);
            add(l);
        }
        JPanel last = new JPanel(new BorderLayout(ClientTheme.SPACE_2, 0));
        last.setOpaque(false);
        last.setAlignmentX(Component.LEFT_ALIGNMENT);
        activity.setFocusable(false);
        last.add(activity, BorderLayout.CENTER);
        details.getAccessibleContext().setAccessibleName(RemoteText.SUMMARY_DETAILS_ACCESSIBLE);
        details.addActionListener(e -> onDetails.run());
        last.add(details, BorderLayout.EAST);
        add(last);
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
        organization.setText(RemoteText.summaryOrganization(linked, org));
        seat.setText(RemoteText.summarySeat(linked, machine.seatName().orElse("")));
        activity.setText(RemoteText.summaryActivity(machine.activity()));
        StringBuilder all = new StringBuilder();
        for (JLabel l : new JLabel[] {state, organization, seat, activity}) {
            l.setVisible(!l.getText().isEmpty());
            // o texto inteiro fica acessivel mesmo quando um nome longo e cortado pela largura da janela
            l.setToolTipText(l.getText());
            if (l.isVisible()) {
                all.append(all.length() == 0 ? "" : ". ").append(l.getText());
            }
        }
        getAccessibleContext().setAccessibleName(all.toString());
        revalidate();
        repaint();
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        state.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
        state.setForeground(Themes.color(t.text()));
        for (JLabel l : new JLabel[] {organization, seat, activity}) {
            l.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
            l.setForeground(Themes.color(t.textSecondary()));
        }
    }

    // ------------------------------------------------------------------ para os testes de tela

    String stateText() {
        return state.getText();
    }

    String organizationText() {
        return organization.isVisible() ? organization.getText() : "";
    }

    String seatText() {
        return seat.isVisible() ? seat.getText() : "";
    }

    String activityText() {
        return activity.getText();
    }

    /** A primeira linha cabe inteira na largura que a tela lhe da agora (nada do que diz a comunicacao foi cortado). */
    boolean stateFits() {
        return state.getPreferredSize().width <= state.getWidth();
    }

    GhostButton detailsButton() {
        return details;
    }
}
