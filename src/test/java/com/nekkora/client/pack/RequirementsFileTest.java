package com.nekkora.client.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.pack.RequirementsFile.Kind;
import com.nekkora.client.pack.Workshop.DependencyChoice;
import com.nekkora.client.pack.Workshop.DependencySource;

/**
 * AGENT-CLIENT-PREPARACAO-002 T04: o arquivo de dependencias ({@code requirements.txt}) e lido ESTATICAMENTE - so texto. O
 * manifesto so representa {@code nome==versao}; tudo o mais e dito por linha e nunca omitido em silencio. Uma so origem das
 * dependencias vale; o arquivo original nao e alterado e o caminho dele nao vai para o manifesto.
 */
class RequirementsFileTest {

    @TempDir
    Path tmp;

    private Workshop workshop;

    @BeforeEach
    void open() {
        workshop = new Workshop(tmp.resolve("dados"));
    }

    private static Kind kind(String line) {
        RequirementsFile.Parsed p = RequirementsFile.parse(line);
        assertEquals(1, p.lines().size(), line);
        return p.lines().get(0).kind();
    }

    // ------------------------------------------------------------------ leitura

    @Test
    void versoesExatasComentariosELinhasEmBranco() {
        RequirementsFile.Parsed p = RequirementsFile.parse(String.join("\n", "# dependencias do robo", "", "requests==2.31.0", "   ",
                "openpyxl[lxml] == 3.1.5   # planilhas", "Nekkora_Fixture.A==1.2.3", "pkg==1.0+local.1", "# fim", ""));
        assertEquals(List.of("requests==2.31.0", "openpyxl[lxml]==3.1.5", "Nekkora_Fixture.A==1.2.3", "pkg==1.0+local.1"), p.dependencies());
        assertTrue(p.resolved());
        assertFalse(p.empty());
        assertEquals(List.of(3, 5, 6, 7), p.lines().stream().map(RequirementsFile.Line::number).toList(), "o numero e o da linha no arquivo");
        for (String d : p.dependencies()) {
            assertTrue(ExecutionPackage.validDependency(d), "o que sai da leitura e o que o manifesto aceita: " + d);
        }
        // arquivo vazio ou so com comentarios: nada declarado (e NAO um erro)
        for (String vazio : new String[] {"", "\n\n", "# nada\n   # ainda nada\n"}) {
            RequirementsFile.Parsed v = RequirementsFile.parse(vazio);
            assertTrue(v.empty() && v.resolved() && v.dependencies().isEmpty());
        }
        // BOM e CRLF
        assertEquals(List.of("a==1.0"), RequirementsFile.parse(RequirementsFile.decode("\uFEFFa==1.0\r\n".getBytes(StandardCharsets.UTF_8)).orElseThrow()).dependencies());
    }

