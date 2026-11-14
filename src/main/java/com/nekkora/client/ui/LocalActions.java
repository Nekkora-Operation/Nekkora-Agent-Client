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
import com.nekkora.client.run.Runner;

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
 * <li>executar e SO LOCAL e nunca comeca sozinho: abre a preparacao (pacote conferido, Python escolhido pela pessoa,
 * pasta de saida, aprovacao explicita) e so "Iniciar execucao" lanca o processo. Automacao cujo pacote nao esta neste
 * computador nao e baixada: a tela explica e aponta a importacao;</li>
 * <li>disco, rede e processos ficam fora da fila de eventos.</li>
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
    private final Runner runner;
    private final Executor background;
    private final Host host;
    /** A execucao que o formulario aberto prepara ou acompanha. */
    private record Target(Navigation.Context context, String fingerprint, java.util.UUID automationId, java.util.UUID versionId) {
    }

    private JDialog runDialog;
    private RunForm runForm;
    private Target runTarget;
    private String runId;
    /** Os testes abrem os formularios sem bloquear a fila de eventos. */
    boolean modal = true;
    private JDialog dialog;
    private JPanel form;

    LocalActions(Workshop workshop, Runner runner, Executor background, Host host) {
        this.workshop = workshop;
        this.runner = runner;
        this.background = background;
        this.host = host;
        if (runner != null) {
            runner.addListener(s -> SwingUtilities.invokeLater(() -> {
                if (runForm != null && s.id().equals(runId)) {
                    runForm.showStatus(s);
                    if (s.finished() && runDialog != null) {
                        runDialog.pack(); // o resultado ocupa mais linhas que o andamento
                    }
                }
            }));
        }
    }

    @Override
    public java.util.function.Consumer<ContextView.Entry> run() {
        return runner == null ? null : this::execute;
    }

    @Override
    public java.util.function.Consumer<ContextView.Entry> edit() {
        return this::rename;
    }

    @Override
    public Runnable generate() {
        return this::openGenerate;
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

    /** O contexto da tela agora, ou nulo (sem sessao, sem organizacao ou sem ambiente): sem contexto, nenhuma acao. */
    private Navigation.Context context() {
        Navigation nav = host.navigation();
        return nav == null ? null : nav.state().context();
    }

    private boolean still(Navigation.Context context) {
        Navigation.Context now = context();
        return now != null && now.organization().id().equals(context.organization().id()) && now.workspace().id().equals(context.workspace().id());
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

    // ------------------------------------------------------------------ gerar pacote novo (pasta -> .zip)

    /** O formulario de geracao aberto e o retrato da pasta escolhida nele. Um de cada vez. */
    private GenerateForm generateForm;
    private Workshop.Draft generateDraft;
    private long generateTurn;
    private String generatedPath;
    private final java.util.concurrent.atomic.AtomicBoolean generateCancelled = new java.util.concurrent.atomic.AtomicBoolean();
    /** Pergunta antes de substituir um arquivo existente. Os testes trocam por uma resposta fixa. */
    java.util.function.Predicate<String> confirmOverwrite = path -> {
        Object[] options = {NavText.CANCEL, NavText.GENERATE_REPLACE};
        return javax.swing.JOptionPane.showOptionDialog(dialog, NavText.generateReplace(path), NavText.GENERATE_TITLE, javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE, null, options, options[0]) == 1;
    };
    /**
     * Mostra, no gerenciador de arquivos, a pasta do arquivo gerado. Quem monta o aplicativo entrega a acao (a tela nao
     * conhece arquivos nem o sistema); sem ela, o caminho ja esta escrito na tela. Os testes trocam por um registro.
     */
    java.util.function.Consumer<String> reveal = path -> {
    };

    /**
     * "Gerar pacote novo": abre o formulario. Nada e lido ate a pessoa escolher a pasta, e nada e gravado ate ela escolher
     * onde salvar. Nao depende de organizacao nem de ambiente: o resultado e so um arquivo .zip neste computador.
     */
    void openGenerate() {
        GenerateForm f = new GenerateForm(new GenerateForm.Events() {
            @Override
            public void browse() {
                String folder = chooseFolder();
                if (folder != null) {
                    setProjectFolder(folder);
                }
            }

            @Override
            public void changed() {
                refreshSummary();
            }

            @Override
            public void generate() {
                String suggested = generateForm == null ? "pacote.zip" : NavText.generateFileName(generateForm.values().name(), generateForm.values().version());
                String destination = chooseSave(suggested);
                if (destination != null) {
                    generateTo(destination);
                }
            }

            @Override
            public void cancel() {
                generateCancelled.set(true);
                close();
            }

            @Override
            public void reveal() {
                if (generatedPath != null) {
                    reveal.accept(generatedPath);
                }
            }
        });
        open(NavText.GENERATE_TITLE, f, this::dropGenerate);
        generateForm = f;
        generatedPath = null;
        generateCancelled.set(false);
    }

    /** Fecha o retrato da pasta (apaga a copia de trabalho) e esquece o formulario. */
    private void dropGenerate() {
        generateTurn++;
        generateCancelled.set(true);
        Workshop.Draft d = generateDraft;
        generateDraft = null;
        generateForm = null;
        if (d != null) {
            background.execute(d::close);
        }
    }

    /** Seletor de PASTA. Devolve o caminho escolhido, ou nulo se a pessoa desistiu. */
    private String chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(NavText.GENERATE_BROWSE_TITLE);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        return chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().getAbsolutePath() : null;
    }

    /** "Salvar como": so .zip. Devolve o destino escolhido (com a extensao), ou nulo se a pessoa desistiu. */
    private String chooseSave(String suggested) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(NavText.GENERATE_SAVE_TITLE);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(NavText.IMPORT_FILTER, "zip"));
        chooser.setSelectedFile(chooser.getFileSystemView().createFileObject(suggested));
        if (chooser.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        String path = chooser.getSelectedFile().getAbsolutePath();
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? path : path + ".zip";
    }

    /**
     * A pessoa escolheu (ou trocou) a pasta do projeto: o retrato anterior e descartado, a entrada escolhida antes some e a
     * pasta nova e lida fora da fila de eventos. Ler a pasta nao executa nada dela.
     */
    void setProjectFolder(String folderPath) {
        GenerateForm f = generateForm;
        if (f == null) {
            return;
        }
        long mine = ++generateTurn;
        Workshop.Draft old = generateDraft;
        generateDraft = null;
        generatedPath = null;
        f.showReading(folderPath);
        background.execute(() -> {
            if (old != null) {
                old.close();
            }
            Workshop.Draft draft = null;
            String refused = null;
            try {
                draft = workshop.openFolder(folderPath);
            } catch (Workshop.Refused r) {
                refused = r.getMessage();
            } catch (RuntimeException e) {
                refused = NavText.GENERATE_FOLDER_FAILED;
            }
            Workshop.Draft opened = draft;
            String why = refused;
            SwingUtilities.invokeLater(() -> {
                if (generateForm != f || generateTurn != mine) {
                    if (opened != null) {
                        background.execute(opened::close); // a pessoa ja trocou de pasta ou fechou
                    }
                    return;
                }
                if (opened == null) {
                    f.showFolderRefused(why);
                    return;
                }
                generateDraft = opened;
                f.showFolder(opened);
                refreshSummary();
            });
        });
    }

    /** Refaz o resumo com as escolhas atuais. So leitura do retrato: nada e gravado. */
    void refreshSummary() {
        GenerateForm f = generateForm;
        Workshop.Draft draft = generateDraft;
        if (f == null || draft == null || !f.usable() || f.done()) {
            return;
        }
        GenerateForm.Values v = f.values();
        if (v.entry().isEmpty() || v.name().isEmpty()) {
            f.clearSummary();
            return;
        }
        long mine = ++generateTurn;
        background.execute(() -> {
            Workshop.PackageSummary summary = null;
            String refused = null;
            try {
                summary = workshop.summary(draft, v.name(), v.version(), v.entry(), v.python(), v.dependencies(), v.dependenciesConfirmed());
            } catch (Workshop.Refused r) {
                refused = r.getMessage();
            } catch (RuntimeException e) {
                refused = NavText.GENERATE_FAILED;
            }
            Workshop.PackageSummary done = summary;
            String why = refused;
            SwingUtilities.invokeLater(() -> {
                if (generateForm != f || generateTurn != mine || generateDraft != draft) {
                    return;
                }
                if (done == null) {
                    f.clearSummary();
                    f.setError(why);
                    f.setAdvancedOpen(true); // o que falta informar fica na secao avancada
                } else {
                    f.showSummary(done);
                }
            });
        });
    }

    /**
     * Gera o .zip no destino escolhido. Destino que ja existe: pergunta antes; sem o sim, nada e gravado. Gerar nao executa
     * o codigo, nao prepara Python, nao aprova nada, nao envia nada a Platform e nao acrescenta nada a lista de automacoes.
     */
    void generateTo(String destination) {
        GenerateForm f = generateForm;
        Workshop.Draft draft = generateDraft;
        if (f == null || draft == null || !f.usable() || f.done()) {
            return;
        }
        GenerateForm.Values v = f.values();
        if (v.entry().isEmpty()) {
            f.setError(NavText.GENERATE_NEEDS);
            return;
        }
        boolean overwrite = false;
        if (workshop.exists(destination)) {
            if (!confirmOverwrite.test(destination)) {
                f.setError(NavText.GENERATE_NOT_REPLACED);
                return;
            }
            overwrite = true;
        }
        boolean replace = overwrite;
        long mine = ++generateTurn;
        generateCancelled.set(false);
        f.setBusy(true);
        background.execute(() -> {
            Workshop.Generated generated = null;
            String refused = null;
            try {
                generated = workshop.generate(draft, v.name(), v.version(), v.entry(), v.python(), v.dependencies(), v.dependenciesConfirmed(), destination,
                        replace, generateCancelled::get);
            } catch (Workshop.Refused r) {
                refused = r.getMessage();
            } catch (RuntimeException e) {
                refused = NavText.GENERATE_FAILED;
            }
            Workshop.Generated done = generated;
            String why = refused;
            SwingUtilities.invokeLater(() -> {
                if (generateForm != f || generateTurn != mine) {
                    return; // formulario fechado no meio: o nucleo ja apagou o temporario
                }
                if (done == null) {
                    f.setBusy(false);
                    f.setError(why);
                    return;
                }
                generatedPath = done.path();
                f.showGenerated(done.path());
            });
        });
    }

    GenerateForm generateForm() {
        return generateForm;
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

    // ------------------------------------------------------------------ executar (so local)

    /**
     * "Executar" numa linha: abre a PREPARACAO. Nada e lancado aqui. Sem o pacote neste computador, explica e aponta a
     * importacao (o aplicativo nao baixa codigo por uma rota de metadados).
     */
    void execute(ContextView.Entry entry) {
        Navigation.Context context = context();
        if (context == null) {
            return;
        }
        if (entry.local() == null) {
            host.notice(NavText.RUN_NO_PACKAGE, false);
            return;
        }
        if (runner.running().isPresent()) {
            host.notice(NavText.RUN_BUSY, false);
            if (runDialog != null && !runDialog.isVisible()) {
                runDialog.setVisible(true);
            }
            return;
        }
        closeRun();
        // a versao da Platform so acompanha a execucao se o pacote local for EXATAMENTE o publicado
        java.util.UUID automation = entry.automation() == null ? null : entry.automation().id();
        java.util.UUID version = entry.automation() != null && entry.automation().published() != null
                && entry.local().matches(entry.automation().published().fingerprint()) ? entry.automation().published().versionId() : null;
        Target target = new Target(context, entry.local().fingerprint(), automation, version);
        RunForm f = new RunForm(this::choosePython, this::chooseOutput, this::approve, this::start, () -> runner.cancel(), this::closeRun);
        JDialog d = new JDialog(host.frame(), NavText.RUN_TITLE, false);
        d.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        d.setContentPane(f);
        d.pack();
        d.setLocationRelativeTo(host.frame());
        runDialog = d;
        runForm = f;
        runTarget = target;
        runId = null;
        d.setVisible(true);
        background.execute(() -> {
            Runner.Checked checked = null;
            String problem = null;
            try {
                checked = runner.check(context.organization().id(), context.workspace().id(), target.fingerprint());
            } catch (Runner.Refused r) {
                problem = r.getMessage();
            } catch (RuntimeException e) {
                problem = "Não foi possível conferir o pacote.";
            }
            Runner.Checked ok = checked;
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm == f) {
                    f.showPackage(ok, why);
                    d.pack();
                }
            });
        });
    }

    private void choosePython() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Escolher o interpretador Python");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(runDialog) == JFileChooser.APPROVE_OPTION) {
            setPython(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Escolher a pasta de saída");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(runDialog) == JFileChooser.APPROVE_OPTION) {
            setOutput(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /** O interpretador que a pessoa escolheu: conferido contra o pacote (versao e dependencias), sem instalar nada. */
    void setPython(String path) {
        RunForm f = runForm;
        Target t = runTarget;
        if (f == null || t == null) {
            return;
        }
        f.setBusy(true);
        background.execute(() -> {
            Runner.Python found = null;
            String problem = null;
            try {
                found = runner.python(t.context().organization().id(), t.context().workspace().id(), t.fingerprint(), path);
            } catch (Runner.Refused r) {
                problem = r.getMessage();
            } catch (RuntimeException e) {
                problem = "Não foi possível conferir este interpretador.";
            }
            Runner.Python ok = found;
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm == f) {
                    f.setBusy(false);
                    f.showPython(path, ok, why);
                    runDialog.pack();
                }
            });
        });
    }

    void setOutput(String folder) {
        RunForm f = runForm;
        if (f == null) {
            return;
        }
        background.execute(() -> {
            String ok = null;
            String problem = null;
            try {
                ok = runner.output(folder);
            } catch (Runner.Refused r) {
                problem = r.getMessage();
            } catch (RuntimeException e) {
                problem = "Não foi possível usar esta pasta.";
            }
            String out = ok == null ? folder : ok;
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm == f) {
                    f.showOutput(out, why);
                    runDialog.pack();
                }
            });
        });
    }

    /** Aprovacao explicita: e o clique da pessoa neste botao, para este conteudo. Nao inicia nada. */
    private void approve() {
        RunForm f = runForm;
        Target t = runTarget;
        if (f == null || t == null) {
            return;
        }
        f.setBusy(true);
        background.execute(() -> {
            String problem = null;
            try {
                runner.approve(t.context().organization().id(), t.context().workspace().id(), t.fingerprint());
            } catch (Runner.Refused r) {
                problem = r.getMessage();
            } catch (RuntimeException e) {
                problem = "Não foi possível registrar a aprovação.";
            }
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm == f) {
                    f.setBusy(false);
                    f.showApproved(why == null, why);
                    runDialog.pack();
                }
            });
        });
    }

    private void start() {
        RunForm f = runForm;
        Target t = runTarget;
        if (f == null || t == null || !f.canStart()) {
            return;
        }
        if (!still(t.context())) {
            // a tela nao esta mais no contexto em que a execucao foi preparada: nada e lancado
            closeRun();
            return;
        }
        f.setBusy(true);
        background.execute(() -> {
            Runner.Status started = null;
            String problem = null;
            try {
                started = runner.start(t.context().organization().id(), t.context().workspace().id(), t.fingerprint(), t.automationId(), t.versionId(), f.pythonPath(),
                        f.outputFolder());
            } catch (Runner.Refused r) {
                problem = r.getMessage();
            } catch (RuntimeException e) {
                problem = "Não foi possível iniciar a execução.";
            }
            Runner.Status s = started;
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm != f) {
                    return;
                }
                f.setBusy(false);
                if (s == null) {
                    f.showStartProblem(why);
                } else {
                    runId = s.id();
                    f.showStatus(s);
                }
                runDialog.pack();
            });
        });
    }

    /** Fecha a janela da execucao. NAO cancela nada: a execucao em andamento segue, e as salvaguardas valem. */
    void closeRun() {
        JDialog d = runDialog;
        if (d != null && runner != null && runner.running().isPresent()) {
            d.setVisible(false); // segue acompanhando; "Executar" a traz de volta
            return;
        }
        runDialog = null;
        runForm = null;
        runTarget = null;
        runId = null;
        if (d != null) {
            d.dispose();
        }
    }

    RunForm runForm() {
        return runForm;
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

    /**
     * Fecha o formulario aberto, se houver (tambem quando a sessao termina ou a pessoa sai do contexto). A preparacao de
     * uma execucao que ainda nao comecou fecha junto; a janela de uma execucao EM ANDAMENTO fica.
     */
    void close() {
        closeRun();
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
