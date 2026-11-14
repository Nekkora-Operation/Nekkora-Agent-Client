package com.nekkora.client.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.signin.SignInState;
import com.nekkora.client.signin.SignInState.Phase;
import com.nekkora.client.theme.ClientTheme;

/**
 * Tela de entrada: logo completa no centro, "Entrar" abaixo e, sob o botao, uma area de altura fixa para o andamento
 * (spinner, "Aguardando login no navegador..." e "Cancelar") ou para a mensagem do resultado da ultima tentativa
 * (erro, recusa, prazo ou cancelamento). Sem e-mail, senha,
 * codigo ou identificador tecnico: a entrada acontece no navegador. No pe da tela, o rodape ({@link SignInFooter}).
 */
public final class SignInView extends JPanel {

    private static final long serialVersionUID = 1L;

    static final int LOGO_HEIGHT = 44;
    private static final int MESSAGE_WIDTH = 300;
    /** Altura da area sob o botao: cabe o andamento com "Cancelar" ou a mensagem mais longa, sem empurrar o resto. */
    static final int STATUS_HEIGHT = 92;

    private final PrimaryButton signIn = new PrimaryButton(SignInText.SIGN_IN);
    private final GhostButton cancel = GhostButton.text(SignInText.CANCEL);
    private final Spinner spinner = new Spinner();
    private final JLabel progress = new JLabel();
    private final WrappedText message = new WrappedText(MESSAGE_WIDTH);
    private final JPanel progressRow = new JPanel(new FlowLayout(FlowLayout.CENTER, ClientTheme.SPACE_2, 0));
    private final SignInFooter footer;
    private SignInState shown = SignInState.INITIAL;

    public SignInView(Runnable onSignIn, Runnable onCancel, BuildInfo build) {
        super(new BorderLayout());
        setOpaque(true);
        setFocusable(true);
        getAccessibleContext().setAccessibleName("Entrada");

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        LogoView logo = new LogoView(LOGO_HEIGHT);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        signIn.setAlignmentX(Component.CENTER_ALIGNMENT);
        signIn.addActionListener(e -> onSignIn.run());
        cancel.addActionListener(e -> onCancel.run());

        progressRow.setOpaque(false);
        progressRow.add(spinner);
        progressRow.add(progress);
        progressRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancel.setAlignmentX(Component.CENTER_ALIGNMENT);
        message.setAlignmentX(Component.CENTER_ALIGNMENT);

        // area de altura fixa: o logo e o botao nao mudam de lugar entre os estados
        JPanel status = new JPanel();
        status.setOpaque(false);
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        status.add(progressRow);
        status.add(cancel);
        status.add(message);
        Dimension area = new Dimension(MESSAGE_WIDTH + 2 * ClientTheme.SPACE_4, STATUS_HEIGHT);
        status.setPreferredSize(area);
        status.setMinimumSize(area);
        status.setMaximumSize(area);
        status.setAlignmentX(Component.CENTER_ALIGNMENT);

        column.add(logo);
        column.add(Box.createVerticalStrut(ClientTheme.SPACE_8 + ClientTheme.SPACE_2));
        column.add(signIn);
        column.add(Box.createVerticalStrut(ClientTheme.SPACE_4));
        column.add(status);
        // o conteudo fica centralizado no espaco acima do rodape; o rodape fica sempre no pe da tela, em todos os estados
        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        center.add(column);
        add(center, BorderLayout.CENTER);
        footer = new SignInFooter(build);
        add(footer, BorderLayout.SOUTH);

        Themes.addListener(t -> restyle());
        restyle();
        show(SignInState.INITIAL);
    }

    public PrimaryButton signInButton() {
        return signIn;
    }

    GhostButton cancelButton() {
        return cancel;
    }

    Spinner spinner() {
        return spinner;
    }

    String progressText() {
        return progressRow.isVisible() ? progress.getText() : "";
    }

    String messageText() {
        return message.isVisible() ? message.getText() : "";
    }

    WrappedText message() {
        return message;
    }

    SignInFooter footer() {
        return footer;
    }

    /** Mostra o estado. Na fila de eventos. */
    public void show(SignInState state) {
        boolean wasBusy = shown.busy();
        shown = state;
        boolean busy = state.busy();
        boolean hadFocus = signIn.isFocusOwner() || isFocusOwner();
        signIn.setLoading(busy);
        if (busy && !wasBusy && hadFocus) {
            // o botao desabilitado perde o foco: ele fica na propria tela (sem anel), e nao pula para outro botao.
            // Um segundo Espaco ou Enter durante a espera nao aciona nada; Tab leva a Cancelar
            requestFocusInWindow();
        } else if (!busy && wasBusy && (hadFocus || cancel.isFocusOwner())) {
            signIn.requestFocusInWindow();
        }
        progressRow.setVisible(busy);
        spinner.setVisible(busy);
        progress.setText(state.phase() == Phase.WAITING ? SignInText.WAITING : SignInText.OPENING);
        // Cancelar so depois que o navegador abriu: antes disso nao ha o que a pessoa esteja esperando
        cancel.setVisible(state.phase() == Phase.WAITING);
        String text = busy ? "" : SignInText.of(state.notice());
        message.setVisible(!text.isEmpty());
        message.setText(text);
        // o estado tambem e dito a quem nao ve a cor: "Erro na entrada" ou "Aviso da entrada" na mensagem, e o que a tela
        // esta fazendo (ou dizendo) na descricao da propria tela, que e quem tem o foco durante a espera
        message.getAccessibleContext().setAccessibleDescription(SignInText.kind(state.notice()));
        getAccessibleContext().setAccessibleDescription(busy ? progress.getText() : text);
        restyle();
        revalidate();
        repaint();
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        progress.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        progress.setForeground(Themes.color(t.textSecondary()));
        message.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        message.setForeground(Themes.color(SignInText.neutral(shown.notice()) ? t.textSecondary() : t.dangerText()));
    }
}
