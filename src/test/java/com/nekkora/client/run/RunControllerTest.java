package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.ExecutionPackage;
import com.nekkora.client.pack.ImportedProject;
import com.nekkora.client.pack.JsonReader;
import com.nekkora.client.pack.LocalPackages;
import com.nekkora.client.pack.PackageContext;
import com.nekkora.client.pack.PackagePlan;
import com.nekkora.client.pack.ProjectImporter;
import com.nekkora.client.pack.SafeZip;
import com.nekkora.client.pack.ZipLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Execucao local com processos REAIS: o interpretador de {@code NEKKORA_TEST_PYTHON} rodando scripts gerados na pasta
 * temporaria do teste. Sem a variavel, os testes sao PULADOS (e contados como pulados), nunca dados por aprovados.
 */
class RunControllerTest {
    @TempDir
    Path tmp;

    Path python;
    Path dataDir;
    Path out;
    RunFixtures.ShiftClock clock;
    RunFixtures.FakeSession session;
    ApprovalLedger approvals;
    RunStore runs;
    RunController controller;

    @BeforeEach
    void setUp() throws Exception {
        python = RunFixtures.python();
        Assumptions.assumeTrue(python != null, RunFixtures.SKIP);
        dataDir = tmp.resolve("dados");
        out = Files.createDirectories(tmp.resolve("saida"));
        clock = new RunFixtures.ShiftClock();
        session = new RunFixtures.FakeSession();
        approvals = new ApprovalLedger(ApprovalLedger.defaultFile(dataDir), AtomicWriter.DEFAULT);
        runs = new RunStore(RunStore.defaultRoot(dataDir), AtomicWriter.DEFAULT);
        controller = new RunController(runs, approvals, session, clock, dataDir, Duration.ofMillis(100), Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        if (controller != null) controller.close();
    }

    ExecutionPackage approved(String name, String main) throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pkg-" + name), name, main));
        approvals.approve(p, clock.instant());
        return p;
    }

    RunController.Request request(ExecutionPackage p, Duration timeout) {
        return new RunController.Request(p.dir(), RunFixtures.context(p), python, out, timeout);
    }

