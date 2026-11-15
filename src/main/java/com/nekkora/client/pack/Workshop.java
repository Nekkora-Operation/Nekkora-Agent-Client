package com.nekkora.client.pack;

import java.io.IOException;
import java.io.OutputStream;
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
 * <p>
 * T02 - pacote nesta maquina: {@link #inspectLocal} e {@link #useLocal} tratam um .zip ou uma pasta de pacote escolhidos
 * para uma automacao (comparacao SO pela impressao digital), e {@link #downloadTarget}, {@link #installDownloaded} e
 * {@link #discardDownload} guardam um pacote que OUTRA camada baixou da Platform, depois de conferir tamanho, SHA-256,
 * formato e impressao digital. Aqui continua sem rede.
 */
public final class Workshop {

    /** O campo do formulario a que uma recusa se refere: a tela poe a frase junto dele. */
    public enum Field { FOLDER, ENTRY, NAME, VERSION, PYTHON, DEPENDENCIES, DESTINATION, GENERAL }

    /**
     * Recusa de uma operacao local, com a frase para a pessoa. {@link #getMessage()} traz a frase com o codigo de suporte no
     * fim (como sempre); {@link #plain()} e so a frase, {@link #code()} o codigo (para os detalhes) e {@link #field()} o
     * campo a que ela se refere.
     */
    public static class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        private final String plain;
        private final String code;
        private final Field field;

        public Refused(String message) {
            this(message, message, null, Field.GENERAL);
        }

        Refused(String message, String plain, String code, Field field) {
            super(message, null, false, false);
            this.plain = plain;
            this.code = code;
            this.field = field;
        }

        /** A frase, sem o codigo de suporte. */
        public String plain() {
            return plain;
        }

        /** O codigo de suporte ({@code AGT-PAC-xxx}), ou {@code null}. */
        public String code() {
            return code;
        }

        public Field field() {
            return field;
        }
    }

    /** A recusa do nucleo como recusa da operacao, com o campo a que ela se refere. */
    static Refused refused(ImportProblem e) {
        Field f = switch (e.code()) {
            case NAME_INVALID -> Field.NAME;
            case VERSION_INVALID -> Field.VERSION;
            case ENTRY_INVALID -> Field.ENTRY;
            case PYTHON_INVALID -> Field.PYTHON;
            case DEPENDENCY_INVALID, DEPENDENCIES_UNCONFIRMED, REQUIREMENTS_UNRESOLVED, REQUIREMENTS_CHANGED, REQUIREMENTS_UNREADABLE, REQUIREMENTS_COLLISION,
                    DEPENDENCY_SOURCES_CONFLICT, DEPENDENCY_SOURCE_MISSING -> Field.DEPENDENCIES;
            case DESTINATION_INVALID, DESTINATION_EXISTS, TARGET_EXISTS, SAME_AS_SOURCE, NO_SPACE, WRITE_FAILED, WRITE_DENIED, TARGET_LOCKED -> Field.DESTINATION;
            case FOLDER_NOT_FOUND, FOLDER_CHANGED, FOLDER_UNREADABLE -> Field.FOLDER;
            default -> Field.GENERAL;
        };
        String plain = e.code().text() + e.detail().map(d -> " (" + d + ")").orElse("");
        return new Refused(e.message(), plain, e.code().code(), f);
    }

    /**
     * O arquivo de destino ja existe e a pessoa ainda nao consentiu em substitui-lo. Nada foi gravado: a tela pergunta e,
     * com o sim, chama {@link #generate} de novo com {@code overwrite}.
     */
    public static final class DestinationExists extends Refused {
        private static final long serialVersionUID = 1L;

        public DestinationExists(String message) {
            super(message, message, ImportProblem.Code.DESTINATION_EXISTS.code(), Field.DESTINATION);
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

    /**
     * O que a pessoa digitou em "Requisito de Python (opcional)", como o aplicativo o entende.
     *
     * @param valid falso: o texto nao e uma forma aceita; precisa ser corrigido ou apagado (nada e posto no lugar)
     * @param declared ha um requisito (falso com {@code valid}: campo em branco, sem restricao declarada)
     * @param text o requisito como vai no manifesto, ou vazio
     * @param meaning a interpretacao em palavras, para a tela
     */
    public record PythonReading(boolean valid, boolean declared, String text, String meaning) {
    }

    // ------------------------------------------------------------------ dependencias (AGENT-CLIENT-PREPARACAO-002 T04)

    /** De onde vem a lista de dependencias do pacote. Uma so origem vale de cada vez. */
    public enum DependencySource {
        /** O manifesto valido que ja esta na pasta. */
        MANIFEST,
        /** O arquivo de dependencias selecionado (requirements.txt), lido estaticamente. */
        FILE,
        /** A lista digitada pela pessoa nas opcoes avancadas. */
        MANUAL
    }

    /** Uma linha com conteudo do arquivo de dependencias, com a situacao dela. */
    public record RequirementLine(int number, String text, String status, boolean problem) {
    }

    /**
     * O arquivo de dependencias selecionado, como foi lido (uma vez, para uma copia estavel).
     *
     * @param path o caminho do arquivo, para a tela (nunca vai para o manifesto)
     * @param detected foi encontrado sozinho na raiz do projeto (e nao escolhido pela pessoa)
     * @param external esta FORA da pasta do projeto: so entra no pacote se a pessoa mandar copiar
     * @param packagePath onde ele esta (ou ficaria, se copiado) dentro do pacote; {@code null} se nao ha nome livre
     * @param dependencies as dependencias exatas, sem repeticao
     * @param lines cada linha com conteudo e a situacao dela
     * @param resolved nenhuma linha e um problema: a lista pode ir para o manifesto
     */
    public record Requirements(String path, boolean detected, boolean external, String packagePath, List<String> dependencies, List<RequirementLine> lines,
                               boolean resolved) {
        public Requirements {
            dependencies = List.copyOf(dependencies);
            lines = List.copyOf(lines);
        }

        public List<RequirementLine> problems() {
            return lines.stream().filter(RequirementLine::problem).toList();
        }
    }

    /**
     * A decisao da pessoa sobre as dependencias.
     *
     * @param source a origem que vale; {@code null}: ainda nao decidido (so e aceito quando nao ha duas origens em conflito)
     * @param manual a lista digitada (so com {@link DependencySource#MANUAL})
     * @param copyExternalFile copiar para o pacote o arquivo de dependencias que esta fora da pasta do projeto
     */
    public record DependencyChoice(DependencySource source, List<String> manual, boolean copyExternalFile) {
        public DependencyChoice {
            manual = manual == null ? List.of() : List.copyOf(manual);
        }
    }

    /** Nome com que um arquivo de dependencias de fora do projeto entra no pacote (ou o alternativo, se o primeiro existe). */
    static final String REQUIREMENTS_NAME = "requirements.txt";
    static final String REQUIREMENTS_ALTERNATIVE = "requirements-selecionado.txt";

    /** A copia estavel do arquivo de dependencias selecionado. */
    private static final class Selection {
        final Path file;
        final boolean detected;
        final boolean external;
        final String packagePath;
        final byte[] bytes;
        final String sha256;
        final RequirementsFile.Parsed parsed;

        Selection(Path file, boolean detected, boolean external, String packagePath, byte[] bytes, RequirementsFile.Parsed parsed) {
            this.file = file;
            this.detected = detected;
            this.external = external;
            this.packagePath = packagePath;
            this.bytes = bytes;
            this.sha256 = java.util.HexFormat.of().formatHex(ProjectImporter.sha256().digest(bytes));
            this.parsed = parsed;
        }

        Requirements view() {
            List<RequirementLine> lines = new ArrayList<>();
            for (RequirementsFile.Line l : parsed.lines()) lines.add(new RequirementLine(l.number(), l.text(), l.kind().label(), l.problem()));
            return new Requirements(file.toString(), detected, external, packagePath, parsed.dependencies(), lines, parsed.resolved());
        }
    }

    /** Sem requisito: o autor nao declarou restricao. Nao e promessa de que qualquer Python serve. */
    public static final String NO_PYTHON_REQUIREMENT = "Sem restrição de Python declarada";

    /**
     * Interpreta o texto do campo: {@code 3.12} e a familia 3.12.x; {@code 3.12.0} e a versao exata; um especificador do
     * manifesto ({@code >=3.10,<3.14}) fica como esta; em branco, sem restricao declarada. So leitura.
     */
    public static PythonReading readPython(String typed) {
        PythonRequirement.Typed t = PythonRequirement.interpret(typed);
        if (!t.valid()) return new PythonReading(false, false, "", "");
        return t.requirement().map(r -> new PythonReading(true, true, r.text(), r.meaning())).orElse(new PythonReading(true, false, "", NO_PYTHON_REQUIREMENT));
    }

    /** O requisito de um manifesto em palavras (ou {@link #NO_PYTHON_REQUIREMENT}). */
    public static String pythonMeaning(String requirement) {
        if (requirement == null || requirement.isBlank()) return NO_PYTHON_REQUIREMENT;
        return PythonRequirement.parse(requirement).map(PythonRequirement::meaning).orElse(requirement);
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
     * @param dependencySource de onde veio a lista de dependencias; {@code null} quando quem pediu nao disse a origem
     * @param requirementsCopiedAs o nome, na raiz do pacote, da COPIA do arquivo de dependencias que estava fora do projeto;
     *        {@code null} se nenhum arquivo de fora entra
     */
    public record PackageSummary(String name, String version, String entry, String python, List<String> dependencies, List<String> included,
                                 List<ExcludedFile> excluded, List<String> warned, String fingerprint, long sizeBytes, boolean manifestReused,
                                 DependencySource dependencySource, String requirementsCopiedAs) {
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
    public record LocalItem(String fingerprint, String name, String declaredName, String packageVersion, UUID linkedAutomationId,
                            LocalPackages.Origin origin, String sourceLabel, UUID sourceAutomationId, UUID automationVersionId, Integer versionNumber) {

        /**
         * De onde o pacote veio, para a tela ("Arquivo .zip", "Pasta de pacote", "Gerado neste computador", "Baixado da
         * Platform"); vazio para um item guardado antes de a origem ser registrada. {@code sourceAutomationId},
         * {@code automationVersionId} e {@code versionNumber} so existem para um pacote baixado.
         */
        public String originLabel() {
            return origin == null ? "" : origin.label();
        }

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
        private Selection requirements;

        private Draft(ImportedProject project) {
            this.project = project;
            // o requirements.txt da RAIZ do projeto e reconhecido sozinho (a tela diz que foi); outro arquivo, so por escolha
            if (project.fromFolder() && project.file(REQUIREMENTS_NAME).isPresent()) {
                try {
                    requirements = read(project.folder().root().resolve(REQUIREMENTS_NAME), true);
                } catch (ImportProblem e) {
                    requirements = null; // ilegivel: nao e origem de dependencias (continua sendo um arquivo do projeto)
                }
            }
        }

        /** O arquivo de dependencias selecionado agora (o da raiz, reconhecido sozinho, ou o escolhido), se houver. */
        public synchronized Optional<Requirements> requirements() {
            return Optional.ofNullable(requirements).map(Selection::view);
        }

        /**
         * A pessoa escolheu um arquivo de dependencias. Ele e lido UMA vez, como texto, para uma copia estavel: o resumo, o
         * manifesto e (se copiado) o arquivo dentro do pacote saem dessa mesma copia. Nada e instalado nem resolvido, e
         * inclusoes ({@code -r}) e URLs nao sao seguidas. O arquivo original nunca e alterado.
         */
        public synchronized Requirements selectRequirements(String path) throws Refused {
            try {
                if (path == null || path.isBlank()) throw new ImportProblem(ImportProblem.Code.REQUIREMENTS_UNREADABLE);
                requirements = read(Path.of(path), false);
                return requirements.view();
            } catch (InvalidPathException e) {
                throw refused(new ImportProblem(ImportProblem.Code.REQUIREMENTS_UNREADABLE));
            } catch (ImportProblem e) {
                throw refused(e);
            }
        }

        /** "Remover selecao": nenhum arquivo e origem das dependencias (o da raiz continua sendo um arquivo do projeto). */
        public synchronized void clearRequirements() {
            requirements = null;
        }

        /**
         * O manifesto da pasta e o arquivo de dependencias dizem listas DIFERENTES? Enquanto for assim, a pessoa precisa
         * dizer qual vale: nenhuma e escolhida sozinha.
         */
        public synchronized boolean dependencySourcesConflict() {
            Optional<Manifest> m = existingManifest();
            if (m.isEmpty() || requirements == null) return false;
            return !requirements.parsed.resolved() || !sameDependencies(m.get().dependencies(), requirements.parsed.dependencies());
        }

        /** A origem que vale quando a pessoa nao disse nada; {@code null} com duas origens em conflito. */
        public synchronized DependencySource defaultDependencySource() {
            if (dependencySourcesConflict()) return null;
            if (existingManifest().isPresent()) return DependencySource.MANIFEST;
            return requirements != null ? DependencySource.FILE : DependencySource.MANUAL;
        }

        private Selection read(Path chosen, boolean detected) throws ImportProblem {
            Path file;
            byte[] bytes;
            boolean external;
            String packagePath;
            try {
                file = chosen.toAbsolutePath().normalize();
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > RequirementsFile.MAX_BYTES) {
                    throw new ImportProblem(ImportProblem.Code.REQUIREMENTS_UNREADABLE);
                }
                Path real = file.toRealPath();
                Path root = project.fromFolder() ? project.folder().root() : null;
                String rel = root != null && real.startsWith(root) ? root.relativize(real).toString().replace('\\', '/') : null;
                Optional<ImportedProject.ProjectFile> inProject = rel == null ? Optional.empty() : project.file(rel);
                if (inProject.isPresent() && project.folder().stamps().containsKey(rel)) {
                    // dentro do projeto e copiado no retrato: o conteudo e o do retrato (o mesmo que vai para o pacote)
                    bytes = Files.readAllBytes(project.root().resolve(rel));
                    external = false;
                    packagePath = rel;
                } else {
                    bytes = Files.readAllBytes(file);
                    external = true;
                    packagePath = freeName();
                }
            } catch (IOException | RuntimeException e) {
                throw new ImportProblem(ImportProblem.Code.REQUIREMENTS_UNREADABLE);
            }
            String text = RequirementsFile.decode(bytes).orElseThrow(() -> new ImportProblem(ImportProblem.Code.REQUIREMENTS_UNREADABLE));
            return new Selection(file, detected, external, packagePath, bytes, RequirementsFile.parse(text));
        }

        /** Um nome livre na raiz do pacote para a copia do arquivo de fora: o habitual ou o alternativo; {@code null} se os dois existem. */
        private String freeName() {
            for (String name : List.of(REQUIREMENTS_NAME, REQUIREMENTS_ALTERNATIVE)) {
                boolean taken = false;
                for (ImportedProject.ProjectFile f : project.files()) {
                    if (ZipNames.collisionKey(f.path()).equals(ZipNames.collisionKey(name))) taken = true;
                }
                if (!taken) return name;
            }
            return null;
        }

        /** O arquivo de FORA do projeto continua como foi lido? (o de dentro e conferido com a pasta) */
        private synchronized boolean requirementsUnchanged() {
            Selection s = requirements;
            if (s == null || !s.external) return true;
            try {
                return Files.isRegularFile(s.file, LinkOption.NOFOLLOW_LINKS)
                        && s.sha256.equals(java.util.HexFormat.of().formatHex(ProjectImporter.sha256().digest(Files.readAllBytes(s.file))));
            } catch (IOException | RuntimeException e) {
                return false;
            }
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

        /** De onde veio {@link #python()} (por exemplo, {@code pyproject.toml (requires-python)}), para a tela dizer. */
        public Optional<String> pythonSource() {
            return project.declarations().python().isPresent() ? Optional.ofNullable(project.declarations().pythonSource()) : Optional.empty();
        }

        /** Um requisito de Python declarado no projeto que o manifesto nao consegue representar (a pessoa revisa). */
        public Optional<String> pythonUnsupported() {
            return project.declarations().pythonUnsupported();
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
    private final DownloadedPackages downloads;

    /** {@code dataRoot}: a pasta de dados do Client (nunca a do Nekkora Agent administrativo). */
    public Workshop(Path dataRoot) {
        this(new LocalPackages(LocalPackages.defaultRoot(dataRoot), AtomicWriter.DEFAULT, Clock.systemUTC()),
                new ProjectImporter(ProjectImporter.defaultRoot(dataRoot), ZipLimits.DEFAULT),
                new DownloadedPackages(DownloadedPackages.defaultRoot(dataRoot), AtomicWriter.DEFAULT, Clock.systemUTC()));
    }

    /** Os pacotes baixados ficam em {@code baixados}, ao lado da pasta dos pacotes guardados. */
    public Workshop(LocalPackages packages, ProjectImporter importer) {
        this(packages, importer, new DownloadedPackages(packages.root().resolveSibling("baixados"), AtomicWriter.DEFAULT, Clock.systemUTC()));
    }

    public Workshop(LocalPackages packages, ProjectImporter importer, DownloadedPackages downloads) {
        this.packages = packages;
        this.importer = importer;
        this.downloads = downloads;
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
            LocalPackages.Added a = packages.add(new PackageContext(organization, workspace), zip,
                    LocalPackages.Provenance.local(LocalPackages.Origin.LOCAL_ZIP, zip.toString()), SafeZip.Cancel.NEVER);
            return new Imported(item(a.item()), a.alreadyPresent());
        } catch (ImportProblem e) {
            throw refused(e);
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
            throw refused(e);
        }
    }

    /** Abre um ZIP de codigo para preparar um pacote novo. Fechar o {@link Draft} apaga a copia de trabalho. */
    public Draft open(String zipPath) throws Refused {
        try {
            return new Draft(importer.open(zip(zipPath), SafeZip.Cancel.NEVER));
        } catch (ImportProblem e) {
            throw refused(e);
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
            throw refused(e);
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
            throw refused(e);
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
     * {@code python} e a excecao: nulo usa o que a pasta declara; um texto e o que a pessoa deixou no campo (ver
     * {@link #readPython}) - em branco, o pacote sai sem restricao de Python declarada.
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
            return summarize(draft, preview(draft, name, version, entry, python, dependencies, dependenciesConfirmed, destination), null);
        } catch (ImportProblem e) {
            throw refused(e);
        }
    }

    /**
     * O endereco {@code file:} da PASTA em que o arquivo esta, para a tela pedir ao sistema que a mostre. Vazio se o
     * caminho nao serve.
     */
    public Optional<String> folderUri(String filePath) {
        try {
            Path parent = Path.of(filePath).toAbsolutePath().getParent();
            return parent != null && Files.isDirectory(parent) ? Optional.of(parent.toUri().toString()) : Optional.empty();
        } catch (InvalidPathException | SecurityException e) {
            return Optional.empty();
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
        return generate(draft, name, version, entry, python, dependencies, dependenciesConfirmed, null, destinationZipPath, overwrite, cancel);
    }

    /**
     * {@link #generate} com a ORIGEM das dependencias dita pela pessoa ({@link DependencyChoice}): o manifesto da pasta, o
     * arquivo de dependencias selecionado ou a lista digitada. O arquivo selecionado e conferido de novo antes e depois de
     * gravar: se ele mudou desde que foi lido, nada e gerado.
     */
    public Generated generate(Draft draft, String name, String version, String entry, String python, DependencyChoice dependencies,
                              String destinationZipPath, boolean overwrite, SafeZip.Cancel cancel) throws Refused {
        return generate(draft, name, version, entry, python, null, true, dependencies == null ? new DependencyChoice(null, null, false) : dependencies,
                destinationZipPath, overwrite, cancel);
    }

    private Generated generate(Draft draft, String name, String version, String entry, String python, List<String> dependencies,
                               boolean dependenciesConfirmed, DependencyChoice choice, String destinationZipPath, boolean overwrite, SafeZip.Cancel cancel)
            throws Refused {
        Path written = null;
        try {
            Path destination = destination(destinationZipPath);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
                throw new ImportProblem(ImportProblem.Code.DESTINATION_EXISTS, ZipNames.display(String.valueOf(destination.getFileName())));
            }
            Packager.Preview pv = preview(draft, name, version, entry, python, dependencies, dependenciesConfirmed, choice, destination);
            boolean fromFile = choice != null && choice.source() == DependencySource.FILE;
            if (fromFile && !draft.requirementsUnchanged()) throw new ImportProblem(ImportProblem.Code.REQUIREMENTS_CHANGED);
            boolean existed = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            Packager.Built b = Packager.writeTo(draft.project, pv, destination, overwrite, cancel == null ? SafeZip.Cancel.NEVER : cancel);
            if (fromFile && !draft.requirementsUnchanged()) {
                // mudou DURANTE a geracao: o pacote recem-gravado nao e anunciado como pronto (um arquivo que ja existia e foi
                // substituido com o consentimento da pessoa nao tem como voltar; os dois casos sao ditos na mesma frase)
                if (!existed) written = b.zip();
                throw new ImportProblem(ImportProblem.Code.REQUIREMENTS_CHANGED);
            }
            return new Generated(b.zip().toString(), b.name(), b.version(), b.entry(), b.fingerprint(), b.sha256(), b.files(), b.size());
        } catch (ImportProblem e) {
            if (written != null) {
                try {
                    Files.deleteIfExists(written);
                } catch (IOException ignored) {
                    // a frase ja diz que o pacote nao vale
                }
            }
            if (e.code() == ImportProblem.Code.DESTINATION_EXISTS) throw new DestinationExists(e.message());
            throw refused(e);
        }
    }

    /** {@link #summary(Draft, String, String, String, String, List, boolean, String)} com a origem das dependencias dita pela pessoa. */
    public PackageSummary summary(Draft draft, String name, String version, String entry, String python, DependencyChoice dependencies) throws Refused {
        try {
            DependencyChoice choice = dependencies == null ? new DependencyChoice(null, null, false) : dependencies;
            Packager.Preview pv = preview(draft, name, version, entry, python, null, true, choice, null);
            return summarize(draft, pv, choice.source() == null ? draft.defaultDependencySource() : choice.source());
        } catch (ImportProblem e) {
            throw refused(e);
        }
    }

    private Packager.Preview preview(Draft draft, String name, String version, String entry, String python, List<String> dependencies,
                                     boolean dependenciesConfirmed, Path destination) throws ImportProblem, Refused {
        return preview(draft, name, version, entry, python, dependencies, dependenciesConfirmed, null, destination);
    }

    private Packager.Preview preview(Draft draft, String name, String version, String entry, String python, List<String> dependencies,
                                     boolean dependenciesConfirmed, DependencyChoice choice, Path destination) throws ImportProblem, Refused {
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
        // nulo: o que a pasta ja declara (manifesto ou metadados). Texto (mesmo em branco): o que a pessoa deixou no campo -
        // em branco e "sem restricao declarada"; texto que nao e um requisito e recusado, nunca trocado por um padrao
        String py;
        if (python == null) {
            py = existing != null ? existing.pythonRequirement() : p.declarations().python().orElse(null);
        } else {
            PythonRequirement.Typed typed = PythonRequirement.interpret(python);
            if (!typed.valid()) throw new ImportProblem(ImportProblem.Code.PYTHON_INVALID, ZipNames.display(python.strip()));
            py = typed.text();
        }
        List<String> deps = dependencies != null ? dependencies : existing != null ? existing.dependencies() : p.declarations().exact();
        java.util.Map<String, byte[]> extra = new java.util.HashMap<>();
        if (choice != null) {
            // a pessoa disse de onde vem a lista: UMA origem vale. Duas origens que divergem nunca sao misturadas nem
            // escolhidas sozinhas, e um arquivo com linhas nao representaveis nunca vira "lista vazia"
            DependencySource source = choice.source();
            synchronized (draft) {
                if (source == null) {
                    if (draft.dependencySourcesConflict()) throw new ImportProblem(ImportProblem.Code.DEPENDENCY_SOURCES_CONFLICT);
                    source = draft.defaultDependencySource();
                }
                switch (source) {
                    case MANIFEST -> {
                        if (existing == null) throw new ImportProblem(ImportProblem.Code.DEPENDENCY_SOURCE_MISSING);
                        deps = existing.dependencies();
                    }
                    case FILE -> {
                        Selection s = draft.requirements;
                        if (s == null) throw new ImportProblem(ImportProblem.Code.DEPENDENCY_SOURCE_MISSING);
                        if (!s.parsed.resolved()) {
                            RequirementsFile.Line first = s.parsed.problems().get(0);
                            throw new ImportProblem(ImportProblem.Code.REQUIREMENTS_UNRESOLVED, "linha " + first.number() + ": " + first.text());
                        }
                        deps = s.parsed.dependencies();
                        if (s.external && choice.copyExternalFile()) {
                            if (s.packagePath == null) throw new ImportProblem(ImportProblem.Code.REQUIREMENTS_COLLISION);
                            extra.put(s.packagePath, s.bytes);
                        }
                    }
                    default -> deps = choice.manual();
                }
            }
        }
        Set<String> data = new HashSet<>();
        if (existing != null) {
            // um arquivo de dados que o manifesto da pasta ja lista continua no pacote: nada do que ele declara some
            for (ImportedProject.ProjectFile f : p.files()) {
                if (f.rule() == InclusionRules.Rule.DATA && existing.files().containsKey(f.path())) data.add(f.path());
            }
        }
        return Packager.preview(p, new PackagePlan(n, v, en, py, deps, dependenciesConfirmed, data, selfInclusion(p, destination), existing != null, extra));
    }

    /** As duas listas declaram as mesmas dependencias (nome normalizado, extras e versao), em qualquer ordem? */
    static boolean sameDependencies(List<String> a, List<String> b) {
        return normalizedSet(a).equals(normalizedSet(b));
    }

    private static Set<String> normalizedSet(List<String> dependencies) {
        Set<String> out = new HashSet<>();
        for (String d : dependencies) {
            String[] nv = d.split("==", 2);
            int at = nv[0].indexOf('[');
            String extras = "";
            if (at >= 0) {
                List<String> e = new ArrayList<>(List.of(nv[0].substring(at + 1, nv[0].length() - 1).toLowerCase(Locale.ROOT).split(",")));
                e.sort(null);
                extras = String.join(",", e);
            }
            out.add(ExecutionPackage.normalized(at >= 0 ? nv[0].substring(0, at) : nv[0]) + "[" + extras + "]==" + (nv.length > 1 ? nv[1] : ""));
        }
        return out;
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

    private static PackageSummary summarize(Draft draft, Packager.Preview pv, DependencySource source) {
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
                pv.warned(), pv.fingerprint(), pv.bytes(), reused, source, plan.extraFiles().keySet().stream().sorted().findFirst().orElse(null));
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

    // ------------------------------------------------------------------ pacote nesta maquina (T02)

    /** O que a pessoa escolheu para "pacote nesta maquina". */
    public enum LocalKind {
        /** Um .zip que e um pacote Python externo valido. */
        PACKAGE_ZIP,
        /** Uma pasta com um {@code nekkora-external.json} valido, cujos arquivos listados conferem. */
        PACKAGE_FOLDER,
        /** Uma pasta com codigo Python e sem manifesto: a tela oferece "Gerar pacote novo"; nada e associado. */
        CODE_FOLDER,
        /** Nada que possa ser usado; {@link LocalCandidate#message()} explica. */
        UNSUPPORTED
    }

    /**
     * O resultado de olhar a escolha, sem guardar nada.
     *
     * @param pathLabel o caminho escolhido, como texto para a tela
     * @param declaredName nome declarado no pacote (so para pacote)
     * @param packageVersion versao declarada no pacote (so para pacote)
     * @param fingerprint impressao digital do conteudo (so para pacote)
     * @param message a frase para a pessoa
     */
    public record LocalCandidate(LocalKind kind, String pathLabel, String declaredName, String packageVersion, String fingerprint, String message) {

        public boolean isPackage() {
            return kind == LocalKind.PACKAGE_ZIP || kind == LocalKind.PACKAGE_FOLDER;
        }

        /** A origem para a tela: "Arquivo .zip" ou "Pasta de pacote"; vazio se nao e pacote. */
        public String originLabel() {
            return kind == LocalKind.PACKAGE_ZIP ? LocalPackages.Origin.LOCAL_ZIP.label()
                    : kind == LocalKind.PACKAGE_FOLDER ? LocalPackages.Origin.LOCAL_FOLDER.label() : "";
        }
    }

    /** O que fazer quando o pacote escolhido NAO e o conteudo da versao publicada. */
    public enum Choice {
        /** Nao guardar: devolver {@link UseLocalOutcome#DIFFERS} para a tela explicar e perguntar. */
        ASK,
        /** Guardar como item so local, separado, SEM associar a automacao. */
        SEPARATE_LOCAL
    }

    public enum UseLocalOutcome {
        /** Mesma impressao digital da versao publicada: guardado e associado a automacao. */
        MATCHES_PUBLISHED,
        /** Conteudo diferente da versao publicada (ou nao ha versao publicada): nada foi guardado. */
        DIFFERS,
        /** Guardado como item so local, sem associacao: nunca e apresentado como a versao publicada. */
        STORED_SEPARATE
    }

    /**
     * @param item o item guardado ({@code null} em {@link UseLocalOutcome#DIFFERS})
     * @param origin "Arquivo .zip" ou "Pasta de pacote"
     * @param pathLabel o caminho escolhido
     * @param fingerprint impressao digital do que foi escolhido
     */
    public record UseLocalResult(UseLocalOutcome outcome, LocalItem item, String origin, String pathLabel, String fingerprint, String declaredName,
                                 String packageVersion) {
    }

    /** Um pacote escolhido, ja lido e conferido numa pasta privada (apagada em {@link #close()}). */
    private final class Picked implements AutoCloseable {
        private LocalKind kind = LocalKind.UNSUPPORTED;
        private String label = "";
        private String message;
        private ExecutionPackage pkg;
        private Path work;
        private ImportedProject project;

        private Picked refuse(ImportProblem.Code code) {
            message = new ImportProblem(code).message();
            return this;
        }

        private LocalCandidate candidate() {
            return new LocalCandidate(kind, label, pkg == null ? null : pkg.name(), pkg == null ? null : pkg.packageVersion(),
                    pkg == null ? null : pkg.fingerprint(), message);
        }

        @Override
        public void close() {
            Io.deleteTree(work);
            importer.close(project);
        }
    }

    /**
     * Le e confere a escolha. Um .zip passa pelo leitor seguro e pelo validador; uma pasta passa pelo mesmo retrato de
     * {@link #openFolder} (sem seguir ligacao nem juncao) e o manifesto e os arquivos listados sao conferidos pelo mesmo
     * validador - as mesmas regras nos dois casos.
     */
    private Picked pick(String path) {
        Picked k = new Picked();
        Path p;
        try {
            if (path == null || path.isBlank()) return k.refuse(ImportProblem.Code.LOCAL_PICK_INVALID);
            p = Path.of(path);
        } catch (InvalidPathException e) {
            return k.refuse(ImportProblem.Code.LOCAL_PICK_INVALID);
        }
        k.label = p.toString();
        if (Files.isDirectory(p)) {
            try {
                k.project = importer.openFolder(p, SafeZip.Cancel.NEVER);
            } catch (ImportProblem e) {
                k.message = e.message();
                return k;
            }
            k.label = k.project.source().toString();
            switch (k.project.kind()) {
                case EXTERNAL_PACKAGE -> {
                    k.kind = LocalKind.PACKAGE_FOLDER;
                    k.pkg = k.project.existing();
                    k.message = "Pacote Python externo válido (pasta de pacote).";
                }
                case CODE -> {
                    if (k.project.blockers().isEmpty()) {
                        k.kind = LocalKind.CODE_FOLDER;
                        k.refuse(ImportProblem.Code.LOCAL_CODE_FOLDER);
                    } else {
                        k.refuse(ImportProblem.Code.LOCAL_FOLDER_NOT_PACKAGE);
                    }
                }
                default -> k.message = k.project.blockers().isEmpty() ? new ImportProblem(ImportProblem.Code.LOCAL_FOLDER_NOT_PACKAGE).message()
                        : k.project.blockers().get(0).message();
            }
            return k;
        }
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return k.refuse(ImportProblem.Code.LOCAL_PICK_NOT_FOUND);
        if (!path.toLowerCase(Locale.ROOT).endsWith(".zip")) return k.refuse(ImportProblem.Code.LOCAL_PICK_INVALID);
        try {
            k.work = importer.newWork();
            Path into = Files.createDirectories(k.work.resolve("x"));
            SafeZip.Content c = SafeZip.extract(p, into, ZipLimits.DEFAULT, SafeZip.Cancel.NEVER);
            String prefix = ProjectImporter.rootPrefix(c);
            k.pkg = ExecutionPackage.inspect(prefix.isEmpty() ? into : into.resolve(prefix));
            k.kind = LocalKind.PACKAGE_ZIP;
            k.message = "Pacote Python externo válido (arquivo .zip).";
        } catch (ImportProblem e) {
            k.message = e.message();
        } catch (ExecRefused e) {
            // um ZIP so de codigo nao e pacote: a frase diz o que fazer; os outros motivos sao os do validador
            if (e.code() == ErrorCode.EXEC_PACKAGE_UNKNOWN) k.refuse(ImportProblem.Code.LOCAL_ZIP_NOT_PACKAGE);
            else k.message = e.code().message();
        } catch (IOException e) {
            k.message = Io.work(e, k.work).message();
        }
        return k;
    }

    /**
     * Diz o que e o arquivo ou a pasta que a pessoa escolheu para uma automacao, sem guardar nem associar nada. A
     * impressao digital e do CONTEUDO: o mesmo pacote recompactado tem a mesma impressao.
     */
    public LocalCandidate inspectLocal(String path) {
        try (Picked k = pick(path)) {
            return k.candidate();
        }
    }

    /**
     * Usa o pacote escolhido para a automacao. A comparacao com a versao publicada e SO pela impressao digital (conteudo
     * canonico), nunca pelo nome nem pelos bytes do ZIP:
     * <ul>
     *   <li>igual a {@code publishedFingerprint}: guarda no contexto e associa a automacao
     *       ({@link UseLocalOutcome#MATCHES_PUBLISHED});</li>
     *   <li>diferente, ou sem versao publicada: com {@link Choice#ASK} nada e guardado ({@link UseLocalOutcome#DIFFERS});
     *       com {@link Choice#SEPARATE_LOCAL} o pacote e guardado como item so local, SEM associacao
     *       ({@link UseLocalOutcome#STORED_SEPARATE});</li>
     *   <li>pasta de codigo ou escolha invalida: {@link Refused} com a explicacao; nada e guardado.</li>
     * </ul>
     * O que e guardado e exatamente o que foi lido e conferido aqui (uma unica leitura). Nada e executado nem enviado.
     */
    public UseLocalResult useLocal(UUID organization, UUID workspace, UUID automationId, String path, String publishedFingerprint, Choice choice)
            throws Refused {
        PackageContext context = new PackageContext(organization, workspace);
        try (Picked k = pick(path)) {
            LocalCandidate c = k.candidate();
            if (!c.isPackage()) throw new Refused(c.message());
            boolean matches = sameFingerprint(c.fingerprint(), publishedFingerprint);
            if (matches) java.util.Objects.requireNonNull(automationId, "automationId");
            if (!matches && choice != Choice.SEPARATE_LOCAL) {
                return new UseLocalResult(UseLocalOutcome.DIFFERS, null, c.originLabel(), c.pathLabel(), c.fingerprint(), c.declaredName(), c.packageVersion());
            }
            LocalPackages.Origin origin = k.kind == LocalKind.PACKAGE_ZIP ? LocalPackages.Origin.LOCAL_ZIP : LocalPackages.Origin.LOCAL_FOLDER;
            LocalPackages.Item item = packages.addInspected(context, k.pkg, LocalPackages.Provenance.local(origin, c.pathLabel())).item();
            if (matches && !automationId.equals(item.linkedAutomationId())) item = packages.link(context, item.fingerprint(), automationId);
            return new UseLocalResult(matches ? UseLocalOutcome.MATCHES_PUBLISHED : UseLocalOutcome.STORED_SEPARATE, item(item), c.originLabel(), c.pathLabel(),
                    c.fingerprint(), c.declaredName(), c.packageVersion());
        } catch (ImportProblem e) {
            throw refused(e);
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        }
    }

    private static boolean sameFingerprint(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.strip().toLowerCase(Locale.ROOT);
        String y = b.strip().toLowerCase(Locale.ROOT);
        return (x.startsWith("sha256:") ? x.substring(7) : x).equals(y.startsWith("sha256:") ? y.substring(7) : y);
    }

    // ------------------------------------------------------------------ pacote baixado da Platform (T02)

    /**
     * Reserva o arquivo PARCIAL de um download ({@code <dados>/baixados/.parcial/...}) e devolve o caminho. Esta camada
     * nao baixa nada: quem baixa escreve em {@link #downloadStream} e depois chama {@link #installDownloaded} ou
     * {@link #discardDownload}. Um parcial nunca fica num lugar que o aplicativo trata como pronto.
     */
    public String downloadTarget(String platformOrigin, UUID organization, UUID automationId, UUID versionId) throws Refused {
        try {
            return downloads.target(platformOrigin, organization, automationId, versionId).toString();
        } catch (ImportProblem e) {
            throw refused(e);
        }
    }

    /** Abre o arquivo parcial para escrita (do inicio). So aceita um caminho entregue por {@link #downloadTarget}. */
    public OutputStream downloadStream(String tempFilePath) throws Refused {
        try {
            return downloads.open(partial(tempFilePath));
        } catch (ImportProblem e) {
            throw refused(e);
        }
    }

    /**
     * Confere o arquivo baixado e instala o pacote neste contexto, associado a automacao, com origem "Baixado da Platform"
     * e a versao. Ordem: tamanho e SHA-256 contra o que a Platform informou (antes de abrir como ZIP), leitura segura e
     * validador real, impressao digital igual a informada. Qualquer divergencia: {@link Refused}, o parcial e apagado e
     * nada e instalado. A mesma versao de novo nao duplica; outra versao da mesma automacao convive, sem substituir a
     * anterior. Nada e executado e nenhuma dependencia e instalada.
     */
    public LocalItem installDownloaded(String platformOrigin, UUID organization, UUID workspace, UUID automationId, UUID versionId, int versionNumber,
                                       String tempFilePath, String expectedSha256, long expectedSizeBytes, String expectedFingerprint, SafeZip.Cancel cancel)
            throws Refused {
        try {
            return item(downloads.install(packages, platformOrigin, new PackageContext(organization, workspace), automationId, versionId, versionNumber,
                    partial(tempFilePath), expectedSha256, expectedSizeBytes, expectedFingerprint, cancel == null ? SafeZip.Cancel.NEVER : cancel));
        } catch (ImportProblem e) {
            throw refused(e);
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        }
    }

    /** Apaga um arquivo parcial (cancelamento ou falha). Qualquer caminho fora da pasta de parciais e recusado. */
    public void discardDownload(String tempFilePath) throws Refused {
        try {
            downloads.discard(partial(tempFilePath));
        } catch (ImportProblem e) {
            throw refused(e);
        }
    }

    private static Path partial(String tempFilePath) throws ImportProblem {
        try {
            if (tempFilePath == null || tempFilePath.isBlank()) throw new ImportProblem(ImportProblem.Code.DOWNLOAD_NOT_MANAGED);
            return Path.of(tempFilePath);
        } catch (InvalidPathException e) {
            throw new ImportProblem(ImportProblem.Code.DOWNLOAD_NOT_MANAGED);
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
        LocalPackages.Provenance o = i.provenance();
        return new LocalItem(i.fingerprint(), i.displayName(), i.declaredName(), i.packageVersion(), i.linkedAutomationId(), o == null ? null : o.origin(),
                o == null ? null : o.sourceLabel(), o == null ? null : o.automationId(), o == null ? null : o.automationVersionId(),
                o == null ? null : o.versionNumber());
    }
}
