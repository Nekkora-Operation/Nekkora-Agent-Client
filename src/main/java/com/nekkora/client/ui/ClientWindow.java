package com.nekkora.client.ui;

import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.signin.SignInState;
import com.nekkora.client.signin.SignInState.Phase;
import com.nekkora.client.theme.ClientTheme;

/**
 * Janela unica do Client. Cabecalho compacto na barra de titulo (icone, "Nekkora Agent", troca de tema e os controles
 * normais da janela); abaixo, a tela de entrada ou a tela inicial. Fechar a janela encerra o aplicativo: a tentativa
 * em curso e cancelada e a sessao, descartada.
 */
public final class ClientWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    static final String SIGN_IN = "entrada";
    static final String HOME = "inicio";

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final SignInView signInView;
    private final HomeView homeView = new HomeView();
    private final GhostButton themeToggle;
    private String showing = SIGN_IN;

    public ClientWindow(SignInFlow flow, Consumer<ClientTheme> onThemeChosen, Runnable onClose) {
        this(flow, onThemeChosen, onClose, BuildInfo.load());
    }

    /** {@code build}: o que o rodape da entrada mostra (os testes passam o caso sem metadados). */
    public ClientWindow(SignInFlow flow, Consumer<ClientTheme> onThemeChosen, Runnable onClose, BuildInfo build) {
        super(SignInText.APP_NAME);
        setIconImages(BrandAssets.appIcons());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        signInView = new SignInView(flow::start, flow::cancel, build);
        content.add(signInView, SIGN_IN);
        content.add(homeView, HOME);
        setContentPane(content);

        themeToggle = GhostButton.themeGlyph(toggleText());
        themeToggle.addActionListener(e -> {
            ClientTheme next = Themes.current().other();
            Themes.apply(next);
            onThemeChosen.accept(next);
        });
        Themes.addListener(t -> themeToggle.describe(toggleText()));
        JMenuBar bar = new JMenuBar();
        bar.add(Box.createHorizontalGlue());
        bar.add(themeToggle);
        bar.add(Box.createHorizontalStrut(ClientTheme.SPACE_2));
        setJMenuBar(bar);

        getRootPane().setDefaultButton(signInView.signInButton());
        flow.addListener(this::show);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose.run();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                // o foco inicial e o do botao principal, nao o da troca de tema
                signInView.signInButton().requestFocusInWindow();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                // a janela voltou a ficar ativa: confere a sessao (o fluxo limita a frequencia)
                flow.checkSession();
            }
        });

        setMinimumSize(new Dimension(380, 460));
        setSize(480, 600);
        setLocationRelativeTo(null);
        show(flow.state());
    }

    private static String toggleText() {
        ClientTheme t = Themes.current();
        return SignInText.themeToggle(t.label(), t.other().label());
    }

    /** So {@link Phase#SIGNED_IN} mostra a tela inicial; qualquer outro estado e a tela de entrada. */
    void show(SignInState state) {
        String target = state.phase() == Phase.SIGNED_IN ? HOME : SIGN_IN;
        signInView.show(state);
        if (!target.equals(showing)) {
            showing = target;
            cards.show(content, target);
            if (SIGN_IN.equals(target)) {
                signInView.signInButton().requestFocusInWindow();
            }
        }
        getRootPane().setDefaultButton(SIGN_IN.equals(target) ? signInView.signInButton() : null);
    }

    String showing() {
        return showing;
    }

    SignInView signInView() {
        return signInView;
    }

    GhostButton themeToggle() {
        return themeToggle;
    }
}
