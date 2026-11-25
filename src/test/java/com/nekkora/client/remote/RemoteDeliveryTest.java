package com.nekkora.client.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.remote.MachineChannel.Problem;
import com.nekkora.client.remote.MachineChannel.Refused;
import com.nekkora.client.remote.RemoteMachine.Status;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02 e T03 - a tentativa nesta maquina, do recebimento ao desfecho: perguntar, gravar no
 * diario, confirmar, baixar o pacote, localizar o Python escolhido, iniciar so depois de a Platform reconferir, acompanhar,
 * obedecer ao cancelamento e informar o desfecho. Contra um canal simulado (HTTPS de verdade) e com a protecao REAL do
 * Windows. Quem executa e simulado aqui ({@link FakeWork}): o motor de verdade e provado em {@code ManagedRunTest} e a ponte
 * em {@code RemoteWorkTest}.
 */
@EnabledOnOs(OS.WINDOWS)
class RemoteDeliveryTest {

    private static final String NEXT = "/agent/api/v1/remote/next";
    private static final String LOCAL_PYTHON = "computador:c:\\python312\\python.exe";

    /** Quem executa, simulado: guarda o que recebeu e devolve o que o teste mandar. */
    static class FakeWork implements RemoteMachine.Work {
        final Path root;
        final List<RemoteMachine.Job> jobs = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        final List<String> discarded = new ArrayList<>();
        volatile boolean busy;
        volatile String refusal;
        volatile Throwable crash;
        volatile String state = "RUNNING";
        volatile Integer exitCode;
        volatile boolean known = true;
        /** O motor conferiu que nada da arvore de processos da execucao continua vivo. */
        volatile boolean treeGone = true;
        /** Quando nao nulo, o que o motor diz dos processos (GONE, ALIVE ou UNCERTAIN); senao vale {@link #treeGone}. */
        volatile String processes;
        volatile byte[] zipSeen;
        /** Roda dentro do inicio da execucao (depois de o diario dizer "iniciando", antes de ele dizer "em execucao"). */
        volatile Runnable onStart;

        FakeWork(Path root) {
            this.root = root;
        }

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public Optional<String> dir(String attemptId) {
            return Optional.of(root.resolve(attemptId).toString());
        }

