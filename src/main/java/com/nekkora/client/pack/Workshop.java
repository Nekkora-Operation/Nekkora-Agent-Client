package com.nekkora.client.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 * <p>
 * AGENT-CLIENT-PACOTES-001 T01 - gerar um pacote .zip a partir de uma PASTA: {@link #openFolder} tira um retrato da pasta
 * (copia privada, sem seguir ligacao nem juncao), {@link #summary} mostra o que SERIA gerado e {@link #generate} grava o
 * ZIP onde a pessoa escolheu. Nada e guardado no contexto, nada e executado, importado ou instalado, e a pasta da pessoa
 * nunca e alterada.
 */
public final class Workshop {

    /** Recusa de uma operacao local, com a frase para a pessoa (inclui o codigo de suporte quando ha). */
    public static class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * O arquivo de destino ja existe e a pessoa ainda nao consentiu em substitui-lo. Nada foi gravado: a tela pergunta e,
     * com o sim, chama {@link #generate} de novo com {@code overwrite}.
     */
    public static final class DestinationExists extends Refused {
        private static final long serialVersionUID = 1L;

        public DestinationExists(String message) {
            super(message);
        }
    }

    /**
     * Os metadados de um {@code nekkora-external.json} VALIDO que ja esta na raiz da pasta ("metadados existentes"): os
     * valores iniciais do formulario. A versao nunca e aumentada sozinha.
     */
    public record Manifest(String name, String version, String entry, String python, List<String> dependencies, String fingerprint) {
        public Manifest {
            dependencies = List.copyOf(dependencies);
        }
    }

    /** Um arquivo (ou uma subpasta, com {@code /} no fim) que fica fora do pacote, e por que. */
    public record ExcludedFile(String path, String reason) {
    }

    /**
     * O pacote que SERIA gerado (nada foi gravado).
     *
     * @param included arquivos que entram, relativos a pasta, com {@code /} (o descritor nao esta na lista)
     * @param excluded o que fica fora, com o motivo
     * @param warned arquivos que entram mas cujo nome sugere credencial: a pessoa confere
     * @param fingerprint impressao digital do pacote ({@code sha256:...})
     * @param sizeBytes total do conteudo incluido
     * @param manifestReused o {@code nekkora-external.json} da pasta vai para o pacote como esta (mesma impressao digital)
     */
    public record PackageSummary(String name, String version, String entry, String python, List<String> dependencies, List<String> included,
                                 List<ExcludedFile> excluded, List<String> warned, String fingerprint, long sizeBytes, boolean manifestReused) {
        public PackageSummary {
            dependencies = List.copyOf(dependencies);
            included = List.copyOf(included);
            excluded = List.copyOf(excluded);
            warned = List.copyOf(warned);
        }
    }

    /**
     * O pacote gravado e conferido. {@code sha256} e {@code sizeBytes} sao do arquivo .zip; {@code fingerprint} e do
     * conteudo (e o que identifica o pacote).
     */
    public record Generated(String path, String name, String version, String entry, String fingerprint, String sha256, int fileCount, long sizeBytes) {
    }

    /** Versao usada quando a pasta nao tem manifesto valido e a pessoa nao informou nenhuma. */
    public static final String FIRST_VERSION = "1.0.0";
    private static final String REASON_DESTINATION = "Fora: é o próprio arquivo de destino do pacote";

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
     * Um ZIP de codigo ({@link #open}) ou uma PASTA de projeto ({@link #openFolder}) aberta para virar pacote. Guarda uma
     * copia de trabalho ate {@link #close()}. {@code blockers} nao vazio: a origem nao pode virar pacote, e as frases dizem
     * por que.
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

        /** A origem e uma pasta de projeto (e nao um ZIP). */
        public boolean folder() {
            return project.fromFolder();
        }

        /** A origem como texto, para a tela: o caminho da pasta (ou do ZIP). */
        public String sourceLabel() {
            return project.source().toString();
        }

        /**
         * Os metadados do {@code nekkora-external.json} valido da raiz da pasta, se houver. Um manifesto invalido, em
         * conflito ou de outro formato nunca aparece aqui: vira uma frase em {@link #blockers()}.
         */
        public Optional<Manifest> existingManifest() {
            ExecutionPackage e = project.existing();
            return project.fromFolder() && project.kind() == ImportedProject.Kind.EXTERNAL_PACKAGE && e != null
                    ? Optional.of(new Manifest(e.name(), e.packageVersion(), e.entry(), e.pythonRequirement(), e.dependencies(), e.fingerprint()))
                    : Optional.empty();
        }

        public List<String> blockers() {
            List<String> out = new ArrayList<>();
            project.blockers().forEach(b -> out.add(b.message()));
            if (project.fromFolder()) return out; // uma pasta com manifesto valido pode gerar o pacote: nao e impedimento
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

        /**
         * Arquivos Python do projeto: as opcoes de ponto de entrada (relativos, com {@code /}, inclusive em subpastas). A
         * escolha e sempre da pessoa. Numa pasta, so aparecem arquivos que estao dentro dela e que entram no pacote.
         */
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

    // ------------------------------------------------------------------ pacote a partir de uma pasta

    /**
     * Abre uma PASTA de projeto para gerar um pacote. Le a pasta UMA vez para uma copia privada (apagada em
     * {@link Draft#close()}): o que e mostrado, conferido e empacotado depois e sempre esse retrato. Nao segue ligacao
     * simbolica nem juncao; ambiente virtual, cache e controle de versao nao sao lidos; credenciais nao sao copiadas. A
     * pasta da pessoa (e um manifesto que esteja nela) nunca e alterada.
     */
    public Draft openFolder(String folderPath) throws Refused {
        try {
            if (folderPath == null || folderPath.isBlank()) throw new ImportProblem(ImportProblem.Code.FOLDER_NOT_FOUND);
            return new Draft(importer.openFolder(Path.of(folderPath), SafeZip.Cancel.NEVER));
        } catch (InvalidPathException e) {
            throw new Refused(new ImportProblem(ImportProblem.Code.FOLDER_NOT_FOUND).message());
        } catch (ImportProblem e) {
            throw new Refused(e.message());
        }
    }

    /** {@link #summary(Draft, String, String, String, String, List, boolean, String)} sem destino escolhido ainda. */
    public PackageSummary summary(Draft draft, String name, String version, String entry, String python, List<String> dependencies,
                                  boolean dependenciesConfirmed) throws Refused {
        return summary(draft, name, version, entry, python, dependencies, dependenciesConfirmed, null);
    }

    /**
     * O que SERIA gerado com estas escolhas; nao grava nada. Campo nulo ou em branco usa o do manifesto valido da pasta,
     * se houver; a versao, sem manifesto, e {@link #FIRST_VERSION} (a de um manifesto existente nunca e aumentada sozinha).
     * A entrada precisa ser exatamente um dos {@link Draft#pythonFiles()}.
     * <p>
     * {@code destinationZipPath} (opcional): se o destino fica DENTRO da pasta e ja estava nela quando foi aberta (um
     * pacote gerado antes), esse arquivo fica fora da composicao - o pacote nunca se inclui. Os outros .zip da pasta
     * seguem a regra de sempre (entram, como qualquer arquivo).
     */
    public PackageSummary summary(Draft draft, String name, String version, String entry, String python, List<String> dependencies,
                                  boolean dependenciesConfirmed, String destinationZipPath) throws Refused {
        try {
            Path destination = destinationZipPath == null || destinationZipPath.isBlank() ? null : destination(destinationZipPath);
            return summarize(draft, preview(draft, name, version, entry, python, dependencies, dependenciesConfirmed, destination));
        } catch (ImportProblem e) {
            throw new Refused(e.message());
        }
    }

    /** O destino ja existe? (a tela pergunta antes de substituir; {@link #generate} tambem recusa sem {@code overwrite}) */
    public boolean exists(String destinationZipPath) {
        try {
            return destinationZipPath != null && Files.exists(Path.of(destinationZipPath), LinkOption.NOFOLLOW_LINKS);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * Gera o pacote e o grava em {@code destinationZipPath} (um arquivo .zip numa pasta que existe). Nao guarda nada no
     * contexto, nao executa, nao instala e nao fala com a rede.
     * <ul>
     *   <li>destino existente sem {@code overwrite}: {@link DestinationExists}, e o arquivo fica como estava;</li>
     *   <li>se algum arquivo incluido mudou na pasta desde a abertura (tamanho, data ou conteudo), recusa e pede para
     *       abrir a pasta de novo - o ZIP nunca mistura dois momentos;</li>
     *   <li>o ZIP e escrito num temporario ao lado do destino, relido pelo validador real e so entao trocado de nome; em
     *       falha ou cancelamento o temporario e apagado e nenhum arquivo com o nome final aparece;</li>
     *   <li>a mesma pasta com as mesmas escolhas gera os mesmos bytes e a mesma impressao digital.</li>
     * </ul>
     */
    public Generated generate(Draft draft, String name, String version, String entry, String python, List<String> dependencies,
                              boolean dependenciesConfirmed, String destinationZipPath, boolean overwrite, SafeZip.Cancel cancel) throws Refused {
        try {
            Path destination = destination(destinationZipPath);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
                throw new ImportProblem(ImportProblem.Code.DESTINATION_EXISTS, ZipNames.display(String.valueOf(destination.getFileName())));
            }
            Packager.Preview pv = preview(draft, name, version, entry, python, dependencies, dependenciesConfirmed, destination);
            Packager.Built b = Packager.writeTo(draft.project, pv, destination, overwrite, cancel == null ? SafeZip.Cancel.NEVER : cancel);
            return new Generated(b.zip().toString(), b.name(), b.version(), b.entry(), b.fingerprint(), b.sha256(), b.files(), b.size());
        } catch (ImportProblem e) {
            if (e.code() == ImportProblem.Code.DESTINATION_EXISTS) throw new DestinationExists(e.message());
            throw new Refused(e.message());
        }
    }

    private Packager.Preview preview(Draft draft, String name, String version, String entry, String python, List<String> dependencies,
                                     boolean dependenciesConfirmed, Path destination) throws ImportProblem, Refused {
        ImportedProject p = draft.project;
        if (!p.fromFolder()) throw new Refused("Abra a pasta do projeto para gerar o pacote.");
        synchronized (draft) {
            if (draft.closed) throw new Refused("A pasta foi fechada. Abra a pasta de novo para gerar o pacote.");
        }
        if (!p.blockers().isEmpty()) throw p.blockers().get(0);
        ExecutionPackage existing = p.kind() == ImportedProject.Kind.EXTERNAL_PACKAGE ? p.existing() : null;
        String n = blank(name) && existing != null ? existing.name() : name;
        String v = !blank(version) ? version : existing != null ? existing.packageVersion() : FIRST_VERSION;
        String en = blank(entry) && existing != null ? existing.entry() : entry;
        String py = !blank(python) ? python : existing != null ? existing.pythonRequirement() : p.declarations().python().orElse(null);
        List<String> deps = dependencies != null ? dependencies : existing != null ? existing.dependencies() : p.declarations().exact();
        Set<String> data = new HashSet<>();
        if (existing != null) {
            // um arquivo de dados que o manifesto da pasta ja lista continua no pacote: nada do que ele declara some
            for (ImportedProject.ProjectFile f : p.files()) {
                if (f.rule() == InclusionRules.Rule.DATA && existing.files().containsKey(f.path())) data.add(f.path());
            }
        }
        return Packager.preview(p, new PackagePlan(n, v, en, py, deps, dependenciesConfirmed, data, selfInclusion(p, destination), existing != null));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * O arquivo do retrato que e o proprio destino (um pacote gerado antes, dentro da pasta): fica fora. O destino so e
     * conhecido depois do retrato, por isso a exclusao e feita aqui, pelo caminho, e nao na abertura.
     */
    private static Set<String> selfInclusion(ImportedProject p, Path destination) {
        Set<String> out = new HashSet<>();
        if (destination == null || !destination.startsWith(p.folder().root())) return out;
        String rel = ZipNames.collisionKey(p.folder().root().relativize(destination).toString().replace('\\', '/'));
        for (ImportedProject.ProjectFile f : p.files()) {
            if (ZipNames.collisionKey(f.path()).equals(rel)) out.add(f.path());
        }
        return out;
    }

    private static PackageSummary summarize(Draft draft, Packager.Preview pv) {
        ImportedProject p = draft.project;
        List<ExcludedFile> excluded = new ArrayList<>();
        for (Packager.Excluded x : pv.excluded()) {
            excluded.add(new ExcludedFile(x.path(), pv.plan().exclude().contains(x.path()) ? REASON_DESTINATION : x.rule().label()));
        }
        for (String dir : p.folder().skippedDirs()) excluded.add(new ExcludedFile(dir, InclusionRules.Rule.ENVIRONMENT.label()));
        excluded.sort(Comparator.comparing(ExcludedFile::path));
        boolean reused = p.kind() == ImportedProject.Kind.EXTERNAL_PACKAGE && pv.fingerprint().equals(p.existing().fingerprint());
        PackagePlan plan = pv.plan();
        return new PackageSummary(plan.name(), plan.version(), plan.entry(), plan.python(), plan.dependencies(), new ArrayList<>(pv.files().keySet()), excluded,
                pv.warned(), pv.fingerprint(), pv.bytes(), reused);
    }

    /** O destino: um arquivo .zip (nunca pasta nem ligacao) numa pasta que existe; caminho real da pasta + nome. */
    private static Path destination(String path) throws ImportProblem {
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".zip")) throw new ImportProblem(ImportProblem.Code.DESTINATION_INVALID);
        try {
            Path d = Path.of(path).toAbsolutePath().normalize();
            if (d.getParent() == null || d.getFileName() == null) throw new ImportProblem(ImportProblem.Code.DESTINATION_INVALID);
            d = d.getParent().toRealPath().resolve(d.getFileName());
            if (!Files.isDirectory(d.getParent()) || Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(d)) {
                throw new ImportProblem(ImportProblem.Code.DESTINATION_INVALID);
            }
            return d;
        } catch (InvalidPathException | IOException e) {
            throw new ImportProblem(ImportProblem.Code.DESTINATION_INVALID);
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
