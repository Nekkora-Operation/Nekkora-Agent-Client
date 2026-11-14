package com.nekkora.client.ui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

import com.nekkora.client.nav.Automation;
import com.nekkora.client.theme.ClientTheme;

/**
 * "Pacote nesta maquina" (AGENT-CLIENT-PACOTES-001 T02), dentro de Editar: de onde vem o pacote que ESTE computador usa para
 * a automacao. Duas alternativas, sempre por escolha da pessoa:
 * <ul>
 * <li><b>Usar projeto ou pacote local</b>: um .zip de pacote ou a pasta de um pacote. So e apresentado como a versao
 * publicada se o conteudo for o mesmo (impressao digital); diferente, a tela diz e pede a escolha;</li>
 * <li><b>Baixar da Platform</b>: uma versao publicada escolhida, com progresso e cancelamento.</li>
 * </ul>
 * Nenhuma das duas executa codigo, instala dependencias, publica ou altera a versao publicada. Renomear e outra coisa e
 * fica na outra secao de Editar.
 */
final class PackageForm extends JPanel {

    private static final long serialVersionUID = 1L;

    interface Events {
        void pickZip();

        void pickFolder();

        /** O conteudo escolhido difere da versao publicada e a pessoa decidiu guarda-lo como projeto local separado. */
        void useSeparate();

        /** A pasta escolhida e codigo sem manifesto: abrir "Gerar pacote novo" com ela. */
        void generateFromFolder();

        void download();

        void cancelDownload();

        void close();
    }