        @Override
        public String start(RemoteMachine.Job job) throws RemoteMachine.WorkRefused {
            jobs.add(job);
            try {
                zipSeen = Files.readAllBytes(Path.of(job.zip()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            if (crash instanceof Error err) {
                throw err;
            }
            if (crash instanceof RuntimeException re) {
                throw re;
            }
            if (refusal != null) {
                throw new RemoteMachine.WorkRefused(refusal);
            }
            busy = true;
            Runnable then = onStart;
            if (then != null) {
                then.run();
            }
            return "ex-" + "1".repeat(32);
        }

        @Override
        public Optional<RemoteMachine.Progress> progress(String run) {
            return known ? Optional.of(new RemoteMachine.Progress(state, exitCode)) : Optional.empty();
        }

        @Override
        public String processes(String run) {
            return processes != null ? processes : treeGone ? "GONE" : "ALIVE";
        }

        @Override
        public void cancel(String run) {
            cancelled.add(run);
            state = "CANCELLED";
            busy = false;
        }

        final List<String> outputsDiscarded = new ArrayList<>();
        /** tentativa -> motivo da pendencia. */
        final java.util.Map<String, String> outputsHeld = new java.util.LinkedHashMap<>();
        /** Roda depois da PRIMEIRA listagem dos resultados de uma tentativa (o envio), antes da conferencia final. */
        volatile Runnable afterListing;

        @Override
        public void holdOutputs(String attemptId, String reason) {
            outputsHeld.put(attemptId, reason);
        }
        final List<String> swept = new ArrayList<>();

        /** O que a conferencia de dependencias responde, e com o que foi chamada ({@code python|dependencias}). */
        volatile RemoteMachine.Verified verified = new RemoteMachine.Verified("SATISFIED", List.of());
        volatile RuntimeException verifyCrash;
        final List<String> verifications = new ArrayList<>();

        @Override
        public RemoteMachine.Verified verify(String pythonLocalId, List<String> dependencies) {
            verifications.add(pythonLocalId + "|" + dependencies);
            if (verifyCrash != null) {
                throw verifyCrash;
            }
            return verified;
        }

        @Override
        public void discardOutputs(String attemptId) {
            outputsDiscarded.add(attemptId);
            outputsHeld.remove(attemptId);
        }

        /** As pastas de saida guardadas, como o motor as informaria. */
        final List<RemoteMachine.Held> heldNow = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int retentionDays = -1;
        /** O relogio da limpeza deste motor de teste (segundos). */
        volatile long sweepNow = java.time.Instant.now().getEpochSecond();

        @Override
        public List<RemoteMachine.Held> held(String exceptAttemptId) {
            return heldNow.stream().filter(h -> !h.attemptId().equals(exceptAttemptId)).toList();
        }

        @Override
        public void retentionDays(int days) {
            retentionDays = days;
        }

        @Override
        public int sweep(String keepAttemptId) {
            swept.add(String.valueOf(keepAttemptId));
            // como o motor real: a pasta guardada sai quando o prazo dela venceu, e nunca a da tentativa pendente
            int before = heldNow.size();
            heldNow.removeIf(h -> !h.attemptId().equals(keepAttemptId) && h.removeAtEpochSecond() <= sweepNow);
            return before - heldNow.size();
        }

        @Override
        public void discard(String attemptId, String run) {
            discarded.add(attemptId);
            try {
                Files.deleteIfExists(root.resolve(attemptId).resolve("pacote.zip"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Arquivos que a "execucao" deixou na pasta de saida: nome relativo -> conteudo. */
        final java.util.Map<String, byte[]> outputs = new java.util.LinkedHashMap<>();

        @Override
        public List<RemoteMachine.ResultFile> results(String attemptId, String run) {
            List<RemoteMachine.ResultFile> out = new ArrayList<>();
            try {
                Path dir = Files.createDirectories(root.resolve(attemptId).resolve("saida"));
                for (java.util.Map.Entry<String, byte[]> e : outputs.entrySet()) {
                    Path f = dir.resolve(e.getKey().replace('/', '_'));
                    Files.write(f, e.getValue());
                    out.add(new RemoteMachine.ResultFile(e.getKey(), f.toString(), e.getValue().length));
                }
            } catch (java.io.IOException x) {
                throw new IllegalStateException(x);
            }
            Runnable then = afterListing;
            afterListing = null;
            if (then != null) {
                then.run();
            }
            return out;
        }

        void ends(String how, Integer exit) {
            state = how;
            exitCode = exit;
            busy = false;
        }
    }

    @TempDir
    Path tmp;
    private FakeGateway gw;
    private Path dir;
    private FakeWork work;
    private volatile boolean pythonPresent = true;

    @BeforeEach
    void open() throws Exception {
        gw = new FakeGateway();
        dir = tmp.resolve("remota");
        work = new FakeWork(tmp.resolve("tentativas"));
    }

    @AfterEach
    void close() {
        gw.close();
    }

    private RemoteMachine machine(boolean executes) {
        RemoteMachine m = new RemoteMachine(new MachineVault(dir, new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"));
        m.setPythonFinder(() -> pythonPresent ? List.of(new RemoteMachine.PythonSeen(LOCAL_PYTHON, false, "3.12.10")) : List.of());
        if (executes) {
            m.setWork(work);
        }
        return m;
    }

    /** Habilitada, com o inventario publicado; a entrega passa a indicar o Python escolhido pelo identificador opaco dele. */
    private RemoteMachine enabled(boolean executes) throws Exception {
        RemoteMachine m = machine(executes);
        m.enable(gw.code("remote"));
        m.beat();
        Matcher id = Pattern.compile("\"environmentId\":\"([A-Za-z0-9_-]{43})\"").matcher(gw.inventory);
        assertTrue(id.find(), "inventario publicado");
        gw.offerPython = id.group(1);
        assertFalse(gw.inventory.toLowerCase().contains("python312"), "o caminho nunca vai a Platform");
        return m;
    }

    private Path journal() {
        return dir.resolve(AttemptJournal.FILE);
    }

    private String journalText() throws Exception {
        return Files.readString(journal(), StandardCharsets.UTF_8);
    }

    private long acks() {
        return gw.calls.stream().filter(c -> c.path().endsWith("/ack")).count();
    }

    private long downloads() {
        return gw.calls.stream().filter(c -> c.path().endsWith("/package")).count();
    }

    @Test
    void aTelaSabeOQueEstaRodandoAgoraEDeQuemEEOCodigoDoAmbienteEOMesmoQueVaiAPlatform() throws Exception {
        RemoteMachine fresh = machine(true);
        assertEquals(RemoteMachine.Activity.IDLE, fresh.activity());
        assertTrue(fresh.organizationId().isEmpty(), "sem vinculo, nao ha organizacao a dizer");
        assertTrue(fresh.pythonHere().isEmpty());
        // execucao local com o computador ainda nao habilitado: e local, e a tela sabe dizer isso
        work.busy = true;
        assertEquals(RemoteMachine.Activity.LOCAL_RUNNING, fresh.activity());
        work.busy = false;

        RemoteMachine m = enabled(true);
        assertEquals(gw.organizationId, m.organizationId().orElseThrow());
        // o codigo mostrado aqui sao os 6 primeiros caracteres do identificador opaco PUBLICADO; a pasta fica so neste computador
        List<RemoteMachine.PythonHere> here = m.pythonHere();
        assertEquals(1, here.size());
        assertEquals(gw.offerPython.substring(0, 6), here.get(0).code());
        assertEquals("3.12.10", here.get(0).version());
        assertFalse(here.get(0).virtual());
        assertEquals("c:\\python312\\python.exe", here.get(0).where());
        assertFalse(gw.inventory.contains("python312"), "onde o ambiente esta nunca vai a Platform");
        assertEquals(RemoteMachine.Activity.IDLE, m.activity());
        // execucao local com o computador habilitado: continua sendo dita como local
        work.busy = true;
        assertEquals(RemoteMachine.Activity.LOCAL_RUNNING, m.activity());
        work.busy = false;
        // solicitacao da Platform: em execucao; depois do termino, enviando; resolvida, nada em andamento
        gw.queue();
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        assertEquals(RemoteMachine.Activity.REMOTE_RUNNING, m.activity());
        work.ends("SUCCEEDED", 0);
        gw.status = 503;
        m.pull();
        assertEquals(RemoteMachine.Activity.REMOTE_SENDING, m.activity(), "terminou aqui, mas a Platform ainda nao recebeu o desfecho");
        gw.status = null;
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertEquals(RemoteMachine.Activity.IDLE, m.activity());
        // desabilitado: nada de organizacao nem de ambientes
        m.disable();
        assertTrue(m.organizationId().isEmpty());
        assertTrue(m.pythonHere().isEmpty());
    }

    @Test
    void semQuemExecuteAMaquinaNaoDeclaraExecucaoNemPerguntaPelaEntrega() throws Exception {
        RemoteMachine m = enabled(false);
        gw.queue();
        m.pull();
        m.beat();
        assertFalse(gw.declaredRun, "sem quem execute, nao declara a capacidade");
        assertTrue(gw.lastHeartbeat.contains("\"capabilities\":[\"PY_INVENTORY_1_0\"]"));
        assertEquals(0, gw.count(NEXT));
        assertEquals("QUEUED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    /**
     * A ocorrencia de 17/11: no instante em que a execucao comeca, OUTRA leitura tem o diario aberto (a propria tela o le ao se
     * redesenhar; um antivirus faria o mesmo). No Windows a troca do arquivo e negada enquanto isso. O aplicativo CONTINUA aberto e
     * sabe qual execucao iniciou: a tentativa nao pode virar "resultado nao confirmado" por causa de uma gravacao que nao entrou.
     */
    @Test
    void diarioQueNaoPodeSerTrocadoNoInicioNaoViraResultadoNaoConfirmadoEnquantoOAplicativoSegueAberto() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        java.util.concurrent.atomic.AtomicReference<java.io.InputStream> reader = new java.util.concurrent.atomic.AtomicReference<>();
        work.onStart = () -> {
            try {
                reader.set(Files.newInputStream(journal()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        try {
            m.pull();
        } finally {
            if (reader.get() != null) {
                reader.get().close();
            }
        }
        work.onStart = null;
        assertEquals(List.of("PREPARING", "STARTING", "RUNNING"), gw.reported);
        // a execucao termina bem; a consulta seguinte informa o desfecho verdadeiro, com o codigo de saida
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState, "o desfecho informado a Platform");
        assertEquals(0, gw.attemptExit);
        assertFalse(gw.reported.contains("ENDED_UNKNOWN"), String.valueOf(gw.reported));
        assertFalse(Files.exists(journal()));
    }

    /**
     * A duvida LEGITIMA continua sendo duvida: o diario ficou em "iniciando" e o aplicativo FECHOU antes de gravar a execucao. O
     * processo novo nao sabe se algo foi lancado: informa "resultado nao confirmado", diz que nao sabe dos processos, nao inicia
     * de novo e nao da a maquina por livre.
     */
    @Test
    void aplicativoReabertoComODiarioEmIniciandoContinuaSendoResultadoNaoConfirmado() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        java.util.concurrent.atomic.AtomicReference<java.io.InputStream> reader = new java.util.concurrent.atomic.AtomicReference<>();
        work.onStart = () -> {
            try {
                reader.set(Files.newInputStream(journal()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        try {
            m.pull();
        } finally {
            reader.get().close();
        }
        work.onStart = null;
        assertTrue(journalText().contains("\"fase\":\"STARTING\""), "o disco ficou para tras: o diario nao pode ser trocado naquele instante");
        // o aplicativo fecha e abre: o que o processo anterior sabia se perdeu; so o disco vale
        work.ends("SUCCEEDED", 0);
        RemoteMachine reopened = machine(true);
        reopened.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState, "sem prova do que foi lancado, nada e presumido");
        assertFalse(gw.lastProcessAbsent, "nenhuma afirmacao de que nao ha processo: a Platform nao recebe prova que nao existe");
        assertFalse("ABSENT".equals(gw.lastProcessTree), gw.lastProcessTree);
        assertEquals(1, work.jobs.size(), "nada e iniciado de novo");
        assertFalse(gw.reported.contains("SUCCEEDED"), String.valueOf(gw.reported));
        assertEquals(attempt, gw.attemptId);
    }

    /**
     * Dentro do aplicativo, a tela le o diario (atividade, pendencias) enquanto a consulta o grava. Essas leituras nunca deixam o
     * diario para tras: em cada inicio o disco passa a dizer "em execucao", e toda tentativa termina com o desfecho verdadeiro.
     */
    @Test
    void leiturasDaTelaDuranteAConsultaNaoDeixamODiarioParaTras() throws Exception {
        RemoteMachine m = enabled(true);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong();
        List<Thread> screens = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            Thread t = new Thread(() -> {
                while (!stop.get()) {
                    m.activity();
                    m.heldResults();
                    reads.incrementAndGet();
                }
            }, "tela-" + k);
            t.setDaemon(true);
            t.start();
            screens.add(t);
        }
        try {
            for (int round = 1; round <= 25; round++) {
                gw.reported.clear();
                work.state = "RUNNING";
                work.exitCode = null;
                gw.queue();
                m.pull();
                assertTrue(journalText().contains("\"fase\":\"RUNNING\""), "rodada " + round + ": o diario diz em execucao");
                work.ends("SUCCEEDED", 0);
                m.pull();
                assertEquals("SUCCEEDED", gw.attemptState, "rodada " + round + ": " + gw.reported);
                assertFalse(gw.reported.contains("ENDED_UNKNOWN"), "rodada " + round + ": " + gw.reported);
                assertFalse(Files.exists(journal()), "rodada " + round);
            }
        } finally {
            stop.set(true);
            for (Thread t : screens) {
                t.join(5_000);
            }
        }
        assertTrue(reads.get() > 100, "as leituras concorrentes aconteceram: " + reads.get());
    }

    /** A troca de um arquivo de estado e tentada de novo enquanto o destino esta aberto por outra leitura; nunca e dada por feita. */
    @Test
    void aTrocaDoArquivoDeEstadoEsperaALeituraTerminarEFalhaSeElaNaoTerminar() throws Exception {
        Path target = tmp.resolve("estado.json");
        Path fresh = tmp.resolve("estado.json.novo");
        Files.writeString(target, "antigo");
        // leitura que termina logo: a troca entra numa das novas tentativas
        Files.writeString(fresh, "novo-1");
        java.io.InputStream brief = Files.newInputStream(target);
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(120);
                brief.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        closer.start();
        Replace.into(fresh, target);
        closer.join();
        assertEquals("novo-1", Files.readString(target));
        assertFalse(Files.exists(fresh));
        // leitura que nao termina: esgotadas as tentativas a falha e lancada e o destino continua como estava
        Files.writeString(fresh, "novo-2");
        try (java.io.InputStream held = Files.newInputStream(target)) {
            assertTrue(held.read() >= 0);
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> Replace.into(fresh, target));
        }
        assertEquals("novo-1", Files.readString(target), "o destino nao mudou");
        assertTrue(Files.exists(fresh), "o arquivo novo continua la para a proxima tentativa");
    }

    @Test
    void recebeBaixaIniciaAcompanhaEInformaOSucesso() throws Exception {
        RemoteMachine m = enabled(true);
        assertTrue(gw.declaredRun, "com quem execute, declara REMOTE_RUN_1_0");
        String attempt = gw.queue();
        m.pull();
        // a ordem que a Platform viu: preparando, iniciando (reconferencia), em execucao
        assertEquals(List.of("PREPARING", "STARTING", "RUNNING"), gw.reported);
        assertEquals("RUNNING", gw.attemptState);
        assertEquals(1, acks());
        assertEquals(1, downloads());
        // o que quem executa recebeu: o ZIP inteiro, o SHA-256 declarado, a impressao, as dependencias, o Python LOCAL e os ids
        assertEquals(1, work.jobs.size());
        RemoteMachine.Job job = work.jobs.get(0);
        assertArrayEquals(gw.packageBytes, work.zipSeen);
        assertEquals(FakeGateway.sha256(gw.packageBytes), job.sha256());
        assertEquals("c".repeat(64), job.fingerprint());
        assertEquals(List.of("requests==2.32.3"), job.dependencies());
        assertEquals(LOCAL_PYTHON, job.pythonLocalId());
        assertEquals(attempt, job.attemptId());
        assertEquals(gw.organizationId, job.organizationId());
        assertEquals(gw.workspaceId, job.workspaceId());
        assertEquals(gw.automationVersionId, job.automationVersionId());
        assertEquals(work.root.resolve(attempt).toString(), job.dir());
        // o diario: em execucao, com o identificador da execucao; o token nunca em claro; nenhum caminho do Python
        String file = journalText();
        assertTrue(file.contains("\"fase\":\"RUNNING\""));
        assertTrue(file.contains("\"execucao\":\"ex-"));
        assertFalse(file.contains(gw.attemptToken));
        assertFalse(file.toLowerCase().contains("python312"));
        // o token do download foi em cabecalho, nunca na URL
        assertTrue(gw.calls.stream().noneMatch(c -> c.path().contains(gw.attemptToken)));
        // enquanto roda: cada consulta repete o estado (renova o prazo), sem nova entrega nem novo inicio
        m.pull();
        m.pull();
        assertEquals(List.of("PREPARING", "STARTING", "RUNNING", "RUNNING", "RUNNING"), gw.reported);
        assertEquals(1, gw.count(NEXT));
        assertEquals(1, work.jobs.size());
        m.beat();
        assertTrue(gw.lastHeartbeat.contains("\"busy\":true"));
        // terminou: o desfecho com o codigo de saida; so entao o diario some e o codigo da tentativa e apagado
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertEquals(0, gw.attemptExit);
        assertFalse(Files.exists(journal()));
        assertTrue(work.discarded.contains(attempt));
        assertFalse(Files.exists(Path.of(job.zip())));
        m.beat();
        assertTrue(gw.lastHeartbeat.contains("\"busy\":false"));
        assertEquals(Status.RECEIVING, m.view().status());
    }

    @Test
    void depoisDoDesfechoOsResultadosSaoEnviadosEOEnvioEFechadoSemMudarODesfecho() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.outputs.put("relatorio.txt", "ok".getBytes(StandardCharsets.UTF_8));
        work.outputs.put("dados/relatório final.csv", "a;b\n".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState, "o desfecho e informado antes dos arquivos, e nao muda por causa deles");
        // o nome que vai a Platform e o RELATIVO a pasta de saida; o caminho deste computador nao vai
        assertEquals(2, gw.results.size());
        assertTrue(gw.results.get(1).startsWith("relatorio.txt|" + FakeGateway.sha256("ok".getBytes(StandardCharsets.UTF_8)) + "|2"));
        assertTrue(gw.results.get(2).startsWith("dados/relatório final.csv|"));
        assertTrue(gw.calls.stream().filter(c -> c.path().contains("/results/")).noneMatch(c -> c.path().contains(gw.attemptToken)), "token nunca na URL");
        assertEquals("2|0", gw.resultsClosure);
        assertFalse(Files.exists(journal()), "so depois do fechamento o diario some");
        // T04: com o recebimento de TUDO confirmado, a pasta de saida pode sair deste computador
        assertEquals(List.of(gw.attemptId), work.outputsDiscarded);
    }

    private Path queueFile(String attempt) {
        return dir.resolve("fila").resolve(attempt + ".fila");
    }

    private long logCalls() {
        return gw.calls.stream().filter(c -> c.path().endsWith("/logs") || c.path().endsWith("/logs/complete")).count();
    }

    @Test
    void osRegistrosVaoEmLotesDuranteAExecucaoEAFilaSoEApagadaComOFechamentoConfirmado() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        RemoteMachine.LogSink log = work.jobs.get(0).log();
        assertNotNull(log, "quem executa recebe para onde mandar a saida do processo");
        assertEquals(0, logCalls(), "sem linha, nada e enviado");
        // o processo escreve; na consulta seguinte, com a execucao AINDA em andamento, as linhas ja estao na Platform
        log.line(false, 1_000, false, "iniciando");
        log.line(true, 1_001, false, "aviso com o token " + gw.attemptToken + " e a credencial " + gw.credential);
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        assertEquals("STDOUT|false|iniciando", gw.logs.get(1));
        assertEquals("STDERR|false|aviso com o token [removido] e a credencial [removido]", gw.logs.get(2), "STDERR e canal; segredos nunca saem");
        assertNull(gw.logsClosure, "em andamento, o envio nao e fechado");
        assertTrue(Files.exists(queueFile(attempt)));
        String onDisk = new String(Files.readAllBytes(queueFile(attempt)), StandardCharsets.ISO_8859_1);
        assertFalse(onDisk.contains("iniciando") || onDisk.contains(gw.attemptToken) || onDisk.contains(gw.credential), "a fila no disco e protegida");
        // nem o token nem a credencial aparecem no texto de linha nenhuma enviada; o token nunca vai na URL
        assertTrue(gw.calls.stream().filter(c -> c.path().endsWith("/logs")).noneMatch(c -> c.path().contains(gw.attemptToken)
                || c.body().contains("credencial " + gw.credential) || c.body().split("\"lines\"", 2)[1].contains(gw.attemptToken)));
        // sem rede para os registros: a execucao nao e interrompida e nada e perdido
        gw.failLogs = 5;
        log.line(false, 1_002, true, "terceira (cortada pela captura)");
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        assertEquals(2, gw.logs.size());
        assertTrue(work.cancelled.isEmpty());
        gw.failLogs = 0;
        // o robo FALHA: o desfecho e o dele; depois vao o que faltava e o fechamento, e so entao a fila e apagada
        log.line(false, 1_003, false, "ultima");
        work.ends("FAILED", 3);
        m.pull();
        assertEquals("FAILED", gw.attemptState);
        assertEquals(4, gw.logs.size());
        assertEquals("STDOUT|true|terceira (cortada pela captura)", gw.logs.get(3));
        assertEquals("4|0", gw.logsClosure);
        assertFalse(Files.exists(queueFile(attempt)), "confirmado pela Platform: a fila sai deste computador");
        assertFalse(Files.exists(journal()));
        long after = logCalls();
        m.pull();
        assertEquals(after, logCalls(), "nada e reenviado depois de resolvido");
    }

    @Test
    void semConfirmacaoDoFechamentoAFilaFicaAteOPrazoEOResultadoDoRoboNaoMuda() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        RemoteMachine.LogSink log = work.jobs.get(0).log();
        log.line(false, 1, false, "um");
        log.line(false, 2, false, "dois");
        work.ends("SUCCEEDED", 0);
        // o fechamento volta com OUTRA contagem: nao e a confirmacao desta entrega
        gw.wrongLogsClosure = true;
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState, "registro nao muda o resultado");
        assertEquals("2|0", gw.logsClosure);
        assertTrue(Files.exists(queueFile(attempt)), "sem confirmacao, a fila nao e apagada");
        assertFalse(Files.exists(journal()), "a tentativa esta resolvida mesmo assim");
        // a limpeza respeita o prazo de guarda: dentro dele fica; vencido, sai
        m.sweep();
        assertTrue(Files.exists(queueFile(attempt)));
        Files.setLastModifiedTime(queueFile(attempt), java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(7)).minusSeconds(60)));
        // arquivo que nao e fila nao e tocado
        Path other = Files.writeString(dir.resolve("fila").resolve("anotacao.txt"), "x");
        Files.setLastModifiedTime(other, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(90))));
        m.sweep();
        assertFalse(Files.exists(queueFile(attempt)));
        assertTrue(Files.exists(other));
        assertEquals(7, MachineVault.DEFAULT_RETENTION_DAYS);
    }

    private static void age(Path file, java.time.Duration howOld) throws Exception {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(howOld)));
    }

    private void retentionFile(String content) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MachineVault.RETENTION_FILE), content, StandardCharsets.UTF_8);
    }

