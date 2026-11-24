package com.nekkora.client.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * AMBIENTE-PYTHON-001 - o ambiente gerenciado do lado do canal da maquina: a capacidade so depois da presenca dizer que a Platform
 * a oferece; o modo PREPARED igual ao de sempre; a ordem do preparo (diario em ENV_PREPARING antes de criar, etapas, relatorio,
 * STARTING, so entao o lancamento); a falha (relatorio antes da recusa, nada iniciado); o reinicio no meio do preparo; a presenca e a
 * consulta seguindo com o preparo parado; a renovacao a cada 30 s; o cancelamento; e a resolucao. Canal simulado em HTTPS de
 * verdade, protecao REAL do Windows; quem prepara e simulado aqui (o preparo de verdade, com Python e pip, esta em
 * {@code ManagedEnvironmentsTest} e {@code ManagedEnvironmentWorkTest}).
 */
@EnabledOnOs(OS.WINDOWS)
class ManagedEnvironmentDeliveryTest {

    private static final String LOCAL_PYTHON = "computador:c:\\python312\\python.exe";
    private static final String SHA_LOCK = "a".repeat(64);
    private static final String ENVIRONMENT = "{\"mode\":\"MANAGED\",\"index\":\"PYPI\",\"lockSha256\":\"" + SHA_LOCK + "\",\"lock\":[{\"name\":\"nekkora-fixture-a\","
            + "\"version\":\"1.2.3\",\"sha256\":\"" + "b".repeat(64) + "\",\"fileName\":\"nekkora_fixture_a-1.2.3-py3-none-any.whl\"}],\"checks\":[]}";

    /** Quem executa E prepara, simulado: o teste diz o que o preparo devolve e pode segura-lo. */
    static final class EnvWork extends RemoteDeliveryTest.FakeWork {
        final Path journal;
        volatile boolean manages = true;
        volatile RemoteMachine.Prepared prepared;
        volatile CountDownLatch hold;
        volatile boolean observedCancel;
        final List<String> journalAtPrepare = new CopyOnWriteArrayList<>();
        final List<String> journalAtStart = new CopyOnWriteArrayList<>();
        final List<RemoteMachine.PrepareJob> prepares = new CopyOnWriteArrayList<>();
        final List<String> resolutions = new CopyOnWriteArrayList<>();
        volatile RemoteMachine.Resolved resolved = new RemoteMachine.Resolved("RESOLVED", "3.12.10", "25.0.1",
                List.of(new RemoteMachine.LockItem("nekkora-fixture-a", "1.2.3", "b".repeat(64), "nekkora_fixture_a-1.2.3-py3-none-any.whl")), null);
        final AtomicLong idles = new AtomicLong();

        EnvWork(Path root, Path journal) {
            super(root);
            this.journal = journal;
            onStart = () -> journalAtStart.add(read(journal));
        }

