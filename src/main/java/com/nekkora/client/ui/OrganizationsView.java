package com.nekkora.client.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Loadable;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.theme.ClientTheme;

/**
 * Seletor de organizacoes, a primeira tela depois da entrada. Titulo fixo no alto, cartoes numa area rolavel e o rodape
 * (versao e direitos) sempre no pe. A escolha e sempre da pessoa: mesmo com uma organizacao so, nada e escolhido sozinho.
 * <p>
 * Quatro estados que nunca se confundem: carregando, falha (com "Tentar novamente"), sem organizacoes e a lista.
 */
final class OrganizationsView extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String LOADING = "carregando";
    static final String FAILED = "falha";
    static final String EMPTY = "vazio";
    static final String LIST = "lista";

    private final JLabel title = new JLabel(NavText.ORGANIZATIONS_TITLE, SwingConstants.CENTER);
    private final CardLayout states = new CardLayout();
    private final JPanel body = new JPanel(states);
    private final Spinner spinner = new Spinner();
    private final JLabel loadingText = new JLabel(NavText.ORGANIZATIONS_LOADING);
    private final WrappedText failure = new WrappedText(300);
    private final PrimaryButton retry = new PrimaryButton(NavText.RETRY);
    private final WrappedText empty = new WrappedText(300);
    private final CardGrid grid = new CardGrid();
    private final JScrollPane scroll = new JScrollPane(grid, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final AppFooter footer;
    private final Consumer<Organization> onChoose;
    private String showing = LOADING;
    private List<Organization> shown = List.of();

    OrganizationsView(Consumer<Organization> onChoose, Runnable onRetry, BuildInfo build) {
        super(new BorderLayout());
        this.onChoose = onChoose;
        setOpaque(true);
        getAccessibleContext().setAccessibleName(NavText.ORGANIZATIONS_TITLE);

        title.setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_6, ClientTheme.SPACE_4, ClientTheme.SPACE_2, ClientTheme.SPACE_4));
        add(title, BorderLayout.NORTH);

        JPanel loading = centered(row(spinner, loadingText));
        retry.addActionListener(e -> onRetry.run());
        failure.setAlignmentX(Component.CENTER_ALIGNMENT);
        retry.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel failed = centered(column(failure, Box.createVerticalStrut(ClientTheme.SPACE_4), retry));
        empty.setText(NavText.ORGANIZATIONS_EMPTY);
        JPanel none = centered(empty);

        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        body.setOpaque(false);
        body.add(loading, LOADING);
        body.add(failed, FAILED);
        body.add(none, EMPTY);
        body.add(scroll, LIST);
        add(body, BorderLayout.CENTER);

        footer = new AppFooter(build);
        add(footer, BorderLayout.SOUTH);

        Themes.addListener(t -> restyle());
        restyle();
    }

    private static JPanel centered(Component c) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    private static JPanel row(Component... parts) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, ClientTheme.SPACE_2, 0));
        p.setOpaque(false);
        for (Component c : parts) {
            p.add(c);
        }
        return p;
    }

    private static JPanel column(Component... parts) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (Component c : parts) {
            p.add(c);
        }
        return p;
    }

    /** Mostra o estado da consulta. Na fila de eventos. */
    void show(Loadable<List<Organization>> organizations) {
        String next = switch (organizations.status()) {
            case IDLE, LOADING -> LOADING;
            case FAILED -> FAILED;
            case READY -> organizations.value().isEmpty() ? EMPTY : LIST;
        };
        spinner.setVisible(LOADING.equals(next));
        if (FAILED.equals(next)) {
            failure.setText(NavText.of(organizations.failure()));
            failure.getAccessibleContext().setAccessibleDescription("Erro ao carregar as organizações");
        }
        if (LIST.equals(next) && !organizations.value().equals(shown)) {
            fill(organizations.value());
        }
        if (!LIST.equals(next)) {
            shown = List.of();
            grid.removeAll();
        }
        if (!next.equals(showing)) {
            boolean hadFocus = isAncestorOf(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            showing = next;
            states.show(body, next);
            if (hadFocus || FAILED.equals(next)) {
                focusFirst();
            }
        }
        getAccessibleContext().setAccessibleDescription(switch (next) {
            case LOADING -> NavText.ORGANIZATIONS_LOADING;
            case FAILED -> failure.getText();
            case EMPTY -> NavText.ORGANIZATIONS_EMPTY;
            default -> organizations.value().size() + (organizations.value().size() == 1 ? " organização" : " organizações");
        });
        revalidate();
        repaint();
    }

    private void fill(List<Organization> list) {
        shown = List.copyOf(list);
        grid.removeAll();
        for (Organization o : list) {
            grid.add(new OrganizationCard(o, () -> onChoose.accept(o)));
        }
        grid.revalidate();
        scroll.getVerticalScrollBar().setValue(0);
    }

    /** Leva o foco ao primeiro alvo do estado atual: o primeiro cartao, ou "Tentar novamente". */
    void focusFirst() {
        if (LIST.equals(showing) && grid.getComponentCount() > 0) {
            grid.getComponent(0).requestFocusInWindow();
        } else if (FAILED.equals(showing)) {
            retry.requestFocusInWindow();
        } else {
            requestFocusInWindow();
        }
    }

    String showing() {
        return showing;
    }

    List<OrganizationCard> cards() {
        List<OrganizationCard> out = new ArrayList<>();
        for (Component c : grid.getComponents()) {
            out.add((OrganizationCard) c);
        }
        return out;
    }

    CardGrid grid() {
        return grid;
    }

    JScrollPane scroll() {
        return scroll;
    }

    PrimaryButton retryButton() {
        return retry;
    }

    String failureText() {
        return FAILED.equals(showing) ? failure.getText() : "";
    }

    String emptyText() {
        return EMPTY.equals(showing) ? empty.getText() : "";
    }

    JLabel title() {
        return title;
    }

    AppFooter footer() {
        return footer;
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        setFocusable(true);
        title.setFont(Themes.font(ClientTheme.FONT_SIZE_XL, true));
        title.setForeground(Themes.color(t.text()));
        loadingText.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        loadingText.setForeground(Themes.color(t.textSecondary()));
        failure.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        failure.setForeground(Themes.color(t.dangerText()));
        empty.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        empty.setForeground(Themes.color(t.textSecondary()));
        repaint();
    }
}