    @Test
    void oPrazoDeGuardaEConfiguravelPorArquivoEValorInvalidoNaoEncurtaNemAlongaAGuarda() throws Exception {
        MachineVault vault = new MachineVault(dir, new WindowsKeyring());
        assertEquals(7, vault.retentionDays(), "sem arquivo: o padrao");
        for (String ok : new String[] {"14", "14\n", "  14  \r\ncomentario na segunda linha", "1", "90"}) {
            retentionFile(ok);
            assertEquals(Integer.parseInt(ok.strip().split("\\R")[0].strip()), vault.retentionDays(), ok);
        }
        for (String bad : new String[] {"", "0", "91", "-3", "7 dias", "sete", "3.5", "007", "1".repeat(100)}) {
            retentionFile(bad);
            assertEquals(7, vault.retentionDays(), "valor invalido volta ao padrao: [" + bad + "]");
        }
        // o prazo chega a quem guarda a pasta de saida, e e lido de novo a cada limpeza (sem reabrir o aplicativo)
        RemoteMachine m = enabled(true);
        retentionFile("3");
        m.sweep();
        assertEquals(3, work.retentionDays);
        assertEquals(3, m.retentionDays());
        retentionFile("30");
        m.heldResults();
        assertEquals(30, work.retentionDays);
    }

    @Test
    void filaRemovidaPeloPrazoDeixaRegistroDaPerdaEAPlatformEAvisadaQuandoPossivel() throws Exception {
        RemoteMachine m = enabled(true);
        retentionFile("2");
        String attempt = gw.queue();
        m.pull();
        RemoteMachine.LogSink log = work.jobs.get(0).log();
        log.line(false, 1, false, "um");
        log.line(false, 2, false, "dois");
        log.line(true, 3, false, "tres");
        // com a execucao EM ANDAMENTO o prazo nao conta: a fila da tentativa pendente nunca e removida, por mais antiga que seja
        m.pull();
        age(queueFile(attempt), java.time.Duration.ofDays(400));
        m.sweep();
        assertTrue(Files.exists(queueFile(attempt)), "execucao em andamento: fora da contagem do prazo");
        assertTrue(m.heldLogs().isEmpty(), "a fila da tentativa pendente nao e pendencia de guarda");
        assertTrue(m.lost().isEmpty());
        // termina; o fechamento nao e confirmado: a fila fica, e a pendencia e visivel com a data de remocao (prazo de 2 dias)
        work.ends("SUCCEEDED", 0);
        gw.wrongLogsClosure = true;
        m.pull();
        assertTrue(Files.exists(queueFile(attempt)));
        List<RemoteMachine.HeldLogs> held = m.heldLogs();
        assertEquals(1, held.size());
        assertEquals(attempt, held.get(0).attemptId());
        assertEquals(held.get(0).sinceEpochSecond() + 2 * 86_400L, held.get(0).removeAtEpochSecond(), "a data de remocao e conhecida antes");
        assertTrue(m.lost().isEmpty(), "guardado nao e perdido");
        // dentro do prazo: fica, e nada e avisado
        age(queueFile(attempt), java.time.Duration.ofDays(2).minusMinutes(5));
        m.sweep();
        assertTrue(Files.exists(queueFile(attempt)));
        assertTrue(gw.losses.isEmpty());
        // vencido, e sem rede para o aviso: a fila sai, o registro minimo fica dizendo o que houve, e a Platform ainda nao sabe
        age(queueFile(attempt), java.time.Duration.ofDays(2).plusMinutes(5));
        gw.failLoss = 1;
        m.sweep();
        assertFalse(Files.exists(queueFile(attempt)));
        assertTrue(m.heldLogs().isEmpty());
        List<RemoteMachine.Lost> lost = m.lost();
        assertEquals(1, lost.size());
        assertEquals(new RemoteMachine.Lost(attempt, true, 3, lost.get(0).lostAtEpochSecond(), "PENDING"), lost.get(0));
        assertTrue(gw.losses.isEmpty());
        // o registro no disco e protegido: nem o token nem a tentativa em claro
        Path record = dir.resolve("guarda").resolve(attempt + ".guarda");
        String raw = new String(Files.readAllBytes(record), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(gw.attemptToken) || raw.contains(attempt) || raw.contains("PENDING"), "registro de guarda em claro");
        // na limpeza seguinte o aviso chega: diz quanto HAVIA aqui (3 linhas), nao o conteudo
        m.sweep();
        assertEquals(List.of(attempt + "|LOGS|3"), gw.losses);
        assertEquals("SENT", m.lost().get(0).notice());
        assertTrue(gw.calls.stream().filter(c -> c.path().endsWith("/retention-loss")).noneMatch(c -> c.body().contains("um") && c.body().contains("dois")));
        // avisado: o token sai do registro, e nada mais e enviado
        assertEquals("", new MachineVault(dir, new WindowsKeyring()).losses().load(attempt).orElseThrow().token());
        long calls = gw.calls.stream().filter(c -> c.path().endsWith("/retention-loss")).count();
        m.sweep();
        m.sweep();
        assertEquals(calls, gw.calls.stream().filter(c -> c.path().endsWith("/retention-loss")).count());
        assertEquals("SUCCEEDED", gw.attemptState, "nada disto muda o resultado da execucao");
    }

