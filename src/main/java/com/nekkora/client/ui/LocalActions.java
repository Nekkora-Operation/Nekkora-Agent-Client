package com.nekkora.client.ui;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.nekkora.client.nav.Directory;
import com.nekkora.client.nav.Navigation;
import com.nekkora.client.pack.Workshop;

/**
 * As acoes locais da tela da organizacao: importar um pacote (.zip), preparar uma automacao nova a partir de um ZIP de
 * codigo, e renomear. Regras:
 * <ul>
 * <li>toda acao nasce no CONTEXTO (organizacao e ambiente) da tela naquele instante e termina nele: o pacote vai para a
 * area daquele contexto, e se a pessoa trocou de contexto no meio, a tela nova nao recebe aviso nem lista da antiga;</li>
 * <li>importar e preparar sao locais: nada vai para a Platform, nada e executado, nada e instalado, e nao dependem de
 * maquina pareada. Exigem so a sessao e o contexto que a Platform listou;</li>
 * <li>renomear uma automacao da Platform e uma chamada ao servidor, que decide se a pessoa pode; renomear um item so
 * local mexe apenas no nome mostrado neste computador;</li>
 * <li>disco e rede ficam fora da fila de eventos.</li>
 * </ul>
 */
final class LocalActions implements ContextView.Actions {

    /** O que as acoes precisam da janela. */
    interface Host {
        JFrame frame();

        /** A navegacao da sessao atual, ou nulo se a pessoa nao esta dentro. */
        Navigation navigation();

        Directory directory();

        void notice(String text, boolean error);

        /** Os pacotes locais do contexto mudaram: a janela recarrega a lista dele. */
        void localChanged(Navigation.Context context);
    }

    private final Workshop workshop;
    private final Executor background;
    private final Host host;
    /** Os testes abrem os formularios sem bloquear a fila de eventos. */
    boolean modal = true;
    private JDialog dialog;
    private JPanel form;

    LocalActions(Workshop workshop, Executor background, Host host) {
        this.workshop = workshop;
        this.background = background;
        this.host = host;
    }

    @Override
    public java.util.function.Consumer<ContextView.Entry> run() {
        return null; // execucao local: tarefa seguinte
    }

    @Override
    public java.util.function.Consumer<ContextView.Entry> edit() {
        return this::rename;
    }

    @Override
    public Runnable create() {
        return () -> {
            String zip = chooseZip(NavText.NEW_CHOOSE);
            if (zip != null) {
                newAutomation(zip);
            }
        };
    }

    @Override
    public Runnable importZip() {
        return () -> {
            String zip = chooseZip(NavText.IMPORT_TITLE);
            if (zip != null) {
                importZip(zip);
            }
        };
    }

    /** O contexto da tela agora, ou nulo (sem sessao, sem organizacao ou sem ambiente): sem contexto, nenhuma acao. */
    private Navigation.Context context() {
        Navigation nav = host.navigation();
        return nav == null ? null : nav.state().context();
    }

    private boolean still(Navigation.Context context) {
        Navigation.Context now = context();
        return now != null && now.organization().id().equals(context.organization().id()) && now.workspace().id().equals(context.workspace().id());
    }

    /** Seletor de arquivo que so oferece .zip. Devolve o caminho escolhido, ou nulo se a pessoa desistiu. */
    private String chooseZip(String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(NavText.IMPORT_FILTER, "zip"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        return chooser.showOpenDialog(host.frame()) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().getAbsolutePath() : null;
    }

    // ------------------------------------------------------------------ importar

    /** Importa o pacote do arquivo para o contexto atual. Nao executa nada. */
    void importZip(String zipPath) {
        Navigation.Context context = context();
        if (context == null) {
            return;
        }
        host.notice(NavText.WORKING, false);
        background.execute(() -> {
            String text;
            boolean error = false;
            try {
                Workshop.Imported done = workshop.importZip(context.organization().id(), context.workspace().id(), zipPath);
                text = NavText.imported(done.item().name(), done.item().packageVersion(), done.alreadyPresent());
            } catch (Workshop.Refused r) {
                text = r.getMessage();
                error = true;
            } catch (RuntimeException e) {
                text = "Não foi possível importar este arquivo.";
                error = true;
            }
            finish(context, text, error, !error);
        });
    }

    private void finish(Navigation.Context context, String text, boolean error, boolean changed) {
        SwingUtilities.invokeLater(() -> {
            if (!still(context)) {
                return; // a pessoa trocou de contexto: o resultado pertence ao anterior e nao aparece no novo
            }
            host.notice(text, error);
            if (changed) {
                host.localChanged(context);
            }
        });
    }

    // ------------------------------------------------------------------ nova automacao

    /** Abre o ZIP de codigo e mostra o formulario do pacote. Nada e gravado ate "Preparar pacote". */
    void newAutomation(String zipPath) {
        Navigation.Context context = context();
        if (context == null) {
            return;
        }
        host.notice(NavText.WORKING, false);
        background.execute(() -> {
            Workshop.Draft draft;
            try {
                draft = workshop.open(zipPath);
            } catch (Workshop.Refused r) {
                finish(context, r.getMessage(), true, false);
                return;
            } catch (RuntimeException e) {
                finish(context, "Não foi possível abrir este arquivo.", true, false);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (!still(context)) {
                    draft.close();
                    return;
                }
                host.notice("", false);
                showNewForm(context, draft);
            });
        });
    }