    @Test
    void cadaLinhaQueOPacoteNaoRepresentaEDitaENuncaDescartada() {
        assertEquals(Kind.RANGE, kind("requests>=2.0"));
        assertEquals(Kind.RANGE, kind("requests>=2.0,<3"));
        assertEquals(Kind.RANGE, kind("requests~=2.31"));
        assertEquals(Kind.RANGE, kind("requests==2.31.*"));
        assertEquals(Kind.RANGE, kind("requests!=2.30"));
        assertEquals(Kind.RANGE, kind("requests===2.31.0"));
        assertEquals(Kind.NO_VERSION, kind("requests"));
        assertEquals(Kind.NO_VERSION, kind("openpyxl[lxml]"));
        assertEquals(Kind.MARKER, kind("pywin32==306; sys_platform == \"win32\""));
        assertEquals(Kind.REMOTE, kind("https://exemplo.invalid/pacote-1.0.whl"));
        assertEquals(Kind.REMOTE, kind("git+https://exemplo.invalid/repo.git@v1#egg=pacote"));
        assertEquals(Kind.REMOTE, kind("pacote @ https://exemplo.invalid/pacote-1.0.tar.gz"));
        assertEquals(Kind.LOCAL_PATH, kind("./vendor/pacote"));
        assertEquals(Kind.LOCAL_PATH, kind("C:\\libs\\pacote-1.0.whl"));
        assertEquals(Kind.LOCAL_PATH, kind("pacote-1.0-py3-none-any.whl"));
        assertEquals(Kind.LOCAL_PATH, kind("-e ./meu-pacote"));
        assertEquals(Kind.INCLUDE, kind("-r base.txt"));
        assertEquals(Kind.INCLUDE, kind("--requirement base.txt"));
        assertEquals(Kind.INCLUDE, kind("-c constraints.txt"));
        assertEquals(Kind.OPTION, kind("--index-url https://exemplo.invalid/simple"));
        assertEquals(Kind.OPTION, kind("--no-binary :all:"));
        assertEquals(Kind.HASH, kind("requests==2.31.0 --hash=sha256:" + "a".repeat(64)));
        assertEquals(Kind.HASH, kind("requests==2.31.0 \\\n    --hash=sha256:" + "a".repeat(64)), "linha continuada com \\");
        assertEquals(Kind.UNKNOWN, kind("?? isto nao e nada"));
        for (Kind k : Kind.values()) {
            assertEquals(k != Kind.EXACT && k != Kind.DUPLICATE, k.problem(), k.toString());
            assertFalse(k.label().isBlank());
        }
        RequirementsFile.Parsed p = RequirementsFile.parse("a==1.0\nb>=2\n-r outro.txt\nc\n");
        assertEquals(List.of("a==1.0"), p.dependencies(), "so o que e exato vira dependencia; nenhuma versao e inventada");
        assertFalse(p.resolved());
        assertEquals(List.of(2, 3, 4), p.problems().stream().map(RequirementsFile.Line::number).toList());
    }

    @Test
    void repetidaIgualEUsadaUmaVezERepetidaDiferenteEConflitoNasDuasLinhas() {
        RequirementsFile.Parsed igual = RequirementsFile.parse("Requests==2.31.0\nrequests==2.31.0\nREQUESTS == 2.31.0\n");
        assertEquals(List.of("Requests==2.31.0"), igual.dependencies());
        assertTrue(igual.resolved(), "a mesma declaracao outra vez nao e um problema");
        assertEquals(List.of(Kind.EXACT, Kind.DUPLICATE, Kind.DUPLICATE), igual.lines().stream().map(RequirementsFile.Line::kind).toList());

        RequirementsFile.Parsed outra = RequirementsFile.parse("requests==2.31.0\nopenpyxl==3.1.5\nrequests==2.32.3\n");
        assertEquals(List.of("openpyxl==3.1.5"), outra.dependencies(), "nenhuma das versoes em conflito e escolhida");
        assertFalse(outra.resolved());
        assertEquals(List.of(Kind.CONFLICT, Kind.EXACT, Kind.CONFLICT), outra.lines().stream().map(RequirementsFile.Line::kind).toList());
        assertEquals(List.of(Kind.CONFLICT, Kind.CONFLICT), RequirementsFile.parse("openpyxl==3.1.5\nopenpyxl[lxml]==3.1.5\n").lines().stream()
                .map(RequirementsFile.Line::kind).toList(), "extras diferentes tambem sao conflito");
    }

