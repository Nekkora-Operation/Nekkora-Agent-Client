package com.nekkora.client.ui;

import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Directory;
import com.nekkora.client.nav.Loadable;
import com.nekkora.client.nav.Navigation;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.run.Runner;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.signin.SignInState;
import com.nekkora.client.signin.SignInState.Phase;
import com.nekkora.client.theme.ClientTheme;

/**
 * Janela unica do Client. Cabecalho compacto na barra de titulo (icone, "Nekkora Agent", avatar da pessoa depois da
 * entrada, troca de tema e os controles normais da janela); abaixo, a tela de entrada, o seletor de organizacoes ou a
 * tela da organizacao escolhida.
 * <p>
 * So {@link Phase#SIGNED_IN} sai da tela de entrada. Ao sair dessa fase (prazo, "Terminar sessao", sessao recusada), o
 * que estava na tela e descartado: nada de uma sessao aparece na seguinte. Fechar a janela, ou "Sair" no menu, encerra o
 * aplicativo: a tentativa em curso e cancelada e a sessao, descartada.
 */
public final class ClientWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    static final String SIGN_IN = "entrada";
    static final String ORGANIZATIONS = "organizacoes";
    static final String CONTEXT = "organizacao";

    private final SignInFlow flow;
    private final transient Executor background;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final SignInView signInView;
    private final OrganizationsView organizationsView;
    private final ContextView contextView;
    private final GhostButton themeToggle;
    private final AvatarButton avatar;
    private final transient Workshop workshop;
    private final transient Runner runner;
    private final transient Runnable onClose;

    /** Como a janela pergunta e avisa nas salvaguardas (os testes trocam por respostas fixas, sem dialogos). */
    interface Guard {
        /** Sair com execucao em andamento: {@code true} so se a pessoa confirmar que quer encerrar a execucao. */
        boolean confirmExit(String title, String text);

        /** Operacao bloqueada: explica e nada muda. */
        void blocked(String text);
    }

    transient Guard guard = new Guard() {
        @Override
        public boolean confirmExit(String title, String text) {
            Object[] options = {NavText.EXIT_STAY, NavText.EXIT_CONFIRM};
            return javax.swing.JOptionPane.showOptionDialog(ClientWindow.this, text, title, javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE,
                    null, options, options[0]) == 1;
        }

        @Override
        public void blocked(String text) {
            javax.swing.JOptionPane.showMessageDialog(ClientWindow.this, text, SignInText.APP_NAME, javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }
    };
    private final transient LocalActions actions;
    private transient Navigation navigation;
    /** O contexto cujos pacotes locais a tela mostra (ou pediu). Mudou o contexto, a lista anterior nao serve mais. */
    private transient Navigation.Context localContext;
    private String showing = SIGN_IN;

    public ClientWindow(SignInFlow flow, Consumer<ClientTheme> onThemeChosen, Runnable onClose) {
        this(flow, onThemeChosen, onClose, BuildInfo.load());
    }

    /** {@code build}: o que o rodape mostra (os testes passam o caso sem metadados). */
    public ClientWindow(SignInFlow flow, Consumer<ClientTheme> onThemeChosen, Runnable onClose, BuildInfo build) {
        this(flow, onThemeChosen, onClose, build, Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "consultas");
            t.setDaemon(true);
            return t;
        }));
    }

    /** {@code background}: onde as consultas a Platform rodam (os testes passam um executor que eles controlam). */
    public ClientWindow(SignInFlow flow, Consumer<ClientTheme> onThemeChosen, Runnable onClose, BuildInfo build, Executor background) {
        this(flow, onThemeChosen, onClose, build, background, null);
    }

    /** {@code workshop}: a area local de pacotes. Nulo: as acoes locais ficam indisponiveis (e a tela diz isso). */
    public ClientWindow(SignInFlow flow, Consumer<ClientTheme> onThemeChosen, Runnable onClose, BuildInfo build, Executor background, Workshop workshop) {
        this(flow, onThemeChosen, onClose, build, background, workshop, null);
    }

    /** {@code runner}: a execucao local. Nulo: "Executar" fica indisponivel (e a tela diz isso). */
    public ClientWindow(SignInFlow flow, Consumer<ClientTheme> onThemeChosen, Runnable onClose, BuildInfo build, Executor background, Workshop workshop,
                        Runner runner) {
        super(SignInText.APP_NAME);
        this.flow = flow;
        this.background = background;
        this.workshop = workshop;
        this.runner = runner;
        this.onClose = onClose;
        setIconImages(BrandAssets.appIcons());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        signInView = new SignInView(flow::start, flow::cancel, build);
        organizationsView = new OrganizationsView(o -> {
            if (navigation != null) {
                navigation.select(o);
            }
        }, () -> {
            if (navigation != null) {
                navigation.loadOrganizations();
            }
        }, build);
        contextView = new ContextView(() -> {
            if (navigation != null && !blockedByRun(NavText.RUNNING_BLOCKS_CONTEXT)) {
                navigation.back();
            }
        }, this::chooseWorkspace, () -> {
            if (navigation != null) {
                navigation.loadWorkspaces();
            }
        }, () -> {
            if (navigation != null) {
                navigation.loadAutomations();
            }
        }, build);
        actions = workshop == null ? null : new LocalActions(workshop, runner, background, new LocalActions.Host() {
            @Override
            public JFrame frame() {
                return ClientWindow.this;
            }

            @Override
            public Navigation navigation() {
                return navigation;
            }

            @Override
            public Directory directory() {
                return flow.directory();
            }

            @Override
            public void notice(String text, boolean error) {
                contextView.showNotice(text, error);
            }

            @Override
            public void localChanged(Navigation.Context context) {
                loadLocal(context);
            }
        });
        contextView.setActions(actions);
        content.add(signInView, SIGN_IN);
        content.add(organizationsView, ORGANIZATIONS);
        content.add(contextView, CONTEXT);
        setContentPane(content);

        avatar = new AvatarButton(this::requestSignOut, this::requestExit);
        avatar.setVisible(false);
        themeToggle = GhostButton.themeGlyph(toggleText());
        themeToggle.addActionListener(e -> {
            ClientTheme next = Themes.current().other();
            Themes.apply(next);
            onThemeChosen.accept(next);
        });
        Themes.addListener(t -> themeToggle.describe(toggleText()));
        JMenuBar bar = new JMenuBar();
        bar.add(Box.createHorizontalGlue());
        bar.add(avatar);
        bar.add(Box.createHorizontalStrut(ClientTheme.SPACE_2));
        bar.add(themeToggle);
        bar.add(Box.createHorizontalStrut(ClientTheme.SPACE_2));
        setJMenuBar(bar);

        getRootPane().setDefaultButton(signInView.signInButton());
        flow.addListener(this::show);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestExit();
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

    /** So {@link Phase#SIGNED_IN} mostra as telas de depois da entrada; qualquer outro estado e a tela de entrada. */
    void show(SignInState state) {
        signInView.show(state);
        if (state.phase() == Phase.SIGNED_IN) {
            if (navigation == null) {
                open();
            }
        } else {
            leave();
        }
    }

    /** Entrou: comeca do seletor de organizacoes, com as consultas desta sessao. */
    private void open() {
        Directory directory = flow.directory();
        if (directory == null) {
            return; // a sessao acabou entre o aviso e agora: o proximo estado traz a tela de entrada
        }
        navigation = new Navigation(directory, background, r -> {
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                SwingUtilities.invokeLater(r);
            }
        });
        navigation.addListener(this::show);
        avatar.setProfile(null);
        avatar.setVisible(true);
        organizationsView.show(Loadable.loading());
        to(ORGANIZATIONS);
        navigation.start();
    }

    /** Saiu da sessao: as consultas param e nada do que estava na tela fica para a proxima entrada. */
    private void leave() {
        if (navigation != null) {
            navigation.close();
            navigation = null;
        }
        if (actions != null) {
            actions.close();
        }
        localContext = null;
        contextView.setLocal(java.util.List.of());
        contextView.showNotice("", false);
        avatar.closeMenu();
        avatar.setVisible(false);
        avatar.setProfile(null);
        organizationsView.show(Loadable.loading());
        contextView.show(null, Loadable.idle(), null, Loadable.idle());
        to(SIGN_IN);
    }

    private void show(Navigation.State state) {
        if (navigation == null) {
            return;
        }
        avatar.setProfile(state.profile().isReady() ? state.profile().value() : null);
        organizationsView.show(state.organizations());
        Navigation.Context context = state.context();
        if (!same(context, localContext)) {
            // outro contexto (ou nenhum): os pacotes, o aviso e o formulario do anterior saem da tela antes de qualquer coisa
            localContext = context;
            contextView.setLocal(java.util.List.of());
            contextView.showNotice("", false);
            if (actions != null) {
                actions.close();
            }
            if (context != null) {
                loadLocal(context);
            }
        }
        contextView.show(state);
        to(state.selected() != null ? CONTEXT : ORGANIZATIONS);
    }

    /** Troca de ambiente pedida no seletor. Com execucao em andamento, e bloqueada e o seletor volta ao ambiente dela. */
    private void chooseWorkspace(com.nekkora.client.nav.Workspace w) {
        if (navigation == null) {
            return;
        }
        Navigation.Context now = navigation.state().context();
        boolean same = now != null && w != null && now.workspace().id().equals(w.id());
        if (!same && blockedByRun(NavText.RUNNING_BLOCKS_CONTEXT)) {
            contextView.show(navigation.state());
            return;
        }
        navigation.selectWorkspace(w);
    }

    /** Ha execucao local em andamento? Entao a operacao e BLOQUEADA, com a explicacao, e nada muda. */
    private boolean blockedByRun(String text) {
        if (runner != null && runner.running().isPresent()) {
            guard.blocked(text);
            return true;
        }
        return false;
    }

    /** "Terminar sessao": bloqueado enquanto uma execucao local estiver em andamento. */
    void requestSignOut() {
        if (!blockedByRun(NavText.RUNNING_BLOCKS_SIGN_OUT)) {
            flow.signOut();
        }
    }

    /**
     * "Sair" ou fechar a janela. Com execucao em andamento, pergunta antes: so com a confirmacao a execucao e encerrada
     * (registrada como cancelada pelo encerramento) e o aplicativo sai. Sem confirmacao, nada muda.
     */
    void requestExit() {
        if (runner != null && runner.running().isPresent() && !guard.confirmExit(NavText.EXIT_TITLE, NavText.EXIT_WHILE_RUNNING)) {
            return;
        }
        onClose.run();
    }

    private static boolean same(Navigation.Context a, Navigation.Context b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.organization().id().equals(b.organization().id()) && a.workspace().id().equals(b.workspace().id());
    }

    /** Le os pacotes locais do contexto, fora da fila de eventos; o resultado so entra se o contexto ainda for o mesmo. */
    private void loadLocal(Navigation.Context context) {
        if (workshop == null) {
            return;
        }
        background.execute(() -> {
            java.util.List<Workshop.LocalItem> items;
            try {
                items = workshop.list(context.organization().id(), context.workspace().id());
            } catch (RuntimeException e) {
                items = java.util.List.of();
            }
            java.util.List<Workshop.LocalItem> loaded = items;
            SwingUtilities.invokeLater(() -> {
                if (navigation != null && same(context, localContext)) {
                    contextView.setLocal(loaded);
                    contextView.show(navigation.state());
                }
            });
        });
    }

    LocalActions actions() {
        return actions;
    }

    private void to(String target) {
        if (!target.equals(showing)) {
            showing = target;
            cards.show(content, target);
            switch (target) {
                case SIGN_IN -> signInView.signInButton().requestFocusInWindow();
                case ORGANIZATIONS -> organizationsView.focusFirst();
                default -> contextView.backButton().requestFocusInWindow();
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

    OrganizationsView organizationsView() {
        return organizationsView;
    }

    ContextView contextView() {
        return contextView;
    }

    AvatarButton avatar() {
        return avatar;
    }

    GhostButton themeToggle() {
        return themeToggle;
    }
}