    @Test
    void pastaDeSaidaRemovidaPeloPrazoDeixaRegistroEOAvisoImpossivelFicaDito() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        work.outputs.put("um.txt", "1".getBytes(StandardCharsets.UTF_8));
        work.outputs.put("dois.txt", "22".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        // o fechamento dos resultados nao e confirmado: a pasta fica guardada, com data de remocao conhecida
        gw.wrongClosure = true;
        m.pull();
        assertEquals("NOT_CONFIRMED", work.outputsHeld.get(attempt));
        long now = java.time.Instant.now().getEpochSecond();
        work.heldNow.add(new RemoteMachine.Held(attempt, "NOT_CONFIRMED", work.root.resolve(attempt).resolve("saida").toString(), 2, 3, now, now + 7 * 86_400L));
        assertEquals(1, m.heldResults().size());
        // dentro do prazo: nada sai, nada e perdido
        m.sweep();
        assertEquals(1, m.heldResults().size());
        assertTrue(m.lost().isEmpty());
        // a pessoa apaga a pasta por conta propria, de outra tentativa: sumir fora do prazo NAO e perda por prazo de guarda
        // (aqui: a mesma tentativa continua guardada, entao nada muda)
        // vence o prazo: o motor remove a pasta; fica o registro com quantos arquivos havia
        work.heldNow.clear();
        work.heldNow.add(new RemoteMachine.Held(attempt, "NOT_CONFIRMED", work.root.resolve(attempt).resolve("saida").toString(), 2, 3, now - 8 * 86_400L, now - 86_400L));
        // a Platform nao reconhece mais esta atribuicao (ja ha outra tentativa na fila dela): o aviso e impossivel, e isso fica dito
        gw.attemptToken = "outro-token-que-nao-e-o-da-atribuicao";
        m.sweep();
        assertTrue(m.heldResults().isEmpty());
        List<RemoteMachine.Lost> lost = m.lost();
        assertEquals(1, lost.size());
        assertEquals(new RemoteMachine.Lost(attempt, false, 2, lost.get(0).lostAtEpochSecond(), "IMPOSSIBLE"), lost.get(0));
        assertTrue(gw.losses.isEmpty(), "nada foi dado como entregue nem como avisado");
        long calls = gw.calls.stream().filter(c -> c.path().endsWith("/retention-loss")).count();
        m.sweep();
        assertEquals(calls, gw.calls.stream().filter(c -> c.path().endsWith("/retention-loss")).count(), "aviso impossivel nao e repetido");
        // desabilitar o computador nao apaga o registro da perda (o token ja saiu dele)
        m.disable();
        assertEquals(1, m.lost().size());
        assertEquals("", new MachineVault(dir, new WindowsKeyring()).losses().load(attempt).orElseThrow().token());
    }

    @Test
    void pastaGuardadaQueSomeAntesDoPrazoNaoViraPerdaPorPrazo() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        work.outputs.put("um.txt", "1".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        gw.wrongClosure = true;
        m.pull();
        long now = java.time.Instant.now().getEpochSecond();
        RemoteMachine.Held h = new RemoteMachine.Held(attempt, "NOT_CONFIRMED", work.root.resolve(attempt).resolve("saida").toString(), 1, 1, now, now + 7 * 86_400L);
        work.heldNow.add(h);
        m.sweep();
        // a pessoa copiou e apagou a pasta: ela some da lista, mas o prazo nao venceu - nao ha perda por prazo a registrar nem a avisar
        work.heldNow.clear();
        m.sweep();
        assertTrue(m.lost().isEmpty());
        assertTrue(gw.losses.isEmpty());
    }

