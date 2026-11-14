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

        /** O endereco da Platform desta sessao: separa, no disco, os pacotes baixados de Platforms diferentes. */
        String platformOrigin();
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
    // ---- ambiente Python da preparacao aberta (so em memoria; nada disto e gravado)
    /** O pacote conferido: a faixa de Python e as dependencias que ele declara. */
    private Runner.Checked runChecked;
    private java.util.List<Runner.PythonOption> machinePythons = java.util.List.of();
    /** Os ambientes da pasta que a pessoa indicou. Outra pasta SUBSTITUI estes; nunca se acumulam. */
    private java.util.List<Runner.PythonOption> projectPythons = java.util.List.of();
    private String pythonFolder;
    private String chosenPythonId;
    private java.util.List<String> pythonFailedSources = java.util.List.of();
    /** Cada busca ou conferencia tem a sua vez: a resposta de uma vez anterior nao mexe na tela. */
    private long pythonTurn;
    private java.util.concurrent.atomic.AtomicBoolean pythonCancel = new java.util.concurrent.atomic.AtomicBoolean();
    /** So nos testes: a area util da tela a considerar (simula tela pequena ou escala alta). Nulo: a da tela de verdade. */
    java.awt.Rectangle usableArea;
    /** Os testes abrem os formularios sem bloquear a fila de eventos. */
    boolean modal = true;
    private JDialog dialog;
    private JPanel form;

    LocalActions(Workshop workshop, Runner runner, Executor background, Host host) {
        this.workshop = workshop;
        this.runner = runner;
        this.background = background;
        this.host = host;
        // a troca de tema muda medidas dos controles: a janela do formulario aberto (Gerar, Editar, Renomear) se ajusta de novo
        // ao conteudo e a tela, em vez de ganhar uma barra de rolagem que nao precisa
        Themes.addListener(t -> SwingUtilities.invokeLater(() -> {
            JDialog d = dialog;
            if (d != null && d.isDisplayable()) {
                fit(d);
            }
        }));
        if (runner != null) {
            // a troca de tema muda medidas dos controles: a janela da preparacao se ajusta de novo ao conteudo e a tela
            Themes.addListener(t -> SwingUtilities.invokeLater(this::fitRunDialog));
            runner.addListener(s -> SwingUtilities.invokeLater(() -> {
                if (runForm != null && s.id().equals(runId)) {
                    runForm.showStatus(s);
                    if (s.finished() && runDialog != null) {
                        fitRunDialog(); // o resultado ocupa mais linhas que o andamento
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
        return this::edit;
    }

    @Override
    public Runnable generate() {
        return this::openGenerate;
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

    // ------------------------------------------------------------------ editar: nome e pacote nesta maquina

    /** A automacao cujo "Editar" esta aberto, no contexto em que foi aberto. */
    private record EditTarget(Navigation.Context context, com.nekkora.client.nav.Automation automation, com.nekkora.client.nav.Automation.Capabilities capabilities) {
    }

    private EditTarget editTarget;
    private PackageForm packageForm;
    /** O ultimo arquivo ou pasta escolhido que ainda espera uma decisao (conteudo diferente, ou pasta de codigo). */
    private String pendingLocalPath;
    private final java.util.concurrent.atomic.AtomicBoolean downloadCancelled = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * "Editar". Item so deste computador: muda o nome mostrado aqui. Automacao da Platform: abre as duas secoes - o nome
     * cadastrado (so com a permissao de renomear) e o pacote que ESTE computador usa. Sao operacoes distintas, e nenhuma
     * altera uma versao publicada.
     */
    void edit(ContextView.Entry entry) {
        Navigation.Context context = context();
        if (context == null) {
            return;
        }
        if (entry.localOnly()) {
            rename(entry);
            return;
        }
        Navigation nav = host.navigation();
        com.nekkora.client.nav.Automation.Capabilities caps = nav != null && nav.state().automations().isReady()
                ? nav.state().automations().value().capabilities() : com.nekkora.client.nav.Automation.Capabilities.NONE;
        RenameForm[] holder = new RenameForm[1];
        RenameForm r = caps.rename()
                ? new RenameForm(NavText.RENAME_REMOTE_TITLE, NavText.RENAME_REMOTE_SCOPE, entry.automation().name(), true, n -> renameRemote(context, entry, holder[0], n),
                        this::close)
                : null;
        holder[0] = r;
        PackageForm p = new PackageForm(new PackageForm.Events() {
            @Override
            public void pickZip() {
                String zip = chooseZip(NavText.PKG_PICK_ZIP_TITLE);
                if (zip != null) {
                    chooseLocal(zip);
                }
            }

            @Override
            public void pickFolder() {
                String folder = choosePackageFolder();
                if (folder != null) {
                    chooseLocal(folder);
                }
            }

            @Override
            public void useSeparate() {
                LocalActions.this.useSeparate();
            }

            @Override
            public void generateFromFolder() {
                openGenerate(pendingLocalPath);
            }

            @Override
            public void download() {
                downloadChosen();
            }

            @Override
            public void retryVersions() {
                loadVersions();
            }

            @Override
            public void cancelDownload() {
                downloadCancelled.set(true);
            }

            @Override
            public void close() {
                LocalActions.this.close();
            }
        });
        EditTarget target = new EditTarget(context, entry.automation(), caps);
        open(NavText.EDIT_TITLE, new EditForm(r, p), () -> {
            if (packageForm == p) {
                // fechou (ou trocou de contexto, ou a sessao acabou): um download em curso e interrompido e descartado
                downloadCancelled.set(true);
                packageForm = null;
                editTarget = null;
                pendingLocalPath = null;
            }
        }, () -> {
            packageForm = p;
            editTarget = target;
            pendingLocalPath = null;
            downloadCancelled.set(false);
            refreshPackageStatus();
            loadVersions();
        });
    }

    /** O formulario ainda e o deste alvo, no mesmo contexto? */
    private boolean editing(PackageForm f, EditTarget t) {
        return packageForm == f && editTarget == t && still(t.context());
    }

    /** Seletor de arquivo que so oferece .zip. Devolve o caminho escolhido, ou nulo se a pessoa desistiu. */
    private String chooseZip(String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(NavText.IMPORT_FILTER, "zip"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        return chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().getAbsolutePath() : null;
    }

    private String choosePackageFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(NavText.PKG_PICK_FOLDER_TITLE);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        return chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().getAbsolutePath() : null;
    }

    /** O que este computador tem para a automacao: o pacote da versao publicada, se estiver aqui; senao, um ligado a ela. */
    void refreshPackageStatus() {
        PackageForm f = packageForm;
        EditTarget t = editTarget;
        if (f == null || t == null) {
            return;
        }
        background.execute(() -> {
            java.util.List<Workshop.LocalItem> items;
            try {
                items = workshop.list(t.context().organization().id(), t.context().workspace().id());
            } catch (RuntimeException | LinkageError e) {
                items = java.util.List.of();
            }
            com.nekkora.client.nav.Automation.Published pub = t.automation().published();
            Workshop.LocalItem best = null;
            boolean same = false;
            for (Workshop.LocalItem i : items) {
                if (pub != null && i.matches(pub.fingerprint())) {
                    best = i;
                    same = true;
                    break;
                }
            }
            if (best == null) {
                for (Workshop.LocalItem i : items) {
                    if (t.automation().id().equals(i.linkedAutomationId())) {
                        best = i;
                        break;
                    }
                }
            }
            String text = best == null ? NavText.PKG_NONE : NavText.packageStatus(best, same, pub == null ? null : pub.number());
            SwingUtilities.invokeLater(() -> {
                if (editing(f, t)) {
                    f.showStatus(text);
                }
            });
        });
    }

    /**
     * A pessoa escolheu um .zip ou uma pasta para esta automacao. O conteudo e conferido (manifesto, arquivos, integridade)
     * e comparado com a versao publicada pela impressao digital - nunca pelo nome nem pelos bytes do ZIP. Igual: fica
     * disponivel e ligado a automacao. Diferente: NADA e guardado ate a pessoa decidir. Pasta de codigo sem manifesto:
     * a tela oferece "Gerar pacote novo". Nada e executado.
     */
    void chooseLocal(String path) {
        useLocal(path, Workshop.Choice.ASK);
    }

    /** A decisao explicita depois de "Projeto local diferente da versao publicada": guardar como projeto local separado. */
    void useSeparate() {
        if (pendingLocalPath != null) {
            useLocal(pendingLocalPath, Workshop.Choice.SEPARATE_LOCAL);
        }
    }

    private void useLocal(String path, Workshop.Choice choice) {
        PackageForm f = packageForm;
        EditTarget t = editTarget;
        if (f == null || t == null) {
            return;
        }
        pendingLocalPath = null;
        f.setBusy(true);
        f.showLocal(NavText.WORKING, false, false, false);
        com.nekkora.client.nav.Automation.Published pub = t.automation().published();
        Integer number = pub == null ? null : pub.number();
        background.execute(() -> {
            String text;
            boolean error = false;
            boolean offerSeparate = false;
            boolean offerGenerate = false;
            boolean changed = false;
            String pending = null;
            try {
                Workshop.LocalCandidate c = workshop.inspectLocal(path);
                if (c.kind() == Workshop.LocalKind.CODE_FOLDER) {
                    text = c.message();
                    error = true;
                    offerGenerate = true;
                    pending = path;
                } else if (!c.isPackage()) {
                    text = c.message();
                    error = true;
                } else {
                    Workshop.UseLocalResult r = workshop.useLocal(t.context().organization().id(), t.context().workspace().id(), t.automation().id(), path,
                            pub == null ? null : pub.fingerprint(), choice);
                    switch (r.outcome()) {
                        case MATCHES_PUBLISHED -> {
                            text = NavText.packageMatched(r, number);
                            changed = true;
                        }
                        case STORED_SEPARATE -> {
                            text = NavText.packageSeparate(r);
                            changed = true;
                        }
                        default -> {
                            text = NavText.packageDiffers(r, number);
                            offerSeparate = true;
                            pending = path;
                        }
                    }
                }
            } catch (Workshop.Refused r) {
                text = r.getMessage();
                error = true;
            } catch (RuntimeException | LinkageError e) {
                text = NavText.PKG_LOCAL_FAILED;
                error = true;
            }
            String shown = text;
            boolean bad = error;
            boolean sep = offerSeparate;
            boolean gen = offerGenerate;
            boolean stored = changed;
            String wait = pending;
            SwingUtilities.invokeLater(() -> {
                if (stored && still(t.context())) {
                    host.localChanged(t.context()); // o que foi guardado pertence ao contexto, com ou sem o formulario aberto
                }
                if (!editing(f, t)) {
                    return;
                }
                pendingLocalPath = wait;
                f.setBusy(false);
                f.showLocal(shown, bad, sep, gen);
                if (stored) {
                    refreshPackageStatus();
                }
            });
        });
    }

    /** Consulta as versoes publicadas da automacao (so metadados). Nenhuma e escolhida sozinha. */
    void loadVersions() {
        PackageForm f = packageForm;
        EditTarget t = editTarget;
        Directory directory = host.directory();
        if (f == null || t == null || directory == null) {
            return;
        }
        f.showVersionsLoading();
        background.execute(() -> {
            java.util.List<com.nekkora.client.nav.Automation.Version> all = null;
            Directory.Failure failure = null;
            try {
                all = directory.versions(t.context().organization().id(), t.automation().id());
            } catch (Directory.Failed x) {
                failure = x.failure();
            } catch (RuntimeException | LinkageError x) {
                failure = Directory.Failure.INVALID;
            }
            java.util.List<com.nekkora.client.nav.Automation.Version> list = all;
            Directory.Failure why = failure;
            SwingUtilities.invokeLater(() -> {
                if (!editing(f, t)) {
                    return;
                }
                if (list == null) {
                    f.showVersionsFailed(NavText.of(why));
                    return;
                }
                java.util.List<com.nekkora.client.nav.Automation.Version> downloadable = list.stream().filter(com.nekkora.client.nav.Automation.Version::downloadable).toList();
                String blocked = null;
                if (!t.capabilities().download()) {
                    // publicada com pacote mas sem o que conferir: a Platform e anterior ao contrato de download; senao, falta permissao
                    boolean old = list.stream().anyMatch(v -> v.published() && v.fingerprint() != null && v.sha256() == null);
                    blocked = old ? NavText.PKG_DOWNLOAD_UNSUPPORTED : NavText.PKG_DOWNLOAD_DENIED;
                }
                f.showVersions(downloadable, blocked);
            });
        });
    }

    /**
     * Baixa a versao publicada ESCOLHIDA pela pessoa. Os bytes vao para um arquivo parcial numa area propria do aplicativo;
     * so depois de conferidos (tamanho, SHA-256, pacote valido e a impressao digital que a Platform informou) viram um pacote
     * desta maquina. Cancelado, interrompido ou adulterado: o parcial e descartado e nada fica pronto. Baixar nao executa
     * nem instala dependencias, e nao substitui outra versao ja guardada nem uma execucao em andamento.
     */
    void downloadChosen() {
        PackageForm f = packageForm;
        EditTarget t = editTarget;
        Directory directory = host.directory();
        com.nekkora.client.nav.Automation.Version v = f == null ? null : f.chosenVersion();
        if (f == null || t == null || directory == null || v == null || !v.downloadable() || !t.capabilities().download()) {
            return;
        }
        downloadCancelled.set(false);
        f.showDownloading(0, v.sizeBytes());
        String origin = host.platformOrigin();
        java.util.UUID org = t.context().organization().id();
        java.util.UUID ws = t.context().workspace().id();
        java.util.UUID automation = t.automation().id();
        background.execute(() -> {
            String partial = null;
            java.io.OutputStream out = null;
            String text;
            boolean error = true;
            boolean installed = false;
            try {
                partial = workshop.downloadTarget(origin, org, automation, v.versionId());
                out = workshop.downloadStream(partial);
                java.io.OutputStream target = out;
                long[] shown = {0};
                directory.download(org, automation, v.versionId(), v.sizeBytes(), new Directory.Sink() {
                    @Override
                    public boolean write(byte[] data, int length) {
                        try {
                            target.write(data, 0, length);
                            return true;
                        } catch (java.io.IOException e) {
                            return false;
                        }
                    }

                    @Override
                    public boolean cancelled() {
                        return downloadCancelled.get();
                    }

                    @Override
                    public void progress(long received, long total) {
                        // no maximo uma atualizacao a cada ~1% (e a ultima), para nao inundar a fila de eventos
                        if (received == total || received - shown[0] >= Math.max(1, total / 100)) {
                            shown[0] = received;
                            SwingUtilities.invokeLater(() -> {
                                if (editing(f, t) && !downloadCancelled.get()) {
                                    f.showDownloading(received, total);
                                }
                            });
                        }
                    }
                });
                out.close();
                out = null;
                workshop.installDownloaded(origin, org, ws, automation, v.versionId(), v.number(), partial, v.sha256(), v.sizeBytes(), v.fingerprint(),
                        downloadCancelled::get);
                partial = null; // conferido e instalado: nao ha mais parcial
                installed = true;
                error = false;
                text = NavText.packageDownloaded(v.number());
            } catch (Directory.Failed x) {
                text = NavText.downloadFailed(x.failure());
            } catch (Workshop.Refused r) {
                text = r.getMessage();
            } catch (java.io.IOException | RuntimeException | LinkageError e) {
                text = NavText.downloadFailed(Directory.Failure.LOCAL);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (java.io.IOException e) {
                        // o parcial sera descartado logo abaixo
                    }
                }
                if (partial != null) {
                    try {
                        workshop.discardDownload(partial);
                    } catch (Workshop.Refused | RuntimeException | LinkageError e) {
                        // sobra de parcial e limpa pela propria area de downloads (nunca vira pacote)
                    }
                }
            }
            String shownText = text;
            boolean bad = error;
            boolean done = installed;
            SwingUtilities.invokeLater(() -> {
                if (done && still(t.context())) {
                    host.localChanged(t.context());
                }
                if (!editing(f, t)) {
                    return;
                }
                f.showDownload(shownText, bad);
                if (done) {
                    refreshPackageStatus();
                }
            });
        });
    }

    PackageForm packageForm() {
        return packageForm;
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
     * Mostra, no gerenciador de arquivos, a PASTA dita (o pacote de origem, a copia executada ou a saida). Devolve falso se a
     * pasta nao existe mais. Abrir a pasta nao executa nada dela. Os testes trocam por um registro.
     */
    java.util.function.Predicate<String> openFolder = folder -> false;

    /**
     * "Gerar pacote novo": abre o formulario. Nada e lido ate a pessoa escolher a pasta, e nada e gravado ate ela escolher
     * onde salvar. Nao depende de organizacao nem de ambiente: o resultado e so um arquivo .zip neste computador.
     */
    void openGenerate() {
        openGenerate(null);
    }

    /** {@code folder}: a pasta ja escolhida em Editar (codigo sem manifesto), ou nulo para comecar sem pasta. */
    void openGenerate(String folder) {
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
                requestGenerate();
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

            @Override
            public void selectRequirements() {
                String file = chooseRequirements();
                if (file != null) {
                    setRequirementsFile(file);
                }
            }

            @Override
            public void clearRequirements() {
                clearRequirementsFile();
            }
        });
        open(NavText.GENERATE_TITLE, f, () -> dropGenerate(f), () -> {
            generateForm = f;
            generatedPath = null;
            generateCancelled.set(false);
            if (folder != null) {
                setProjectFolder(folder);
            }
        });
    }

    /** Fecha o retrato da pasta (apaga a copia de trabalho) e esquece o formulario - se ainda for o da tela. */
    private void dropGenerate(GenerateForm closed) {
        if (generateForm != closed) {
            return;
        }
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

    /** Seletor do ARQUIVO de dependencias (um arquivo de texto; nunca uma pasta). Nulo se a pessoa desistiu. */
    private String chooseRequirements() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(NavText.REQUIREMENTS_SELECT_TITLE);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setFileFilter(new FileNameExtensionFilter(NavText.REQUIREMENTS_FILTER, "txt"));
        Workshop.Draft draft = generateDraft;
        if (draft != null) {
            chooser.setCurrentDirectory(chooser.getFileSystemView().createFileObject(draft.sourceLabel())); // comeca na pasta do projeto
        }
        return chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().getAbsolutePath() : null;
    }

    /**
     * A pessoa escolheu o arquivo de dependencias: ele e lido uma vez, como TEXTO, fora da fila de eventos. Nada e instalado,
     * resolvido na rede nem executado. Se nao puder ser lido, a selecao anterior fica como estava e a tela diz por que.
     */
    void setRequirementsFile(String path) {
        GenerateForm f = generateForm;
        Workshop.Draft draft = generateDraft;
        if (f == null || draft == null || !f.usable() || f.done() || f.busy()) {
            return;
        }
        generateTurn++;
        f.setBusy(true, NavText.WORKING);
        background.execute(() -> {
            Workshop.Refused refused = null;
            try {
                draft.selectRequirements(path);
            } catch (Workshop.Refused r) {
                refused = r;
            } catch (RuntimeException | LinkageError e) {
                refused = new Workshop.Refused(NavText.REQUIREMENTS_FAILED);
            }
            Workshop.Refused why = refused;
            SwingUtilities.invokeLater(() -> {
                if (generateForm != f) {
                    return;
                }
                f.setBusy(false, null);
                if (generateDraft != draft) {
                    return;
                }
                if (why != null) {
                    f.showProblem(why);
                    return;
                }
                f.showDependencies(draft);
                refreshSummary();
            });
        });
    }

    /** "Remover selecao": nenhum arquivo e a origem das dependencias. O arquivo em si nao e tocado. */
    void clearRequirementsFile() {
        GenerateForm f = generateForm;
        Workshop.Draft draft = generateDraft;
        if (f == null || draft == null || f.busy() || f.done()) {
            return;
        }
        draft.clearRequirements();
        f.showDependencies(draft);
        refreshSummary();
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
            } catch (RuntimeException | LinkageError e) {
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

    /**
     * "Gerar pacote": confere TODOS os campos antes de abrir o "Salvar como". Com um campo a corrigir, o seletor de destino
     * nem abre: a frase aparece junto do campo, e os campos ficam como estao. Desistir no seletor nao mostra sucesso nem
     * erro: o formulario continua como estava.
     */
    void requestGenerate() {
        GenerateForm f = generateForm;
        Workshop.Draft draft = generateDraft;
        if (f == null || draft == null || !f.usable() || f.done() || f.busy()) {
            return;
        }
        f.commit();
        GenerateForm.Values v = f.values();
        if (v.entry().isEmpty() || v.name().isEmpty()) {
            f.setError(NavText.GENERATE_NEEDS);
            return;
        }
        if (f.pythonInvalid()) {
            return; // o campo ja diz o que corrigir
        }
        generateTurn++; // um resumo pedido antes nao mexe mais na tela
        f.setBusy(true, NavText.GENERATE_CHECKING);
        background.execute(() -> {
            Workshop.Refused refused = null;
            String failed = null;
            try {
                workshop.summary(draft, v.name(), v.version(), v.entry(), v.python(), v.dependencies());
            } catch (Workshop.Refused r) {
                refused = r;
            } catch (RuntimeException | LinkageError e) {
                failed = NavText.GENERATE_FAILED;
            }
            Workshop.Refused why = refused;
            String broke = failed;
            SwingUtilities.invokeLater(() -> {
                if (generateForm != f) {
                    return; // o formulario fechou
                }
                f.setBusy(false, null);
                if (generateDraft != draft) {
                    return;
                }
                if (why != null) {
                    f.showProblem(why);
                    return;
                }
                if (broke != null) {
                    f.setError(broke);
                    return;
                }
                String destination = chooseSave(NavText.generateFileName(v.name(), v.version()));
                if (destination != null) {
                    generateTo(destination);
                }
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
            Workshop.Refused refused = null;
            try {
                summary = workshop.summary(draft, v.name(), v.version(), v.entry(), v.python(), v.dependencies());
            } catch (Workshop.Refused r) {
                refused = r;
            } catch (RuntimeException | LinkageError e) {
                refused = new Workshop.Refused(NavText.GENERATE_FAILED);
            }
            Workshop.PackageSummary done = summary;
            Workshop.Refused why = refused;
            SwingUtilities.invokeLater(() -> {
                if (generateForm != f || generateTurn != mine || generateDraft != draft || f.busy()) {
                    return;
                }
                if (done == null) {
                    f.clearSummary();
                    f.showProblem(why); // junto do campo a corrigir (a secao avancada abre se o campo e dela)
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
        if (f == null || draft == null || !f.usable() || f.done() || f.busy()) {
            return; // inclusive um segundo pedido enquanto o primeiro corre: so uma geracao por vez
        }
        GenerateForm.Values v = f.values();
        if (v.entry().isEmpty()) {
            f.setError(NavText.GENERATE_NEEDS);
            return;
        }
        boolean overwrite = false;
        if (workshop.exists(destination)) {
            if (!confirmOverwrite.test(destination)) {
                f.setInfo(NavText.GENERATE_NOT_REPLACED); // decisao da pessoa, nao uma falha
                return;
            }
            overwrite = true;
        }
        boolean replace = overwrite;
        generateTurn++; // um resumo pedido antes nao mexe mais na tela
        generateCancelled.set(false);
        f.setBusy(true, NavText.GENERATING);
        background.execute(() -> {
            Workshop.Generated generated = null;
            Workshop.Refused refused = null;
            try {
                generated = workshop.generate(draft, v.name(), v.version(), v.entry(), v.python(), v.dependencies(), destination, replace,
                        generateCancelled::get);
            } catch (Workshop.Refused r) {
                refused = r;
            } catch (RuntimeException | LinkageError e) {
                refused = new Workshop.Refused(NavText.GENERATE_FAILED);
            }
            Workshop.Generated done = generated;
            Workshop.Refused why = refused;
            SwingUtilities.invokeLater(() -> {
                if (generateForm != f) {
                    return; // formulario fechado no meio: o nucleo ja apagou o temporario
                }
                // toda geracao termina num estado visivel: o andamento acaba em "Pacote gerado" ou no motivo da falha
                if (done == null) {
                    f.setBusy(false, null);
                    f.showProblem(why);
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
        open(entry.localOnly() ? NavText.RENAME_LOCAL_TITLE : NavText.RENAME_REMOTE_TITLE, f, null, null);
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
            } catch (RuntimeException | LinkageError x) {
                failure = Directory.Failure.INVALID;
            }
            Directory.Failure why = failure;
            SwingUtilities.invokeLater(() -> {
                if (renameFormShown() != f) {
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
            } catch (RuntimeException | LinkageError e) {
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
        RunForm f = new RunForm(new RunForm.Events() {
            @Override
            public void pythonChosen() {
                LocalActions.this.pythonChosen();
            }

            @Override
            public void locateEnvironment() {
                chooseEnvironmentFolder();
            }

            @Override
            public void searchAgain() {
                searchPythons(false);
            }

            @Override
            public void chooseOutput() {
                LocalActions.this.chooseOutput();
            }

            @Override
            public void approve() {
                LocalActions.this.approve();
            }

            @Override
            public void start() {
                LocalActions.this.start();
            }

            @Override
            public void cancel() {
                runner.cancel();
            }

            @Override
            public void close() {
                closeRun();
            }

            @Override
            public void openFolder(String folder) {
                openRunFolder(folder);
            }
        });
        JDialog d = new JDialog(host.frame(), NavText.RUN_TITLE, false);
        d.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        // em tela pequena ou escala alta (150%, 200%) a preparacao e mais alta que a area util: ela rola, em vez de sair da tela
        d.setContentPane(scrollable(d, f));
        d.pack();
        d.setLocationRelativeTo(host.frame());
        runDialog = d;
        fitRunDialog();
        runForm = f;
        runTarget = target;
        runId = null;
        resetPythons();
        d.setVisible(true);
        background.execute(() -> {
            Runner.Checked checked = null;
            String problem = null;
            try {
                checked = runner.check(context.organization().id(), context.workspace().id(), target.fingerprint());
            } catch (Runner.Refused r) {
                problem = r.getMessage();
            } catch (RuntimeException | LinkageError e) {
                problem = "Não foi possível conferir o pacote.";
            }
            Runner.Checked ok = checked;
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm == f) {
                    f.showPackage(ok, why);
                    fitRunDialog();
                    if (ok != null) {
                        runChecked = ok;
                        searchPythons(true);
                    }
                }
            });
        });
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Escolher a pasta de saída");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(runDialog) == JFileChooser.APPROVE_OPTION) {
            setOutput(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------ ambiente Python

    private void resetPythons() {
        pythonCancel.set(true); // o que estiver em curso de uma preparacao anterior e abandonado
        pythonCancel = new java.util.concurrent.atomic.AtomicBoolean();
        pythonTurn++;
        runChecked = null;
        machinePythons = java.util.List.of();
        projectPythons = java.util.List.of();
        pythonFolder = null;
        chosenPythonId = null;
        pythonFailedSources = java.util.List.of();
    }

    /** As opcoes como a pessoa as ve: o ambiente virtual que ela localizou primeiro, depois as instalacoes do computador. */
    private java.util.List<Runner.PythonOption> pythonOptions() {
        java.util.List<Runner.PythonOption> all = new java.util.ArrayList<>(projectPythons);
        for (Runner.PythonOption o : machinePythons) {
            // o mesmo interpretador pode ter chegado tambem pelo PATH: fica so a entrada do ambiente localizado
            if (all.stream().noneMatch(p -> p.path().equalsIgnoreCase(o.path()))) {
                all.add(o);
            }
        }
        return all;
    }

    private Runner.PythonOption chosenPython() {
        String id = chosenPythonId;
        return id == null ? null : pythonOptions().stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    private boolean anyUsablePython() {
        return pythonOptions().stream().anyMatch(Runner.PythonOption::usable);
    }

    /** Uma mensagem com a gravidade dela. */
    private record Said(Notice.Kind kind, String text) {
    }

    /**
     * O que dizer abaixo da caixa. {@code lead}: o resultado da acao que acabou (com a gravidade DELA), se houver. Sem isso, o
     * resumo curto do ambiente escolhido: compativel, ou o motivo em uma frase. Um ambiente escolhido que nao serve e um
     * impedimento (erro); nenhuma escolha feita ainda e so o que falta (texto comum ou aviso).
     */
    private Said pythonSaid(Said lead) {
        Runner.Checked c = runChecked;
        if (c == null) {
            return new Said(Notice.Kind.PLAIN, "");
        }
        String partial = NavText.pythonPartial(pythonFailedSources);
        if (lead != null) {
            return new Said(lead.kind(), (lead.text() + partial).trim());
        }
        Runner.PythonOption chosen = chosenPython();
        if (chosen != null) {
            return new Said(chosen.usable() ? Notice.Kind.PLAIN : Notice.Kind.ERROR, NavText.pythonSummary(chosen, c.pythonRequirement()) + partial);
        }
        if (!anyUsablePython()) {
            return new Said(Notice.Kind.WARNING, NavText.pythonNoneCompatible(c.pythonRequirement(), c.dependencies()) + partial);
        }
        return new Said(Notice.Kind.PLAIN, NavText.PY_CHOOSE + partial);
    }

    private void presentPythons(RunForm f, Said lead) {
        Said said = pythonSaid(lead);
        f.showPythons(pythonOptions(), chosenPythonId, said.kind(), said.text(), pythonFailedSources);
        fitRunDialog();
    }

    /** A pessoa mexeu na caixa de selecao: e uma escolha explicita dela (escolher nao aprova nem inicia nada). */
    private void pythonChosen() {
        RunForm f = runForm;
        if (f == null) {
            return;
        }
        Runner.PythonOption o = f.chosenPython();
        chosenPythonId = o == null ? null : o.id();
        Said said = pythonSaid(null);
        f.showPythonMessage(said.kind(), said.text());
        fitRunDialog();
    }

    /**
     * Procura as instalacoes de Python deste computador (descoberta LIMITADA) e confere cada uma contra o pacote; se a pessoa
     * localizou um ambiente virtual, ele e conferido de novo. Nao e "Atualizar": nada da Platform e consultado.
     * <p>
     * {@code initial}: a busca ao abrir a preparacao. So nela, sem escolha anterior e com EXATAMENTE um ambiente totalmente
     * compativel, esse ambiente vem selecionado. Depois, a escolha e sempre da pessoa: a que existe e preservada pela
     * identidade do ambiente; se ele sumiu, a selecao e limpa e NENHUM outro entra no lugar.
     */
    void searchPythons(boolean initial) {
        RunForm f = runForm;
        Target t = runTarget;
        if (f == null || t == null || runChecked == null) {
            return;
        }
        pythonCancel.set(true);
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        pythonCancel = cancel;
        long mine = ++pythonTurn;
        String folder = pythonFolder;
        String before = chosenPythonId;
        f.showPythonSearching(NavText.PY_SEARCHING);
        fitRunDialog();
        background.execute(() -> {
            Runner.PythonList found = null;
            Runner.ProjectPythons located = null;
            String problem = null;
            try {
                found = runner.pythons(t.context().organization().id(), t.context().workspace().id(), t.fingerprint(), cancel::get);
                if (folder != null && !found.cancelled()) {
                    located = runner.virtualEnvironment(t.context().organization().id(), t.context().workspace().id(), t.fingerprint(), folder, cancel::get);
                }
            } catch (Runner.Refused r) {
                problem = r.getMessage();
            } catch (RuntimeException | LinkageError e) {
                problem = NavText.PY_SEARCH_FAILED;
            }
            Runner.PythonList list = found;
            Runner.ProjectPythons ofFolder = located;
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm != f || pythonTurn != mine) {
                    return; // outra preparacao, outra busca ou a janela fechou: esta resposta nao vale mais
                }
                if (list == null || list.cancelled() || (ofFolder != null && ofFolder.cancelled())) {
                    // a busca falhou: as opcoes e a escolha anteriores ficam como estavam, e a pessoa pode tentar de novo
                    presentPythons(f, new Said(Notice.Kind.ERROR, why == null ? NavText.PY_SEARCH_FAILED : why));
                    return;
                }
                machinePythons = list.options();
                pythonFailedSources = list.failedSources();
                if (ofFolder != null) {
                    projectPythons = ofFolder.options(); // vazio se o ambiente localizado deixou de existir ou de responder
                    if (ofFolder.options().isEmpty()) {
                        pythonFolder = null;
                    }
                }
                Said lead = null;
                if (before != null) {
                    if (pythonOptions().stream().noneMatch(o -> o.id().equals(before))) {
                        chosenPythonId = null;
                        // sem a escolha de antes e sem nenhum outro que sirva, a frase diz tambem o que a automacao precisa
                        Runner.Checked c = runChecked;
                        lead = new Said(Notice.Kind.WARNING, NavText.PY_GONE
                                + (anyUsablePython() || c == null ? "" : " " + NavText.pythonNoneCompatible(c.pythonRequirement(), c.dependencies())));
                    }
                } else if (initial) {
                    java.util.List<Runner.PythonOption> usable = pythonOptions().stream().filter(Runner.PythonOption::usable).toList();
                    if (usable.size() == 1) {
                        chosenPythonId = usable.get(0).id();
                    }
                }
                presentPythons(f, lead);
            });
        });
    }

    /** Seletor de PASTA: a raiz do ambiente virtual (nao um arquivo .py nem o python.exe). Desistir preserva as opcoes e a escolha. */
    private void chooseEnvironmentFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(NavText.PY_LOCATE_TITLE);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setFileHidingEnabled(false); // a pasta de um ambiente virtual costuma comecar com ponto
        if (chooser.showOpenDialog(runDialog) == JFileChooser.APPROVE_OPTION) {
            setEnvironmentFolder(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /**
     * A pessoa apontou a pasta RAIZ de um ambiente virtual (com o nome que ele tiver). Escolher o ambiente nao muda a pasta do
     * codigo nem a de saida.
     * <ul>
     *   <li>a pasta e um ambiente virtual e ele e totalmente compativel: entra nas opcoes e fica ESCOLHIDO - foi para usa-lo que
     *       a pessoa o localizou;</li>
     *   <li>e um ambiente virtual, mas nao atende ao pacote: entra nas opcoes (para ver o motivo) e a escolha anterior e
     *       mantida;</li>
     *   <li>nao e um ambiente virtual (ou esta incompleto, e um link, nao respondeu): NADA muda - nem as opcoes, nem a escolha.
     *       Se ha outro Python que serve, isto e so uma informacao; nunca um erro geral do formulario.</li>
     * </ul>
     * O ambiente localizado antes sai das opcoes quando outro e localizado: nao se acumulam.
     */
    void setEnvironmentFolder(String folder) {
        RunForm f = runForm;
        Target t = runTarget;
        if (f == null || t == null || runChecked == null) {
            return;
        }
        pythonCancel.set(true);
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        pythonCancel = cancel;
        long mine = ++pythonTurn;
        f.showPythonSearching(NavText.PY_SEARCHING_FOLDER);
        fitRunDialog();
        background.execute(() -> {
            Runner.ProjectPythons located = null;
            String problem = null;
            try {
                located = runner.virtualEnvironment(t.context().organization().id(), t.context().workspace().id(), t.fingerprint(), folder, cancel::get);
            } catch (Runner.Refused r) {
                problem = r.getMessage();
            } catch (RuntimeException | LinkageError e) {
                problem = NavText.PY_FOLDER_FAILED;
            }
            Runner.ProjectPythons found = located;
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm != f || pythonTurn != mine) {
                    return;
                }
                if (found == null || found.cancelled()) {
                    presentPythons(f, new Said(Notice.Kind.ERROR, why == null ? NavText.PY_FOLDER_FAILED : why));
                    return;
                }
                if (found.options().isEmpty()) {
                    // tentativa que nao serve: opcoes e escolha ficam EXATAMENTE como estavam. Com outro Python utilizavel, a
                    // frase e uma informacao; sem nenhum, um aviso - e o caminho do Python escolhido nao entra nela
                    com.nekkora.client.run.PythonEnvironments.Problem p = found.problems().isEmpty() ? null : found.problems().get(0);
                    boolean none = p == null || p.issue() == com.nekkora.client.run.PythonEnvironments.Issue.NOT_AN_ENVIRONMENT;
                    boolean other = anyUsablePython();
                    String text = none ? (other ? NavText.PY_NOT_AN_ENVIRONMENT : NavText.PY_NOT_AN_ENVIRONMENT_ALONE)
                            : NavText.pythonProblem(p.name(), p.issue()) + (chosenPython() != null ? " " + NavText.PY_KEPT : "");
                    presentPythons(f, new Said(other ? Notice.Kind.INFO : Notice.Kind.WARNING, text));
                    return;
                }
                Runner.PythonOption env = found.options().get(0);
                String before = chosenPythonId;
                boolean chosenIsLocated = before != null && projectPythons.stream().anyMatch(o -> o.id().equals(before)) && !env.id().equals(before);
                if (env.usable()) {
                    pythonFolder = folder;
                    projectPythons = found.options();
                    chosenPythonId = env.id();
                    presentPythons(f, null);
                    return;
                }
                // localizado, mas nao atende ao pacote: nunca toma o lugar de uma escolha valida. Se o ambiente escolhido e o
                // localizado antes, ele FICA (as opcoes nao mudam); senao, o novo entra nas opcoes, para a pessoa ver o motivo
                if (!chosenIsLocated) {
                    pythonFolder = folder;
                    projectPythons = found.options();
                }
                Runner.Checked c = runChecked;
                String reason = NavText.pythonChoice(env) + ". " + NavText.pythonSummary(env, c == null ? null : c.pythonRequirement());
                presentPythons(f, new Said(Notice.Kind.WARNING, "O ambiente virtual localizado não atende ao pacote: " + reason
                        + (chosenPython() != null ? " " + NavText.PY_KEPT : "")));
            });
        });
    }

    /** Confere de novo o ambiente escolhido (o inicio foi recusado): a caixa mostra a situacao de agora, sem trocar de ambiente. */
    private void recheckChosenPython() {
        RunForm f = runForm;
        Target t = runTarget;
        String id = chosenPythonId;
        if (f == null || t == null || id == null) {
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean cancel = pythonCancel;
        long mine = ++pythonTurn;
        f.showPythonChecking(id);
        background.execute(() -> {
            java.util.Optional<Runner.PythonOption> now = java.util.Optional.empty();
            try {
                now = runner.recheckPython(t.context().organization().id(), t.context().workspace().id(), t.fingerprint(), id, cancel::get);
            } catch (Runner.Refused | RuntimeException | LinkageError e) {
                // fica como estava; a frase do inicio recusado ja diz o que houve
            }
            java.util.Optional<Runner.PythonOption> checked = now;
            SwingUtilities.invokeLater(() -> {
                if (runForm != f || pythonTurn != mine) {
                    return;
                }
                checked.ifPresent(o -> {
                    machinePythons = machinePythons.stream().map(x -> x.id().equals(o.id()) ? o : x).toList();
                    projectPythons = projectPythons.stream().map(x -> x.id().equals(o.id()) ? o : x).toList();
                });
                presentPythons(f, null);
            });
        });
    }

    /** "Abrir pasta": mostra, no gerenciador de arquivos, a pasta dita. Nada dela e executado. */
    private void openRunFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return;
        }
        if (!openFolder.test(folder)) {
            host.notice(NavText.RUN_FOLDER_GONE, false);
        }
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
            } catch (RuntimeException | LinkageError e) {
                problem = "Não foi possível usar esta pasta.";
            }
            String out = ok == null ? folder : ok;
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm == f) {
                    f.showOutput(out, why);
                    fitRunDialog();
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
            } catch (RuntimeException | LinkageError e) {
                problem = "Não foi possível registrar a aprovação.";
            }
            String why = problem;
            SwingUtilities.invokeLater(() -> {
                if (runForm == f) {
                    f.setBusy(false);
                    f.showApproved(why == null, why);
                    fitRunDialog();
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
            } catch (RuntimeException | LinkageError e) {
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
                    recheckChosenPython(); // o ambiente pode ter mudado desde a conferencia: a lista passa a dizer a situacao de agora
                } else {
                    runId = s.id();
                    f.showStatus(s);
                }
                fitRunDialog();
            });
        });
    }

    /**
     * A janela da preparacao no tamanho do conteudo, mas nunca maior que a area util da tela (sem a barra de tarefas) nem
     * fora dela: se o conteudo nao couber, a janela fica com a altura da tela e o conteudo rola.
     */
    private void fitRunDialog() {
        fit(runDialog);
    }

    /**
     * Qualquer janela de formulario no tamanho do conteudo, mas nunca maior que a area util da tela nem fora dela: se o
     * conteudo nao couber (tela pequena, escala de 150% ou 200%), a janela fica com a altura da tela e o conteudo rola.
     */
    private void fit(JDialog d) {
        if (d == null) {
            return;
        }
        d.pack();
        java.awt.GraphicsConfiguration gc = d.getGraphicsConfiguration();
        if (gc == null) {
            return;
        }
        java.awt.Rectangle area = gc.getBounds();
        java.awt.Insets in = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
        area = new java.awt.Rectangle(area.x + in.left, area.y + in.top, area.width - in.left - in.right, area.height - in.top - in.bottom);
        if (usableArea != null) {
            area = usableArea;
        }
        java.awt.Dimension size = d.getSize();
        if (size.height > area.height) {
            size.height = area.height;
            // a barra de rolagem ocupa largura: o texto continua inteiro
            size.width = Math.min(area.width, size.width + javax.swing.UIManager.getInt("ScrollBar.width") + 4);
        }
        size.width = Math.min(size.width, area.width);
        d.setSize(size);
        int x = Math.max(area.x, Math.min(d.getX(), area.x + area.width - size.width));
        int y = Math.max(area.y, Math.min(d.getY(), area.y + area.height - size.height));
        d.setLocation(x, y);
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
        resetPythons();
        if (d != null) {
            d.dispose();
        }
    }

    RunForm runForm() {
        return runForm;
    }

    // ------------------------------------------------------------------ formularios

    /**
     * Abre o formulario. O dialogo e MODAL: {@code setVisible(true)} so retorna quando ele FECHA. Por isso tudo o que liga
     * o formulario as acoes (o estado que os retornos conferem, as consultas iniciais) vai em {@code ready}, que roda
     * ANTES de a janela aparecer. Nada que o formulario precise pode ficar depois da chamada a este metodo.
     */
    private void open(String title, JPanel content, Runnable onDispose, Runnable ready) {
        close();
        JDialog d = new JDialog(host.frame(), title, modal);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        // Editar, Gerar pacote novo e Renomear tambem cabem na tela em escala alta: o conteudo rola, em vez de sair da tela
        d.setContentPane(scrollable(d, content));
        d.pack();
        d.setLocationRelativeTo(host.frame());
        fit(d);
        // o formulario avisa quando muda de altura (secao aberta, mensagem nova): a janela acompanha, dentro da tela
        content.addPropertyChangeListener("conteudo", e -> SwingUtilities.invokeLater(() -> {
            if (dialog == d && d.isDisplayable()) {
                fit(d);
            }
        }));
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (onDispose != null) {
                    onDispose.run();
                }
            }
        });
        dialog = d;
        form = content;
        if (ready != null) {
            ready.run();
        }
        d.setVisible(true);
    }

    /**
     * O conteudo de uma janela de formulario dentro de uma rolagem vertical (nunca horizontal), com a rolagem acompanhando o
     * foco do teclado: quem navega por Tab ve o campo e o botao em que esta.
     */
    private static javax.swing.JScrollPane scrollable(JDialog d, JPanel content) {
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(content, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        java.beans.PropertyChangeListener follow = e -> {
            if (e.getNewValue() instanceof javax.swing.JComponent c && SwingUtilities.isDescendingFrom(c, content)) {
                c.scrollRectToVisible(new java.awt.Rectangle(0, 0, c.getWidth(), c.getHeight()));
            }
        };
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", follow);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", follow);
            }
        });
        return scroll;
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

    /** O formulario de renomear na tela: sozinho (item local) ou dentro do Editar (automacao da Platform). */
    RenameForm renameFormShown() {
        return form instanceof RenameForm r ? r : form instanceof EditForm e ? e.renameForm() : null;
    }
}
