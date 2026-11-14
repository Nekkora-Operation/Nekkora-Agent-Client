package com.nekkora.client.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.Automation;
import com.nekkora.client.nav.Loadable;
import com.nekkora.client.nav.Navigation;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Workspace;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.theme.ClientTheme;

/**
 * Tela da organizacao escolhida. De cima para baixo: a seta para voltar ao seletor e o nome da organizacao; o seletor
 * "Ambiente"; a lista de automacoes do ambiente (area rolavel); as acoes do ambiente ("Nova automacao" e
 * "Importar (.zip)"), FORA da area rolavel; e o rodape.
 * <p>
 * "Ambiente" e so o rotulo da tela para o que a Platform chama de workspace. Nenhum ambiente e escolhido sozinho, e
 * trocar de ambiente apaga a lista anterior antes de mostrar a nova. Carregando, falha (com "Tentar novamente"), lista
 * vazia e lista sao estados distintos, tanto para os ambientes quanto para as automacoes.
 */
final class ContextView extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String HINT = "orientacao";
    static final String LOADING = "carregando";
    static final String FAILED = "falha";
    static final String EMPTY = "vazio";
    static final String LIST = "lista";

    /** O que os botoes da tela fazem. Nulo: a acao ainda nao existe, e o botao fica desabilitado, dizendo isso. */
    interface Actions {
        Consumer<Entry> run();

        Consumer<Entry> edit();

        Runnable create();

        Runnable importZip();
    }

    /**
     * Uma linha da lista. {@code automation} nulo: item que so existe neste computador. {@code local} nulo: automacao da
     * Platform cujo pacote NAO esta neste computador (listar os metadados nao traz o codigo).
     */
    record Entry(Automation automation, Workshop.LocalItem local) {
        String name() {
            return automation != null ? automation.name() : local.name();
        }

        boolean localOnly() {
            return automation == null;
        }
    }

    private final GhostButton back = GhostButton.text("←");
    private final JLabel name = new JLabel();
    private final JLabel workspaceLabel = new JLabel(NavText.WORKSPACE);
    private final JComboBox<Object> workspaces = new JComboBox<>();
    private final WrappedText workspaceNote = new WrappedText(380);
    private final GhostButton retryWorkspaces = GhostButton.text(NavText.RETRY);
    private final JLabel status = new JLabel();
    private final CardLayout states = new CardLayout();
    private final JPanel body = new JPanel(states);
    private final WrappedText hint = new WrappedText(320);
    private final Spinner spinner = new Spinner();
    private final JLabel loadingText = new JLabel(NavText.AUTOMATIONS_LOADING);
    private final WrappedText failure = new WrappedText(320);
    private final PrimaryButton retryAutomations = new PrimaryButton(NavText.RETRY);
    private final WrappedText empty = new WrappedText(320);
    private final RowList rows = new RowList();
    private final JScrollPane scroll = new JScrollPane(rows, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final JLabel truncated = new JLabel(NavText.AUTOMATIONS_TRUNCATED);
    private final WrappedText unavailable = new WrappedText(380);
    private final WrappedText notice = new WrappedText(380);
    private List<Workshop.LocalItem> local = List.of();
    private List<Workshop.LocalItem> shownLocal = List.of();
    private final GhostButton create = GhostButton.text(NavText.NEW_AUTOMATION);
    private final GhostButton importZip = GhostButton.text(NavText.IMPORT_ZIP);
    private final JPanel actionsBar = new JPanel(new FlowLayout(FlowLayout.CENTER, ClientTheme.SPACE_3, 0));
    private final AppFooter footer;

    private final Consumer<Workspace> onWorkspace;
    private transient Actions actions;
    private Organization organization;
    private boolean updating;
    private String showing = HINT;
    private List<Workspace> shownWorkspaces;
    private Automation.Listing shownListing;

    ContextView(Runnable onBack, Consumer<Workspace> onWorkspace, Runnable onRetryWorkspaces, Runnable onRetryAutomations, BuildInfo build) {
        super(new BorderLayout());
        this.onWorkspace = onWorkspace;
        setOpaque(true);
        back.describe(NavText.BACK);
        back.addActionListener(e -> onBack.run());

        JPanel header = new JPanel(new BorderLayout(ClientTheme.SPACE_2, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_3, ClientTheme.SPACE_4, ClientTheme.SPACE_2, ClientTheme.SPACE_4));
        header.add(back, BorderLayout.WEST);
        header.add(name, BorderLayout.CENTER);

        // seletor de ambiente
        workspaceLabel.setLabelFor(workspaces);
        workspaces.getAccessibleContext().setAccessibleName(NavText.WORKSPACE);
        workspaces.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                String text = value instanceof Workspace w ? w.name() : String.valueOf(value);
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        workspaces.addActionListener(e -> {
            if (!updating) {
                Object chosen = workspaces.getSelectedItem();
                onWorkspace.accept(chosen instanceof Workspace w ? w : null);
            }
        });
        retryWorkspaces.addActionListener(e -> onRetryWorkspaces.run());
        JPanel pick = new JPanel(new BorderLayout(ClientTheme.SPACE_3, 0));
        pick.setOpaque(false);
        pick.add(workspaceLabel, BorderLayout.WEST);
        pick.add(workspaces, BorderLayout.CENTER);
        pick.add(retryWorkspaces, BorderLayout.EAST);
        pick.setAlignmentX(Component.LEFT_ALIGNMENT);
        workspaceNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(0, ClientTheme.SPACE_4, ClientTheme.SPACE_2, ClientTheme.SPACE_4));
        top.add(pick);
        top.add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        top.add(workspaceNote);
        top.add(status);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(header, BorderLayout.NORTH);
        north.add(top, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        // automacoes
        retryAutomations.addActionListener(e -> onRetryAutomations.run());
        failure.setAlignmentX(Component.CENTER_ALIGNMENT);
        retryAutomations.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel failed = new JPanel();
        failed.setOpaque(false);
        failed.setLayout(new BoxLayout(failed, BoxLayout.Y_AXIS));
        failed.add(failure);
        failed.add(Box.createVerticalStrut(ClientTheme.SPACE_4));
        failed.add(retryAutomations);
        JPanel loading = new JPanel(new FlowLayout(FlowLayout.CENTER, ClientTheme.SPACE_2, 0));
        loading.setOpaque(false);
        loading.add(spinner);
        loading.add(loadingText);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        empty.setText(NavText.AUTOMATIONS_EMPTY);
        body.setOpaque(false);
        body.add(centered(hint), HINT);
        body.add(centered(loading), LOADING);
        body.add(centered(failed), FAILED);
        body.add(centered(empty), EMPTY);
        body.add(scroll, LIST);
        add(body, BorderLayout.CENTER);

        // acoes do ambiente, fora da area rolavel, e o rodape
        create.addActionListener(e -> {
            if (actions != null && actions.create() != null) {
                actions.create().run();
            }
        });
        importZip.addActionListener(e -> {
            if (actions != null && actions.importZip() != null) {
                actions.importZip().run();
            }
        });
        actionsBar.setOpaque(false);
        actionsBar.setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_2, ClientTheme.SPACE_4, 0, ClientTheme.SPACE_4));
        actionsBar.add(create);
        actionsBar.add(importZip);
        truncated.setHorizontalAlignment(JLabel.CENTER);
        truncated.setVisible(false);
        JPanel bottomTop = new JPanel(new BorderLayout());
        bottomTop.setOpaque(false);
        JPanel note = centered(unavailable);
        note.setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_2, ClientTheme.SPACE_4, 0, ClientTheme.SPACE_4));
        notice.setVisible(false);
        JPanel upper = new JPanel(new BorderLayout());
        upper.setOpaque(false);
        upper.add(truncated, BorderLayout.NORTH);
        JPanel noticeRow = centered(notice);
        noticeRow.setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_2, ClientTheme.SPACE_4, 0, ClientTheme.SPACE_4));
        upper.add(noticeRow, BorderLayout.SOUTH);
        bottomTop.add(upper, BorderLayout.NORTH);
        bottomTop.add(actionsBar, BorderLayout.CENTER);
        bottomTop.add(note, BorderLayout.SOUTH);
        footer = new AppFooter(build);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(bottomTop, BorderLayout.NORTH);
        south.add(footer, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        Themes.addListener(t -> restyle());
        restyle();
        show(null, Loadable.idle(), null, Loadable.idle());
    }

    private static JPanel centered(Component c) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    void setActions(Actions actions) {
        this.actions = actions;
        shownListing = null; // as linhas sao refeitas com as acoes novas
        refreshActions();
    }

    /** Os pacotes locais do contexto ATUAL (organizacao e ambiente escolhidos). Trocar de contexto zera a lista. */
    void setLocal(List<Workshop.LocalItem> items) {
        this.local = List.copyOf(items);
    }

    /** Resultado da ultima acao local (importar, preparar, renomear), em linha; {@code null} ou vazio apaga. */
    void showNotice(String text, boolean error) {
        notice.setText(text == null ? "" : text);
        notice.setVisible(text != null && !text.isEmpty());
        notice.setForeground(Themes.color(error ? Themes.current().dangerText() : Themes.current().textSecondary()));
        notice.getAccessibleContext().setAccessibleDescription(error ? "Erro" : "Aviso");
        revalidate();
        repaint();
    }

    String noticeText() {
        return notice.isVisible() ? notice.getText() : "";
    }

    /**
     * As linhas: primeiro as automacoes da Platform, na ordem dela, cada uma com o pacote local correspondente (o ligado
     * a ela, ou o de mesma impressao digital da versao publicada); depois os itens que so existem neste computador.
     */
    static List<Entry> entries(Automation.Listing listing, List<Workshop.LocalItem> local) {
        List<Entry> out = new ArrayList<>();
        List<Workshop.LocalItem> rest = new ArrayList<>(local);
        for (Automation a : listing.items()) {
            Workshop.LocalItem match = null;
            for (Workshop.LocalItem i : rest) {
                if (a.id().equals(i.linkedAutomationId()) || (a.published() != null && i.matches(a.published().fingerprint()))) {
                    match = i;
                    break;
                }
            }
            if (match != null) {
                rest.remove(match);
            }
            out.add(new Entry(a, match));
        }
        rest.forEach(i -> out.add(new Entry(null, i)));
        return out;
    }

    void show(Navigation.State state) {
        show(state.selected(), state.workspaces(), state.workspace(), state.automations());
    }

    /** Mostra o estado. Na fila de eventos. */
    void show(Organization organization, Loadable<List<Workspace>> list, Workspace chosen, Loadable<Automation.Listing> automations) {
        this.organization = organization;
        name.setText(organization == null ? "" : organization.name());
        name.setToolTipText(organization == null ? null : organization.name() + " (" + organization.label() + ")");
        getAccessibleContext().setAccessibleName(organization == null ? "" : "Organização " + organization.name());

        // ---- ambientes
        updating = true;
        try {
            List<Workspace> ready = list.isReady() ? list.value() : null;
            if (ready == null || !ready.equals(shownWorkspaces)) {
                shownWorkspaces = ready;
                DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
                if (ready == null) {
                    model.addElement(list.status() == Loadable.Status.LOADING ? NavText.WORKSPACES_LOADING : NavText.WORKSPACE_PLACEHOLDER);
                } else if (ready.isEmpty()) {
                    model.addElement(NavText.WORKSPACES_NONE);
                } else {
                    model.addElement(NavText.WORKSPACE_PLACEHOLDER);
                    ready.forEach(model::addElement);
                }
                workspaces.setModel(model);
            }
            workspaces.setEnabled(ready != null && !ready.isEmpty());
            if (ready != null && !ready.isEmpty()) {
                workspaces.setSelectedItem(chosen == null ? NavText.WORKSPACE_PLACEHOLDER : chosen);
            }
        } finally {
            updating = false;
        }
        boolean workspacesFailed = list.status() == Loadable.Status.FAILED;
        retryWorkspaces.setVisible(workspacesFailed);
        String note = workspacesFailed ? NavText.of(list.failure()) : list.isReady() && list.value().isEmpty() ? NavText.WORKSPACES_NONE_HINT : "";
        workspaceNote.setText(note);
        workspaceNote.setVisible(!note.isEmpty());
        workspaceNote.setForeground(Themes.color(workspacesFailed ? Themes.current().dangerText() : Themes.current().textSecondary()));
        workspaceNote.getAccessibleContext().setAccessibleDescription(workspacesFailed ? "Erro ao carregar os ambientes" : "");

        // ---- automacoes
        String next;
        if (chosen == null) {
            next = HINT;
            hint.setText(list.isReady() && !list.value().isEmpty() ? NavText.AUTOMATIONS_HINT : "");
        } else {
            next = switch (automations.status()) {
                case IDLE, LOADING -> LOADING;
                case FAILED -> FAILED;
                case READY -> entries(automations.value(), local).isEmpty() ? EMPTY : LIST;
            };
        }
        spinner.setVisible(LOADING.equals(next));
        if (FAILED.equals(next)) {
            failure.setText(NavText.of(automations.failure()));
            failure.getAccessibleContext().setAccessibleDescription("Erro ao carregar as automações");
        }
        Automation.Listing listing = automations.isReady() && chosen != null ? automations.value() : null;
        if (listing == null || !listing.equals(shownListing) || !local.equals(shownLocal)) {
            shownListing = listing;
            shownLocal = local;
            rows.removeAll();
            if (listing != null) {
                for (Entry e : entries(listing, local)) {
                    rows.add(row(e, listing.capabilities()));
                }
            }
            rows.revalidate();
            scroll.getVerticalScrollBar().setValue(0);
        }
        truncated.setVisible(listing != null && listing.truncated());
        if (!next.equals(showing)) {
            showing = next;
            states.show(body, next);
        }
        refreshActions();
        revalidate();
        repaint();
    }

    private AutomationRow row(Entry e, Automation.Capabilities capabilities) {
        PrimaryButton run = PrimaryButton.compact(NavText.RUN);
        GhostButton edit = GhostButton.text(NavText.EDIT);
        Consumer<Entry> onRun = actions == null ? null : actions.run();
        Consumer<Entry> onEdit = actions == null ? null : actions.edit();
        enable(run, onRun != null, NavText.RUN + ": " + e.name(), NavText.ACTION_UNAVAILABLE);
        // item so local: o nome mostrado aqui e da pessoa. Automacao da Platform: so com a permissao (o servidor confere de
        // novo); sem ela o botao fica desabilitado dizendo por que
        boolean mayEdit = e.localOnly() || capabilities.rename();
        enable(edit, onEdit != null && mayEdit, NavText.EDIT + ": " + e.name(), onEdit == null ? NavText.ACTION_UNAVAILABLE : NavText.EDIT_DENIED);
        if (onRun != null) {
            run.addActionListener(x -> onRun.accept(e));
        }
        if (onEdit != null) {
            edit.addActionListener(x -> onEdit.accept(e));
        }
        List<String> details = new ArrayList<>();
        if (e.localOnly()) {
            details.add(NavText.LOCAL_ONLY);
            details.add("Versão do pacote " + e.local().packageVersion());
        } else {
            details.add(NavText.version(e.automation()));
            details.add(e.local() == null ? NavText.LOCAL_ABSENT : NavText.LOCAL_PRESENT + ": " + e.local().name() + " " + e.local().packageVersion());
        }
        AutomationRow row = new AutomationRow(e.name(), details, run, edit);
        row.putClientProperty("entry", e);
        return row;
    }

    private static void enable(JButton b, boolean enabled, String accessibleName, String whyNot) {
        b.setEnabled(enabled);
        b.getAccessibleContext().setAccessibleName(accessibleName);
        b.setToolTipText(enabled ? null : whyNot);
        b.getAccessibleContext().setAccessibleDescription(enabled ? null : whyNot);
    }

    /** "Nova automacao" e "Importar (.zip)" so valem com organizacao E ambiente escolhidos. */
    private void refreshActions() {
        boolean context = organization != null && workspaces.getSelectedItem() instanceof Workspace;
        boolean hasCreate = actions != null && actions.create() != null;
        boolean hasImport = actions != null && actions.importZip() != null;
        enable(create, hasCreate && context, NavText.NEW_AUTOMATION, hasCreate ? NavText.NEEDS_WORKSPACE : NavText.ACTION_UNAVAILABLE);
        enable(importZip, hasImport && context, NavText.IMPORT_ZIP, hasImport ? NavText.NEEDS_WORKSPACE : NavText.ACTION_UNAVAILABLE);
        // enquanto alguma acao nao existe, a tela diz isso por escrito (nao so na dica do botao)
        List<String> missing = new ArrayList<>();
        if (actions == null || actions.run() == null) {
            missing.add(NavText.RUN);
        }
        if (actions == null || actions.edit() == null) {
            missing.add(NavText.EDIT);
        }
        if (!hasCreate) {
            missing.add(NavText.NEW_AUTOMATION);
        }
        if (!hasImport) {
            missing.add("Importar");
        }
        unavailable.setText(NavText.unavailable(missing));
        unavailable.setVisible(!missing.isEmpty() && organization != null);
    }

    Organization organization() {
        return organization;
    }

    GhostButton backButton() {
        return back;
    }

    JLabel nameLabel() {
        return name;
    }

    JComboBox<Object> workspaceBox() {
        return workspaces;
    }

    JLabel workspaceLabel() {
        return workspaceLabel;
    }

    String workspaceNote() {
        return workspaceNote.isVisible() ? workspaceNote.getText() : "";
    }

    GhostButton retryWorkspacesButton() {
        return retryWorkspaces;
    }

    String showing() {
        return showing;
    }

    String hintText() {
        return HINT.equals(showing) ? hint.getText() : "";
    }

    String failureText() {
        return FAILED.equals(showing) ? failure.getText() : "";
    }

    String emptyText() {
        return EMPTY.equals(showing) ? empty.getText() : "";
    }

    PrimaryButton retryAutomationsButton() {
        return retryAutomations;
    }

    List<AutomationRow> rows() {
        List<AutomationRow> out = new ArrayList<>();
        for (Component c : rows.getComponents()) {
            out.add((AutomationRow) c);
        }
        return out;
    }

    JScrollPane scroll() {
        return scroll;
    }

    RowList rowList() {
        return rows;
    }

    GhostButton createButton() {
        return create;
    }

    GhostButton importButton() {
        return importZip;
    }

    JPanel actionsBar() {
        return actionsBar;
    }

    JLabel statusLabel() {
        return status;
    }

    String unavailableNote() {
        return unavailable.isShowing() ? unavailable.getText() : "";
    }

    JLabel truncatedLabel() {
        return truncated;
    }

    AppFooter footer() {
        return footer;
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        name.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        name.setForeground(Themes.color(t.text()));
        back.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, false));
        workspaceLabel.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
        workspaceLabel.setForeground(Themes.color(t.text()));
        workspaces.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        workspaceNote.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        status.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
        status.setForeground(Themes.color(t.textSecondary()));
        for (WrappedText w : List.of(hint, empty)) {
            w.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
            w.setForeground(Themes.color(t.textSecondary()));
        }
        loadingText.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        loadingText.setForeground(Themes.color(t.textSecondary()));
        failure.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        failure.setForeground(Themes.color(t.dangerText()));
        notice.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        unavailable.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
        unavailable.setForeground(Themes.color(t.textSecondary()));
        truncated.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
        truncated.setForeground(Themes.color(t.textSecondary()));
        repaint();
    }
}