    @Test
    void soTextoUtf8DentroDoLimite() {
        assertTrue(RequirementsFile.decode(new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01}).isEmpty(), "binario");
        assertTrue(RequirementsFile.decode("a==1\u0000".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(RequirementsFile.decode(new byte[RequirementsFile.MAX_BYTES + 1]).isEmpty(), "grande demais");
        assertTrue(RequirementsFile.decode(null).isEmpty());
    }

    // ------------------------------------------------------------------ na geracao do pacote

    private Path projeto(String nome, String requirements) throws Exception {
        Path p = Files.createDirectories(tmp.resolve(nome));
        Files.writeString(p.resolve("main.py"), "from tarefas import relatorio\nprint(relatorio.gerar())\n");
        Files.createDirectories(p.resolve("tarefas"));
        Files.writeString(p.resolve("tarefas").resolve("relatorio.py"), "def gerar():\n    return 1\n");
        if (requirements != null) {
            Files.writeString(p.resolve("requirements.txt"), requirements);
        }
        return p;
    }

    private static String manifesto(Path zip) throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return new String(z.getInputStream(z.getEntry("nekkora-external.json")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> nomes(Path zip) throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return z.stream().map(e -> e.getName()).sorted().toList();
        }
    }

    private static byte[] dentro(Path zip, String nome) throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return z.getInputStream(z.getEntry(nome)).readAllBytes();
        }
    }

    private static String sha(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    private List<String> arquivos(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private String recusa(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(Workshop.Refused.class, e).getMessage();
    }

    private static final DependencyChoice PADRAO = new DependencyChoice(null, null, false);

    @Test
    void requirementsDaRaizEReconhecidoEViraAListaDoManifesto() throws Exception {
        Path pasta = projeto("com-arquivo", "# robo\nrequests==2.31.0\nopenpyxl[lxml]==3.1.5\nrequests==2.31.0\n");
        String antes = sha(pasta.resolve("requirements.txt"));
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            Workshop.Requirements r = d.requirements().orElseThrow();
            assertTrue(r.detected(), "reconhecido sozinho na raiz, e dito");
            assertFalse(r.external());
            assertEquals("requirements.txt", r.packagePath());
            assertEquals(List.of("requests==2.31.0", "openpyxl[lxml]==3.1.5"), r.dependencies());
            assertTrue(r.resolved());
            assertEquals(DependencySource.FILE, d.defaultDependencySource());
            assertFalse(d.dependencySourcesConflict());
            Workshop.PackageSummary s = workshop.summary(d, "robo", "1.0.0", "main.py", "", PADRAO);
            assertEquals(DependencySource.FILE, s.dependencySource());
            assertEquals(List.of("requests==2.31.0", "openpyxl[lxml]==3.1.5"), s.dependencies());
            assertNull(s.requirementsCopiedAs(), "ja e um arquivo do projeto: nada e copiado de fora");
            Path zip = saida.resolve("robo.zip");
            workshop.generate(d, "robo", "1.0.0", "main.py", "", PADRAO, zip.toString(), false, SafeZip.Cancel.NEVER);
            String m = manifesto(zip);
            assertTrue(m.contains("\"requests==2.31.0\"") && m.contains("\"openpyxl[lxml]==3.1.5\""), m);
            assertFalse(m.contains(pasta.toString()) || m.contains(tmp.getFileName().toString()) || m.contains(":\\\\"), "nenhum caminho absoluto no manifesto\n" + m);
            assertEquals(List.of("main.py", "nekkora-external.json", "requirements.txt", "tarefas/relatorio.py"), nomes(zip));
            // o conteudo do manifesto e o do arquivo dentro do pacote vem da mesma copia
            assertEquals(List.of("requests==2.31.0", "openpyxl[lxml]==3.1.5"),
                    RequirementsFile.parse(new String(dentro(zip, "requirements.txt"), StandardCharsets.UTF_8)).dependencies());
            workshop.importZip(UUID.randomUUID(), UUID.randomUUID(), zip.toString()); // o validador real aceita
        }
        assertEquals(antes, sha(pasta.resolve("requirements.txt")), "o arquivo original nao e alterado");
    }

    @Test
    void arquivoComLinhaNaoRepresentavelImpedeAGeracaoPorEleENuncaViraListaVazia() throws Exception {
        Path pasta = projeto("com-faixa", "requests>=2.0\nopenpyxl==3.1.5\n-r base.txt\npandas\n");
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            Workshop.Requirements r = d.requirements().orElseThrow();
            assertFalse(r.resolved());
            assertEquals(List.of(1, 3, 4), r.problems().stream().map(Workshop.RequirementLine::number).toList());
            assertEquals(List.of("openpyxl==3.1.5"), r.dependencies());
            String m = recusa(() -> workshop.summary(d, "robo", "1.0.0", "main.py", "", PADRAO));
            assertTrue(m.contains("AGT-PAC-047") && m.contains("linha 1: requests>=2.0"), m);
            assertTrue(recusa(() -> workshop.generate(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, false),
                    saida.resolve("x.zip").toString(), false, SafeZip.Cancel.NEVER)).contains("AGT-PAC-047"));
            assertEquals(List.of(), arquivos(saida), "nada gerado");
            // a pessoa assume a lista: edicao manual, explicitamente
            Path zip = saida.resolve("manual.zip");
            workshop.generate(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.MANUAL, List.of("requests==2.31.0", "openpyxl==3.1.5"), false),
                    zip.toString(), false, SafeZip.Cancel.NEVER);
            assertTrue(manifesto(zip).contains("\"requests==2.31.0\""));
            // sem arquivo como origem (selecao removida) e sem lista: nenhuma dependencia declarada - permitido
            d.clearRequirements();
            assertTrue(d.requirements().isEmpty());
            assertEquals(DependencySource.MANUAL, d.defaultDependencySource());
            Path vazio = saida.resolve("vazio.zip");
            workshop.generate(d, "robo", "1.0.0", "main.py", "", PADRAO, vazio.toString(), false, SafeZip.Cancel.NEVER);
            assertTrue(manifesto(vazio).contains("\"dependencies\": []"), manifesto(vazio));
            assertTrue(nomes(vazio).contains("requirements.txt"), "o arquivo continua sendo um arquivo do projeto");
        }
    }

    @Test
    void projetoSemArquivoGeraSemDependenciasDeclaradas() throws Exception {
        Path pasta = projeto("sem-arquivo", null);
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            assertTrue(d.requirements().isEmpty());
            assertEquals(DependencySource.MANUAL, d.defaultDependencySource());
            Workshop.PackageSummary s = workshop.summary(d, "robo", "1.0.0", "main.py", "", PADRAO);
            assertEquals(List.of(), s.dependencies());
            assertEquals(DependencySource.MANUAL, s.dependencySource());
        }
    }

    @Test
    void arquivoDeForaSoEntraNoPacoteSeAPessoaMandarCopiarESoEle() throws Exception {
        Path pasta = projeto("sem-arquivo-proprio", null);
        Path fora = Files.createDirectories(tmp.resolve("outra pasta"));
        Files.writeString(fora.resolve("segredo.txt"), "nao e para entrar");
        Path req = fora.resolve("dependências do robô.txt");
        Files.writeString(req, "requests==2.31.0\n");
        String antes = sha(req);
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            Workshop.Requirements r = d.selectRequirements(req.toString());
            assertTrue(r.external());
            assertFalse(r.detected());
            assertEquals("requirements.txt", r.packagePath(), "o destino da copia e dito antes");
            assertEquals(DependencySource.FILE, d.defaultDependencySource());
            // sem mandar copiar: a lista vai para o manifesto, o arquivo nao entra
            Path sem = saida.resolve("sem-copia.zip");
            workshop.generate(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, false), sem.toString(), false,
                    SafeZip.Cancel.NEVER);
            assertEquals(List.of("main.py", "nekkora-external.json", "tarefas/relatorio.py"), nomes(sem));
            assertTrue(manifesto(sem).contains("\"requests==2.31.0\""));
            // mandando copiar: so esse arquivo, com o nome dito; nada mais da pasta dele
            Workshop.PackageSummary s = workshop.summary(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, true));
            assertEquals("requirements.txt", s.requirementsCopiedAs());
            Path com = saida.resolve("com-copia.zip");
            workshop.generate(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, true), com.toString(), false,
                    SafeZip.Cancel.NEVER);
            assertEquals(List.of("main.py", "nekkora-external.json", "requirements.txt", "tarefas/relatorio.py"), nomes(com));
            assertEquals("requests==2.31.0\n", new String(dentro(com, "requirements.txt"), StandardCharsets.UTF_8));
            String m = manifesto(com);
            assertFalse(m.contains("outra pasta") || m.contains("depend") && m.contains("rob\u00f4"), "o caminho de origem nao vai para o manifesto\n" + m);
            workshop.importZip(UUID.randomUUID(), UUID.randomUUID(), com.toString());
        }
        assertEquals(antes, sha(req));
        assertEquals(List.of("dependências do robô.txt", "segredo.txt"), arquivos(fora), "a pasta de origem fica como estava");
        assertEquals(List.of("main.py", "tarefas"), arquivos(pasta), "nada e criado no projeto");
    }

    @Test
    void copiaDeForaNuncaSobrepoeUmArquivoDoProjeto() throws Exception {
        Path pasta = projeto("com-o-seu", "openpyxl==3.1.5\n");
        Path req = Files.writeString(tmp.resolve("outro.txt"), "requests==2.31.0\n");
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            Workshop.Requirements r = d.selectRequirements(req.toString());
            assertEquals("requirements-selecionado.txt", r.packagePath(), "o nome habitual ja e de um arquivo do projeto");
            Path zip = saida.resolve("colisao.zip");
            workshop.generate(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, true), zip.toString(), false,
                    SafeZip.Cancel.NEVER);
            assertEquals("openpyxl==3.1.5\n", new String(dentro(zip, "requirements.txt"), StandardCharsets.UTF_8), "o do projeto continua o do projeto");
            assertEquals("requests==2.31.0\n", new String(dentro(zip, "requirements-selecionado.txt"), StandardCharsets.UTF_8));
            assertTrue(manifesto(zip).contains("\"requests==2.31.0\"") && !manifesto(zip).contains("\"openpyxl==3.1.5\""), "uma so origem vale");
        }
        // os dois nomes ocupados: nao ha onde copiar, e isso e dito
        Files.writeString(pasta.resolve("requirements-selecionado.txt"), "x==1\n");
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            assertNull(d.selectRequirements(req.toString()).packagePath());
            assertTrue(recusa(() -> workshop.summary(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, true)))
                    .contains("AGT-PAC-043"));
            workshop.summary(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, false)); // sem copiar, segue
        }
    }

    @Test
    void arquivoAlteradoDepoisDeLidoImpedeAGeracao() throws Exception {
        Path pasta = projeto("muda-fora", null);
        Path req = Files.writeString(tmp.resolve("lista.txt"), "requests==2.31.0\n");
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            d.selectRequirements(req.toString());
            Files.writeString(req, "requests==2.32.3\n");
            String m = recusa(() -> workshop.generate(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, true),
                    saida.resolve("x.zip").toString(), false, SafeZip.Cancel.NEVER));
            assertTrue(m.contains("AGT-PAC-048"), m);
            assertEquals(List.of(), arquivos(saida), "nenhum pacote, nem parcial");
            // lido de novo, vale o conteudo novo
            d.selectRequirements(req.toString());
            Path zip = saida.resolve("novo.zip");
            workshop.generate(d, "robo", "1.0.0", "main.py", "", new DependencyChoice(DependencySource.FILE, null, false), zip.toString(), false,
                    SafeZip.Cancel.NEVER);
            assertTrue(manifesto(zip).contains("\"requests==2.32.3\""));
        }
        // o arquivo do PROJETO mudando depois de aberto: a regra de sempre da pasta
        Path dentro = projeto("muda-dentro", "requests==2.31.0\n");
        try (Workshop.Draft d = workshop.openFolder(dentro.toString())) {
            Files.writeString(dentro.resolve("requirements.txt"), "requests==2.32.3\n");
            assertTrue(recusa(() -> workshop.generate(d, "robo", "1.0.0", "main.py", "", PADRAO, saida.resolve("y.zip").toString(), false, SafeZip.Cancel.NEVER))
                    .contains("AGT-PAC-072"));
        }
        assertFalse(Files.exists(saida.resolve("y.zip")));
    }

    @Test
    void manifestoEArquivoQueDivergemExigemAEscolhaDaPessoa() throws Exception {
        Path pasta = projeto("com-manifesto", "requests==2.31.0\n");
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        // primeiro pacote: gera um manifesto valido e o poe na pasta (como quem ja empacotou antes)
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            Path zip = saida.resolve("primeiro.zip");
            workshop.generate(d, "robo", "2.0.0", "main.py", ">=3.10", PADRAO, zip.toString(), false, SafeZip.Cancel.NEVER);
            Files.write(pasta.resolve("nekkora-external.json"), dentro(zip, "nekkora-external.json"));
        }
        // iguais: nao ha conflito, e o manifesto e a origem
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            assertFalse(d.dependencySourcesConflict());
            assertEquals(DependencySource.MANIFEST, d.defaultDependencySource());
            assertEquals(List.of("requests==2.31.0"), workshop.summary(d, null, null, null, null, PADRAO).dependencies());
        }
        // o arquivo mudou e o manifesto nao: duas origens, duas listas
        Files.writeString(pasta.resolve("requirements.txt"), "requests==2.32.3\nopenpyxl==3.1.5\n");
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            assertEquals(1, d.blockers().size(), "o manifesto da pasta nao confere mais com os arquivos: " + d.blockers());
        }
        // manifesto coerente com os arquivos, mas com uma lista diferente da do arquivo de dependencias
        Files.delete(pasta.resolve("nekkora-external.json"));
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            Path zip = saida.resolve("segundo.zip");
            workshop.generate(d, "robo", "2.0.0", "main.py", ">=3.10", new DependencyChoice(DependencySource.MANUAL, List.of("requests==2.31.0"), false),
                    zip.toString(), false, SafeZip.Cancel.NEVER);
            Files.write(pasta.resolve("nekkora-external.json"), dentro(zip, "nekkora-external.json"));
        }
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            assertEquals(List.of(), d.blockers());
            assertTrue(d.dependencySourcesConflict());
            assertNull(d.defaultDependencySource(), "nenhuma origem e escolhida sozinha");
            String m = recusa(() -> workshop.summary(d, null, null, null, null, PADRAO));
            assertTrue(m.contains("AGT-PAC-059"), m);
            assertEquals(List.of("requests==2.31.0"),
                    workshop.summary(d, null, null, null, null, new DependencyChoice(DependencySource.MANIFEST, null, false)).dependencies());
            assertEquals(List.of("requests==2.32.3", "openpyxl==3.1.5"),
                    workshop.summary(d, null, null, null, null, new DependencyChoice(DependencySource.FILE, null, false)).dependencies());
        }
    }

    @Test
    void arquivoQueNaoETextoOuNaoExisteERecusadoEASelecaoAnteriorFica() throws Exception {
        Path pasta = projeto("selecao", "requests==2.31.0\n");
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            Path binario = Files.write(tmp.resolve("binario.txt"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
            assertTrue(recusa(() -> d.selectRequirements(binario.toString())).contains("AGT-PAC-049"));
            assertTrue(recusa(() -> d.selectRequirements(tmp.resolve("nao-existe.txt").toString())).contains("AGT-PAC-049"));
            assertTrue(recusa(() -> d.selectRequirements(pasta.toString())).contains("AGT-PAC-049"), "uma pasta nao e um arquivo de dependencias");
            assertTrue(d.requirements().orElseThrow().detected(), "a selecao anterior continua valendo");
        }
    }
}
