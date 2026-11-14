package com.nekkora.client.pack;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * O que a tela pede a area local de pacotes, sem que a tela conheca arquivos: recebe caminhos como texto, devolve
 * resumos e, quando algo e recusado, UMA mensagem em portugues ({@link Refused}).
 * <p>
 * Tudo aqui e local: nada vai para a Platform, nada e executado e nada e instalado. Cada operacao leva o contexto
 * (organizacao e ambiente) em que foi pedida, e so enxerga os pacotes desse contexto. As chamadas BLOQUEIAM (disco): a
 * tela as faz fora da fila de eventos.
 */
public final class Workshop {

    /** Recusa de uma operacao local, com a frase para a pessoa (inclui o codigo de suporte quando ha). */
    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Um pacote local do contexto. {@code name} e o nome mostrado (o local, se a pessoa deu um; senao o declarado no
     * pacote); {@code declaredName} e {@code packageVersion} sao os do manifesto e nunca mudam.
     */
    public record LocalItem(String fingerprint, String name, String declaredName, String packageVersion, UUID linkedAutomationId) {

        /** Este pacote e o conteudo exato desta impressao digital publicada? */
        public boolean matches(String publishedFingerprint) {
            return publishedFingerprint != null && normalize(publishedFingerprint).equals(normalize(fingerprint));
        }

        private static String normalize(String f) {
            String s = f.trim().toLowerCase(Locale.ROOT);
            return s.startsWith("sha256:") ? s.substring(7) : s;
        }
    }

    public record Imported(LocalItem item, boolean alreadyPresent) {
    }

    /**
     * Um ZIP de codigo aberto para virar pacote ("Nova automacao"). Guarda uma copia de trabalho ate {@link #close()}.
     * {@code blockers} nao vazio: este ZIP nao pode virar pacote, e as frases dizem por que.
     */
    public final class Draft implements AutoCloseable {
        private final ImportedProject project;
        private boolean closed;

        private Draft(ImportedProject project) {
            this.project = project;
        }

        public String kind() {
            return project.kind().label();
        }

        public boolean code() {
            return project.kind() == ImportedProject.Kind.CODE;
        }

        public List<String> blockers() {
            List<String> out = new ArrayList<>();
            project.blockers().forEach(b -> out.add(b.message()));
            if (!code() && out.isEmpty()) {
                out.add(project.kind() == ImportedProject.Kind.EXTERNAL_PACKAGE
                        ? "Este ZIP já é um pacote pronto. Use Importar (.zip) para trazê-lo como está."
                        : "Este ZIP não é um projeto Python que possa virar pacote.");
            }
            return out;
        }

        public List<String> notes() {
            return project.notes();
        }

        /** Arquivos Python do projeto: as opcoes de ponto de entrada. A escolha e sempre da pessoa. */
        public List<String> pythonFiles() {
            return project.pythonFiles();
        }

        public Optional<String> suggestedEntry() {
            return project.suggestedEntry();
        }

        /** Requisito de Python declarado no projeto, se houver. */
        public Optional<String> python() {
            return project.declarations().python();
        }

        /** Dependencias com versao exata declaradas no projeto ({@code nome==versao}). */
        public List<String> dependencies() {
            return project.declarations().exact();
        }

        /** Dependencias declaradas de um jeito que o pacote nao aceita (sem versao exata, por exemplo). */
        public List<String> unsupportedDependencies() {
            return project.declarations().unsupported();
        }

        public boolean dependenciesNeedConfirmation() {
            return project.declarations().dependenciesNeedConfirmation();
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                importer.close(project);
            }
        }
    }

    private final LocalPackages packages;
    private final ProjectImporter importer;

    /** {@code dataRoot}: a pasta de dados do Client (nunca a do Nekkora Agent administrativo). */
    public Workshop(Path dataRoot) {
        this(new LocalPackages(LocalPackages.defaultRoot(dataRoot), AtomicWriter.DEFAULT, Clock.systemUTC()),
                new ProjectImporter(ProjectImporter.defaultRoot(dataRoot), ZipLimits.DEFAULT));
    }

    public Workshop(LocalPackages packages, ProjectImporter importer) {
        this.packages = packages;
        this.importer = importer;
    }

    public LocalPackages packages() {
        return packages;
    }

    /**
     * Importa um pacote pronto de um arquivo .zip para o contexto. Valida o ZIP e o manifesto com as regras de sempre; o
     * mesmo conteudo importado de novo nao duplica.
     */
    public Imported importZip(UUID organization, UUID workspace, String zipPath) throws Refused {
        Path zip = zip(zipPath);
        try {
            LocalPackages.Added a = packages.add(new PackageContext(organization, workspace), zip, SafeZip.Cancel.NEVER);
            return new Imported(item(a.item()), a.alreadyPresent());
        } catch (ImportProblem e) {
            throw new Refused(e.message());
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        }
    }

    /** Os pacotes locais DESTE contexto, em ordem estavel. Os de outro ambiente ou organizacao nao aparecem. */
    public List<LocalItem> list(UUID organization, UUID workspace) {
        List<LocalItem> out = new ArrayList<>();
        packages.list(new PackageContext(organization, workspace)).forEach(i -> out.add(item(i)));
        return out;
    }

    /**
     * Muda SO o nome mostrado neste computador. Manifesto, arquivos e impressao digital nao mudam, e nada vai para a
     * Platform. Nome vazio volta ao nome declarado no pacote.
     */
    public LocalItem rename(UUID organization, UUID workspace, String fingerprint, String localName) throws Refused {
        try {
            return item(packages.rename(new PackageContext(organization, workspace), fingerprint, localName));
        } catch (ImportProblem e) {
            throw new Refused(e.message());
        }
    }

    /** Abre um ZIP de codigo para preparar um pacote novo. Fechar o {@link Draft} apaga a copia de trabalho. */
    public Draft open(String zipPath) throws Refused {
        try {
            return new Draft(importer.open(zip(zipPath), SafeZip.Cancel.NEVER));
        } catch (ImportProblem e) {
            throw new Refused(e.message());
        }
    }

    /**
     * Gera o pacote a partir do codigo aberto e o guarda no contexto. Local: nao cria automacao, rascunho nem versao na
     * Platform, nao executa o codigo e nao instala dependencias.
     */
    public Imported build(UUID organization, UUID workspace, Draft draft, String name, String version, String entry, String python,
                          List<String> dependencies, boolean dependenciesConfirmed) throws Refused {
        PackagePlan plan = new PackagePlan(name, version, entry, python, dependencies, dependenciesConfirmed, Set.of(), Set.of(), false);
        try {
            LocalPackages.Added a = packages.addBuilt(new PackageContext(organization, workspace), draft.project, plan, SafeZip.Cancel.NEVER);
            return new Imported(item(a.item()), a.alreadyPresent());
        } catch (ImportProblem e) {
            throw new Refused(e.message());
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        }
    }

    private static Path zip(String zipPath) throws Refused {
        if (zipPath == null || !zipPath.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new Refused("Escolha um arquivo .zip.");
        }
        try {
            return Path.of(zipPath);
        } catch (InvalidPathException e) {
            throw new Refused("Escolha um arquivo .zip.");
        }
    }

    private static LocalItem item(LocalPackages.Item i) {
        return new LocalItem(i.fingerprint(), i.displayName(), i.declaredName(), i.packageVersion(), i.linkedAutomationId());
    }
}