    private void showNewForm(Navigation.Context context, Workshop.Draft draft) {
        NewPackageForm[] holder = new NewPackageForm[1];
        NewPackageForm f = new NewPackageForm(draft, v -> prepare(context, draft, holder[0], v), () -> {
            draft.close();
            close();
        });
        holder[0] = f;
        open(NavText.NEW_TITLE, f, draft::close);
    }

    private void prepare(Navigation.Context context, Workshop.Draft draft, NewPackageForm f, NewPackageForm.Values v) {
        if (v.entry().isEmpty()) {
            f.setError("Escolha o arquivo de entrada.");
            return;
        }
        f.setBusy(true);
        background.execute(() -> {
            String failure = null;
            Workshop.Imported done = null;
            try {
                done = workshop.build(context.organization().id(), context.workspace().id(), draft, v.name(), v.version(), v.entry(), v.python(), v.dependencies(),
                        v.dependenciesConfirmed());
            } catch (Workshop.Refused r) {
                failure = r.getMessage();
            } catch (RuntimeException e) {
                failure = "Não foi possível preparar o pacote.";
            }
            String why = failure;
            Workshop.Imported result = done;
            SwingUtilities.invokeLater(() -> {
                if (form != f) {
                    return; // formulario ja fechado
                }
                f.setBusy(false);
                if (why != null) {
                    f.setError(why);
                    return;
                }
                draft.close();
                close();
                if (still(context)) {
                    host.notice(NavText.prepared(result.item().name(), result.item().packageVersion()), false);
                    host.localChanged(context);
                }
            });
        });
    }

    // ------------------------------------------------------------------ renomear

    void rename(ContextView.Entry entry) {
        Navigation.Context context = context();
        if (context == null) {
            return;
        }
        RenameForm[] holder = new RenameForm[1];
        RenameForm f = entry.localOnly()
                ? new RenameForm(NavText.RENAME_LOCAL_TITLE, NavText.RENAME_LOCAL_SCOPE, entry.local().name(), false, n -> renameLocal(context, entry, holder[0], n), this::close)
                : new RenameForm(NavText.RENAME_REMOTE_TITLE, NavText.RENAME_REMOTE_SCOPE, entry.automation().name(), true, n -> renameRemote(context, entry, holder[0], n),
                        this::close);
        holder[0] = f;
        open(entry.localOnly() ? NavText.RENAME_LOCAL_TITLE : NavText.RENAME_REMOTE_TITLE, f, null);
    }

    private void renameRemote(Navigation.Context context, ContextView.Entry entry, RenameForm f, String name) {
        Directory directory = host.directory();
        if (directory == null || !still(context)) {
            close();
            return;
        }
        if (name.equals(entry.automation().name())) {
            close();
            return;
        }
        f.setBusy(true);
        background.execute(() -> {
            Directory.Failure failure = null;
            try {
                directory.rename(context.organization().id(), entry.automation().id(), name);
            } catch (Directory.Failed x) {
                failure = x.failure();
            } catch (RuntimeException x) {
                failure = Directory.Failure.INVALID;
            }
            Directory.Failure why = failure;
            SwingUtilities.invokeLater(() -> {
                if (form != f) {
                    return;
                }
                f.setBusy(false);
                if (why != null) {
                    // recusa do servidor: nada mudou, e a tela nao mostra o nome novo
                    f.setError(why == Directory.Failure.DENIED ? NavText.RENAME_REMOTE_DENIED : NavText.of(why));
                    return;
                }
                close();
                if (still(context)) {
                    host.notice(NavText.RENAME_REMOTE_DONE, false);
                    Navigation nav = host.navigation();
                    if (nav != null) {
                        nav.loadAutomations(); // o nome mostrado e o que a Platform devolver
                    }
                }
            });
        });
    }

    private void renameLocal(Navigation.Context context, ContextView.Entry entry, RenameForm f, String name) {
        f.setBusy(true);
        background.execute(() -> {
            String failure = null;
            try {
                workshop.rename(context.organization().id(), context.workspace().id(), entry.local().fingerprint(), name.isEmpty() ? null : name);
            } catch (Workshop.Refused r) {
                failure = r.getMessage();
            } catch (RuntimeException e) {
                failure = "Não foi possível alterar o nome.";
            }
            String why = failure;
            SwingUtilities.invokeLater(() -> {
                if (form != f) {
                    return;
                }
                f.setBusy(false);
                if (why != null) {
                    f.setError(why);
                    return;
                }
                close();
                if (still(context)) {
                    host.notice(NavText.RENAME_LOCAL_DONE, false);
                    host.localChanged(context);
                }
            });
        });
    }

    // ------------------------------------------------------------------ formularios

    private void open(String title, JPanel content, Runnable onDispose) {
        close();
        JDialog d = new JDialog(host.frame(), title, modal);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        d.setContentPane(content);
        d.pack();
        d.setLocationRelativeTo(host.frame());
        if (onDispose != null) {
            d.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    onDispose.run();
                }
            });
        }
        dialog = d;
        form = content;
        d.setVisible(true);
    }

    /** Fecha o formulario aberto, se houver (tambem quando a sessao termina ou a pessoa sai do contexto). */
    void close() {
        JDialog d = dialog;
        dialog = null;
        form = null;
        if (d != null) {
            d.dispose();
        }
    }

    JPanel form() {
        return form;
    }
}