    ExecutionRecord waitEnd(RunController c, Duration max) throws Exception {
        long deadline = System.nanoTime() + max.toNanos();
        while (c.isRunning() && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(c.isRunning(), "terminou no prazo do teste");
        return c.current().orElseThrow().record();
    }

    ExecutionRecord waitEnd(Duration max) throws Exception {
        return waitEnd(controller, max);
    }

    /** Tudo o que o aplicativo gravou (registros, eventos, aprovacoes), para procurar vazamento. A copia do pacote fica de fora. */
    String appFiles() throws Exception {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> s = Files.walk(dataDir)) {
            for (Path p : s.filter(Files::isRegularFile).filter(f -> !f.toString().contains(java.io.File.separator + "pacote" + java.io.File.separator)
                    && !f.getFileName().toString().endsWith(".py")).toList()) {
                b.append(Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return b.toString();
    }

    private Map<String, Long> waitPids() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Stream<Path> s = Files.walk(out)) {
                for (Path p : s.filter(f -> f.getFileName().toString().equals("pids.json")).toList()) {
                    try {
                        Map<String, Object> m = JsonReader.object(Files.readString(p));
                        if (m.get("filho") instanceof Long filho && m.get("pai") instanceof Long pai) return Map.of("pai", pai, "filho", filho);
                    } catch (JsonReader.Invalid | IOException e) {
                        // ainda sendo gravado
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("pids.json nao apareceu");
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test
    @DisplayName("sucesso sinalizado pelo script; roda da copia conferida, sem argumento de fora, com ambiente minimo; a saida nao e guardada")
    void sucesso() throws Exception {
        List<RunController.Snapshot> seen = new CopyOnWriteArrayList<>();
        controller.addListener(seen::add);
        ExecutionPackage p = approved("sucesso", RunFixtures.SUCESSO);
        RunController.Snapshot s = controller.start(request(p, null));
        assertEquals(ExecutionState.RUNNING, s.record().state());
        assertTrue(s.running());
        assertNotNull(s.record().owner());
        assertEquals(RunFixtures.context(p), s.context());
        ExecutionRecord r = waitEnd(Duration.ofSeconds(60));
        assertEquals(ExecutionState.SUCCEEDED, r.state(), String.valueOf(r.code()));
        assertNull(r.code(), "sucesso de negocio sinalizado: sem ressalva");
        assertEquals(0, r.exitCode());
        assertEquals(RunFixtures.context(p), r.context());
        assertTrue(r.outputBytes() > 0, "saida drenada e contada");
        assertFalse(appFiles().contains("SENTINELA-VALOR-DA-AUTOMACAO"), "nenhum valor da automacao no que o aplicativo grava");
        List<String> ev = runs.events(r.id());
        assertTrue(ev.get(0).contains("STARTING") && ev.stream().anyMatch(l -> l.contains("\"RUNNING\"")) && ev.get(ev.size() - 1).contains("SUCCEEDED")
                && ev.get(ev.size() - 1).contains("BUSINESS_SUCCESS"), ev.toString());
        Path copy = runs.dir(r.id()).resolve("pacote");
        assertTrue(Files.isRegularFile(copy.resolve("main.py")), "rodou da copia conferida");
        Path output = out.resolve(r.id().value());
        assertEquals(output, runs.output(r.id()).orElseThrow(), "uma subpasta por execucao dentro da pasta escolhida");
        Map<String, Object> onde = JsonReader.object(Files.readString(output.resolve("onde.json")));
        assertEquals(copy.toRealPath(), Path.of((String) onde.get("cwd")).toRealPath(), "a pasta de trabalho e a copia");
        assertEquals(List.of("main.py"), onde.get("argv"), "nenhum argumento alem da entrada");
        assertEquals(List.of(1L, 1L, 1L), onde.get("flags"), "-E -s -B");
        List<?> env = (List<?>) onde.get("env");
        for (Object k : env) {
            String key = String.valueOf(k).toUpperCase(java.util.Locale.ROOT);
            assertTrue(PythonRuntime.ENV_KEEP.contains(key) || Set.of("PYTHONIOENCODING", "PYTHONUTF8", "NEKKORA_SAIDA", "NEKKORA_SINAL").contains(key),
                    "variavel fora da lista fechada: " + k);
        }
        assertTrue(env.contains("NEKKORA_SAIDA") && env.contains("NEKKORA_SINAL"));
        assertFalse(env.contains(RunFixtures.PYTHON_ENV), "a variavel do teste existe neste processo e nao foi herdada");
        assertTrue(seen.stream().anyMatch(RunController.Snapshot::running) && !seen.get(seen.size() - 1).running());
        assertTrue(seen.stream().allMatch(x -> x.context().equals(RunFixtures.context(p))));
        assertTrue(controller.running().isEmpty());
        assertTrue(controller.current().orElseThrow().recordSaved());
    }

    @Test
    @DisplayName("codigo 0 sem sinal: termino sem erro sinalizado (AGT-EXT-001); codigo diferente de 0 e sinal de falha: FAILED")
    void desfechos() throws Exception {
        controller.start(request(approved("sem-sinal", RunFixtures.SEM_SINAL), null));
        ExecutionRecord u = waitEnd(Duration.ofSeconds(60));
        assertEquals(ExecutionState.SUCCEEDED, u.state());
        assertEquals(ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL, u.code(), "codigo 0 sozinho nao e sucesso de negocio");
        controller.start(request(approved("codigo-3", RunFixtures.CODIGO_3), null));
        ExecutionRecord f = waitEnd(Duration.ofSeconds(60));
        assertEquals(ExecutionState.FAILED, f.state());
        assertEquals(ErrorCode.EXT_EXIT_NONZERO, f.code());
        assertEquals(3, f.exitCode());
        controller.start(request(approved("falha-de-negocio", RunFixtures.FALHA_DE_NEGOCIO), null));
        ExecutionRecord b = waitEnd(Duration.ofSeconds(60));
        assertEquals(ExecutionState.FAILED, b.state());
        assertEquals(ErrorCode.EXT_BUSINESS_FAILURE, b.code());
        assertEquals(0, b.exitCode());
        assertEquals(3, runs.all(new PackageContext(RunFixtures.ORG, RunFixtures.WORKSPACE)).size());
    }

    @Test
    @DisplayName("bloqueios que dependem do Python: versao fora da faixa, dependencia ausente ou em outra versao, pasta de saida invalida")
    void bloqueiosDoAmbiente() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.py", RunFixtures.SEM_SINAL);
        ExecutionPackage oldPython = ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pkg-py39"), "py39", files, "main.py", "==3.9.*", List.of()));
        approvals.approve(oldPython, clock.instant());
        assertEquals(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, assertThrows(ExecRefused.class, () -> controller.start(request(oldPython, null))).code());
        ExecutionPackage missingDep = ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pkg-dep"), "dep", files, "main.py", ">=3.10",
                List.of("nekkora-pacote-que-nao-existe==1.2.3")));
        approvals.approve(missingDep, clock.instant());
        assertEquals(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, assertThrows(ExecRefused.class, () -> controller.start(request(missingDep, null))).code());
        // conferencias avulsas, como a tela faz antes de habilitar "Iniciar"
        PythonRuntime.Probe probe = PythonRuntime.probe(python, controller.probeDir());
        assertTrue(probe.pythonVersion().matches("3\\.[0-9]+\\.[0-9]+"), probe.pythonVersion());
        assertEquals(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, assertThrows(ExecRefused.class, () -> PythonRuntime.requireCompatible(probe, oldPython)).code());
        assertEquals(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, assertThrows(ExecRefused.class, () -> PythonRuntime.requireDependencies(probe.python(), missingDep)).code());
        ExecutionPackage ok = approved("ok", RunFixtures.SEM_SINAL);
        PythonRuntime.requireCompatible(probe, ok);
        PythonRuntime.requireDependencies(probe.python(), ok);
        // nada foi instalado para "resolver" a dependencia ausente: a mesma conferencia continua recusando
        assertEquals(ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE, assertThrows(ExecRefused.class, () -> PythonRuntime.requireDependencies(probe.python(), missingDep)).code());
        // pasta de saida: precisa existir e ficar fora do pacote e da pasta de dados do aplicativo (nem conte-la)
        for (Path bad : List.of(tmp.resolve("nao-existe"), ok.dir(), dataDir, dataDir.resolve("execucoes"), tmp)) {
            RunController.Request r = new RunController.Request(ok.dir(), RunFixtures.context(ok), python, bad, null);
            assertEquals(ErrorCode.EXEC_OUTPUT_INVALID, assertThrows(ExecRefused.class, () -> controller.start(r)).code(), bad.toString());
        }
        assertFalse(controller.isRunning());
        assertTrue(runs.all().stream().allMatch(r -> r.state() == ExecutionState.BLOCKED && r.owner() == null), "todos BLOCKED, sem processo do pacote");
        assertEquals(7, runs.all().size());
    }

    @Test
    @DisplayName("uma execucao por vez: pedido concorrente recusado sem registro; cancelar encerra a arvore possuida (filho incluido)")
    void concorrenciaECancelamento() throws Exception {
        ExecutionPackage slow = approved("lento", RunFixtures.LENTO);
        ExecutionId id = controller.start(request(slow, null)).record().id();
        assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, assertThrows(ExecRefused.class, () -> controller.start(request(slow, null))).code());
        ExecutionPackage other = approved("outro", RunFixtures.SUCESSO);
        assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, assertThrows(ExecRefused.class, () -> controller.start(request(other, null))).code());
        RunController.Snapshot now = controller.running().orElseThrow();
        assertEquals(id, now.record().id());
        assertEquals(RunFixtures.context(slow), now.context(), "running() diz de quem e a execucao ativa");
        Map<String, Long> pids = waitPids();
        assertTrue(alive(pids.get("pai")) && alive(pids.get("filho")));
        assertEquals(RunController.CancelResult.REQUESTED, controller.cancel(id, ExecutionRecord.CancelSource.LOCAL_UI));
        assertEquals(RunController.CancelResult.ALREADY_REQUESTED, controller.cancel(id, ExecutionRecord.CancelSource.APPLICATION_SHUTDOWN), "idempotente");
        assertTrue(controller.cancel(), "e o atalho da janela tambem");
        ExecutionRecord r = waitEnd(Duration.ofSeconds(30));
        assertEquals(ExecutionState.CANCELLED, r.state());
        assertEquals(ErrorCode.EXEC_CANCELLED, r.code());
        assertEquals(ExecutionRecord.CancelSource.LOCAL_UI, r.cancellation().source(), "o primeiro pedido vale");
        assertEquals(ExecutionRecord.CancelOutcome.CONFIRMED, r.cancellation().outcome());
        for (long pid : pids.values()) assertFalse(alive(pid), "processo " + pid + " encerrado");
        assertEquals(1, runs.all().size(), "o pedido recusado nao criou execucao");
        assertEquals(RunController.CancelResult.ALREADY_ENDED, controller.cancel(id, ExecutionRecord.CancelSource.LOCAL_UI));
        List<String> ev = runs.events(id);
        assertEquals(1, ev.stream().filter(l -> l.contains("CANCEL_REQUESTED")).count(), ev.toString());
        assertTrue(ev.stream().anyMatch(l -> l.contains("CANCEL_CONFIRMED")) && ev.stream().anyMatch(l -> l.contains("TERMINATED")), ev.toString());
        // a vaga foi liberada: outra execucao pode comecar
        controller.start(request(other, null));
        assertEquals(ExecutionState.SUCCEEDED, waitEnd(Duration.ofSeconds(60)).state());
    }

