package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.PackageContext;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Registro da execucao: ciclo de vida fechado, formato em disco e, sobretudo, o CONTEXTO IMUTAVEL: uma execucao pertence
 * a organizacao, ao ambiente, ao pacote e a automacao do inicio, e nada a reatribui. Sem processos: nao depende de Python.
 */
class RunRecordTest {

    static final String FP = "sha256:" + "ab".repeat(32);
    static final String OTHER_FP = "sha256:" + "cd".repeat(32);
    static final Instant T0 = Instant.parse("2026-11-13T12:00:00Z");

    @TempDir
    Path tmp;

    RunStore runs;

    @BeforeEach
    void open() {
        runs = new RunStore(tmp.resolve("dados").resolve("execucoes"), AtomicWriter.DEFAULT);
    }

    static RunContext ctx() {
        return new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, FP, "Fechamento mensal — São Paulo", RunFixtures.AUTOMATION, RunFixtures.AUTOMATION_VERSION);
    }

    static ExecutionRecord running(ExecutionId id, RunContext c) {
        return ExecutionRecord.ready(id, c, T0).moveTo(ExecutionState.STARTING, null, T0.plusSeconds(1))
                .withOwner(new ProcessOwner(4321, T0.toEpochMilli(), ProcessOwner.digest("C:\\py\\python.exe"))).moveTo(ExecutionState.RUNNING, null, T0.plusSeconds(2));
    }

    @Test
    void contextoValidaOsSeusCampos() {
        RunContext c = ctx();
        assertEquals(new PackageContext(RunFixtures.ORG, RunFixtures.WORKSPACE), c.where());
        assertTrue(c.in(new PackageContext(RunFixtures.ORG, RunFixtures.WORKSPACE)));
        assertFalse(c.in(new PackageContext(RunFixtures.OTHER_ORG, RunFixtures.WORKSPACE)), "outra organizacao");
        assertFalse(c.in(new PackageContext(RunFixtures.ORG, RunFixtures.OTHER_WORKSPACE)), "outro ambiente");
        assertFalse(c.in(null));
        assertThrows(NullPointerException.class, () -> new RunContext(null, RunFixtures.WORKSPACE, FP, "x", null, null));
        assertThrows(NullPointerException.class, () -> new RunContext(RunFixtures.ORG, null, FP, "x", null, null));
        for (String bad : new String[] {null, "", "abab", "sha256:" + "AB".repeat(32), "sha256:" + "ab".repeat(31)}) {
            assertThrows(IllegalArgumentException.class, () -> new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, bad, "x", null, null), String.valueOf(bad));
        }
        for (String bad : new String[] {null, "", "   ", "x".repeat(81), "duas\nlinhas"}) {
            assertThrows(IllegalArgumentException.class, () -> new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, FP, bad, null, null), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, FP, "x", null, RunFixtures.AUTOMATION_VERSION),
                "versao sem automacao");
        RunContext local = new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, FP, "x", null, null);
        assertNull(local.automationId());
        assertNull(local.automationVersionId());
    }

    /** Nenhum caminho do registro troca o contexto: todo metodo que devolve outro registro devolve o MESMO contexto. */
    @Test
    void nenhumMetodoDoRegistroTrocaOContexto() throws Exception {
        RunContext c = ctx();
        ExecutionId id = ExecutionId.random();
        ExecutionRecord r = running(id, c);
        List<ExecutionRecord> versions = new ArrayList<>(List.of(ExecutionRecord.ready(id, c, T0), r, r.withSuspension(), r.withLock(), r.adoptedAfterRestart(),
                r.withExit(0, 10), r.withOwner(null),
                r.withCancellation(new ExecutionRecord.Cancellation(T0, ExecutionRecord.CancelSource.LOCAL_UI, ExecutionRecord.CancelOutcome.PENDING))));
        for (ExecutionState end : EnumSet.of(ExecutionState.SUCCEEDED, ExecutionState.FAILED, ExecutionState.CANCELLED, ExecutionState.TIMED_OUT,
                ExecutionState.OUTCOME_UNKNOWN)) {
            versions.add(r.moveTo(end, null, T0.plusSeconds(9)));
        }
        for (ExecutionRecord v : versions) {
            assertEquals(c, v.context());
            assertEquals(id, v.id());
            assertEquals(FP, v.fingerprint());
        }
        // e a API nao oferece outro caminho: nenhum metodo publico de instancia recebe um contexto, um UUID ou um texto
        for (Method m : ExecutionRecord.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers()) || m.getReturnType() != ExecutionRecord.class) continue;
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(p == RunContext.class || p == UUID.class || p == String.class || p == PackageContext.class || p == ExecutionId.class,
                        m.getName() + " poderia reatribuir a execucao");
            }
        }
        for (RecordComponent rc : RunContext.class.getRecordComponents()) {
            assertTrue(Modifier.isFinal(RunContext.class.getDeclaredField(rc.getName()).getModifiers()));
        }
        assertEquals(0, java.util.Arrays.stream(RunContext.class.getDeclaredMethods()).filter(m -> !Modifier.isStatic(m.getModifiers())
                && m.getReturnType() == RunContext.class).count(), "o contexto nao tem metodo que devolva outro contexto");
        assertThrows(NullPointerException.class, () -> ExecutionRecord.ready(id, null, T0), "nao existe registro sem contexto");
    }

    @Test
    void cicloDeVidaFechado() {
        ExecutionRecord ready = ExecutionRecord.ready(ExecutionId.random(), ctx(), T0);
        assertThrows(IllegalStateException.class, () -> ready.moveTo(ExecutionState.RUNNING, null, T0), "READY nao pula para RUNNING");
        assertThrows(IllegalStateException.class, () -> ready.moveTo(ExecutionState.SUCCEEDED, null, T0));
        ExecutionRecord done = running(ExecutionId.random(), ctx()).moveTo(ExecutionState.SUCCEEDED, ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL, T0.plusSeconds(5));
        assertEquals(T0.plusSeconds(2), done.started());
        assertEquals(T0.plusSeconds(5), done.ended());
        for (ExecutionState s : ExecutionState.values()) {
            assertThrows(IllegalStateException.class, () -> done.moveTo(s, null, T0), "terminado nao muda: " + s);
            if (s.terminal()) assertEquals(EnumSet.noneOf(ExecutionState.class), s.next());
        }
        assertEquals("Em andamento", ExecutionState.RUNNING.label());
        assertEquals("Resultado desconhecido", ExecutionState.OUTCOME_UNKNOWN.label());
        assertTrue(ExecutionId.valid(ExecutionId.random().value()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionId("../fora"));
        assertNotEquals(ExecutionId.random(), ExecutionId.random());
    }

    @Test
    void registroVaiEVoltaDoDiscoComOContexto() throws Exception {
        ExecutionId id = ExecutionId.random();
        ExecutionRecord r = running(id, ctx()).withLock().withSuspension()
                .withCancellation(new ExecutionRecord.Cancellation(T0.plusSeconds(3), ExecutionRecord.CancelSource.APPLICATION_SHUTDOWN, ExecutionRecord.CancelOutcome.PENDING));
        runs.save(r);
        assertEquals(r, runs.record(id).orElseThrow());
        ExecutionRecord end = r.withExit(null, 77).moveTo(ExecutionState.CANCELLED, ErrorCode.EXEC_CANCELLED, T0.plusSeconds(4))
                .withCancellation(r.cancellation().resolved(ExecutionRecord.CancelOutcome.CONFIRMED));
        runs.save(end);
        assertEquals(end, runs.record(id).orElseThrow());
        String json = Files.readString(runs.dir(id).resolve("estado.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema\": \"nekkora-agent-client/execution\"") && json.contains("\"version\": \"1.0.0\""), json);
        assertTrue(json.contains("\"organizationId\": \"" + RunFixtures.ORG + "\"") && json.contains("\"workspaceId\": \"" + RunFixtures.WORKSPACE + "\"")
                && json.contains("\"fingerprint\": \"" + FP + "\"") && json.contains("\"localName\": \"Fechamento mensal \\u2014 S\\u00e3o Paulo\"")
                && json.contains("\"automationId\": \"" + RunFixtures.AUTOMATION + "\"")
                && json.contains("\"automationVersionId\": \"" + RunFixtures.AUTOMATION_VERSION + "\""), json);
        assertTrue(json.contains("\"source\": \"APPLICATION_SHUTDOWN\"") && json.contains("\"code\": \"AGT-EXE-024\""), json);
        assertFalse(json.contains("python.exe"), "o caminho do executavel nao vai para o registro (so o resumo)");
        // sem automacao: os dois campos vao como null e voltam como null
        ExecutionId local = ExecutionId.random();
        runs.save(ExecutionRecord.ready(local, new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, FP, "x", null, null), T0));
        assertNull(runs.record(local).orElseThrow().context().automationId());
    }

    /** O disco tambem nao deixa: na pasta de uma execucao so entra registro com o contexto que ja esta la. */
    @Test
    void gravarComOutroContextoERecusadoEORegistroFicaComoEstava() throws Exception {
        ExecutionId id = ExecutionId.random();
        RunContext c = ctx();
        ExecutionRecord r = running(id, c);
        runs.save(r);
        List<RunContext> others = List.of(
                new RunContext(RunFixtures.OTHER_ORG, c.workspaceId(), c.fingerprint(), c.localName(), c.automationId(), c.automationVersionId()),
                new RunContext(c.organizationId(), RunFixtures.OTHER_WORKSPACE, c.fingerprint(), c.localName(), c.automationId(), c.automationVersionId()),
                new RunContext(c.organizationId(), c.workspaceId(), OTHER_FP, c.localName(), c.automationId(), c.automationVersionId()),
                new RunContext(c.organizationId(), c.workspaceId(), c.fingerprint(), "outro nome", c.automationId(), c.automationVersionId()),
                new RunContext(c.organizationId(), c.workspaceId(), c.fingerprint(), c.localName(), null, null),
                new RunContext(c.organizationId(), c.workspaceId(), c.fingerprint(), c.localName(), c.automationId(), null),
                new RunContext(c.organizationId(), c.workspaceId(), c.fingerprint(), c.localName(), RunFixtures.AUTOMATION_VERSION, RunFixtures.AUTOMATION));
        byte[] before = Files.readAllBytes(runs.dir(id).resolve("estado.json"));
        for (RunContext other : others) {
            // execucao EM ANDAMENTO: nem com o mesmo estado, nem ao terminar
            ExecutionRecord moved = new ExecutionRecord(id, other, r.state(), r.code(), r.created(), r.started(), r.ended(), r.owner(), r.exitCode(), r.suspensions(),
                    r.locks(), r.adopted(), r.outputBytes(), r.cancellation());
            assertEquals(ErrorCode.EXEC_STORAGE_FAILED, assertThrows(ExecRefused.class, () -> runs.save(moved)).code(), other.toString());
            assertEquals(ErrorCode.EXEC_STORAGE_FAILED,
                    assertThrows(ExecRefused.class, () -> runs.save(moved.moveTo(ExecutionState.SUCCEEDED, null, T0.plusSeconds(8)))).code());
        }
        assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(runs.dir(id).resolve("estado.json")), "nenhum byte do registro mudou");
        assertEquals(c, runs.record(id).orElseThrow().context());
        // execucao TERMINADA: nem outro contexto, nem outro desfecho, nem "reabrir"
        ExecutionRecord end = r.withExit(0, 5).moveTo(ExecutionState.SUCCEEDED, ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL, T0.plusSeconds(9));
        runs.save(end);
        runs.save(end); // o mesmo registro de novo: sem efeito
        ExecutionRecord failed = r.withExit(1, 5).moveTo(ExecutionState.FAILED, ErrorCode.EXT_EXIT_NONZERO, T0.plusSeconds(9));
        assertThrows(ExecRefused.class, () -> runs.save(failed), "o desfecho gravado nao e trocado");
        assertThrows(ExecRefused.class, () -> runs.save(r), "um registro terminado nao volta a andar");
        ExecutionRecord endMoved = new ExecutionRecord(id, others.get(0), end.state(), end.code(), end.created(), end.started(), end.ended(), end.owner(), end.exitCode(),
                end.suspensions(), end.locks(), end.adopted(), end.outputBytes(), end.cancellation());
        assertThrows(ExecRefused.class, () -> runs.save(endMoved));
        assertEquals(end, runs.record(id).orElseThrow());
    }

    @Test
    void historicoDeUmContextoNaoMostraODeOutro() throws Exception {
        RunContext a = ctx();
        RunContext otherWorkspace = new RunContext(RunFixtures.ORG, RunFixtures.OTHER_WORKSPACE, FP, "x", null, null);
        RunContext otherOrg = new RunContext(RunFixtures.OTHER_ORG, RunFixtures.WORKSPACE, FP, "x", null, null);
        ExecutionRecord ra1 = ExecutionRecord.ready(ExecutionId.random(), a, T0);
        ExecutionRecord ra2 = ExecutionRecord.ready(ExecutionId.random(), a, T0.plusSeconds(10));
        ExecutionRecord rw = ExecutionRecord.ready(ExecutionId.random(), otherWorkspace, T0.plusSeconds(20));
        ExecutionRecord ro = ExecutionRecord.ready(ExecutionId.random(), otherOrg, T0.plusSeconds(30));
        for (ExecutionRecord r : List.of(ra1, ra2, rw, ro)) runs.save(r);
        assertEquals(List.of(ra2, ra1), runs.all(a.where()), "mais novas primeiro, so as do contexto");
        assertEquals(List.of(rw), runs.all(otherWorkspace.where()));
        assertEquals(List.of(ro), runs.all(otherOrg.where()));
        assertEquals(List.of(), runs.all(new PackageContext(RunFixtures.OTHER_ORG, RunFixtures.OTHER_WORKSPACE)));
        assertEquals(List.of(ro, rw, ra2, ra1), runs.all());
        assertEquals(List.of(rw), runs.list().in(otherWorkspace.where()).records());
    }

    /** Registro sem contexto completo e valido e ILEGIVEL: nunca aparece sem dono nem com dono presumido. */
    @Test
    void registroSemContextoValidoNaoELido() throws Exception {
        ExecutionId id = ExecutionId.random();
        runs.save(ExecutionRecord.ready(id, ctx(), T0));
        Path f = runs.dir(id).resolve("estado.json");
        String good = Files.readString(f, StandardCharsets.UTF_8);
        for (String bad : List.of(good.replace("\"organizationId\": \"" + RunFixtures.ORG + "\"", "\"organizationId\": \"../outra\""),
                good.replace("\"workspaceId\": \"" + RunFixtures.WORKSPACE + "\"", "\"workspaceId\": null"), good.replace(FP, "sha256:curta"),
                good.replaceAll("(?s)  \"context\": \\{.*?\\},\n", ""), good.replace("nekkora-agent-client/execution", "nekkora-agent/execution"))) {
            assertNotEquals(good, bad);
            Files.writeString(f, bad, StandardCharsets.UTF_8);
            assertTrue(runs.record(id).isEmpty(), bad);
            RunStore.Listing l = runs.list();
            assertEquals(List.of(), l.records());
            assertEquals(1, l.unreadable(), "existe, mas nao pode ser lido: a tela nao diz 'nenhuma execucao'");
        }
        // registro copiado para a pasta de OUTRA execucao: o identificador gravado nao e o da pasta
        Files.writeString(f, good, StandardCharsets.UTF_8);
        ExecutionId elsewhere = ExecutionId.random();
        Files.createDirectories(runs.dir(elsewhere));
        Files.copy(f, runs.dir(elsewhere).resolve("estado.json"));
        assertEquals(1, runs.list().records().size());
        assertEquals(1, runs.list().unreadable());
    }

    @Test
    void retencaoPorContextoENuncaDoQueEstaEmAndamento() throws Exception {
        RunContext a = ctx();
        RunContext b = new RunContext(RunFixtures.OTHER_ORG, RunFixtures.OTHER_WORKSPACE, FP, "x", null, null);
        List<ExecutionRecord> doneA = new ArrayList<>();
        for (int i = 0; i < RunStore.KEEP + 3; i++) {
            ExecutionRecord r = ExecutionRecord.ready(ExecutionId.random(), a, T0.plusSeconds(i)).moveTo(ExecutionState.BLOCKED, ErrorCode.EXEC_NOT_APPROVED, T0.plusSeconds(i));
            runs.save(r);
            doneA.add(r);
        }
        ExecutionRecord oldB = ExecutionRecord.ready(ExecutionId.random(), b, T0.minusSeconds(999)).moveTo(ExecutionState.BLOCKED, ErrorCode.EXEC_NOT_APPROVED, T0);
        ExecutionRecord oldRunningA = running(ExecutionId.random(), a);
        ExecutionRecord veryOld = new ExecutionRecord(oldRunningA.id(), a, oldRunningA.state(), null, T0.minusSeconds(5000), oldRunningA.started(), null,
                oldRunningA.owner(), null, 0, 0, false, 0, null);
        runs.save(oldB);
        runs.save(veryOld);
        runs.prune();
        assertEquals(RunStore.KEEP + 1, runs.all(a.where()).size(), "as 50 terminadas mais novas e a que esta em andamento");
        assertTrue(runs.record(veryOld.id()).isPresent(), "nada em andamento e apagado");
        assertTrue(runs.record(doneA.get(0).id()).isEmpty() && runs.record(doneA.get(2).id()).isEmpty(), "as tres terminadas mais antigas de A sairam");
        assertTrue(runs.record(doneA.get(3).id()).isPresent());
        assertTrue(runs.record(oldB.id()).isPresent(), "o historico de outro contexto nao e apagado pelo volume deste");
    }

    @Test
    void eventosTemLimiteEMarcador() {
        ExecutionId id = ExecutionId.random();
        for (int i = 0; i < RunStore.MAX_EVENTS + 20; i++) runs.event(id, T0.plusSeconds(i), "E", java.util.Map.of("n", i));
        List<String> ev = runs.events(id);
        assertEquals(RunStore.MAX_EVENTS, ev.size());
        assertTrue(ev.get(ev.size() - 1).contains(RunStore.LIMIT_MARKER));
        assertEquals(List.of(), runs.events(ExecutionId.random()));
        assertEquals(new RunStore.Listing(List.of(), 0, false), new RunStore(tmp.resolve("nao-existe"), AtomicWriter.DEFAULT).list());
    }
}
