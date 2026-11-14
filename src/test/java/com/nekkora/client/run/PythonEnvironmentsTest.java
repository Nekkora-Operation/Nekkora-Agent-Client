package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.client.pack.ExecutionPackage;
import com.nekkora.client.run.PythonEnvironments.Environment;
import com.nekkora.client.run.PythonEnvironments.Issue;
import com.nekkora.client.run.PythonEnvironments.Kind;
import com.nekkora.client.run.PythonEnvironments.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Descoberta limitada e validacao dos ambientes Python (AGENT-CLIENT-PYTHON-001 T01). Processo Python REAL (o de
 * {@code NEKKORA_TEST_PYTHON}; sem ele, os testes que sondam sao PULADOS, nunca dados por aprovados). As fontes da maquina
 * sao trocadas por listas do teste; os ambientes virtuais sao FIXTURES criadas pelo teste em pasta temporaria (sem pip,
 * sem rede); as "dependencias instaladas" sao so metadados de distribuicao escritos a mao. O codigo testado nao cria,
 * instala nem altera nada.
 */
class PythonEnvironmentsTest {

    @TempDir
    Path tmp;
    private Path python;
    private Path work;
    private boolean windows;

    @BeforeEach
    void setUp() {
        python = RunFixtures.python();
        work = tmp.resolve("trabalho");
        windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private void needsPython() {
        Assumptions.assumeTrue(python != null, RunFixtures.SKIP);
    }

    private static PythonSources sources(PythonSources.Found... found) {
        return cancel -> List.of(found);
    }

    private PythonEnvironments service(PythonSources s) {
        return new PythonEnvironments(s, work, Duration.ofSeconds(30), windows);
    }

    private ExecutionPackage pkg(String name, String requirement, List<String> deps) throws Exception {
        return ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pacotes").resolve(name), name, Map.of("main.py", "print('oi')\n"), "main.py", requirement, deps));
    }

    /** Fixture: um ambiente virtual criado PELO TESTE (sem pip, sem rede) em pasta temporaria. Devolve o interpretador dele. */
    private Path venv(Path dir) throws Exception {
        Files.createDirectories(dir.getParent());
        Process p = new ProcessBuilder(python.toString(), "-I", "-m", "venv", "--without-pip", dir.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, "fixture: o ambiente virtual de teste nao foi criado");
        Path exe = windows ? dir.resolve("Scripts").resolve("python.exe") : dir.resolve("bin").resolve("python");
        Assumptions.assumeTrue(Files.isRegularFile(exe, java.nio.file.LinkOption.NOFOLLOW_LINKS), "fixture: este sistema cria o interpretador do ambiente como link");
        return exe;
    }

    /** Fixture: "instala" uma distribuicao escrevendo SO os metadados dela no ambiente de teste. */
    private void distribution(Path venvDir, String name, String version) throws Exception {
        Path site;
        if (windows) {
            site = venvDir.resolve("Lib").resolve("site-packages");
        } else {
            try (Stream<Path> s = Files.list(venvDir.resolve("lib"))) {
                site = s.filter(p -> p.getFileName().toString().startsWith("python")).findFirst().orElseThrow().resolve("site-packages");
            }
        }
        Path info = site.resolve(name.replace('-', '_') + "-" + version + ".dist-info");
        Files.createDirectories(info);
        Files.writeString(info.resolve("METADATA"), "Metadata-Version: 2.1\nName: " + name + "\nVersion: " + version + "\n", StandardCharsets.UTF_8);
    }

    private static List<String> tree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    // ------------------------------------------------------------------ fontes do sistema

    private PythonSources.Os os(Map<String, String> env, PythonSources.Command command) {
        return new PythonSources.Os(true, env, work, command);
    }

    @Test
    void semInicializadorSemRegistroEPathVazioNaoHaCandidatoNemFalha() {
        List<PythonSources.Found> found = os(Map.of("PATH", ""), (c, w, x) -> {
            throw new AssertionError("nenhum programa deve ser chamado: " + c);
        }).candidates(PythonProbe.Cancel.NEVER);
        assertEquals(List.of(new PythonSources.Found(PythonSources.PATH, List.of(), false)), found, "fonte ausente nao e falha");
    }

    @Test
    void inicializadorListaInstalacoesComEspacosEAcentos() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("Windows"));
        Files.writeString(root.resolve("py.exe"), "x");
        List<List<String>> calls = new ArrayList<>();
        String saida = " -V:3.15 *        C:\\Users\\José da Silva\\AppData\\Local\\Programs\\Python\\Python315\\python.exe\r\n"
                + " -V:3.12          C:\\Program Files\\Python312\\python.exe\r\n" + " -V:3.11-32       C:\\Py 3.11\\python.exe\r\n" + "linha sem caminho\r\n";
        List<PythonSources.Found> found = os(Map.of("SystemRoot", root.toString(), "Path", ""), (c, w, x) -> {
            calls.add(c);
            return new PythonSources.Result(0, saida.getBytes(StandardCharsets.UTF_8));
        }).candidates(PythonProbe.Cancel.NEVER);
        assertEquals(List.of(List.of(root.resolve("py.exe").toString(), "-0p")), calls, "caminho absoluto, argumentos separados, sem shell");
        PythonSources.Found launcher = found.get(0);
        assertEquals(PythonSources.LAUNCHER, launcher.source());
        assertFalse(launcher.failed());
        assertEquals(List.of(Path.of("C:\\Users\\José da Silva\\AppData\\Local\\Programs\\Python\\Python315\\python.exe"), Path.of("C:\\Program Files\\Python312\\python.exe"),
                Path.of("C:\\Py 3.11\\python.exe")), launcher.paths());
    }

    @Test
    void inicializadorQueNaoRespondeMarcaAFonteComoFalhaENaoDerrubaAsOutras() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("Windows"));
        Files.writeString(root.resolve("py.exe"), "x");
        Path dir = Files.createDirectories(tmp.resolve("no path"));
        Files.writeString(dir.resolve("python.exe"), "x");
        List<PythonSources.Found> found = os(Map.of("SystemRoot", root.toString(), "PATH", dir.toString()), (c, w, x) -> null).candidates(PythonProbe.Cancel.NEVER);
        assertEquals(new PythonSources.Found(PythonSources.LAUNCHER, List.of(), true), found.get(0));
        assertEquals(new PythonSources.Found(PythonSources.PATH, List.of(dir.resolve("python.exe")), false), found.get(found.size() - 1));
    }

    @Test
    void registroELidoPorExportacaoEmUnicodeEChaveAusenteNaoEFalha() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("Windows").resolve("System32")).getParent();
        Files.writeString(root.resolve("System32").resolve("reg.exe"), "x");
        List<List<String>> calls = new ArrayList<>();
        List<PythonSources.Found> found = os(Map.of("SYSTEMROOT", root.toString()), (c, w, x) -> {
            calls.add(c);
            if (!c.get(2).startsWith("HKCU")) {
                return new PythonSources.Result(1, new byte[0]); // a chave nao existe
            }
            String reg = "Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\Software\\Python\\PythonCore\\3.12\\InstallPath]\r\n"
                    + "@=\"C:\\\\Usuários\\\\Ação\\\\Python312\\\\\"\r\n\"ExecutablePath\"=\"C:\\\\Usuários\\\\Ação\\\\Python312\\\\python.exe\"\r\n"
                    + "\"WindowedExecutablePath\"=\"C:\\\\Usuários\\\\Ação\\\\Python312\\\\pythonw.exe\"\r\n";
            try {
                Files.write(Path.of(c.get(3)), ("\uFEFF" + reg).getBytes(StandardCharsets.UTF_16LE));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return new PythonSources.Result(0, new byte[0]);
        }).candidates(PythonProbe.Cancel.NEVER);
        assertEquals(3, calls.size());
        for (List<String> c : calls) {
            assertEquals(root.resolve("System32").resolve("reg.exe").toString(), c.get(0));
            assertEquals("export", c.get(1), "so leitura: o registro nunca e alterado");
            assertEquals("/y", c.get(4));
            assertFalse(Files.exists(Path.of(c.get(3))), "o arquivo da leitura e apagado");
        }
        PythonSources.Found registry = found.get(0);
        assertEquals(PythonSources.REGISTRY, registry.source());
        assertFalse(registry.failed());
        assertEquals(List.of(Path.of("C:\\Usuários\\Ação\\Python312\\python.exe")), registry.paths(), "acentos inteiros; pythonw nao e candidato");
    }

    @Test
    void pathIgnoraEntradasRelativasEAPastaDeAliasesDaStore() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("Python 3.12 (ação)"));
        Files.writeString(a.resolve("python.exe"), "x");
        Path store = Files.createDirectories(tmp.resolve("Microsoft").resolve("WindowsApps"));
        Files.writeString(store.resolve("python.exe"), "");
        Path semPython = Files.createDirectories(tmp.resolve("outra"));
        String path = String.join(java.io.File.pathSeparator, "relativa\\bin", "\"" + a + "\"", store.toString(), semPython.toString(), a.toString());
        List<PythonSources.Found> found = os(Map.of("Path", path), (c, w, x) -> null).candidates(PythonProbe.Cancel.NEVER);
        assertEquals(List.of(new PythonSources.Found(PythonSources.PATH, List.of(a.resolve("python.exe")), false)), found);
        assertTrue(PythonSources.storeAlias(store.resolve("python.exe")));
    }

    // ------------------------------------------------------------------ instalacoes do computador

    @Test
    void nenhumPythonListaVaziaSemFalha() throws Exception {
        PythonEnvironments.Discovery d = service(sources(new PythonSources.Found(PythonSources.PATH, List.of(), false))).discover(pkg("p", ">=3.10", List.of()),
                PythonProbe.Cancel.NEVER);
        assertEquals(List.of(), d.environments());
        assertEquals(List.of(), d.failedSources());
        assertEquals(0, d.unanswered());
    }

    @Test
    void umaInstalacaoCompativelEDuplicatasViramUmaSo() throws Exception {
        needsPython();
        Path outraGrafia = Path.of(python.toString().toUpperCase(java.util.Locale.ROOT));
        PythonEnvironments.Discovery d = service(sources(new PythonSources.Found(PythonSources.LAUNCHER, List.of(python), false),
                new PythonSources.Found(PythonSources.REGISTRY, List.of(windows ? outraGrafia : python), false),
                new PythonSources.Found(PythonSources.PATH, List.of(python.getParent().resolve(".").resolve(python.getFileName())), false)))
                .discover(pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(1, d.environments().size(), "tres fontes, a mesma instalacao: " + d.environments());
        Environment e = d.environments().get(0);
        assertEquals(Kind.INSTALLED, e.kind());
        assertEquals(State.COMPATIBLE, e.state());
        assertTrue(e.version().matches("3\\.\\d+\\.\\d+"));
        assertNull(e.folderName());
        assertTrue(e.usable());
    }

    @Test
    void versaoIncompativelNaoEUtilizavelMesmoSemDependencias() throws Exception {
        needsPython();
        PythonEnvironments.Discovery d = service(sources(new PythonSources.Found(PythonSources.PATH, List.of(python), false))).discover(pkg("p", ">=3.99", List.of()),
                PythonProbe.Cancel.NEVER);
        assertEquals(State.VERSION_INCOMPATIBLE, d.environments().get(0).state());
        assertFalse(d.environments().get(0).usable());
    }

    @Test
    void aliasDaStoreNaoESondadoECandidatoQueNaoRespondeNaoViraOpcao() throws Exception {
        needsPython();
        Path store = Files.createDirectories(tmp.resolve("Microsoft").resolve("WindowsApps"));
        Files.writeString(store.resolve("python.exe"), "nao pode ser executado");
        Path falso = Files.createDirectories(tmp.resolve("falso")).resolve(windows ? "python.exe" : "python");
        Files.writeString(falso, "isto nao e um interpretador");
        falso.toFile().setExecutable(true);
        Path removido = tmp.resolve("removido").resolve("python.exe");
        PythonEnvironments.Discovery d = service(sources(new PythonSources.Found(PythonSources.LAUNCHER, List.of(), true),
                new PythonSources.Found(PythonSources.PATH, List.of(store.resolve("python.exe"), falso, removido, python), false)))
                .discover(pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(1, d.environments().size());
        assertEquals(python.toRealPath(), d.environments().get(0).python().toRealPath());
        assertEquals(1, d.unanswered(), "so o arquivo que nao e Python foi sondado e nao respondeu; o alias e o removido nem foram executados");
        assertEquals(List.of(PythonSources.LAUNCHER), d.failedSources(), "fonte que falhou: busca parcial, o resto continua");
    }

    @Test
    void fonteQueLancaErroNaoDerrubaADescoberta() throws Exception {
        PythonEnvironments.Discovery d = service(cancel -> {
            throw new IllegalStateException("fonte quebrada");
        }).discover(pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(List.of(), d.environments());
        assertEquals(1, d.failedSources().size());
    }

    @Test
    void sondagemQueExpiraEEncerradaENaoViraOpcao() throws Exception {
        needsPython();
        long t0 = System.nanoTime();
        PythonEnvironments.Discovery d = new PythonEnvironments(sources(new PythonSources.Found(PythonSources.PATH, List.of(python), false)), work, Duration.ofMillis(1), windows)
                .discover(pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(List.of(), d.environments());
        assertEquals(1, d.unanswered());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 15, "o prazo vale: a descoberta nao fica presa");
        PythonProbe.Failed f = assertThrows(PythonProbe.Failed.class, () -> PythonProbe.run(python, List.of(), work, Duration.ofMillis(1), PythonProbe.Cancel.NEVER));
        assertEquals(PythonProbe.Failure.TIMEOUT, f.failure());
    }

    @Test
    void cancelarInterrompeADescobertaSemListarNada() throws Exception {
        needsPython();
        AtomicInteger asked = new AtomicInteger();
        PythonEnvironments.Discovery d = service(sources(new PythonSources.Found(PythonSources.PATH, List.of(python), false))).discover(pkg("p", ">=3.10", List.of()),
                () -> asked.incrementAndGet() > 1);
        assertTrue(d.cancelled());
        assertEquals(List.of(), d.environments());
    }

    @Test
    void sondagemNaoGravaNaPastaDoProjetoNemDeixaArquivoDeTrabalho() throws Exception {
        needsPython();
        Path projeto = Files.createDirectories(tmp.resolve("projeto"));
        // um modulo com o nome de um da biblioteca padrao: se a pasta do projeto entrasse na busca, a sondagem quebraria
        Files.writeString(projeto.resolve("json.py"), "raise SystemExit('modulo do projeto carregado')\n");
        venv(projeto.resolve(".venv"));
        List<String> antes = tree(projeto);
        PythonEnvironments.Project p = service(sources()).project(projeto, pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(State.COMPATIBLE, p.environments().get(0).state());
        assertEquals(antes, tree(projeto), "a pasta do projeto nao muda: nada criado, nada gravado, nenhum .pyc");
        try (Stream<Path> s = Files.list(work)) {
            assertEquals(List.of(), s.toList(), "a saida da sondagem e apagada");
        }
    }

    // ------------------------------------------------------------------ pasta da automacao

    @Test
    void pastaComPontoVenvEncontraOAmbienteDoProjeto() throws Exception {
        needsPython();
        Path projeto = tmp.resolve("Automação São João");
        Path exe = venv(projeto.resolve(".venv"));
        PythonEnvironments.Project p = service(sources()).project(projeto, pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(List.of(), p.problems());
        assertEquals(1, p.environments().size());
        Environment e = p.environments().get(0);
        assertEquals(Kind.PROJECT, e.kind());
        assertEquals(".venv", e.folderName());
        assertEquals(exe.toRealPath(), e.python().toRealPath(), "caminho com espacos e acentos");
        assertEquals(State.COMPATIBLE, e.state());
    }

    @Test
    void pastaComVenvEComOsDoisListaTodosSemEscolher() throws Exception {
        needsPython();
        Path so = tmp.resolve("so-venv");
        venv(so.resolve("venv"));
        PythonEnvironments.Project um = service(sources()).project(so, pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(List.of("venv"), um.environments().stream().map(Environment::folderName).toList());

        Path dois = tmp.resolve("dois");
        venv(dois.resolve(".venv"));
        venv(dois.resolve("venv"));
        PythonEnvironments.Project p = service(sources()).project(dois, pkg("q", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(List.of(".venv", "venv"), p.environments().stream().map(Environment::folderName).toList(), "os dois, cada um com o seu caminho");
        assertNotEquals(p.environments().get(0).id(), p.environments().get(1).id());
        assertNotEquals(p.environments().get(0).python(), p.environments().get(1).python());
    }

    @Test
    void pastaSemAmbienteNaoProcuraEmSubpastasNemEmOutrasPastas() throws Exception {
        needsPython();
        Path projeto = Files.createDirectories(tmp.resolve("projeto"));
        venv(projeto.resolve("subprojeto").resolve(".venv"));
        venv(projeto.resolve("env")); // nome nao convencional
        venv(tmp.resolve(".venv")); // na pasta de cima
        PythonEnvironments.Project p = service(sources()).project(projeto, pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(List.of(), p.environments(), "so .venv e venv diretamente na pasta escolhida");
        assertEquals(List.of(), p.problems());
    }

    @Test
    void ambienteIncompletoEInformadoENaoViraOpcao() throws Exception {
        Path projeto = Files.createDirectories(tmp.resolve("projeto"));
        Files.createDirectories(projeto.resolve(".venv").resolve("Scripts"));
        Path semExe = Files.createDirectories(projeto.resolve("venv"));
        Files.writeString(semExe.resolve("pyvenv.cfg"), "home = C:\\nada\n");
        PythonEnvironments.Project p = service(sources()).project(projeto, pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(List.of(), p.environments(), "o nome da pasta nao basta");
        assertEquals(List.of(new PythonEnvironments.Problem(".venv", Issue.INCOMPLETE), new PythonEnvironments.Problem("venv", Issue.INCOMPLETE)), p.problems());

        assertEquals(List.of(new PythonEnvironments.Problem(null, Issue.NOT_A_FOLDER)),
                service(sources()).project(tmp.resolve("nao-existe"), pkg("q", ">=3.10", List.of()), PythonProbe.Cancel.NEVER).problems());
    }

    @Test
    void interpretadorComPyvenvCfgQueNaoRespondeFicaComoNaoVerificado() throws Exception {
        Path projeto = Files.createDirectories(tmp.resolve("projeto"));
        Path dir = Files.createDirectories(projeto.resolve(".venv").resolve(windows ? "Scripts" : "bin"));
        Files.writeString(projeto.resolve(".venv").resolve("pyvenv.cfg"), "home = C:\\instalacao-base-que-nao-existe-mais\n");
        Path exe = dir.resolve(windows ? "python.exe" : "python");
        Files.writeString(exe, "nao e um interpretador");
        exe.toFile().setExecutable(true);
        PythonEnvironments.Project p = service(sources()).project(projeto, pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
        assertEquals(1, p.environments().size());
        assertEquals(State.UNVERIFIED, p.environments().get(0).state());
        assertNull(p.environments().get(0).version());
        assertFalse(p.environments().get(0).usable());
    }

    @Test
    void juncaoParaForaDaPastaNaoESeguidaEEInformada() throws Exception {
        needsPython();
        Assumptions.assumeTrue(windows, "juncao e do Windows");
        Path outro = tmp.resolve("outro-projeto");
        venv(outro.resolve(".venv"));
        Path projeto = Files.createDirectories(tmp.resolve("projeto"));
        Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", projeto.resolve(".venv").toString(), outro.resolve(".venv").toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Assumptions.assumeTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "fixture: nao foi possivel criar a juncao");
        try {
            PythonEnvironments.Project r = service(sources()).project(projeto, pkg("p", ">=3.10", List.of()), PythonProbe.Cancel.NEVER);
            assertEquals(List.of(), r.environments(), "o ambiente de outro projeto nao entra por uma juncao");
            assertEquals(List.of(new PythonEnvironments.Problem(".venv", Issue.LINK_NOT_FOLLOWED)), r.problems());
        } finally {
            Files.delete(projeto.resolve(".venv")); // remove so a juncao (a limpeza da pasta temporaria nao a atravessa)
        }
    }

    // ------------------------------------------------------------------ dependencias

    @Test
    void dependenciasCorretasAusentesEDeVersaoDiferente() throws Exception {
        needsPython();
        Path projeto = tmp.resolve("projeto");
        venv(projeto.resolve(".venv"));
        distribution(projeto.resolve(".venv"), "nekkora-fixture-a", "1.2.3");
        distribution(projeto.resolve(".venv"), "nekkora-fixture-b", "2.0.0");
        PythonEnvironments s = service(sources());

        Environment ok = s.project(projeto, pkg("ok", ">=3.10", List.of("nekkora-fixture-a==1.2.3", "nekkora_fixture_b==2.0.0")), PythonProbe.Cancel.NEVER).environments().get(0);
        assertEquals(State.COMPATIBLE, ok.state());
        assertEquals(List.of(), ok.pending());

        Environment pendente = s.project(projeto, pkg("pendente", ">=3.10", List.of("nekkora-fixture-a==1.2.3", "nekkora-fixture-b==2.1.0", "nekkora-fixture-c==0.1.0")),
                PythonProbe.Cancel.NEVER).environments().get(0);
        assertEquals(State.DEPENDENCIES_PENDING, pendente.state());
        assertEquals(List.of(new PythonEnvironments.Pending("nekkora-fixture-b==2.1.0", "2.0.0"), new PythonEnvironments.Pending("nekkora-fixture-c==0.1.0", null)),
                pendente.pending(), "o que falta e o que foi encontrado, requisito por requisito");
        assertFalse(pendente.usable(), "versao compativel sozinha nao e compatibilidade completa");

        Environment versao = s.project(projeto, pkg("versao", ">=3.99", List.of("nekkora-fixture-a==1.2.3")), PythonProbe.Cancel.NEVER).environments().get(0);
        assertEquals(State.VERSION_INCOMPATIBLE, versao.state());
    }

    @Test
    void ambienteVirtualEAInstalacaoBaseNaoSaoOMesmoAmbiente() throws Exception {
        needsPython();
        Path projeto = tmp.resolve("projeto");
        Path exe = venv(projeto.resolve(".venv"));
        distribution(projeto.resolve(".venv"), "nekkora-fixture-a", "1.2.3");
        // o venv chega pelo PATH, junto com a instalacao-base de que ele depende
        PythonEnvironments.Discovery d = service(sources(new PythonSources.Found(PythonSources.PATH, List.of(exe, python), false)))
                .discover(pkg("p", ">=3.10", List.of("nekkora-fixture-a==1.2.3")), PythonProbe.Cancel.NEVER);
        assertEquals(2, d.environments().size(), "nao sao fundidos: " + d.environments());
        Environment base = d.environments().get(0);
        Environment virtual = d.environments().get(1);
        assertEquals(Kind.INSTALLED, base.kind());
        assertEquals(State.DEPENDENCIES_PENDING, base.state(), "a base nao tem a dependencia do ambiente");
        assertEquals(Kind.VIRTUAL, virtual.kind(), "veio pelo PATH, mas e um ambiente virtual: nao e instalacao do computador");
        assertEquals(".venv", virtual.folderName());
        assertEquals(State.COMPATIBLE, virtual.state());
        assertNotEquals(base.id(), virtual.id());
    }

    // ------------------------------------------------------------------ conferir de novo

    @Test
    void conferirDeNovoPercebeInterpretadorRemovidoEPacoteDiferenteSemTrocarDeAmbiente() throws Exception {
        needsPython();
        Path projeto = tmp.resolve("projeto");
        Path exe = venv(projeto.resolve(".venv"));
        PythonEnvironments s = service(sources());
        ExecutionPackage simples = pkg("simples", ">=3.10", List.of());
        Environment e = s.project(projeto, simples, PythonProbe.Cancel.NEVER).environments().get(0);
        assertEquals(State.COMPATIBLE, e.state());

        // o pacote mudou: agora declara uma dependencia que o ambiente nao tem
        Environment outroPacote = s.recheck(e, pkg("exigente", ">=3.10", List.of("nekkora-fixture-a==1.2.3")), PythonProbe.Cancel.NEVER);
        assertEquals(e.id(), outroPacote.id(), "o mesmo item da lista");
        assertEquals(State.DEPENDENCIES_PENDING, outroPacote.state());

        // o interpretador foi removido depois da descoberta
        Files.delete(exe);
        Environment removido = s.recheck(e, simples, PythonProbe.Cancel.NEVER);
        assertNotNull(removido);
        assertEquals(e.id(), removido.id());
        assertEquals(State.UNVERIFIED, removido.state());
        assertEquals(e.python(), removido.python(), "nenhum outro ambiente e posto no lugar");
        assertFalse(removido.usable());
    }

    // ------------------------------------------------------------------ execucao pelo ambiente escolhido

    private RunController controller(Path dataDir) {
        return new RunController(new RunStore(RunStore.defaultRoot(dataDir), com.nekkora.client.pack.AtomicWriter.DEFAULT),
                new ApprovalLedger(ApprovalLedger.defaultFile(dataDir), com.nekkora.client.pack.AtomicWriter.DEFAULT), new RunFixtures.FakeSession(), new RunFixtures.ShiftClock(),
                dataDir, Duration.ofMillis(100), Duration.ofMillis(200));
    }

    private static final String ONDE = String.join("\n", "import json, os, pathlib, sys",
            "pathlib.Path(os.environ['NEKKORA_SAIDA'], 'interpretador.json').write_text(json.dumps({'executavel': sys.executable, 'prefixo': sys.prefix,",
            "    'versao': '%d.%d.%d' % sys.version_info[:3]}), encoding='utf-8')",
            "pathlib.Path(os.environ['NEKKORA_SINAL']).write_text(json.dumps({'schema': 'nekkora-agent/external-signal', 'version': '1.0.0', 'outcome': 'BUSINESS_SUCCESS'}),",
            "    encoding='utf-8')", "");

    @Test
    void aExecucaoUsaOInterpretadorDoAmbienteEscolhido() throws Exception {
        needsPython();
        Path projeto = tmp.resolve("projeto");
        venv(projeto.resolve(".venv"));
        distribution(projeto.resolve(".venv"), "nekkora-fixture-a", "1.2.3");
        ExecutionPackage p = ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pacotes").resolve("onde"), "onde", Map.of("main.py", ONDE), "main.py", ">=3.10",
                List.of("nekkora-fixture-a==1.2.3")));
        Environment e = service(sources()).project(projeto, p, PythonProbe.Cancel.NEVER).environments().get(0);
        assertEquals(State.COMPATIBLE, e.state());

        Path dados = tmp.resolve("dados");
        Path saida = Files.createDirectories(tmp.resolve("saida"));
        RunController c = controller(dados);
        try {
            c.approvals().approve(p, java.time.Instant.now());
            c.start(new RunController.Request(p.dir(), RunFixtures.context(p), e.python(), saida, null));
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (c.isRunning() && System.nanoTime() < limit) Thread.sleep(50);
            assertEquals(ExecutionState.SUCCEEDED, c.current().orElseThrow().record().state());
        } finally {
            c.close();
        }
        Path onde;
        try (Stream<Path> s = Files.walk(saida)) {
            onde = s.filter(f -> f.getFileName().toString().equals("interpretador.json")).findFirst().orElseThrow();
        }
        Map<String, Object> m = com.nekkora.client.pack.JsonReader.object(Files.readString(onde));
        assertEquals(e.python().toRealPath(), Path.of((String) m.get("executavel")).toRealPath(), "o processo e o do ambiente escolhido");
        assertEquals(projeto.resolve(".venv").toRealPath(), Path.of((String) m.get("prefixo")).toRealPath());
        assertEquals(e.version(), m.get("versao"));
    }

    @Test
    void oInicioConfereDeNovoEMudancaDepoisDaValidacaoImpedeAExecucao() throws Exception {
        needsPython();
        Path projeto = tmp.resolve("projeto");
        Path exe = venv(projeto.resolve(".venv"));
        distribution(projeto.resolve(".venv"), "nekkora-fixture-a", "1.2.3");
        ExecutionPackage p = ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pacotes").resolve("onde"), "onde", Map.of("main.py", ONDE), "main.py", ">=3.10",
                List.of("nekkora-fixture-a==1.2.3")));
        Environment e = service(sources()).project(projeto, p, PythonProbe.Cancel.NEVER).environments().get(0);
        assertEquals(State.COMPATIBLE, e.state(), "validado e compativel...");

        Path saida = Files.createDirectories(tmp.resolve("saida"));
        RunController c = controller(tmp.resolve("dados"));
        try {
            c.approvals().approve(p, java.time.Instant.now());
            // ...e depois a dependencia saiu do ambiente
            try (Stream<Path> s = Files.walk(projeto.resolve(".venv"))) {
                for (Path f : s.filter(x -> x.getFileName().toString().equals("METADATA")).toList()) Files.delete(f);
            }
            assertEquals(com.nekkora.client.pack.ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, assertThrows(com.nekkora.client.pack.ExecRefused.class,
                    () -> c.start(new RunController.Request(p.dir(), RunFixtures.context(p), e.python(), saida, null))).code());
            // ...e depois o interpretador foi removido
            Files.delete(exe);
            assertEquals(com.nekkora.client.pack.ErrorCode.EXEC_PYTHON_INVALID, assertThrows(com.nekkora.client.pack.ExecRefused.class,
                    () -> c.start(new RunController.Request(p.dir(), RunFixtures.context(p), e.python(), saida, null))).code());
            assertFalse(c.isRunning());
        } finally {
            c.close();
        }
        try (Stream<Path> s = Files.walk(saida)) {
            assertEquals(List.of(), s.filter(Files::isRegularFile).toList(), "nada foi executado");
        }
    }

    // ------------------------------------------------------------------ as fontes reais desta maquina

    @Test
    void asFontesReaisRespondemSemExecutarAliasNemLancarErro() {
        List<PythonSources.Found> found = PythonSources.system(work).candidates(PythonProbe.Cancel.NEVER);
        assertFalse(found.isEmpty(), "o PATH sempre e uma fonte");
        for (PythonSources.Found f : found) {
            for (Path p : f.paths()) {
                assertTrue(p.isAbsolute(), f.source() + ": " + p);
                assertFalse(f.source().equals(PythonSources.PATH) && PythonSources.storeAlias(p), "alias da Store no PATH nao e candidato");
            }
        }
    }
}
