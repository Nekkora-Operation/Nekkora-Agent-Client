package com.nekkora.client.ui;

import com.nekkora.client.nav.Navigation;
import com.nekkora.client.theme.ClientTheme;

/**
 * O botao "Atualizar" (icone de atualizacao E rotulo; nunca so o icone) e a linha que diz o resultado. Recarrega DADOS da
 * Platform; nao atualiza o programa. Um por tela: o seletor de organizacoes e a tela da organizacao.
 * <ul>
 * <li>em curso: o botao fica desabilitado, com "Atualizando…" (cliques repetidos nao acumulam), e a linha se cala;</li>
 * <li>tudo consultado: "Dados atualizados.", discreto;</li>
 * <li>falha: "Nao foi possivel atualizar. Tente novamente.", a orientacao da falha e o aviso de que a tela mostra os dados
 * de antes - em cor de erro. Nunca uma mensagem de sucesso depois de falha ou de atualizacao parcial;</li>
 * <li>organizacao ou ambiente que saiu do alcance: a linha diz qual, no lugar do "Dados atualizados".</li>
 * </ul>
 */
final class RefreshControl {

    private final ActionButton button = ActionButton.compact(NavText.REFRESH, ActionButton.Kind.SECONDARY, ActionButton.Glyph.REFRESH);
    private final WrappedText note;
    private boolean error;

    RefreshControl(Runnable onRefresh, int noteWidth) {
        note = new WrappedText(noteWidth);
        note.setVisible(false);
        button.getAccessibleContext().setAccessibleName(NavText.REFRESH);
        button.setToolTipText(NavText.REFRESH_TIP);
        button.getAccessibleContext().setAccessibleDescription(NavText.REFRESH_TIP);
        button.addActionListener(e -> onRefresh.run());
        Themes.addListener(t -> restyle());
        restyle();
    }

    ActionButton button() {
        return button;
    }

    WrappedText note() {
        return note;
    }

    String noteText() {
        return note.isVisible() ? note.getText() : "";
    }

    boolean error() {
        return error;
    }

    /**
     * @param refresh   a ultima atualizacao do estado
     * @param available falso enquanto a propria tela ainda carrega pela primeira vez (nao ha o que atualizar)
     */
    void show(Navigation.Refresh refresh, boolean available) {
        boolean running = refresh.running();
        button.setText(running ? NavText.REFRESHING : NavText.REFRESH);
        if (running) {
            button.setLoading(true);
        } else {
            button.setLoading(false);
            button.setEnabled(available);
        }
        button.getAccessibleContext().setAccessibleName(NavText.REFRESH);
        button.getAccessibleContext().setAccessibleDescription(running ? NavText.REFRESHING : NavText.REFRESH_TIP);
        String text = switch (refresh.phase()) {
            case NONE, RUNNING -> "";
            case FAILED -> NavText.refreshFailed(refresh.failure());
            case DONE -> switch (refresh.outcome()) {
                case UPDATED -> NavText.REFRESHED;
                case UPDATED_WITHOUT_NEW_ORGANIZATIONS -> NavText.REFRESH_OLD_PLATFORM;
                case ORGANIZATION_LOST -> NavText.lostOrganization(refresh.lost());
                case WORKSPACE_LOST -> NavText.lostWorkspace(refresh.lost());
            };
        };
        error = refresh.phase() == Navigation.Refresh.Phase.FAILED;
        note.setText(text);
        note.setVisible(!text.isEmpty());
        note.getAccessibleContext().setAccessibleDescription(error ? "Erro ao atualizar" : "");
        restyle();
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        note.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
        note.setForeground(Themes.color(error ? t.dangerText() : t.textSecondary()));
    }
}
