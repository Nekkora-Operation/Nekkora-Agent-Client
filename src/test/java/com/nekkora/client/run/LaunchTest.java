package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.ExecutionPackage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Do pacote aprovado ao processo, sem lancar nada: copia conferida, linha de comando, sinal e desfecho, aprovacoes,
 * escolhas da pessoa e ambiente minimo. Nao depende de Python.
 */
class LaunchTest {

    @TempDir
    Path tmp;

    ExecutionPackage pkg(String name) throws Exception {
        return ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve(name), name, RunFixtures.SUCESSO));
    }

    @Test
    void copiaSoOsArquivosListadosEConfereAImpressao() throws Exception {
        ExecutionPackage p = pkg("pacote-a");
        Files.writeString(p.dir().resolve("nao-listado.txt"), "fica de fora");
        Path copy = Launch.snapshot(p, Files.createDirectories(tmp.resolve("exec")));
        assertEquals(tmp.resolve("exec").resolve("pacote"), copy);
        try (var s = Files.list(copy)) {
            assertEquals(List.of("comum.py", "filho.py", "main.py", "nekkora-external.json"), s.map(f -> f.getFileName().toString()).sorted().toList());
        }
        assertEquals(p.fingerprint(), ExecutionPackage.inspect(copy).fingerprint());
        // mudar a pasta original DEPOIS da copia nao alcanca a execucao
        Files.writeString(p.dir().resolve("main.py"), "print('trocado')\n");
        assertEquals(p.fingerprint(), ExecutionPackage.inspect(copy).fingerprint());
        // e um pacote que mudou depois de inspecionado nao e copiado como se fosse o aprovado
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED,
                assertThrows(ExecRefused.class, () -> Launch.snapshot(p, Files.createDirectories(tmp.resolve("exec2")))).code());
    }

    @Test
    void linhaDeComandoFechadaSemShellESemArgumentoDeFora() throws Exception {
        ExecutionPackage p = pkg("pacote-b");
        Path python = Path.of("C:\\py\\python.exe");
        Launch.Spec spec = Launch.spec(p, python, tmp.resolve("copia"));
        assertEquals(List.of(python.toString(), "-E", "-s", "-B", "main.py"), spec.command());
        assertEquals(tmp.resolve("copia"), spec.workDir());
        assertEquals(python, spec.executable());
        assertThrows(UnsupportedOperationException.class, () -> spec.args().add("--mais"));
    }

    @Test
    void desfechoCodigoZeroNuncaESozinhoSucessoDeNegocio() {
        assertEquals(new Launch.Outcome(ExecutionState.SUCCEEDED, ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL), Launch.classify(ExternalSignal.Signal.NONE, 0));
        assertEquals(new Launch.Outcome(ExecutionState.SUCCEEDED, null), Launch.classify(ExternalSignal.Signal.BUSINESS_SUCCESS, 0));
        assertEquals(new Launch.Outcome(ExecutionState.FAILED, ErrorCode.EXT_EXIT_NONZERO), Launch.classify(ExternalSignal.Signal.NONE, 1));
        assertEquals(new Launch.Outcome(ExecutionState.FAILED, ErrorCode.EXT_EXIT_NONZERO), Launch.classify(ExternalSignal.Signal.BUSINESS_SUCCESS, 2),
                "sinal de sucesso com codigo de erro: falha");
        assertEquals(new Launch.Outcome(ExecutionState.FAILED, ErrorCode.EXT_BUSINESS_FAILURE), Launch.classify(ExternalSignal.Signal.BUSINESS_FAILURE, 0));
        assertEquals(new Launch.Outcome(ExecutionState.FAILED, ErrorCode.EXT_EXIT_NONZERO), Launch.classify(ExternalSignal.Signal.NONE, -1), "fim nao observado");
        assertEquals("NEKKORA_SINAL", ExternalSignal.SIGNAL_ENV);
        assertEquals("NEKKORA_SAIDA", ExternalSignal.OUTPUT_ENV);
        assertEquals("sinal.json", ExternalSignal.SIGNAL_FILE);
        assertEquals("nekkora-agent/external-signal", ExternalSignal.SIGNAL_SCHEMA);
    }

    @Test
    void sinalSoContaNoEsquemaFechado() throws Exception {
        Path out = Files.createDirectories(tmp.resolve("saida"));
        Path f = out.resolve("sinal.json");
        assertEquals(ExternalSignal.Signal.NONE, Launch.reported(out), "ausente");
        Files.writeString(f, "{\"schema\": \"nekkora-agent/external-signal\", \"version\": \"1.0.0\", \"outcome\": \"BUSINESS_SUCCESS\"}");
        assertEquals(ExternalSignal.Signal.BUSINESS_SUCCESS, Launch.reported(out));
        Files.writeString(f, "{\"schema\": \"nekkora-agent/external-signal\", \"version\": \"1.0.0\", \"outcome\": \"BUSINESS_FAILURE\"}");
        assertEquals(ExternalSignal.Signal.BUSINESS_FAILURE, Launch.reported(out));
        for (String bad : List.of("{\"schema\": \"outro\", \"version\": \"1.0.0\", \"outcome\": \"BUSINESS_SUCCESS\"}",
                "{\"schema\": \"nekkora-agent/external-signal\", \"version\": \"2.0.0\", \"outcome\": \"BUSINESS_SUCCESS\"}",
                "{\"schema\": \"nekkora-agent/external-signal\", \"version\": \"1.0.0\", \"outcome\": \"SUCCESS\"}",
                "{\"schema\": \"nekkora-agent/external-signal\", \"version\": \"1.0.0\", \"outcome\": true}", "BUSINESS_SUCCESS", "",
                "{\"schema\": \"nekkora-agent/external-signal\", \"version\": \"1.0.0\", \"outcome\": \"BUSINESS_SUCCESS\", \"x\": \"" + "y".repeat(5000) + "\"}")) {
            Files.writeString(f, bad, StandardCharsets.UTF_8);
            assertEquals(ExternalSignal.Signal.NONE, Launch.reported(out), bad.length() > 80 ? "grande demais" : bad);
        }
    }

    @Test
    void aprovacaoEPorImpressaoPorPerfilENaoIniciaNada() throws Exception {
        Path data = tmp.resolve("perfil");
        ApprovalLedger ledger = new ApprovalLedger(ApprovalLedger.defaultFile(data), AtomicWriter.DEFAULT);
        ExecutionPackage a = pkg("pacote-c");
        ExecutionPackage b = ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pacote-d"), "pacote-d", RunFixtures.SEM_SINAL));
        assertFalse(ledger.approved(a));
        long children = ProcessHandle.current().children().count();
        ledger.approve(a, Instant.parse("2026-11-13T12:00:00Z"));
        ledger.approve(a, Instant.parse("2026-11-13T13:00:00Z"));
        assertEquals(children, ProcessHandle.current().children().count(), "aprovar nao cria processo");
        try (var s = Files.walk(data)) {
            assertEquals(List.of(data.resolve("execucao").resolve("aprovacoes.json")), s.filter(Files::isRegularFile).toList(), "so o arquivo das aprovacoes foi gravado");
        }
        assertTrue(ledger.approved(a));
        assertTrue(ledger.approved(a.fingerprint()));
        assertFalse(ledger.approved(b), "outra impressao, outra decisao");
        assertFalse(ledger.approved(b.fingerprint()));
        assertEquals(1, ledger.load().size(), "idempotente");
        String json = Files.readString(ApprovalLedger.defaultFile(data), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema\": \"nekkora-agent/approvals\"") && json.contains("\"version\": \"1.0.0\"") && json.contains(a.fingerprint())
                && json.contains("\"kind\": \"EXTERNAL_PYTHON\"") && json.contains("\"name\": \"pacote " + a.shortFingerprint() + "\""), json);
        assertFalse(json.contains("pacote-c") || json.contains(tmp.getFileName().toString()), "nem o nome do pacote nem o caminho");
        // o mesmo arquivo lido por outra instancia (o aplicativo reabriu): a aprovacao continua; outro perfil nao a tem
        assertTrue(new ApprovalLedger(ApprovalLedger.defaultFile(data), AtomicWriter.DEFAULT).approved(a));
        assertFalse(new ApprovalLedger(ApprovalLedger.defaultFile(tmp.resolve("outro-perfil")), AtomicWriter.DEFAULT).approved(a));
        // arquivo mudado: outra impressao: sem aprovacao
        Files.writeString(a.dir().resolve("main.py"), "print('trocado')\n");
        assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(a.dir()), "e o pacote nem passa mais pelo validador");
        ledger.revoke(a);
        assertFalse(ledger.approved(a));
        ledger.approve(b, Instant.now());
        ledger.revoke(b.fingerprint());
        assertFalse(ledger.approved(b));
        // arquivo ilegivel vale como "nenhuma aprovacao"
        ledger.approve(b, Instant.now());
        Files.writeString(ApprovalLedger.defaultFile(data), "{ isto nao e json");
        assertFalse(ledger.approved(b));
        ApprovalLedger failing = new ApprovalLedger(ApprovalLedger.defaultFile(data), (target, bytes) -> {
            throw new java.io.IOException("disco");
        });
        assertEquals(ErrorCode.EXEC_STORAGE_FAILED, assertThrows(ExecRefused.class, () -> failing.approve(b, Instant.now())).code());
    }

    @Test
    void escolhasDizemOQueFaltaEmOrdem() throws Exception {
        ExecutionPackage p = pkg("pacote-e");
        ExecutionSetup s = ExecutionSetup.empty();
        assertFalse(s.canStart());
        assertEquals(List.of(ErrorCode.EXEC_PACKAGE_NOT_FOUND, ErrorCode.EXEC_PYTHON_INVALID, ErrorCode.EXEC_OUTPUT_INVALID), s.missing());
        s = s.withSession(new SessionProbe.Session(true, 1, true, ErrorCode.EXEC_SESSION_LOCKED)).withPackage(p, null, false);
        assertEquals(List.of(ErrorCode.EXEC_SESSION_LOCKED, ErrorCode.EXEC_NOT_APPROVED, ErrorCode.EXEC_PYTHON_INVALID, ErrorCode.EXEC_OUTPUT_INVALID), s.missing());
        s = s.withSession(new SessionProbe.Session(true, 1, false, null)).withApproved(true).withOutput(tmp);
        PythonRuntime.Probe old = new PythonRuntime.Probe(Path.of("C:\\py39\\python.exe"), "3.9.7");
        assertEquals(List.of(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE), s.withPython(old, null).missing(), "o pacote pede >=3.10");
        PythonRuntime.Probe ok = new PythonRuntime.Probe(Path.of("C:\\py312\\python.exe"), "3.12.10");
        s = s.withPython(ok, null);
        assertTrue(s.canStart());
        assertEquals(List.of(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE), s.withEnvironmentProblem(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE).missing(), "dependencia ausente");
        assertEquals(List.of(ErrorCode.EXEC_PACKAGE_CHANGED, ErrorCode.EXEC_PYTHON_INVALID).subList(0, 1), s.withPackage(null, ErrorCode.EXEC_PACKAGE_CHANGED, false).missing());
        RunContext c = RunFixtures.context(p);
        RunController.Request req = s.request(c, null).orElseThrow();
        assertEquals(p.dir(), req.packageDir());
        assertEquals(c, req.context());
        assertEquals(ok.python(), req.python());
        assertEquals(tmp, req.outputRoot());
        assertEquals(Duration.ofMinutes(60), req.timeout(), "prazo padrao");
        RunContext otherPackage = new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, "sha256:" + "0".repeat(64), "outro", null, null);
        assertTrue(s.request(otherPackage, null).isEmpty(), "o contexto precisa ser do pacote escolhido");
        assertTrue(s.request(null, null).isEmpty());
        assertTrue(s.withApproved(false).request(c, null).isEmpty());
        // textos
        assertEquals("Pacote Python externo (sem framework Nekkora)", ExecutionSetup.kindLabel(p));
        assertEquals("Origem não verificada · nenhum gerador declarado", ExecutionSetup.originLabel(p));
        assertEquals("Tela bloqueada: a execução não começa", ExecutionSetup.sessionLabel(new SessionProbe.Session(true, 1, true, ErrorCode.EXEC_SESSION_LOCKED)));
        assertEquals("Sessão interativa do usuário (tela desbloqueada)", ExecutionSetup.sessionLabel(new SessionProbe.Session(true, 1, false, null)));
        assertEquals("1 min 05 s", ExecutionSetup.duration(65_000));
        assertEquals("py312", ExecutionSetup.pythonFolder(Path.of("C:\\py312\\Scripts\\python.exe")));
        assertEquals("python.exe", ExecutionSetup.shortName(ok.python()));
        assertTrue(ExecutionSetup.cancellationLabel(new ExecutionRecord.Cancellation(Instant.now(), ExecutionRecord.CancelSource.LOCAL_UI,
                ExecutionRecord.CancelOutcome.PENDING)).startsWith("Aguardando confirmação · pedido pela pessoa operadora às "));
        assertNull(ExecutionSetup.empty().pkg());
    }

    @Test
    void ambienteMinimoECaminhoDoPython() throws Exception {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "x", "Path", "y", "SystemRoot", "C:\\Windows", "AWS_SECRET_ACCESS_KEY", "segredo", "PYTHONPATH", "C:\\fora",
                "PYTHONSTARTUP", "C:\\fora\\s.py", "NEKKORA_TEST_PYTHON", "z", "JAVA_HOME", "j", "PIP_INDEX_URL", "u"));
        PythonRuntime.minimalEnvironment(env);
        assertEquals(Map.of("PATH", "x", "Path", "y", "SystemRoot", "C:\\Windows", "PYTHONIOENCODING", "utf-8", "PYTHONUTF8", "1"), env);
        assertEquals(26, PythonRuntime.ENV_KEEP.size(), "a lista fechada e a do Nekkora Agent");
        Path notPython = Files.writeString(tmp.resolve("cmd.exe"), "x");
        Path named = Files.writeString(tmp.resolve("python.exe"), "nao e um executavel de verdade");
        for (Path bad : new Path[] {null, Path.of("python.exe"), tmp.resolve("nao-existe").resolve("python.exe"), notPython, tmp}) {
            assertEquals(ErrorCode.EXEC_PYTHON_INVALID, assertThrows(ExecRefused.class, () -> PythonRuntime.validate(bad)).code(), String.valueOf(bad));
        }
        assertEquals(named, PythonRuntime.validate(named), "o nome e o tipo passam; a sonda e que confere se responde");
        assertEquals(ErrorCode.EXEC_PYTHON_INVALID, assertThrows(ExecRefused.class, () -> PythonRuntime.probe(named, tmp.resolve("sonda"))).code(),
                "arquivo com nome de Python que nao executa: recusado pela sonda");
        assertFalse(PythonRuntime.PROBE.contains("install") || PythonRuntime.DEPS_PROBE.contains("install") || PythonRuntime.DEPS_PROBE.contains("pip"),
                "as sondas so leem");
    }

    /** Recusas antes de qualquer processo do pacote: nao precisam de um Python de verdade. */
    @Test
    void recusasAntesDeLancarFicamRegistradasComOContextoENadaComeca() throws Exception {
        Path data = tmp.resolve("dados");
        RunStore runs = new RunStore(RunStore.defaultRoot(data), AtomicWriter.DEFAULT);
        ApprovalLedger ledger = new ApprovalLedger(ApprovalLedger.defaultFile(data), AtomicWriter.DEFAULT);
        RunFixtures.FakeSession session = new RunFixtures.FakeSession();
        Path out = Files.createDirectories(tmp.resolve("saida"));
        Path fakePython = tmp.resolve("nao").resolve("python.exe");
        long children = ProcessHandle.current().children().count();
        try (RunController controller = new RunController(runs, ledger, session, new RunFixtures.ShiftClock(), data, Duration.ofMillis(100), Duration.ofMillis(200))) {
            List<RunController.Snapshot> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            controller.addListener(seen::add);
            ExecutionPackage p = pkg("pacote-f");
            RunContext c = new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, p.fingerprint(), "Meu pacote", RunFixtures.AUTOMATION, RunFixtures.AUTOMATION_VERSION);
            assertEquals(ErrorCode.EXEC_NOT_APPROVED,
                    assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(p.dir(), c, fakePython, out, null))).code());
            ledger.approve(p, Instant.now());
            session.locked.set(true);
            assertEquals(ErrorCode.EXEC_SESSION_LOCKED,
                    assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(p.dir(), c, fakePython, out, null))).code());
            session.locked.set(false);
            session.problem.set(ErrorCode.EXEC_SESSION_UNAVAILABLE);
            assertEquals(ErrorCode.EXEC_SESSION_UNAVAILABLE,
                    assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(p.dir(), c, fakePython, out, null))).code());
            session.problem.set(null);
            assertEquals(ErrorCode.EXEC_PYTHON_INVALID,
                    assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(p.dir(), c, fakePython, out, null))).code());
            // a impressao do pedido nao e a do pacote que esta na pasta
            RunContext other = new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, "sha256:" + "0".repeat(64), "Meu pacote", null, null);
            assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED,
                    assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(p.dir(), other, fakePython, out, null))).code());
            assertEquals(ErrorCode.EXEC_PACKAGE_NOT_FOUND,
                    assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(tmp.resolve("sumiu"), c, fakePython, out, null))).code());
            // pacote mudado DEPOIS da aprovacao (mesma pasta): a conferencia na hora de iniciar recusa
            Files.writeString(p.dir().resolve("main.py"), "print('trocado')\n");
            assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED,
                    assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(p.dir(), c, fakePython, out, null))).code());
            assertFalse(controller.isRunning(), "nada foi lancado");
            assertTrue(controller.running().isEmpty());
            assertFalse(controller.cancel(), "nada a cancelar");
            assertEquals(RunController.CancelResult.UNKNOWN_EXECUTION, controller.cancel(ExecutionId.random(), ExecutionRecord.CancelSource.LOCAL_UI));
            List<ExecutionRecord> all = runs.all();
            assertEquals(7, all.size());
            assertTrue(all.stream().allMatch(r -> r.state() == ExecutionState.BLOCKED && r.owner() == null && r.started() == null), "todos BLOCKED, sem processo");
            assertEquals(6, all.stream().filter(r -> r.context().equals(c)).count(), "cada recusa leva o contexto do pedido");
            assertEquals(7, runs.all(c.where()).size());
            assertEquals(RunController.CancelResult.ALREADY_ENDED, controller.cancel(all.get(0).id(), ExecutionRecord.CancelSource.LOCAL_UI));
            assertEquals(7, seen.size(), "a tela e avisada de cada recusa");
            assertTrue(seen.stream().noneMatch(RunController.Snapshot::running));
            assertEquals(ExecutionState.BLOCKED, controller.current().orElseThrow().record().state());
        }
        assertEquals(children, ProcessHandle.current().children().count(), "nenhum processo foi criado");
        try (var s = Files.list(out)) {
            assertEquals(0, s.count(), "nada na pasta de saida");
        }
    }
}