        static String read(Path f) {
            try {
                return Files.readString(f, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                return "";
            }
        }

        @Override
        public boolean managesEnvironments() {
            return manages;
        }

        @Override
        public RemoteMachine.Prepared prepareEnvironment(RemoteMachine.PrepareJob job, java.util.function.Consumer<String> stage, BooleanSupplier cancel) {
            prepares.add(job);
            journalAtPrepare.add(read(journal));
            stage.accept("ENV_CREATING");
            stage.accept("DEPS_INSTALLING");
            CountDownLatch h = hold;
            if (h != null) {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (h.getCount() > 0 && System.nanoTime() < end) {
                    if (cancel.getAsBoolean()) {
                        observedCancel = true;
                        return new RemoteMachine.Prepared("CANCELLED", null, "3.12.10", "25.0.1", List.of(), new RemoteMachine.EnvFailure(null, "OTHER", "interrompido"),
                                10, 0, "registro", null);
                    }
                    try {
                        h.await(20, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
            return prepared;
        }

        @Override
        public RemoteMachine.Resolved resolveEnvironment(String resolutionId, String pythonLocalId, List<String> dependencies, BooleanSupplier cancel) {
            resolutions.add(resolutionId + "|" + pythonLocalId + "|" + dependencies);
            return resolved;
        }

        @Override
        public void environmentsIdle() {
            idles.incrementAndGet();
        }
    }

    /** Um relogio que o teste adianta. */
    static final class StepClock extends Clock {
        final AtomicLong offset = new AtomicLong();

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plusMillis(offset.get());
        }
    }

    @TempDir
    Path tmp;
    private FakeGateway gw;
    private Path dir;
    private EnvWork work;
    private final StepClock clock = new StepClock();
    private final List<RemoteMachine> opened = new CopyOnWriteArrayList<>();

    @BeforeEach
    void open() throws Exception {
        gw = new FakeGateway();
        dir = tmp.resolve("remota");
        work = new EnvWork(tmp.resolve("tentativas"), dir.resolve(AttemptJournal.FILE));
        work.prepared = new RemoteMachine.Prepared("READY", null, "3.12.10", "25.0.1",
                List.of(new RemoteMachine.LockItem("nekkora-fixture-a", "1.2.3", "b".repeat(64), "nekkora_fixture_a-1.2.3-py3-none-any.whl")), null, 1200, 3400,
                "[Nekkora Agent] Ambiente pronto.", "c:\\tentativa\\ambiente\\Scripts\\python.exe");
    }

    @AfterEach
    void close() {
        CountDownLatch h = work.hold;
        if (h != null) {
            h.countDown();
        }
        opened.forEach(RemoteMachine::close);
        gw.close();
    }

    private RemoteMachine machine(RemoteMachine.Work w) {
        RemoteMachine m = new RemoteMachine(new MachineVault(dir, new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"), clock);
        m.setPythonFinder(() -> List.of(new RemoteMachine.PythonSeen(LOCAL_PYTHON, false, "3.12.10")));
        if (w != null) {
            m.setWork(w);
        }
        opened.add(m);
        return m;
    }

    private RemoteMachine enabled(RemoteMachine.Work w) throws Exception {
        RemoteMachine m = machine(w);
        m.enable(gw.code("remote"));
        m.beat();
        Matcher id = Pattern.compile("\"environmentId\":\"([A-Za-z0-9_-]{43})\"").matcher(gw.inventory);
        assertTrue(id.find(), "inventario publicado");
        gw.offerPython = id.group(1);
        return m;
    }

    /** Consulta ate a condicao valer (o preparo roda no executor proprio). */
    private static void until(RemoteMachine m, BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("a condicao nao se cumpriu no prazo do teste");
            }
            m.pull();
            Thread.sleep(20);
        }
    }

    private String journalText() {
        return EnvWork.read(dir.resolve(AttemptJournal.FILE));
    }

    private int callIndex(java.util.function.Predicate<FakeGateway.Call> p) {
        for (int i = 0; i < gw.calls.size(); i++) {
            if (p.test(gw.calls.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void aCapacidadeSoEDeclaradaDepoisDeAPresencaDizerQueAPlatformAOfereceEOCorpoDeAntesNaoMuda() throws Exception {
        RemoteMachine m = enabled(work);
        // Platform sem a chave (ou anterior): o corpo da presenca e EXATAMENTE o de antes
        m.beat();
        assertFalse(gw.declaredManaged);
        assertEquals("{\"agentVersion\":\"0.1.0+client\",\"contractVersion\":\"1.0\",\"capabilities\":[\"PY_INVENTORY_1_0\",\"REMOTE_RUN_1_0\"],"
                + "\"remote\":{\"busy\":false,\"diskPressure\":false}}", gw.lastHeartbeat);
        // a Platform passa a oferecer: a presenca que VE isso nao declara; o aplicativo manda logo uma segunda, ja com a capacidade
        // (sem esperar o minuto seguinte); daí em diante, uma presenca por vez
        gw.managedEnvironments = true;
        long antes = gw.count("/agent/api/v1/heartbeat");
        m.beat();
        assertEquals(antes + 2, gw.count("/agent/api/v1/heartbeat"));
        assertTrue(gw.declaredManaged, "a capacidade vai na presenca logo depois da resposta que oferece");
        m.beat();
        assertEquals(antes + 3, gw.count("/agent/api/v1/heartbeat"));
        assertTrue(gw.declaredManaged);
        assertTrue(gw.lastHeartbeat.contains("\"capabilities\":[\"PY_INVENTORY_1_0\",\"REMOTE_RUN_1_0\",\"MANAGED_ENV_1_0\"]"), gw.lastHeartbeat);
        // a chave desligada de novo: depois da resposta sem o campo, a capacidade deixa de ir
        gw.managedEnvironments = false;
        m.beat();
        m.beat();
        assertFalse(gw.declaredManaged);
        // um aplicativo que nao prepara ambientes nunca declara, mesmo com a Platform oferecendo
        work.manages = false;
        gw.managedEnvironments = true;
        m.beat();
        m.beat();
        assertFalse(gw.declaredManaged);
    }

    @Test
    void oModoPreparadoContinuaExatamenteComoAntes() throws Exception {
        gw.managedEnvironments = true;
        RemoteMachine m = enabled(work);
        m.beat();
        gw.queue();
        m.pull();
        assertEquals(List.of("PREPARING", "STARTING", "RUNNING"), gw.reported);
        assertEquals("RUNNING", gw.attemptState);
        assertEquals(1, work.jobs.size());
        assertNull(work.jobs.get(0).environmentPython(), "modo PREPARED: o Python escolhido, como esta");
        assertEquals(LOCAL_PYTHON, work.jobs.get(0).pythonLocalId());
        assertTrue(work.prepares.isEmpty(), "nada e preparado");
        assertTrue(gw.stages.isEmpty());
        assertTrue(gw.environmentReports.isEmpty());
        assertFalse(journalText().contains("ambienteGerenciado"), "o diario de sempre");
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
    }

    @Test
    void oPreparoVemAntesEOLancamentoSoDepoisDeODiarioGravarStarting() throws Exception {
        gw.managedEnvironments = true;
        RemoteMachine m = enabled(work);
        gw.offerEnvironment = ENVIRONMENT;
        gw.queue();
        m.pull();
        until(m, () -> "RUNNING".equals(gw.attemptState));
        // o diario estava em ENV_PREPARING (com o lock) ANTES de o ambiente ser criado
        assertEquals(1, work.journalAtPrepare.size());
        assertTrue(work.journalAtPrepare.get(0).contains("\"fase\":\"ENV_PREPARING\""), work.journalAtPrepare.get(0));
        assertTrue(work.journalAtPrepare.get(0).contains(SHA_LOCK));
        RemoteMachine.PrepareJob job = work.prepares.get(0);
        assertEquals(LOCAL_PYTHON, job.pythonLocalId(), "o ambiente parte do Python escolhido, localizado pelo identificador opaco");
        assertEquals(List.of("nekkora-fixture-a"), job.lock().stream().map(RemoteMachine.LockItem::name).toList());
        assertEquals(List.of("requests==2.32.3"), job.dependencies());
        // as etapas foram informadas, o relatorio READY foi antes do STARTING, e o lancamento so com o diario em STARTING
        assertEquals(List.of("ENV_CREATING", "DEPS_INSTALLING"), gw.stages.stream().distinct().toList());
        assertEquals(List.of("PREPARING", "STARTING", "RUNNING"), gw.reported);
        int report = callIndex(c -> c.path().endsWith("/environment-report"));
        int starting = callIndex(c -> c.path().endsWith("/state") && c.body().contains("\"state\":\"STARTING\""));
        assertTrue(report >= 0 && report < starting, "o relatorio READY vai antes de pedir o inicio");
        assertEquals(1, gw.environmentReports.size());
        String body = gw.environmentReports.get(0);
        assertTrue(body.contains("\"outcome\":\"READY\"") && body.contains("\"createMs\":1200") && body.contains("\"installMs\":3400")
                && body.contains("\"installed\":[{\"name\":\"nekkora-fixture-a\",\"version\":\"1.2.3\"") && body.contains("\"failure\":null"), body);
        assertFalse(body.contains("ambiente\\\\Scripts"), "o caminho do ambiente nao vai a Platform");
        assertEquals(1, work.journalAtStart.size());
        assertTrue(work.journalAtStart.get(0).contains("\"fase\":\"STARTING\""), "o lancamento so acontece DEPOIS de o diario gravar STARTING");
        assertEquals("c:\\tentativa\\ambiente\\Scripts\\python.exe", work.jobs.get(0).environmentPython(), "a automacao roda no ambiente preparado");
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertFalse(Files.exists(dir.resolve(AttemptJournal.FILE)));
    }

    @Test
    void falhaNoPreparoInformaORelatorioAntesDaRecusaENadaEIniciado() throws Exception {
        gw.managedEnvironments = true;
        RemoteMachine m = enabled(work);
        work.prepared = new RemoteMachine.Prepared("FAILED", "DEPENDENCY_INSTALL_FAILED", "3.12.10", "25.0.1", List.of(),
                new RemoteMachine.EnvFailure("nekkora-fixture-a==1.2.3", "HASH_MISMATCH", "O arquivo baixado não tem o SHA-256 do lock aceito."), 900, 500,
                "registro do preparo", null);
        gw.offerEnvironment = ENVIRONMENT;
        gw.queue();
        m.pull();
        until(m, () -> "REFUSED".equals(gw.attemptState));
        assertEquals("DEPENDENCY_INSTALL_FAILED", gw.attemptReason);
        assertTrue(work.jobs.isEmpty(), "a automacao nao e iniciada");
        assertFalse(gw.reported.contains("STARTING"));
        int report = callIndex(c -> c.path().endsWith("/environment-report"));
        int refused = callIndex(c -> c.path().endsWith("/state") && c.body().contains("\"state\":\"REFUSED\""));
        assertTrue(report >= 0 && report < refused, "primeiro o relatorio FAILED, depois a recusa");
        String body = gw.environmentReports.get(0);
        assertTrue(body.contains("\"outcome\":\"FAILED\"") && body.contains("\"phase\":\"HASH_MISMATCH\"") && body.contains("\"requirement\":\"nekkora-fixture-a==1.2.3\"")
                && body.contains("\"log\":\"registro do preparo\""), body);
        assertFalse(Files.exists(dir.resolve(AttemptJournal.FILE)));
        // as outras recusas novas: prerequisito externo e ambiente
        for (String refusal : List.of("EXTERNAL_PREREQUISITE_MISSING", "ENVIRONMENT_SETUP_FAILED")) {
            work.prepared = new RemoteMachine.Prepared("FAILED", refusal, null, null, List.of(), new RemoteMachine.EnvFailure(null, "OTHER", "x"), 0, 0, "", null);
            gw.environmentReports.clear();
            gw.queue();
            until(m, () -> "REFUSED".equals(gw.attemptState));
            assertEquals(refusal, gw.attemptReason);
        }
        assertTrue(work.jobs.isEmpty());
    }

    @Test
    void reabertoComODiarioEmEnvPreparingATentativaERecusadaComoInterrompidaENadaEIniciado() throws Exception {
        gw.managedEnvironments = true;
        RemoteMachine m = enabled(work);
        work.hold = new CountDownLatch(1);
        gw.offerEnvironment = ENVIRONMENT;
        gw.queue();
        m.pull();
        until(m, () -> !work.prepares.isEmpty());
        assertTrue(journalText().contains("\"fase\":\"ENV_PREPARING\""));
        // o aplicativo fecha no meio do preparo (o pip e encerrado) e abre de novo: so o disco vale
        m.close();
        RemoteMachine reopened = machine(work);
        reopened.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("ENVIRONMENT_SETUP_INTERRUPTED", gw.attemptReason);
        assertEquals(1, gw.environmentReports.size());
        assertTrue(gw.environmentReports.get(0).contains("\"outcome\":\"INTERRUPTED\""), gw.environmentReports.get(0));
        assertTrue(work.jobs.isEmpty(), "ENV_PREPARING: com certeza nada foi iniciado, e nada e iniciado agora");
        assertFalse(Files.exists(dir.resolve(AttemptJournal.FILE)));
        // STARTING continua com a regra de sempre (sem prova: ENDED_UNKNOWN) - provado em RemoteDeliveryTest
    }

    @Test
    void aPresencaEAConsultaSeguemComOPreparoParadoEOPrazoERenovadoACada30Segundos() throws Exception {
        gw.managedEnvironments = true;
        RemoteMachine m = enabled(work);
        work.hold = new CountDownLatch(1);
        gw.offerEnvironment = ENVIRONMENT;
        gw.queue();
        long t0 = System.nanoTime();
        m.pull();
        until(m, () -> !work.prepares.isEmpty());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 15, "a consulta nao espera o preparo");
        // com o preparo segurado: presenca e consulta respondem ja, varias vezes
        long beats = gw.count("/agent/api/v1/heartbeat");
        for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            m.beat();
            m.pull();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - s) < 5000, "presenca e consulta nao esperam o pip");
        }
        assertEquals(beats + 3, gw.count("/agent/api/v1/heartbeat"));
        assertTrue(gw.lastHeartbeat.contains("\"busy\":true"), "com a tentativa em preparo, a maquina diz que esta ocupada");
        assertEquals(RemoteMachine.Activity.REMOTE_PREPARING, m.activity());
        until(m, () -> gw.stages.contains("DEPS_INSTALLING"));
        int sent = gw.stages.size();
        // antes dos 30 s nada e repetido; passados 30 s, a etapa vai de novo (renova o prazo)
        m.pull();
        assertEquals(sent, gw.stages.size());
        clock.offset.addAndGet(31_000);
        m.pull();
        assertEquals(sent + 1, gw.stages.size());
        assertEquals("DEPS_INSTALLING", gw.stages.get(gw.stages.size() - 1));
        // o cancelamento pedido na Platform chega na renovacao: o preparo e parado e a tentativa termina cancelada, sem iniciar
        gw.cancelRequested = true;
        clock.offset.addAndGet(31_000);
        m.pull();
        until(m, () -> "CANCELLED".equals(gw.attemptState));
        assertTrue(work.observedCancel, "quem prepara foi mandado parar");
        assertTrue(work.jobs.isEmpty());
        assertTrue(gw.environmentReports.get(0).contains("\"outcome\":\"INTERRUPTED\""));
        assertFalse(Files.exists(dir.resolve(AttemptJournal.FILE)));
    }

    @Test
    void aResolucaoRodaForaDaConsultaEARespostaVaiComOLockEOAmbienteSumidoViraEnvironmentGone() throws Exception {
        gw.managedEnvironments = true;
        RemoteMachine m = enabled(work);
        String id = UUID.randomUUID().toString();
        gw.environmentResolution = "{\"resolutionId\":\"" + id + "\",\"pythonEnvironmentId\":\"" + gw.offerPython
                + "\",\"dependencies\":[\"nekkora-fixture-a==1.2.3\"],\"index\":\"PYPI\"}";
        gw.failResolutionAnswers = 1;
        m.pull();
        until(m, () -> !gw.resolutionAnswers.isEmpty());
        assertEquals(1, work.resolutions.size(), "uma resolucao so, mesmo com o pedido repetido e a primeira resposta perdida");
        assertEquals(id + "|" + LOCAL_PYTHON + "|[nekkora-fixture-a==1.2.3]", work.resolutions.get(0));
        String answer = gw.resolutionAnswers.get(0);
        assertTrue(answer.startsWith(id + "|"));
        assertTrue(answer.contains("\"outcome\":\"RESOLVED\"") && answer.contains("\"pipVersion\":\"25.0.1\"")
                && answer.contains("\"fileName\":\"nekkora_fixture_a-1.2.3-py3-none-any.whl\"") && answer.contains("\"failure\":null"), answer);
        assertFalse(answer.toLowerCase().contains("python312"), "nenhum caminho vai a Platform");
        // um Python que nao esta mais aqui: ENVIRONMENT_GONE, sem escolher outro
        String gone = UUID.randomUUID().toString();
        gw.environmentResolution = "{\"resolutionId\":\"" + gone + "\",\"pythonEnvironmentId\":\"" + "Z".repeat(43) + "\",\"dependencies\":[],\"index\":\"PYPI\"}";
        m.pull();
        until(m, () -> gw.resolutionAnswers.size() == 2);
        assertTrue(gw.resolutionAnswers.get(1).contains("\"outcome\":\"ENVIRONMENT_GONE\""));
        assertEquals(1, work.resolutions.size());
        until(m, () -> work.idles.get() > 0);
    }

    @Test
    void umAplicativoQueNaoPreparaAmbientesRecusaATentativaGerenciadaComoIncompativel() throws Exception {
        work.manages = false;
        RemoteMachine m = enabled(work);
        gw.offerEnvironment = ENVIRONMENT;
        gw.queue();
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        assertTrue(work.prepares.isEmpty());
        assertTrue(work.jobs.isEmpty());
    }

    @Test
    void oDiarioGuardaOAmbienteGerenciadoEVoltaIgual() throws Exception {
        MachineChannel.Managed managed = new MachineChannel.Managed(SHA_LOCK,
                List.of(new RemoteMachine.LockItem("nekkora-fixture-a", "1.2.3", "b".repeat(64), "nekkora_fixture_a-1.2.3-py3-none-any.whl")),
                List.of("PLAYWRIGHT_CHROMIUM"));
        String agent = UUID.randomUUID().toString();
        MachineChannel.Offer o = new MachineChannel.Offer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "c".repeat(64), "nekkora-agent/external-python-fingerprint/1", List.of("x==1"),
                "Y".repeat(43), "t".repeat(43), 1, managed);
        AttemptJournal j = new MachineVault(dir, new WindowsKeyring()).journal();
        j.save(AttemptJournal.Entry.received(agent, o).withAck().preparingEnvironment());
        AttemptJournal.Entry back = j.load(agent).orElseThrow();
        assertEquals(AttemptJournal.Phase.ENV_PREPARING, back.phase());
        assertEquals(managed, back.environment());
        assertTrue(Files.size(dir.resolve(AttemptJournal.FILE)) > 0);
    }
}