    @Test
    @DisplayName("prazo esgotado encerra a arvore e registra TIMED_OUT")
    void prazo() throws Exception {
        controller.start(request(approved("lento", RunFixtures.LENTO), Duration.ofSeconds(3)));
        Map<String, Long> pids = waitPids();
        ExecutionRecord r = waitEnd(Duration.ofSeconds(40));
        assertEquals(ExecutionState.TIMED_OUT, r.state());
        assertEquals(ErrorCode.EXEC_TIMEOUT, r.code());
        assertNull(r.cancellation(), "ninguem pediu cancelamento");
        for (long pid : pids.values()) assertFalse(alive(pid));
    }

    @Test
    @DisplayName("saida volumosa (mais de 20 MiB em saida e erros) nao trava; so a contagem de bytes fica")
    void volume() throws Exception {
        controller.start(request(approved("volumoso", RunFixtures.VOLUMOSO), null));
        ExecutionRecord r = waitEnd(Duration.ofSeconds(120));
        assertEquals(ExecutionState.SUCCEEDED, r.state(), String.valueOf(r.code()));
        assertTrue(r.outputBytes() > 20_000_000L, String.valueOf(r.outputBytes()));
        assertFalse(appFiles().contains("SENTINELA-VALOR-DA-AUTOMACAO"));
    }