    @Test
    void aplicativoReabertoNoMeioDaExecucaoAFilaVoltaEALacunaFicaDita() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        RemoteMachine.LogSink log = work.jobs.get(0).log();
        log.line(false, 1, false, "antes de fechar, enviada");
        m.pull();
        assertEquals(1, gw.logs.size());
        log.line(false, 2, false, "antes de fechar, ainda nao enviada");
        m.close();
        // o aplicativo abre de novo com a execucao em andamento (adotada por quem executa)
        RemoteMachine again = machine(true);
        again.pull();
        assertEquals("RUNNING", gw.attemptState);
        assertEquals(3, gw.logs.size(), "o que estava na fila foi enviado, sem duplicar a linha ja aceita");
        assertEquals("STDOUT|false|antes de fechar, enviada", gw.logs.get(1));
        assertEquals("STDOUT|false|antes de fechar, ainda nao enviada", gw.logs.get(2));
        assertTrue(gw.logs.get(3).startsWith("EXECUTOR|false|O aplicativo deste computador foi reaberto durante a execução"), gw.logs.get(3));
        work.ends("UNKNOWN", null);
        again.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState);
        assertEquals("3|0", gw.logsClosure);
        assertFalse(Files.exists(queueFile(attempt)));
    }

    @Test
    void limiteDaPlatformOQueCabeVaiOQueNaoCabeEContadoETentativaQueNaoExecutouNaoTemRegistro() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        RemoteMachine.LogSink log = work.jobs.get(0).log();
        for (int i = 1; i <= 5; i++) {
            log.line(false, i, false, "linha " + i);
        }
        gw.logLimit = 3;
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState, "limite de registro nao vira falha do robo");
        assertEquals(3, gw.logs.size());
        assertEquals("3|2", gw.logsClosure, "o que nao coube e dito no fechamento");
        assertFalse(Files.exists(queueFile(attempt)));

        // tentativa recusada antes de iniciar: nenhuma fila, nenhuma chamada de registros
        long before = logCalls();
        gw.logLimit = Integer.MAX_VALUE;
        String refused = gw.queue();
        work.refusal = "DEPENDENCIES_MISSING";
        work.state = "RUNNING";
        m.pull();
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals(before, logCalls());
        assertFalse(Files.exists(queueFile(refused)));
    }

    @Test
    void aPastaDeSaidaSoSaiQuandoAConfirmacaoCorrespondeATentativaEAosArquivosEnviados() throws Exception {
        RemoteMachine m = enabled(true);
        // (1) a Platform confirma um arquivo com OUTRO tamanho: nao e confirmacao; nada e fechado nem apagado, e o envio e retomado
        String attempt = gw.queue();
        m.pull();
        work.outputs.put("um.txt", "1".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        gw.wrongResultSize = true;
        m.pull();
        assertNull(gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("SENDING", work.outputsHeld.get(attempt), "a pasta fica guardada e o envio continua sendo tentado");
        assertFalse(Files.exists(journal()), "o desfecho ja esta na Platform: a tentativa esta resolvida, e a entrega segue a parte");
        gw.wrongResultSize = false;
        // (2) o fechamento volta com OUTRA contagem: nao e a confirmacao desta entrega; a pasta fica, marcada
        gw.wrongClosure = true;
        m.forgetHeldWaits();
        m.pull();
        assertEquals("1|0", gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("NOT_CONFIRMED", work.outputsHeld.get(attempt));
        gw.wrongClosure = false;

        // (3) tudo confirmado, mas o arquivo MUDOU na pasta depois do envio: o que esta no disco nao e o que a Platform tem
        work.outputsHeld.clear();
        String second = gw.queue();
        work.outputs.clear();
        work.outputs.put("dois.txt", "2".getBytes(StandardCharsets.UTF_8));
        work.state = "RUNNING";
        m.pull();
        work.ends("SUCCEEDED", 0);
        work.afterListing = () -> work.outputs.put("dois.txt", "outro conteudo".getBytes(StandardCharsets.UTF_8));
        m.pull();
        assertEquals("1|0", gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("CHANGED_AFTER_SEND", work.outputsHeld.get(second));

        // (4) apareceu um arquivo a mais depois do envio: tambem nao apaga
        work.outputsHeld.clear();
        String third = gw.queue();
        work.outputs.clear();
        work.outputs.put("tres.txt", "3".getBytes(StandardCharsets.UTF_8));
        work.state = "RUNNING";
        m.pull();
        work.ends("SUCCEEDED", 0);
        work.afterListing = () -> work.outputs.put("tardio.txt", "x".getBytes(StandardCharsets.UTF_8));
        m.pull();
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("CHANGED_AFTER_SEND", work.outputsHeld.get(third));

        // (5) o caso bom: confirmado arquivo a arquivo, fechamento igual, pasta igual ao confirmado - so entao sai
        work.outputsHeld.clear();
        String fourth = gw.queue();
        work.outputs.clear();
        work.outputs.put("quatro.txt", "4".getBytes(StandardCharsets.UTF_8));
        work.state = "RUNNING";
        m.pull();
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals(List.of(fourth), work.outputsDiscarded);
        assertTrue(work.outputsHeld.isEmpty());
    }

    @Test
    void resultadoPendenteNaoEApagadoAntesDeAPlatformConfirmarORecebimento() throws Exception {
        RemoteMachine m = enabled(true);
        // (1) a rede cai no meio do envio: nada confirmado, nada apagado; os arquivos continuam no disco
        String attempt = gw.queue();
        m.pull();
        work.outputs.put("um.txt", "1".getBytes(StandardCharsets.UTF_8));
        work.outputs.put("dois.txt", "2".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        gw.failResults = 5;
        m.pull();
        m.forgetHeldWaits();
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertNull(gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty(), "sem confirmacao de recebimento, a pasta de saida fica");
        assertTrue(Files.exists(work.root.resolve(attempt).resolve("saida").resolve("um.txt")));
        assertFalse(Files.exists(journal()), "informar o desfecho resolveu a tentativa; o envio dos arquivos continua pendente");
        assertEquals("SENDING", work.outputsHeld.get(attempt));
        // o aplicativo fecha e abre: a varredura de sobras nao apaga resultado nenhum (a pasta esta marcada, dentro do prazo)
        m.close();
        RemoteMachine again = machine(true);
        again.sweep();
        assertEquals(List.of("null"), work.swept);
        assertTrue(work.outputsDiscarded.isEmpty());
        gw.failResults = 0;
        again.pull();
        assertEquals("2|0", gw.resultsClosure);
        assertEquals(List.of(attempt), work.outputsDiscarded, "so agora, com o fechamento aceito");

        // (2) algum arquivo ficou de fora por limite: ele so existe neste computador, entao a pasta de saida NAO e apagada
        work.outputsDiscarded.clear();
        gw.queue();
        work.outputs.clear();
        work.outputs.put("a.txt", new byte[1]);
        work.outputs.put("grande-demais.bin", new byte[(int) MachineChannel.MAX_RESULT_BYTES + 1]);
        work.state = "RUNNING";
        again.pull();
        work.ends("SUCCEEDED", 0);
        again.pull();
        assertEquals("1|1", gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("OUT_OF_LIMIT", work.outputsHeld.get(gw.attemptId), "a pendencia fica marcada, com o motivo");

        // (3) a Platform diz que o envio ja foi fechado por outra entrega: sem confirmacao de que tudo chegou, nada e apagado
        gw.queue();
        work.outputs.clear();
        work.outputs.put("b.txt", new byte[1]);
        work.state = "RUNNING";
        again.pull();
        work.ends("SUCCEEDED", 0);
        gw.resultsClosure = "0|0";
        again.pull();
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("NOT_CONFIRMED", work.outputsHeld.get(gw.attemptId));
        assertFalse(Files.exists(journal()));
        // sem quem execute, a varredura nao faz nada
        assertEquals(0, machine(false).sweep());
    }

    @Test
    void oQuePassaDoLimiteNaoEEnviadoEEContadoNoFechamento() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.outputs.put("a.txt", new byte[10]);
        work.outputs.put("grande-demais.bin", new byte[(int) MachineChannel.MAX_RESULT_BYTES + 1]);
        work.outputs.put("recusado-pela-platform.bin", new byte[5]);
        work.outputs.put("nome-" + "x".repeat(200) + ".txt", new byte[1]);
        work.outputs.put("b.txt", new byte[3]);
        gw.resultOverLimit = "recusado-pela-platform.bin";
        work.ends("FAILED", 3);
        m.pull();
        assertEquals("FAILED", gw.attemptState, "o resultado do robo nao muda por causa dos arquivos");
        assertEquals(3, gw.attemptExit);
        assertEquals(2, gw.results.size());
        assertTrue(gw.results.get(1).startsWith("a.txt|"));
        assertTrue(gw.results.get(2).startsWith("b.txt|"), "o numero segue a ordem do que foi ENVIADO");
        assertEquals("2|3", gw.resultsClosure, "tres ficaram de fora por limite, e isso e dito");
        assertFalse(Files.exists(journal()));
    }

    @Test
    void semRedeNoMeioDoEnvioEleERetomadoSemRepetirODesfechoNemDuplicarArquivo() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.outputs.put("um.txt", "1".getBytes(StandardCharsets.UTF_8));
        work.outputs.put("dois.txt", "2".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        gw.failResults = 1; // o primeiro arquivo nao chega
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertFalse(Files.exists(journal()), "o desfecho ja foi aceito: a tentativa esta resolvida; falta so o envio, que segue a parte");
        assertNull(gw.resultsClosure);
        long finals = gw.reported.stream().filter("SUCCEEDED"::equals).count();
        // o aplicativo fecha e abre: retoma o envio, sem informar o desfecho de novo
        m.close();
        RemoteMachine again = machine(true);
        again.pull();
        assertEquals(finals, gw.reported.stream().filter("SUCCEEDED"::equals).count());
        assertEquals(2, gw.results.size());
        assertEquals("2|0", gw.resultsClosure);
        assertFalse(Files.exists(journal()));
        // execucao sem arquivo nenhum: o envio e fechado com zero
        gw.queue();
        work.outputs.clear();
        work.state = "RUNNING";
        again.pull();
        work.ends("SUCCEEDED", 0);
        again.pull();
        assertEquals("0|0", gw.resultsClosure);
        // tentativa que nao chegou a executar: nenhum envio, nenhum fechamento
        gw.queue();
        work.refusal = "DEPENDENCIES_MISSING";
        again.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertNull(gw.resultsClosure);
        assertTrue(gw.calls.stream().filter(c -> c.path().contains(gw.attemptId)).noneMatch(c -> c.path().contains("/results/")));
    }

    @Test
    void cadaDesfechoDoProcessoViraOEstadoCerto() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.ends("FAILED", 3);
        m.pull();
        assertEquals("FAILED", gw.attemptState);
        assertEquals(3, gw.attemptExit);

        gw.queue();
        gw.reported.clear();
        work.state = "RUNNING";
        m.pull();
        work.ends("TIMED_OUT", null);
        m.pull();
        assertEquals("TIMED_OUT", gw.attemptState);
        assertNull(gw.attemptExit);

        // o motor nao observou o fim (ou nao conhece mais a execucao): terminou sem prova do resultado, sem codigo de saida
        gw.queue();
        work.state = "RUNNING";
        m.pull();
        work.ends("UNKNOWN", 7);
        m.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState);
        assertNull(gw.attemptExit);
        // o motor conhece a execucao e diz que ela nao esta em andamento: este computador CONFERIU que o processo nao esta mais
        // aqui, e afirma isso junto do desfecho desconhecido - e o que reconcilia a tentativa pela maquina
        assertTrue(gw.lastProcessAbsent);
        gw.queue();
        work.state = "RUNNING";
        m.pull();
        work.known = false;
        m.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState);
        // o motor NAO conhece mais a execucao: nao ha o que conferir, e nada e afirmado
        assertFalse(gw.lastProcessAbsent);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void oQueNaoPodeIniciarERecusadoComOMotivoENadaEExecutado() throws Exception {
        RemoteMachine m = enabled(true);
        // quem executa recusa (dependencia ausente no Python escolhido): o motivo dele vai a Platform
        gw.queue();
        work.refusal = "DEPENDENCIES_MISSING";
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("DEPENDENCIES_MISSING", gw.attemptReason);
        assertFalse(Files.exists(journal()));
        assertFalse(work.busy);
        work.refusal = null;
        // o Python escolhido nao esta mais no computador: recusa; nenhum outro e escolhido e nada e iniciado
        gw.queue();
        pythonPresent = false;
        int started = work.jobs.size();
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("ENVIRONMENT_GONE", gw.attemptReason);
        assertEquals(started, work.jobs.size());
        pythonPresent = true;
        // pacote de um tipo que este aplicativo nao executa, ou entrega sem o Python ou o ambiente: nem baixa
        long before = downloads();
        gw.queue();
        gw.offerRule = "nekkora-agent/package-fingerprint/1";
        m.pull();
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        gw.offerRule = "nekkora-agent/external-python-fingerprint/1";
        gw.queue();
        String python = gw.offerPython;
        gw.offerPython = null;
        m.pull();
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        gw.offerPython = python;
        gw.queue();
        gw.offerWorkspace = false;
        m.pull();
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        gw.offerWorkspace = true;
        // computador ocupado com outra execucao (a local, por exemplo): recusa sem preparar
        gw.queue();
        gw.reported.clear();
        work.busy = true;
        m.pull();
        assertEquals("BUSY", gw.attemptReason);
        assertEquals(List.of("REFUSED"), gw.reported);
        assertEquals(before, downloads());
        assertEquals(started, work.jobs.size());
    }

    /**
     * PLATFORM-LICENCIAMENTO-001 - a Platform reconfere a LICENCA antes de autorizar o inicio. Recusada, nada e iniciado aqui e
     * o aplicativo guarda o motivo para dizer a pessoa; uma execucao nova recebida depois apaga esse aviso.
     */
    @Test
    void recusaDaPlatformPorLicencaNaoIniciaNadaEFicaDitaAtéAProximaExecucao() throws Exception {
        RemoteMachine m = enabled(true);
        assertTrue(m.lastDeclined().isEmpty());
        // a tela ABERTA e avisada no instante da recusa, ja com o motivo disponivel (visto no executavel: sem o aviso, a mensagem
        // so aparecia quando a janela era reaberta)
        List<String> seenByTheWindow = new java.util.ArrayList<>();
        m.addListener(() -> seenByTheWindow.add(m.lastDeclined().map(RemoteMachine.Declined::reason).orElse("-")));
        String attempt = gw.queue();
        gw.refuseStart = "LICENSE_EXPIRED";
        m.pull();
        assertTrue(seenByTheWindow.contains("LICENSE_EXPIRED"), "a tela aberta foi avisada da recusa: " + seenByTheWindow);
        assertEquals("REFUSED", gw.attemptState);
        assertTrue(work.jobs.isEmpty(), "nada foi iniciado");
        assertFalse(Files.exists(journal()));
        assertEquals("LICENSE_EXPIRED", m.lastDeclined().orElseThrow().reason());
        assertEquals(attempt, m.lastDeclined().orElseThrow().attemptId());
        // sem licenca: o mesmo caminho, outro motivo
        gw.queue();
        gw.reported.clear();
        gw.refuseStart = "LICENSE_REQUIRED";
        m.pull();
        assertEquals("LICENSE_REQUIRED", m.lastDeclined().orElseThrow().reason());
        assertTrue(work.jobs.isEmpty());
        // a licenca voltou e uma execucao nova foi recebida: o aviso da anterior sai
        gw.refuseStart = null;
        gw.queue();
        gw.reported.clear();
        m.pull();
        assertTrue(m.lastDeclined().isEmpty(), "uma execucao nova foi recebida");
        assertEquals(1, work.jobs.size());
    }

    @Test
    void antesDeIniciarAPlatformReconfereESeAlgoCaiuNadaEIniciado() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        gw.refuseStart = "AUTHORIZATION_REVOKED";
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("AUTHORIZATION_REVOKED", gw.attemptReason);
        assertEquals(List.of("PREPARING", "STARTING"), gw.reported);
        assertTrue(work.jobs.isEmpty(), "nada foi iniciado");
        assertFalse(Files.exists(journal()));
        assertTrue(work.discarded.contains(attempt), "o pacote baixado foi apagado");
        // cancelamento pedido durante a preparacao: idem
        gw.refuseStart = null;
        gw.queue();
        gw.failStates = 0;
        gw.reported.clear();
        gw.cancelRequested = false;
        // a pessoa cancela depois de a maquina receber
        RemoteMachine.Work real = work;
        m.setWork(new RemoteMachine.Work() {
            @Override
            public boolean busy() {
                return false;
            }

            @Override
            public Optional<String> dir(String a) {
                gw.cancelRequested = true; // o pedido chega enquanto a maquina prepara
                return real.dir(a);
            }

            @Override
            public String start(RemoteMachine.Job job) throws RemoteMachine.WorkRefused {
                return real.start(job);
            }

            @Override
            public Optional<RemoteMachine.Progress> progress(String run) {
                return real.progress(run);
            }

            @Override
            public void cancel(String run) {
                real.cancel(run);
            }

            @Override
            public void discard(String a, String run) {
                real.discard(a, run);
            }

            @Override
            public void discardOutputs(String a) {
                real.discardOutputs(a);
            }

            @Override
            public int sweep(String keep) {
                return real.sweep(keep);
            }

            @Override
            public List<RemoteMachine.ResultFile> results(String a, String run) {
                return real.results(a, run);
            }
        });
        m.pull();
        assertEquals("CANCELLED", gw.attemptState);
        assertTrue(work.jobs.isEmpty());
        assertFalse(Files.exists(journal()));
    }

    @Test
    void cancelamentoPedidoDuranteAExecucaoEObedecidoEInformado() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        gw.cancelRequested = true;
        m.pull();
        assertEquals(1, work.cancelled.size(), "a maquina mandou parar");
        assertEquals("RUNNING", gw.attemptState, "so muda quando o processo parar de fato");
        m.pull();
        assertEquals("CANCELLED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void pacoteQueNaoConfereOuQueAPlatformNaoTemERecusadoSemExecutar() throws Exception {
        RemoteMachine m = enabled(true);
        // os bytes nao batem com o SHA-256 declarado: nada fica no disco, nada e iniciado
        String attempt = gw.queue();
        gw.declaredSha256 = "0".repeat(64);
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("PACKAGE_INVALID", gw.attemptReason);
        assertFalse(Files.exists(work.root.resolve(attempt).resolve("pacote.zip")));
        assertTrue(work.jobs.isEmpty());
        gw.declaredSha256 = null;
        // a Platform nao tem os bytes (ou eles nao conferem la)
        gw.queue();
        gw.packageUnavailable = true;
        m.pull();
        assertEquals("PACKAGE_INVALID", gw.attemptReason);
        assertTrue(work.jobs.isEmpty());
        assertFalse(Files.exists(journal()));
    }

    @Test
    void respostaPerdidaDaConfirmacaoRepeteAMesmaConfirmacaoSemNovaEntrega() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.dropAckResponses = 2;
        m.pull();
        assertEquals("RECEIVED", gw.attemptState, "o servidor processou; a resposta se perdeu");
        assertTrue(journalText().contains("\"confirmada\":false"));
        int epoch = gw.attemptEpoch;
        gw.dropAckResponses = 0; // o cliente do JDK pode ou nao ter repetido sozinho: dai em diante as respostas chegam
        m.pull();
        assertEquals(1, gw.count(NEXT), "nao houve nova entrega");
        assertEquals(epoch, gw.attemptEpoch);
        assertEquals("RUNNING", gw.attemptState);
    }

    @Test
    void aplicativoFechadoDepoisDeGravarAProximaAberturaSegueComOQueJaTinha() throws Exception {
        RemoteMachine first = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        first.pull();
        assertEquals("OFFERED", gw.attemptState);
        assertTrue(Files.exists(journal()), "gravada ANTES de confirmar");
        first.close();

        RemoteMachine second = machine(true);
        second.pull();
        assertEquals(1, gw.count(NEXT), "segue com a que ja tinha; nao pergunta por outra");
        assertEquals("RUNNING", gw.attemptState);
        // em execucao, o aplicativo fecha e abre: continua acompanhando a MESMA execucao, sem iniciar outra
        second.close();
        RemoteMachine third = machine(true);
        third.pull();
        assertEquals(1, work.jobs.size());
        work.ends("SUCCEEDED", 0);
        third.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    /**
     * Decisoes de 16/11: junto do fim, este computador diz o que sabe dos PROCESSOS da tentativa - ausentes (com prova), ainda
     * ativos, ou sem como saber. Sem a prova ele confere de novo a cada consulta e repete o MESMO relato so quando o que sabe
     * muda; com a prova, uma vez, e faz a limpeza que esperava os processos.
     */
    @Test
    void juntoDoFimDizOQueSabeDosProcessosERepeteSoQuandoMuda() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.treeGone = false;
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertEquals("ACTIVE", gw.lastProcessTree, "ha processo da automacao vivo: o desfecho vai, a prova nao");
        assertEquals(RemoteMachine.Activity.REMOTE_FINISHING, m.activity(), "a tela diz: finalizando");
        // os processos continuam: nada e repetido
        m.pull();
        m.pull();
        assertEquals(List.of(), gw.afterEnd);
        // o aplicativo fecha e abre: a pendencia esta no disco, protegida
        m.close();
        RemoteMachine again = machine(true);
        again.pull();
        assertEquals(List.of(), gw.afterEnd);
        // perdeu a capacidade de identifica-los: passa a dizer que nao sabe, uma vez
        work.processes = "UNCERTAIN";
        again.pull();
        again.pull();
        assertEquals(List.of("SUCCEEDED+UNCERTAIN"), gw.afterEnd);
        assertEquals(RemoteMachine.Activity.REMOTE_UNCERTAIN, again.activity(), "a tela passa a dizer: ocupacao incerta");
        // ha prova (o grupo esvaziou, ou a sessao do Windows acabou): afirma uma vez
        work.processes = "GONE";
        again.pull();
        assertEquals(List.of("SUCCEEDED+UNCERTAIN", "SUCCEEDED+ausente"), gw.afterEnd);
        assertEquals("SUCCEEDED", gw.attemptState);
        again.pull();
        assertEquals(2, gw.afterEnd.size(), "afirma uma vez");
        assertEquals(RemoteMachine.Activity.IDLE, again.activity());

        // com prova ja no fim: ABSENT junto do desfecho, e nada fica pendente
        gw.afterEnd.clear();
        gw.queue();
        work.state = "RUNNING";
        again.pull();
        work.ends("FAILED", 3);
        again.pull();
        assertEquals("ABSENT", gw.lastProcessTree);
        again.pull();
        assertEquals(List.of(), gw.afterEnd);

        // sem prova do resultado E sem como saber dos processos; se o motor esquecer a execucao, nada mais e dito
        gw.queue();
        work.state = "RUNNING";
        again.pull();
        work.processes = "UNCERTAIN";
        work.ends("UNKNOWN", null);
        again.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState);
        assertEquals("UNCERTAIN", gw.lastProcessTree);
        work.known = false;
        work.processes = "GONE";
        again.pull();
        again.pull();
        assertEquals(List.of(), gw.afterEnd, "o motor nao conhece mais a execucao: nao ha o que conferir");
    }

    /**
     * Decisao de 17/11: quando a ocupacao deste computador muda (por uma execucao da Platform ou por uma iniciada aqui), a tela e
     * avisada e a presenca e enviada JA. O que sai e so o sim/nao de sempre - ocupada, pouco espaco -, sem dizer por que.
     */
    @Test
    void quandoAOcupacaoMudaATelaEAvisadaEAPresencaVaiJaSoComOSimOuNao() throws Exception {
        RemoteMachine m = enabled(true);
        java.util.concurrent.atomic.AtomicInteger avisos = new java.util.concurrent.atomic.AtomicInteger();
        m.addListener(avisos::incrementAndGet);
        gw.lastHeartbeat = null;
        work.busy = true;
        int antes = avisos.get();
        m.occupancyChanged();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (gw.lastHeartbeat == null && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(avisos.get() > antes, "a tela foi avisada");
        assertTrue(gw.lastHeartbeat.contains("\"remote\":{\"busy\":true,\"diskPressure\":false}"), gw.lastHeartbeat);
        // a presenca inteira: versao, contrato, capacidades e os dois sinais - nada alem disso
        assertTrue(gw.lastHeartbeat.matches("\\{\"agentVersion\":\"[^\"]*\",\"contractVersion\":\"[^\"]*\",\"capabilities\":\\[[A-Z0-9_\",]*\\],\"remote\":\\{\"busy\":true,\"diskPressure\":false\\}\\}"),
                gw.lastHeartbeat);
        gw.lastHeartbeat = null;
        work.busy = false;
        m.occupancyChanged();
        deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (gw.lastHeartbeat == null && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(gw.lastHeartbeat.contains("\"busy\":false"), gw.lastHeartbeat);
    }

    @Test
    void fechouEntrePedirOInicioEGravarAExecucaoTerminaSemProvaENadaELancadoDeNovo() throws Exception {
        RemoteMachine first = enabled(true);
        gw.queue();
        work.crash = new AssertionError("o aplicativo caiu aqui");
        assertThrows(AssertionError.class, first::pull);
        assertEquals("STARTING", gw.attemptState);
        assertTrue(journalText().contains("\"fase\":\"STARTING\""), "a intencao de iniciar foi gravada antes");
        first.close();
        work.crash = null;

        RemoteMachine second = machine(true);
        second.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState);
        assertFalse(gw.lastProcessAbsent, "fechou antes de registrar a execucao: nao ha processo conhecido para conferir, e nada e afirmado");
        assertEquals(1, work.jobs.size(), "nada foi lancado de novo");
        assertFalse(Files.exists(journal()));
    }

    @Test
    void entregaQueNaoValeMaisParaOQueEstiverRodandoENaoAlteraNadaNaPlatform() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        // outra entrega tomou o lugar (token e epoca novos no servidor)
        gw.attemptToken = "Z".repeat(43);
        gw.attemptEpoch++;
        m.pull();
        assertEquals(1, work.cancelled.size(), "o que esta entrega iniciou aqui foi parado");
        assertEquals("RUNNING", gw.attemptState, "o consumidor obsoleto nao sobrescreve o estado");
        assertFalse(Files.exists(journal()));
        // a tentativa que a Platform nao tem mais: idem
        gw.queue();
        work.state = "RUNNING";
        m.pull();
        gw.attemptId = null;
        m.pull();
        assertEquals(2, work.cancelled.size());
        assertFalse(Files.exists(journal()));
        assertEquals(Status.RECEIVING, m.view().status());
    }

    @Test
    void semRedeAExecucaoEmAndamentoNaoEInterrompida() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        gw.failStates = 3;
        m.pull();
        m.pull();
        assertTrue(work.cancelled.isEmpty(), "falta de comunicacao nao para o processo");
        assertTrue(Files.exists(journal()));
        // termina sem rede: o desfecho fica no diario e e informado quando a rede voltar
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        assertTrue(journalText().contains("\"informar\":\"SUCCEEDED\""));
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void entregaForaDoContratoNaoEGravadaNemConfirmada() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.malformedOffer = true;
        m.pull();
        assertFalse(Files.exists(journal()));
        assertEquals(0, acks());
        assertEquals("OFFERED", gw.attemptState);
    }

    @Test
    void semConseguirGravarODiarioNadaEConfirmado() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        Files.createDirectories(journal().resolve("ocupado")); // o lugar do arquivo esta tomado: gravar falha
        m.pull();
        assertEquals(0, acks(), "sem diario, sem confirmacao: a Platform entrega de novo");
        assertEquals("OFFERED", gw.attemptState);
    }

    @Test
    void pausadaNadaEEntregueEComTentativaAtivaOComputadorNaoEDesabilitado() throws Exception {
        RemoteMachine m = enabled(true);
        m.pause();
        gw.queue();
        m.pull();
        assertEquals("QUEUED", gw.attemptState);
        assertFalse(Files.exists(journal()));
        m.resume();
        gw.failAcks = 1;
        m.pull();
        assertTrue(Files.exists(journal()));
        // ha uma tentativa recebida e ainda nao resolvida: desvincular agora a deixaria sem desfecho. Nada muda, nem aqui nem la
        Refused active = assertThrows(Refused.class, m::disable);
        assertEquals(Problem.ATTEMPT_ACTIVE, active.problem());
        assertTrue(Files.exists(journal()));
        assertEquals(Status.RECEIVING, m.view().status());
        assertTrue(gw.unlinkOperations.isEmpty(), "o desvinculo nem foi pedido a Platform");
        // em execucao: continua recusado; pausar continua possivel e NAO mexe na execucao em andamento
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        assertEquals(Problem.ATTEMPT_ACTIVE, assertThrows(Refused.class, m::disable).problem());
        m.pause();
        assertEquals(Status.PAUSED, m.view().status());
        assertTrue(work.cancelled.isEmpty(), "pausar novos trabalhos nao para a execucao atual");
        m.pull();
        assertEquals("RUNNING", gw.attemptState, "a execucao em andamento segue acompanhada com a maquina pausada");
        // o desfecho verdadeiro e informado; so entao o computador pode ser desabilitado
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertFalse(Files.exists(journal()));
        m.disable();
        assertEquals(Status.NOT_ENABLED, m.view().status());
        assertNull(gw.credential);
    }

    /** O que o processo "escreveu": entra na fila de registros da tentativa, como a captura real faria. */
    private void writes(int job, String... lines) {
        for (String l : lines) {
            work.jobs.get(job).log().line(false, System.currentTimeMillis(), false, l);
        }
    }

    private long calls(String part) {
        return gw.calls.stream().filter(c -> c.path().contains(part)).count();
    }

    @Test
    void informarODesfechoNaoEncerraAEntregaQueContinuaAteAPlatformConfirmar() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        writes(0, "linha 1", "linha 2", "linha 3");
        work.outputs.put("relatorio.txt", "ok".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        // a Platform aceita o desfecho, mas o envio de registros e de resultados falha (servidor indisponivel)
        gw.failLogs = 1000;
        gw.failResults = 1000;
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertEquals(0, gw.attemptExit);
        // a tentativa esta RESOLVIDA: o diario saiu, a maquina esta livre e volta a perguntar por trabalho
        assertFalse(Files.exists(journal()));
        assertEquals(RemoteMachine.Activity.IDLE, m.activity());
        long asked = calls("/remote/next");
        // ... e a entrega NAO acabou: fila e pasta guardadas, ditas como "ainda sendo enviadas", com data de remocao
        assertTrue(Files.exists(queueFile(attempt)));
        assertEquals("SENDING", work.outputsHeld.get(attempt));
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals(List.of("SENDING"), m.heldLogs().stream().map(RemoteMachine.HeldLogs::reason).toList());
        assertNull(gw.logsClosure);
        assertNull(gw.resultsClosure);
        // a espera entre tentativas: a consulta seguinte, logo depois, nao insiste; pergunta por trabalho novo normalmente
        long sends = calls("/logs") + calls("/results");
        long since = Files.getLastModifiedTime(queueFile(attempt)).toMillis();
        m.pull();
        assertEquals(sends, calls("/logs") + calls("/results"));
        assertEquals(asked + 1, calls("/remote/next"));
        // passada a espera, tenta de novo; ainda falhando, nada e apagado e o prazo de guarda NAO recomeca
        m.forgetHeldWaits();
        m.pull();
        assertTrue(calls("/logs") + calls("/results") > sends);
        assertTrue(Files.exists(queueFile(attempt)));
        assertEquals(since, Files.getLastModifiedTime(queueFile(attempt)).toMillis(), "tentar de novo nao recomeca o prazo de guarda");
        // o aplicativo fecha e abre: a entrega continua de onde estava, sem informar o desfecho de novo
        long finals = gw.reported.stream().filter("SUCCEEDED"::equals).count();
        m.close();
        RemoteMachine again = machine(true);
        gw.failLogs = 0;
        gw.failResults = 0;
        again.pull();
        assertEquals(finals, gw.reported.stream().filter("SUCCEEDED"::equals).count());
        assertEquals(3, gw.logs.size());
        assertEquals("3|0", gw.logsClosure);
        assertEquals("1|0", gw.resultsClosure);
        // so com a confirmacao de TUDO o que estava guardado sai deste computador, e nao sobra registro nem token
        assertFalse(Files.exists(queueFile(attempt)));
        assertEquals(List.of(attempt), work.outputsDiscarded);
        assertTrue(again.heldLogs().isEmpty());
        assertTrue(again.lost().isEmpty());
        assertFalse(Files.exists(dir.resolve("guarda").resolve(attempt + ".guarda")));
    }

    @Test
    void prazoDeGuardaVencidoComOEnvioAindaSendoTentadoRegistraAPerdaComAQuantidadeVerdadeiraEAvisaQuandoAPlatformVolta() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        writes(0, "linha 1", "linha 2", "linha 3", "linha 4");
        work.ends("SUCCEEDED", 0);
        // a Platform aceita o desfecho e fica indisponivel para os registros
        gw.failLogs = 1000;
        gw.failLoss = 1000;
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertEquals(List.of("SENDING"), m.heldLogs().stream().map(RemoteMachine.HeldLogs::reason).toList());
        // o prazo de guarda vence com o envio ainda sendo tentado: a fila sai, e a perda fica com as 4 linhas que havia - nao zero
        age(queueFile(attempt), java.time.Duration.ofDays(8));
        m.sweep();
        assertFalse(Files.exists(queueFile(attempt)));
        List<RemoteMachine.Lost> lost = m.lost().stream().filter(RemoteMachine.Lost::logs).toList();
        assertEquals(1, lost.size());
        assertEquals(4, lost.get(0).count());
        assertEquals("PENDING", lost.get(0).notice(), "a Platform ainda nao pode ser avisada");
        assertTrue(gw.losses.isEmpty());
        assertTrue(m.heldLogs().isEmpty(), "o que foi removido nao aparece mais como pendente");
        // depois da remocao nao ha novo envio de registros: nada e dado como entregue
        long sends = calls("/logs");
        m.forgetHeldWaits();
        m.pull();
        assertEquals(sends, calls("/logs"));
        assertNull(gw.logsClosure);
        // a Platform volta: a limpeza seguinte avisa, com a quantidade que havia aqui
        gw.failLoss = 0;
        m.sweep();
        assertEquals(List.of(attempt + "|LOGS|4"), gw.losses.stream().filter(x -> x.contains("|LOGS|")).toList());
        assertEquals("SENT", m.lost().stream().filter(RemoteMachine.Lost::logs).findFirst().orElseThrow().notice());
    }

    @Test
    void filaGrandeEEnviadaEmFatiasSemSegurarAMaquinaEOFechamentoSoVemComTudoEnviado() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        // mais de dois lotes (o lote e de ate 200 linhas)
        String[] many = new String[450];
        for (int i = 0; i < many.length; i++) {
            many[i] = "linha " + i;
        }
        writes(0, many);
        work.outputs.put("saida.txt", "x".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        // fatia de tempo esgotada logo depois de um lote: a consulta do desfecho envia um lote ao informar e outro na passada
        // normal dela, e devolve a vez - nao fica enviando ate o fim
        m.heldSendBudgetMillis = 0;
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertFalse(Files.exists(journal()), "a tentativa esta resolvida: a maquina nao fica presa ao envio");
        assertEquals(400, gw.logs.size());
        assertNull(gw.logsClosure, "com linhas por enviar, o envio nao e fechado");
        assertNull(gw.resultsClosure, "os registros vao antes dos resultados");
        assertEquals(List.of("SENDING"), m.heldLogs().stream().map(RemoteMachine.HeldLogs::reason).toList());
        assertTrue(Files.exists(queueFile(attempt)));
        long batches = gw.logBatches.get();
        // a consulta seguinte continua de ONDE PAROU (nao recomeca do inicio), sem espera (nao foi falha), e a maquina pergunta
        // por trabalho no meio
        long asked = calls("/remote/next");
        m.pull();
        assertEquals(asked + 1, calls("/remote/next"));
        assertEquals(450, gw.logs.size());
        assertEquals(batches + 1, gw.logBatches.get(), "so o lote que faltava foi enviado");
        assertEquals("450|0", gw.logsClosure);
        assertEquals("1|0", gw.resultsClosure);
        assertFalse(Files.exists(queueFile(attempt)));
        assertEquals(List.of(attempt), work.outputsDiscarded);
        // na ordem em que foram escritas, sem repeticao
        assertEquals("STDOUT|false|linha 0", gw.logs.get(1));
        assertEquals("STDOUT|false|linha 449", gw.logs.get(450));
    }

    @Test
    void comOutraTentativaAtivaOQueFicouGuardadoCedeAVezUmLotePorConsultaENenhumArquivoEOCancelamentoNaoEspera() throws Exception {
        RemoteMachine m = enabled(true);
        String first = gw.queue();
        m.pull();
        String[] many = new String[850];
        for (int i = 0; i < many.length; i++) {
            many[i] = "linha " + i;
        }
        writes(0, many);
        work.outputs.put("saida.txt", "x".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        m.heldSendBudgetMillis = 0;
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertEquals(400, gw.logs.size(), "dois lotes na consulta do desfecho; 450 linhas e o arquivo ficam guardados");
        // a fatia volta ao normal (10 s): sem a regra de ceder a vez, a consulta seguinte enviaria tudo de uma vez
        m.heldSendBudgetMillis = 10_000;
        work.state = "RUNNING";
        work.exitCode = null;
        gw.queueKeepingEarlier();
        m.pull();
        gw.settle();
        assertEquals("RUNNING", gw.attemptState, "a tentativa nova foi recebida e esta em execucao");
        assertEquals(600, gw.earlierLogs.size(), "com tentativa ativa: UM lote dos registros guardados por consulta");
        assertEquals(1, gw.earlierLogBatches.get());
        assertNull(gw.earlierLogsClosure);
        assertTrue(gw.earlierResults.isEmpty(), "com tentativa ativa: nenhum arquivo de resultado guardado e enviado");
        // o pedido de cancelamento da tentativa ativa e obedecido na consulta em que chega - antes do lote guardado
        gw.cancelRequested = true;
        m.pull();
        gw.settle();
        assertEquals(1, work.cancelled.size(), "a maquina mandou parar na mesma consulta");
        assertEquals(800, gw.earlierLogs.size(), "e o que estava guardado andou mais um lote, sem perder o lugar");
        assertTrue(gw.earlierResults.isEmpty());
        // a tentativa ativa termina: a maquina fica livre e o que estava guardado e entregue ate a confirmacao
        work.ends("CANCELLED", null);
        m.pull();
        gw.settle();
        assertEquals("CANCELLED", gw.attemptState);
        m.pull();
        gw.settle();
        assertEquals(850, gw.earlierLogs.size());
        assertEquals("850|0", gw.earlierLogsClosure);
        assertEquals("1|0", gw.earlierResultsClosure);
        assertEquals("STDOUT|false|linha 849", gw.earlierLogs.get(850), "na ordem, sem repeticao");
        assertFalse(Files.exists(queueFile(first)));
    }

    @Test
    void arquivosDeResultadoGuardadosVaoEmFatiasENaoSaoEnviadosDeNovoNaRetomada() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        for (int i = 1; i <= 4; i++) {
            work.outputs.put("arquivo-" + i + ".txt", ("conteudo " + i).getBytes(StandardCharsets.UTF_8));
        }
        work.ends("SUCCEEDED", 0);
        // fatia esgotada logo depois de um arquivo: cada passada envia UM e devolve a vez; a consulta do desfecho tem duas passadas
        m.heldSendBudgetMillis = 0;
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertEquals(2, gw.results.size());
        assertNull(gw.resultsClosure, "com arquivos por enviar, o envio nao e fechado");
        assertTrue(work.outputsDiscarded.isEmpty(), "e a pasta de saida nao e apagada");
        long uploads = calls("/results/");
        m.pull();
        assertEquals(3, gw.results.size());
        assertEquals(uploads + 1, calls("/results/"), "so o arquivo seguinte foi enviado: os confirmados nao vao de novo");
        m.pull();
        assertEquals(4, gw.results.size());
        assertEquals("4|0", gw.resultsClosure);
        assertEquals(List.of(attempt), work.outputsDiscarded, "so com tudo confirmado a pasta sai");
    }

    @Test
    void impedimentoDeAutorizacaoFicaDitoComOMotivoENaoEContornado() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        writes(0, "linha unica");
        work.outputs.put("saida.txt", "x".getBytes(StandardCharsets.UTF_8));
        work.ends("FAILED", 2);
        gw.failLogs = 1000;
        gw.failResults = 1000;
        m.pull();
        assertEquals("FAILED", gw.attemptState);
        assertEquals("SENDING", work.outputsHeld.get(attempt));
        // a Platform deixa de reconhecer a atribuicao (404): nao ha novo envio, e o motivo fica dito - nos dois tipos
        gw.failLogs = 0;
        gw.failResults = 0;
        gw.attemptId = java.util.UUID.randomUUID().toString();
        m.forgetHeldWaits();
        m.pull();
        assertEquals("NOT_AUTHORIZED", work.outputsHeld.get(attempt));
        assertEquals(List.of("LINK_ENDED"), m.heldLogs().stream().map(RemoteMachine.HeldLogs::reason).toList());
        assertTrue(Files.exists(queueFile(attempt)), "o que esta guardado fica ate o prazo de guarda");
        assertTrue(work.outputsDiscarded.isEmpty());
        long sends = calls("/logs") + calls("/results");
        m.forgetHeldWaits();
        m.pull();
        m.pull();
        assertEquals(sends, calls("/logs") + calls("/results"), "com o impedimento dito, nada mais e enviado");
    }

    @Test
    void vinculoEncerradoComExecucaoEmAndamentoParaOProcessoEGuardaOQueHaSemEnviar() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        writes(0, "antes da revogacao");
        work.outputs.put("parcial.txt", "p".getBytes(StandardCharsets.UTF_8));
        // quem administra revogou a maquina: a Platform nao reconhece mais a credencial
        gw.credential = null;
        long before = gw.calls.size();
        m.pull();
        assertEquals(Status.ENDED, m.view().status());
        // este computador nao esta mais autorizado a executar trabalho da Platform: o processo e parado, de forma controlada
        assertEquals(1, work.cancelled.size());
        assertFalse(Files.exists(journal()));
        // o que ele produziu fica AQUI, dito como impedido por autorizacao; nada e enviado por outro caminho
        assertEquals("LINK_ENDED", work.outputsHeld.get(attempt));
        assertTrue(work.outputsDiscarded.isEmpty());
        assertTrue(Files.exists(queueFile(attempt)));
        assertEquals(List.of("LINK_ENDED"), m.heldLogs().stream().map(RemoteMachine.HeldLogs::reason).toList());
        assertEquals(0, gw.logs.size());
        assertNull(gw.resultsClosure);
        assertEquals("RUNNING", gw.attemptState, "esta maquina nao informou desfecho nenhum: quem encerra a tentativa e a Platform");
        long after = gw.calls.size();
        m.pull();
        m.pull();
        assertEquals(after, gw.calls.size(), "sem vinculo, nenhuma chamada a mais");
        assertTrue(after - before <= 2);
        // remover o vinculo daqui nao apaga o que ficou guardado nem o registro do motivo
        m.disable();
        assertEquals(Status.NOT_ENABLED, m.view().status());
        assertTrue(Files.exists(queueFile(attempt)));
        assertEquals(List.of("LINK_ENDED"), m.heldLogs().stream().map(RemoteMachine.HeldLogs::reason).toList());
        // decisao de 16/11: um VINCULO NOVO (outro codigo, outra credencial) nao herda o que o anterior deixou. Nada do vinculo
        // anterior e reenviado sozinho nem passa a ser do novo; a pendencia continua guardada, com o mesmo motivo
        assertEquals(Status.RECEIVING, m.enable(gw.code("remote")).status());
        m.beat();
        long sent = calls("/logs") + calls("/results") + calls("/retention-loss") + calls(attempt);
        m.forgetHeldWaits();
        m.pull();
        m.pull();
        assertEquals(sent, calls("/logs") + calls("/results") + calls("/retention-loss") + calls(attempt),
                "com o vinculo novo, nenhuma chamada sobre a tentativa do vinculo anterior");
        assertTrue(Files.exists(queueFile(attempt)));
        assertEquals("LINK_ENDED", work.outputsHeld.get(attempt));
        assertEquals(List.of("LINK_ENDED"), m.heldLogs().stream().map(RemoteMachine.HeldLogs::reason).toList());
        assertEquals(0, gw.logs.size());
        // vencido o prazo de guarda, a remocao fica registrada como perda, e o aviso a Platform e dado por impossivel - mesmo
        // com um vinculo novo valendo: a credencial nova nao fala pela tentativa do vinculo anterior
        age(queueFile(attempt), java.time.Duration.ofDays(8));
        m.sweep();
        assertEquals(sent, calls("/logs") + calls("/results") + calls("/retention-loss") + calls(attempt));
        assertFalse(Files.exists(queueFile(attempt)));
        List<RemoteMachine.Lost> lost = m.lost();
        assertEquals(1, lost.stream().filter(RemoteMachine.Lost::logs).count());
        assertTrue(lost.stream().allMatch(l -> "IMPOSSIBLE".equals(l.notice())));
    }

    @Test
    void oDiarioDeOutraMaquinaOuIlegivelNaoVale() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        m.pull();
        AttemptJournal j = new AttemptJournal(dir, new WindowsKeyring());
        assertTrue(j.load(gw.agentId).isPresent());
        assertTrue(j.load(gw.workerId).isEmpty(), "outra maquina (vinculo anterior)");
        assertFalse(j.load(gw.agentId).orElseThrow().toString().contains(gw.attemptToken));
        Files.writeString(journal(), "{\"versao\":2,", StandardCharsets.UTF_8);
        assertTrue(j.load(gw.agentId).isEmpty());
    }

    private static final String CHECK = "5c5c5c5c-5c5c-4c5c-8c5c-5c5c5c5c5c5c";

    private void asks(String environment, String... dependencies) {
        gw.dependencyCheck = "{\"checkId\":\"" + CHECK + "\",\"pythonEnvironmentId\":\"" + environment + "\",\"dependencies\":[\""
                + String.join("\",\"", dependencies) + "\"]}";
    }

    @Test
    void aPlatformPedeAConferenciaDasDependenciasEAMaquinaRespondeSoOQueFaltaSemCaminhoNemExecucao() throws Exception {
        RemoteMachine m = enabled(true);
        // parada: o pedido vem com a consulta de trabalho. A maquina confere no Python LOCAL do identificador opaco e responde
        asks(gw.offerPython, "idna==3.7", "requests==2.32.3");
        work.verified = new RemoteMachine.Verified("MISSING", List.of("requests==2.32.3"));
        m.pull();
        assertEquals(List.of(LOCAL_PYTHON + "|[idna==3.7, requests==2.32.3]"), work.verifications);
        assertEquals(List.of(CHECK + "|{\"outcome\":\"MISSING\",\"missing\":[\"requests==2.32.3\"]}"), gw.checkAnswers);
        assertFalse(gw.checkAnswers.get(0).toLowerCase().contains("python312"), "o caminho do Python nunca vai a Platform");
        assertTrue(work.jobs.isEmpty(), "conferir nao e executar: nada foi iniciado");
        assertFalse(Files.exists(journal()), "nem ha tentativa: o diario continua vazio");
        // respondido, o pedido nao volta; satisfeito vai sem lista
        asks(gw.offerPython, "idna==3.7");
        work.verified = new RemoteMachine.Verified("SATISFIED", List.of());
        m.pull();
        assertEquals(CHECK + "|{\"outcome\":\"SATISFIED\"}", gw.checkAnswers.get(1));
        // o ambiente pedido nao e (mais) deste computador: dito assim, sem conferir em outro e sem escolher outro
        work.verifications.clear();
        asks("Z".repeat(43), "idna==3.7");
        m.pull();
        assertEquals(CHECK + "|{\"outcome\":\"ENVIRONMENT_GONE\"}", gw.checkAnswers.get(2));
        assertTrue(work.verifications.isEmpty());
        // a conferencia falhou, ou respondeu algo que nao foi perguntado: a maquina diz que nao conseguiu verificar - nunca inventa
        asks(gw.offerPython, "idna==3.7");
        work.verifyCrash = new IllegalStateException("o python nao respondeu");
        m.pull();
        assertEquals(CHECK + "|{\"outcome\":\"NOT_VERIFIED\"}", gw.checkAnswers.get(3));
        work.verifyCrash = null;
        asks(gw.offerPython, "idna==3.7");
        work.verified = new RemoteMachine.Verified("MISSING", List.of("numpy==2.1.0"));
        m.pull();
        assertEquals(CHECK + "|{\"outcome\":\"NOT_VERIFIED\"}", gw.checkAnswers.get(4));
        // pedido fora do formato (identificador que nao e opaco): ignorado, sem resposta e sem erro
        gw.dependencyCheck = "{\"checkId\":\"" + CHECK + "\",\"pythonEnvironmentId\":\"C:\\\\Python312\\\\python.exe\",\"dependencies\":[]}";
        m.pull();
        assertEquals(5, gw.checkAnswers.size());
        gw.dependencyCheck = null;

        // com uma execucao em andamento a maquina nao pergunta por trabalho: o pedido vem pela rota propria, e a execucao segue
        String attempt = gw.queue();
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        asks(gw.offerPython, "idna==3.7");
        work.verified = new RemoteMachine.Verified("SATISFIED", List.of());
        long nexts = calls("/remote/next");
        m.pull();
        assertEquals(nexts, calls("/remote/next"));
        assertEquals(6, gw.checkAnswers.size());
        assertEquals("RUNNING", gw.attemptState);
        assertTrue(work.cancelled.isEmpty());
        assertEquals(1, work.jobs.size());
        // a resposta nao chega (503): a Platform fica sem resposta e dira "nao foi possivel verificar"; nada quebra aqui
        asks(gw.offerPython, "idna==3.7");
        gw.failCheckAnswers = 1;
        m.pull();
        assertEquals(6, gw.checkAnswers.size());
        assertEquals("RUNNING", gw.attemptState);
        work.ends("SUCCEEDED", 0);
        gw.dependencyCheck = null;
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertNotNull(attempt);
        // sem quem execute, a maquina nao tem como conferir: responde que nao verificou
        m.close();
        RemoteMachine plain = machine(false);
        plain.setWork(null);
        asks(gw.offerPython, "idna==3.7");
        plain.pull();
        assertEquals(6, gw.checkAnswers.size(), "sem quem execute, a maquina nem pergunta por trabalho");
    }
}