    private final JLabel title = new JLabel(NavText.PKG_TITLE);
    private final WrappedText status = new WrappedText(420);
    private final JLabel localTitle = new JLabel(NavText.PKG_LOCAL_TITLE);
    private final WrappedText localHint = new WrappedText(420);
    private final ActionButton pickZip = ActionButton.compact(NavText.PKG_PICK_ZIP, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final ActionButton pickFolder = ActionButton.compact(NavText.PKG_PICK_FOLDER, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final WrappedText localResult = new WrappedText(420);
    private final ActionButton useSeparate = ActionButton.compact(NavText.PKG_USE_SEPARATE, ActionButton.Kind.SECONDARY, ActionButton.Glyph.ADD);
    private final ActionButton generate = ActionButton.compact(NavText.GENERATE_NEW, ActionButton.Kind.SECONDARY, ActionButton.Glyph.ADD);
    private final JLabel downloadTitle = new JLabel(NavText.PKG_DOWNLOAD_TITLE);
    private final JComboBox<Object> versions = new JComboBox<>();
    private final ActionButton download = ActionButton.compact(NavText.PKG_DOWNLOAD, ActionButton.Kind.SECONDARY, ActionButton.Glyph.IMPORT);
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final GhostButton cancelDownload = GhostButton.text(NavText.PKG_DOWNLOAD_CANCEL);
    private final WrappedText downloadResult = new WrappedText(420);
    private final GhostButton close = GhostButton.text(NavText.CLOSE);
    private boolean localError;
    private boolean downloadError;
    private boolean busy;
    private boolean downloading;
    private String downloadBlocked;

    PackageForm(Events events) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(ClientTheme.SPACE_4, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5));
        setOpaque(true);
        localHint.setText(NavText.PKG_LOCAL_HINT);
        pickZip.addActionListener(e -> events.pickZip());
        pickFolder.addActionListener(e -> events.pickFolder());
        useSeparate.addActionListener(e -> events.useSeparate());
        generate.addActionListener(e -> events.generateFromFolder());
        download.addActionListener(e -> {
            if (download.isEnabled()) {
                events.download();
            }
        });
        cancelDownload.addActionListener(e -> events.cancelDownload());
        close.addActionListener(e -> events.close());
        versions.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focus) {
                String text = value instanceof Automation.Version v ? NavText.versionChoice(v) : String.valueOf(value);
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
        versions.getAccessibleContext().setAccessibleName(NavText.PKG_VERSION_FIELD);
        versions.addActionListener(e -> refresh());
        progress.setStringPainted(true);
        progress.getAccessibleContext().setAccessibleName(NavText.PKG_PROGRESS);
        localResult.setVisible(false);
        downloadResult.setVisible(false);
        useSeparate.setVisible(false);
        generate.setVisible(false);
        progress.setVisible(false);
        cancelDownload.setVisible(false);

        add(left(title));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(status));
        add(Box.createVerticalStrut(ClientTheme.SPACE_4));
        add(left(localTitle));
        add(Box.createVerticalStrut(4));
        add(left(localHint));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(row(pickZip, pickFolder)));
        add(left(localResult));
        add(left(row(useSeparate, generate)));
        add(Box.createVerticalStrut(ClientTheme.SPACE_4));
        add(left(downloadTitle));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(versions));
        add(Box.createVerticalStrut(ClientTheme.SPACE_2));
        add(left(row(download, cancelDownload)));
        add(left(progress));
        add(left(downloadResult));
        add(Box.createVerticalStrut(ClientTheme.SPACE_3));
        JPanel end = new JPanel(new FlowLayout(FlowLayout.RIGHT, ClientTheme.SPACE_2, 0));
        end.setOpaque(false);
        end.add(close);
        add(left(end));
        Themes.addListener(t -> restyle());
        showVersionsLoading();
        restyle();
    }

    private static <T extends javax.swing.JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel row(Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, ClientTheme.SPACE_2, 0));
        p.setOpaque(false);
        for (Component c : parts) {
            p.add(c);
        }
        return p;
    }

    /** O que este computador tem hoje para a automacao. */
    void showStatus(String text) {
        status.setText(text);
        revalidate();
        repaint();
    }

    // ---- pacote local

    void setBusy(boolean busy) {
        this.busy = busy;
        refresh();
    }

    /** Resultado da escolha local. {@code offerSeparate}/{@code offerGenerate}: qual decisao a tela pede agora. */
    void showLocal(String text, boolean error, boolean offerSeparate, boolean offerGenerate) {
        localError = error;
        localResult.setText(text == null ? "" : text);
        localResult.setVisible(text != null && !text.isEmpty());
        localResult.getAccessibleContext().setAccessibleDescription(error ? "Erro" : "");
        useSeparate.setVisible(offerSeparate);
        generate.setVisible(offerGenerate);
        restyle();
        refresh();
        repack();
    }

    // ---- download

    void showVersionsLoading() {
        versions.setModel(new DefaultComboBoxModel<>(new Object[] {NavText.PKG_VERSIONS_LOADING}));
        downloadBlocked = NavText.PKG_VERSIONS_LOADING;
        refresh();
    }

    /** A lista nao veio: a frase diz por que; nada e baixavel. */
    void showVersionsFailed(String message) {
        versions.setModel(new DefaultComboBoxModel<>(new Object[] {NavText.PKG_VERSIONS_FAILED}));
        downloadBlocked = message;
        showDownload(message, true);
    }

    /**
     * As versoes publicadas que podem ser baixadas, da mais nova para a mais antiga. Nenhuma e escolhida sozinha.
     * {@code blocked}: por que o download nao esta disponivel (sem permissao, Platform anterior); nulo se esta.
     */
    void showVersions(List<Automation.Version> downloadable, String blocked) {
        DefaultComboBoxModel<Object> m = new DefaultComboBoxModel<>();
        if (downloadable.isEmpty()) {
            m.addElement(NavText.PKG_VERSIONS_NONE);
        } else {
            m.addElement(NavText.PKG_VERSION_PLACEHOLDER);
            downloadable.forEach(m::addElement);
        }
        versions.setModel(m);
        downloadBlocked = blocked != null ? blocked : downloadable.isEmpty() ? NavText.PKG_VERSIONS_NONE : null;
        if (blocked != null) {
            showDownload(blocked, false);
        }
        refresh();
    }

    Automation.Version chosenVersion() {
        return versions.getSelectedItem() instanceof Automation.Version v ? v : null;
    }

    void showDownloading(long received, long total) {
        downloading = true;
        progress.setVisible(true);
        cancelDownload.setVisible(true);
        progress.setValue(total <= 0 ? 0 : (int) Math.min(1000, received * 1000 / total));
        progress.setString(NavText.progress(received, total));
        downloadResult.setVisible(false);
        refresh();
    }

    void showDownload(String text, boolean error) {
        downloading = false;
        downloadError = error;
        progress.setVisible(false);
        cancelDownload.setVisible(false);
        downloadResult.setText(text == null ? "" : text);
        downloadResult.setVisible(text != null && !text.isEmpty());
        downloadResult.getAccessibleContext().setAccessibleDescription(error ? "Erro" : "");
        restyle();
        refresh();
        repack();
    }

    private void repack() {
        revalidate();
        repaint();
        java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (w != null) {
            w.pack();
        }
    }

    private void refresh() {
        boolean idle = !busy && !downloading;
        pickZip.setEnabled(idle);
        pickFolder.setEnabled(idle);
        useSeparate.setEnabled(idle);
        generate.setEnabled(idle);
        versions.setEnabled(idle && versions.getItemCount() > 1);
        boolean can = idle && downloadBlocked == null && chosenVersion() != null;
        download.setEnabled(can);
        download.setToolTipText(can ? null : downloadBlocked != null ? downloadBlocked : NavText.PKG_VERSION_PLACEHOLDER);
        download.getAccessibleContext().setAccessibleDescription(download.getToolTipText());
    }

    String statusText() {
        return status.getText();
    }

    String localText() {
        return localResult.isVisible() ? localResult.getText() : "";
    }

    boolean localIsError() {
        return localError;
    }

    String downloadText() {
        return downloadResult.isVisible() ? downloadResult.getText() : "";
    }

    boolean downloadIsError() {
        return downloadError;
    }

    ActionButton pickZipButton() {
        return pickZip;
    }

    ActionButton pickFolderButton() {
        return pickFolder;
    }

    ActionButton useSeparateButton() {
        return useSeparate;
    }

    ActionButton generateButton() {
        return generate;
    }

    JComboBox<Object> versionsBox() {
        return versions;
    }

    ActionButton downloadButton() {
        return download;
    }

    GhostButton cancelDownloadButton() {
        return cancelDownload;
    }

    JProgressBar progressBar() {
        return progress;
    }

    GhostButton closeButton() {
        return close;
    }

    List<String> versionLabels() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < versions.getItemCount(); i++) {
            Object o = versions.getItemAt(i);
            out.add(o instanceof Automation.Version v ? NavText.versionChoice(v) : String.valueOf(o));
        }
        return out;
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        setBackground(Themes.color(t.bg()));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Themes.color(t.border())),
                BorderFactory.createEmptyBorder(ClientTheme.SPACE_4, ClientTheme.SPACE_5, ClientTheme.SPACE_4, ClientTheme.SPACE_5)));
        title.setFont(Themes.font(ClientTheme.FONT_SIZE_LG, true));
        title.setForeground(Themes.color(t.text()));
        for (JLabel l : List.of(localTitle, downloadTitle)) {
            l.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, true));
            l.setForeground(Themes.color(t.text()));
        }
        status.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        status.setForeground(Themes.color(t.text()));
        localHint.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
        localHint.setForeground(Themes.color(t.textSecondary()));
        localResult.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        localResult.setForeground(Themes.color(localError ? t.dangerText() : t.text()));
        downloadResult.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        downloadResult.setForeground(Themes.color(downloadError ? t.dangerText() : t.text()));
        versions.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
    }
}