    @Test
    @DisplayName("tela bloqueada e suspensao durante a execucao sao registradas; processo perdido sem sinal: OUTCOME_UNKNOWN")
    void bloqueioESuspensao() throws Exception {
        controller.start(request(approved("lento", RunFixtures.LENTO), null));
        Map<String, Long> pids = waitPids();
        session.locked.set(true);
        Thread.sleep(600);
        session.locked.set(false);
        Thread.sleep(600);
        clock.shift.addAndGet(Duration.ofMinutes(5).toMillis()); // a maquina "dormiu" 5 minutos
        Thread.sleep(600);
        // o processo morre durante ou depois da suspensao sem publicar sinal
        for (long pid : pids.values()) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        ExecutionRecord r = waitEnd(Duration.ofSeconds(30));
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, r.state(), "sem prova, nada presumido");
        assertEquals(ErrorCode.EXEC_SUSPENDED, r.code());
        assertEquals(1, r.suspensions());
        assertEquals(1, r.locks());
        List<String> ev = runs.events(r.id());
        assertTrue(ev.stream().anyMatch(l -> l.contains("SESSION_LOCKED")) && ev.stream().anyMatch(l -> l.contains("SESSION_UNLOCKED"))
                && ev.stream().anyMatch(l -> l.contains("SUSPENSION_DETECTED")), ev.toString());
    }

    @Test
    @DisplayName("encerramento do aplicativo com execucao ativa: cancelamento com origem APPLICATION_SHUTDOWN, confirmado; sem execucao, volta logo")
    void encerramentoDoAplicativo() throws Exception {
        long t0 = System.nanoTime();
        controller.close();
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(2), "sem execucao ativa, close() nao espera");
        ExecutionId id = controller.start(request(approved("lento", RunFixtures.LENTO), null)).record().id();
        Map<String, Long> pids = waitPids();
        long t1 = System.nanoTime();
        controller.close();
        assertTrue(System.nanoTime() - t1 < RunController.CLOSE_WAIT.plusSeconds(2).toNanos(), "close() espera no maximo 15 s");
        assertFalse(controller.isRunning(), "close() so volta com a execucao fechada");
        ExecutionRecord r = runs.record(id).orElseThrow();
        assertEquals(ExecutionState.CANCELLED, r.state(), "o registro esta no disco quando close() volta");
        assertEquals(ErrorCode.EXEC_CANCELLED, r.code());
        assertEquals(ExecutionRecord.CancelSource.APPLICATION_SHUTDOWN, r.cancellation().source());
        assertEquals(ExecutionRecord.CancelOutcome.CONFIRMED, r.cancellation().outcome());
        for (long pid : pids.values()) assertFalse(alive(pid), "nenhum processo fica para tras");
        assertTrue(ExecutionSetup.cancellationLabel(r.cancellation()).startsWith("Confirmado · pedido pelo encerramento do Agent às "));
        assertEquals(Duration.ofSeconds(15), RunController.CLOSE_WAIT);
    }

    @Test
    @DisplayName("contexto imutavel: renomear, associar, remover o pacote ou 'trocar de organizacao' durante a execucao nao muda de quem ela e")
    void contextoImutavelDuranteEDepoisDaExecucao() throws Exception {
        PackageContext where = new PackageContext(RunFixtures.ORG, RunFixtures.WORKSPACE);
        LocalPackages store = new LocalPackages(LocalPackages.defaultRoot(dataDir), AtomicWriter.DEFAULT, Clock.systemUTC());
        Path src = RunFixtures.pkg(tmp.resolve("src-lento"), "lento", RunFixtures.LENTO);
        LocalPackages.Item item = store.add(where, zipOf(src, tmp.resolve("lento.zip")), SafeZip.Cancel.NEVER).item();
        item = store.rename(where, item.fingerprint(), "Nome no início");
        item = store.link(where, item.fingerprint(), RunFixtures.AUTOMATION);
        ExecutionPackage pkg = store.inspect(where, item.fingerprint());
        approvals.approve(pkg, clock.instant());
        RunContext context = RunContext.of(item, RunFixtures.AUTOMATION_VERSION);
        assertEquals(new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, item.fingerprint(), "Nome no início", RunFixtures.AUTOMATION, RunFixtures.AUTOMATION_VERSION),
                context);
        ExecutionId id = controller.start(new RunController.Request(item.dir(), context, python, out, null)).record().id();
        waitPids();
        // durante a execucao: o item muda de nome, perde a associacao e e removido; a pessoa "vai" para outro contexto
        store.rename(where, item.fingerprint(), "Outro nome");
        store.unlink(where, item.fingerprint());
        assertTrue(store.remove(where, item.fingerprint()), "remover o pacote guardado nao alcanca a execucao: ela roda da copia");
        PackageContext elsewhere = new PackageContext(RunFixtures.OTHER_ORG, RunFixtures.OTHER_WORKSPACE);
        assertEquals(List.of(), runs.all(elsewhere), "no outro contexto a execucao nao aparece");
        assertEquals(context, controller.running().orElseThrow().context());
        assertTrue(controller.running().orElseThrow().context().in(where) && !controller.running().orElseThrow().context().in(elsewhere),
                "a tela usa isto para BLOQUEAR a troca de contexto e a saida da sessao enquanto ha execucao");
        assertEquals(context, runs.record(id).orElseThrow().context());
        // ninguem a reatribui pelo disco
        ExecutionRecord onDisk = runs.record(id).orElseThrow();
        ExecutionRecord moved = new ExecutionRecord(id, new RunContext(RunFixtures.OTHER_ORG, RunFixtures.OTHER_WORKSPACE, item.fingerprint(), "Outro nome", null, null),
                onDisk.state(), onDisk.code(), onDisk.created(), onDisk.started(), onDisk.ended(), onDisk.owner(), onDisk.exitCode(), onDisk.suspensions(),
                onDisk.locks(), onDisk.adopted(), onDisk.outputBytes(), onDisk.cancellation());
        assertThrows(ExecRefused.class, () -> runs.save(moved));
        assertTrue(controller.cancel());
        ExecutionRecord end = waitEnd(Duration.ofSeconds(30));
        assertEquals(ExecutionState.CANCELLED, end.state());
        assertEquals(context, end.context(), "o registro final tem o contexto do inicio");
        assertEquals(context, runs.record(id).orElseThrow().context());
        assertEquals(List.of(end), runs.all(where));
        assertEquals(List.of(), runs.all(elsewhere));
        assertThrows(ExecRefused.class, () -> runs.save(new ExecutionRecord(id, moved.context(), end.state(), end.code(), end.created(), end.started(), end.ended(),
                end.owner(), end.exitCode(), end.suspensions(), end.locks(), end.adopted(), end.outputBytes(), end.cancellation())), "nem depois de terminada");
        assertEquals(context, runs.record(id).orElseThrow().context());
    }

    @Test
    @DisplayName("do ZIP de codigo ao resultado: importar, empacotar e guardar nao executam nada; so o inicio aprovado executa")
    void doZipDeCodigoAoResultado() throws Exception {
        PackageContext where = new PackageContext(RunFixtures.ORG, RunFixtures.WORKSPACE);
        Path project = Files.createDirectories(tmp.resolve("projeto"));
        Files.writeString(project.resolve("main.py"), RunFixtures.SUCESSO);
        Files.writeString(project.resolve("comum.py"), RunFixtures.COMUM);
        Files.writeString(project.resolve("setup.py"), "open('SENTINELA-EXECUTOU.txt', 'w').close()\n");
        Path zip = zipOf(project, tmp.resolve("projeto.zip"));
        long children = ProcessHandle.current().children().count();
        ProjectImporter importer = new ProjectImporter(ProjectImporter.defaultRoot(dataDir), ZipLimits.DEFAULT);
        ImportedProject imported = importer.open(zip, SafeZip.Cancel.NEVER);
        assertEquals(ImportedProject.Kind.CODE, imported.kind());
        assertEquals("main.py", imported.suggestedEntry().orElseThrow());
        LocalPackages store = new LocalPackages(LocalPackages.defaultRoot(dataDir), AtomicWriter.DEFAULT, Clock.systemUTC());
        LocalPackages.Item item = store.addBuilt(where, imported, new PackagePlan("relatorio", "1.0.0", "main.py", ">=3.10", List.of(), true, Set.of(), Set.of(), false),
                SafeZip.Cancel.NEVER).item();
        importer.close(imported);
        assertEquals(children, ProcessHandle.current().children().count(), "importar, empacotar e guardar nao criam processo");
        ExecutionPackage pkg = store.inspect(where, item.fingerprint());
        // guardado, mas nao aprovado: nao comeca
        RunController.Request req = new RunController.Request(item.dir(), RunContext.of(item, null), python, out, null);
        assertEquals(ErrorCode.EXEC_NOT_APPROVED, assertThrows(ExecRefused.class, () -> controller.start(req)).code());
        approvals.approve(pkg, clock.instant());
        assertFalse(controller.isRunning(), "aprovar nao inicia");
        controller.start(req);
        ExecutionRecord r = waitEnd(Duration.ofSeconds(60));
        assertEquals(ExecutionState.SUCCEEDED, r.state(), String.valueOf(r.code()));
        assertEquals("relatorio", r.context().localName(), "sem nome local, o nome declarado");
        assertNull(r.context().automationId());
        try (Stream<Path> s = Files.walk(tmp)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().equals("SENTINELA-EXECUTOU.txt")), "o setup.py do projeto nunca rodou");
        }
        // o pacote guardado foi alterado por fora depois da aprovacao: a conferencia do inicio recusa
        Files.writeString(item.dir().resolve("main.py"), "print('trocado')\n");
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> controller.start(req)).code());
    }

    @Test
    @DisplayName("reinicio do aplicativo: processo ainda vivo e ADOTADO com o contexto original (e cancelavel); morto: OUTCOME_UNKNOWN; nada e relancado")
    void reinicio() throws Exception {
        // 1) "o aplicativo caiu" depois de lancar: processo orfao vivo + registro RUNNING
        ExecutionPackage slow = approved("lento", RunFixtures.LENTO);
        RunContext slowContext = new RunContext(RunFixtures.ORG, RunFixtures.WORKSPACE, slow.fingerprint(), "Lento", RunFixtures.AUTOMATION, null);
        List<Process> started = new ArrayList<>();
        ExecutionId id = ExecutionId.random();
        Process orphan = raw(slow, id, started);
        ProcessOwner owner = ProcessOwner.of(orphan, python.toString());
        runs.save(ExecutionRecord.ready(id, slowContext, clock.instant()).moveTo(ExecutionState.STARTING, null, clock.instant()).withOwner(owner)
                .moveTo(ExecutionState.RUNNING, null, clock.instant()));
        // 2) uma execucao de efeito NAO idempotente cujo processo morreu com o aplicativo
        ExecutionPackage effect = approved("efeito", RunFixtures.EFEITO);
        ExecutionId id2 = ExecutionId.random();
        Process gone = raw(effect, id2, started);
        Path efeitos = out.resolve("efeitos.txt");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(efeitos) && System.nanoTime() < deadline) Thread.sleep(50);
        runs.save(ExecutionRecord.ready(id2, RunFixtures.context(effect), clock.instant()).moveTo(ExecutionState.STARTING, null, clock.instant())
                .withOwner(ProcessOwner.of(gone, python.toString())).moveTo(ExecutionState.RUNNING, null, clock.instant()));
        gone.destroyForcibly().waitFor();
        // 3) registro STARTING sem processo (queda entre gravar e lancar) e 4) registro READY
        ExecutionId id3 = ExecutionId.random();
        runs.save(ExecutionRecord.ready(id3, RunFixtures.context(slow), clock.instant()).moveTo(ExecutionState.STARTING, null, clock.instant()));
        ExecutionId id4 = ExecutionId.random();
        runs.save(ExecutionRecord.ready(id4, RunFixtures.context(slow), clock.instant()));
        RunController restarted = new RunController(runs, approvals, session, clock, dataDir, Duration.ofMillis(100), Duration.ofMillis(200));
        try {
            assertEquals(4, restarted.reconcile());
            RunController.Snapshot adopted = restarted.running().orElseThrow();
            assertEquals(id, adopted.record().id(), "o processo vivo foi adotado");
            assertTrue(adopted.record().adopted());
            assertEquals(slowContext, adopted.context(), "com o contexto do inicio");
            assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, assertThrows(ExecRefused.class, () -> restarted.start(request(effect, null))).code(), "a vaga unica continua ocupada");
            Thread.sleep(1500);
            assertEquals(1, Files.readAllLines(efeitos).size(), "o efeito nao idempotente NAO foi repetido");
            assertEquals(ExecutionState.OUTCOME_UNKNOWN, runs.record(id2).orElseThrow().state());
            assertEquals(ErrorCode.EXEC_AGENT_RESTARTED, runs.record(id2).orElseThrow().code());
            assertEquals(ExecutionState.OUTCOME_UNKNOWN, runs.record(id3).orElseThrow().state(), "STARTING sem processo: pode ter comecado; nada presumido");
            assertEquals(ExecutionState.BLOCKED, runs.record(id4).orElseThrow().state(), "READY: nada tinha sido lancado");
            assertEquals(0, restarted.reconcile(), "reconciliar de novo nao toca a execucao adotada nem as fechadas");
            assertTrue(restarted.cancel());
            ExecutionRecord end = waitEnd(restarted, Duration.ofSeconds(30));
            assertFalse(orphan.isAlive(), "a adocao permite cancelar o processo possuido");
            assertEquals(ExecutionState.CANCELLED, end.state());
            assertEquals(slowContext, end.context());
        } finally {
            restarted.close();
            for (Process p : started) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        }
    }

    @Test
    @DisplayName("falha ao gravar o registro depois do lancamento nao solta o processo nem muda o desfecho; a tela e avisada e a gravacao e tentada de novo")
    void falhaDeGravacaoDepoisDoLancamento() throws Exception {
        AtomicBoolean failing = new AtomicBoolean();
        RunStore flaky = new RunStore(RunStore.defaultRoot(dataDir), (target, data) -> {
            if (failing.get() && target.getFileName().toString().equals("estado.json")) throw new IOException("arquivo em uso");
            AtomicWriter.DEFAULT.write(target, data);
        });
        RunController c = new RunController(flaky, approvals, session, clock, dataDir, Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(2));
        try {
            ExecutionId id = c.start(request(approved("lento", RunFixtures.LENTO), null)).record().id();
            Map<String, Long> pids = waitPids();
            failing.set(true);
            assertTrue(c.cancel());
            ExecutionRecord end = waitEnd(c, Duration.ofSeconds(30));
            assertEquals(ExecutionState.CANCELLED, end.state(), "o desfecho e o decidido, mesmo sem disco");
            for (long pid : pids.values()) assertFalse(alive(pid), "o processo foi encerrado mesmo sem conseguir gravar");
            assertFalse(c.current().orElseThrow().recordSaved(), "a tela sabe que o estado mostrado nao esta no disco");
            assertFalse(flaky.record(id).orElseThrow().state().terminal(), "nada incerto e dado por gravado");
            failing.set(false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!c.current().orElseThrow().recordSaved() && System.nanoTime() < deadline) {
                c.close(); // uma nova ocasiao (encerramento) tenta de novo
                Thread.sleep(100);
            }
            assertTrue(c.current().orElseThrow().recordSaved());
            assertEquals(end, flaky.record(id).orElseThrow());
        } finally {
            c.close();
        }
    }

    /** Processo lancado "por fora" (como se o aplicativo tivesse caido depois de lanca-lo): mesmo comando e mesmas variaveis. */
    private Process raw(ExecutionPackage p, ExecutionId id, List<Process> started) throws Exception {
        Path output = Files.createDirectories(out.resolve(id.value()));
        ProcessBuilder pb = new ProcessBuilder(python.toString(), "-E", "-s", "-B", "main.py").directory(p.dir().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        PythonRuntime.minimalEnvironment(pb.environment());
        pb.environment().put(ExternalSignal.OUTPUT_ENV, output.toString());
        pb.environment().put(ExternalSignal.SIGNAL_ENV, output.resolve(ExternalSignal.SIGNAL_FILE).toString());
        Process proc = pb.start();
        started.add(proc);
        return proc;
    }

    /** ZIP comum de uma pasta (arquivos na raiz do ZIP). */
    static Path zipOf(Path folder, Path zip) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8); Stream<Path> s = Files.walk(folder)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                z.putNextEntry(new ZipEntry(folder.relativize(f).toString().replace('\\', '/')));
                z.write(Files.readAllBytes(f));
                z.closeEntry();
            }
        }
        return zip;
    }
}
