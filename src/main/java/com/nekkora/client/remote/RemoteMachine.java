package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.nekkora.client.remote.MachineChannel.Problem;
import com.nekkora.client.remote.MachineChannel.Refused;

/**
 * A habilitacao DESTE computador para execucoes solicitadas pela Platform. E um ato da maquina, distinto da entrada de
 * uma pessoa no aplicativo: nada aqui recebe, le ou guarda a sessao de quem entrou, e entrar ou sair nao habilita nem
 * desabilita nada.
 * <ul>
 * <li>{@link #enable}: so com um codigo criado na Platform para isso e com a confirmacao feita neste computador;</li>
 * <li>{@link #pause} e {@link #resume}: o recebimento de novos trabalhos; a referencia e o servidor;</li>
 * <li>{@link #disable}: encerra o vinculo na Platform e remove a credencial deste computador;</li>
 * <li>{@link #beat}: a presenca, enquanto o aplicativo esta aberto.</li>
 * </ul>
 * Com quem executa ligado ({@link #setWork}), o aplicativo declara que executa, recebe a tentativa, baixa o pacote e a conduz
 * ate o desfecho ({@link #pull}). Esta camada nao cria processos nem abre pacotes: so fala com a Platform e guarda o diario.
 * A execucao feita aqui pela pessoa continua local e nao passa por esta classe.
 */
public final class RemoteMachine implements AutoCloseable {

    public enum Status {
        /** Este computador nao consegue proteger a credencial: a habilitacao nao e oferecida. */
        UNAVAILABLE,
        NOT_ENABLED,
        RECEIVING,
        PAUSED,
        /** Ha um vinculo guardado, mas a Platform o encerrou (ou ele e de outro endereco, ou esta ilegivel). */
        ENDED
    }

    /** {@code inContact}: a ultima presenca foi aceita; {@code false} antes da primeira ou depois de uma falha de rede. */
    public record View(Status status, boolean inContact, Optional<String> credentialExpiresAt, Optional<Integer> pythonEnvironments) {
    }

    /**
     * Uma instalacao de Python que este computador achou. {@code localId} identifica o ambiente AQUI (inclui o caminho) e
     * nunca sai do computador: o que vai para a Platform e um identificador opaco derivado dele.
     */
    public record PythonSeen(String localId, boolean virtual, String version) {
    }

    /**
     * T06 - um ambiente Python como a Platform o conhece e como este computador o conhece. {@code code}: os 6 primeiros
     * caracteres do identificador opaco publicado - o mesmo codigo que a Platform mostra ao lado da versao, para a pessoa
     * distinguir dois ambientes iguais e achar aqui o que escolheu la. {@code where}: onde ele esta NESTE computador; so a tela
     * deste computador o mostra, e ele nunca vai a Platform.
     */
    public record PythonHere(String code, String version, boolean virtual, String where) {
    }

    /** Os ambientes da ultima publicacao feita nesta abertura do aplicativo, na ordem publicada. Vazio antes da primeira. */
    public synchronized List<PythonHere> pythonHere() {
        return link == null || ended ? List.of() : publishedHere;
    }

    /** O que este computador esta fazendo agora. So estado operacional: nada do conteudo de uma execucao. */
    public enum Activity {
        /** Nenhuma execucao em andamento. */
        IDLE,
        /** Uma execucao iniciada AQUI, pelo botao Executar. Nada dela vai a Platform. */
        LOCAL_RUNNING,
        /** Uma solicitacao da Platform foi recebida e esta sendo preparada (pacote, Python, dependencias). */
        REMOTE_PREPARING,
        /** Uma solicitacao da Platform esta em execucao. */
        REMOTE_RUNNING,
        /** A execucao pedida pela Platform terminou; o desfecho, os registros e os resultados estao sendo enviados. */
        REMOTE_SENDING
    }

    /** A atividade de agora e a origem dela. Nunca lanca. */
    public Activity activity() {
        try {
            MachineVault.Link current = usable();
            AttemptJournal.Entry pending = current == null ? null : journal.load(current.agentId()).orElse(null);
            if (pending != null) {
                if (pending.phase() == AttemptJournal.Phase.RESULTS || pending.report() != null) {
                    return Activity.REMOTE_SENDING;
                }
                return pending.phase() == AttemptJournal.Phase.RUNNING ? Activity.REMOTE_RUNNING : Activity.REMOTE_PREPARING;
            }
            Work w = work;
            return w != null && w.busy() ? Activity.LOCAL_RUNNING : Activity.IDLE;
        } catch (RuntimeException x) {
            return Activity.IDLE;
        }
    }

    /**
     * O nome, na Platform, do Worker a que este computador esta associado: e em nome dele que a maquina recebe trabalho. Vem da
     * presenca (o nome atual de la) e fica so em memoria. Vazio sem vinculo, ou enquanto a Platform ainda nao o informou.
     */
    public synchronized Optional<String> seatName() {
        return link == null || ended ? Optional.empty() : Optional.ofNullable(workerName);
    }

    /** A organizacao para a qual este computador esta habilitado (o identificador; quem mostra o nome e a tela). Vazio sem vinculo. */
    public synchronized Optional<String> organizationId() {
        return link == null || ended ? Optional.empty() : Optional.of(link.organizationId());
    }

    /** Quem descobre o Python deste computador (entregue por quem monta o aplicativo; esta camada nao cria processos). */
    public interface PythonFinder {
        List<PythonSeen> find();
    }

    static final int MAX_ENVIRONMENTS = 32;
    static final Pattern PYTHON_VERSION = Pattern.compile("^3\\.[0-9]{1,2}\\.[0-9]{1,3}([abc]|rc)?[0-9]{0,2}$");
    /** O inventario e publicado ao habilitar, na primeira presenca de cada abertura e, depois, a cada 6 horas. */
    static final long INVENTORY_EVERY_SECONDS = 6 * 3600;
    static final long INVENTORY_RETRY_SECONDS = 600;

    static final Pattern CODE = Pattern.compile("^nkp1\\.[0-9a-f-]{36}\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}$");
    static final long BEAT_SECONDS = 60;
    /** De quanto em quanto tempo a maquina pergunta a Platform o que ha para ela (ponto de partida de laboratorio). */
    static final long PULL_SECONDS = 15;
    /** A credencial e renovada quando faltarem ate estes dias para o vencimento (a mesma janela padrao do servidor). */
    static final long RENEW_WITHIN_DAYS = 30;
    /** Depois de uma falha passageira da renovacao: espera crescente, limitada. A credencial atual continua valendo. */
    static final long RENEW_RETRY_FIRST_SECONDS = 60;
    static final long RENEW_RETRY_MAX_SECONDS = 3600;

    private final MachineVault vault;
    private final MachineChannel channel;
    private final String appVersion;
    private final SecureRandom random = new SecureRandom();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Uma operacao de rede por vez (a presenca nao atravessa um pausar, por exemplo). */
    private final Object operation = new Object();

    private MachineVault.Link link;
    private boolean ended;
    private boolean inContact;
    /** O nome do Worker desta maquina, como a ultima presenca aceita o trouxe (nulo: ainda nao veio). */
    private String workerName;
    /** Resposta perdida no resgate: o mesmo codigo e repetido com o MESMO segredo. So em memoria. */
    private String pendingCode;
    private String pendingSecret;
    /** Encerramento em curso: a mesma operacao em toda nova tentativa. So em memoria. */
    private String pendingUnlink;
    private ScheduledExecutorService beats;
    private final java.time.Clock clock;
    /** Inventario de Python: quem descobre (nulo = este aplicativo nao publica), quantos foram publicados e quando tentar de novo. */
    private volatile PythonFinder pythons;
    private Integer published;
    /** O que foi publicado por ultimo, com o que so este computador sabe (onde esta cada ambiente). Nunca sai daqui. */
    private List<PythonHere> publishedHere = List.of();
    private java.time.Instant inventoryNotBefore = java.time.Instant.MIN;
    /** Renovacao: quando tentar de novo depois de uma falha passageira, e a espera da proxima. So em memoria. */
    private java.time.Instant renewNotBefore = java.time.Instant.MIN;
    private long renewWaitSeconds = RENEW_RETRY_FIRST_SECONDS;
    /** O diario da entrega: a tentativa recebida e ainda nao resolvida com a Platform. */
    private final AttemptJournal journal;
    /** Quem executa neste computador (nulo = este aplicativo nao executa o que a Platform solicitar). */
    private volatile Work work;

    /** {@code appVersion}: a versao do build, ou vazio na execucao de desenvolvimento. */
    public RemoteMachine(MachineVault vault, MachineChannel channel, Optional<String> appVersion) {
        this(vault, channel, appVersion, java.time.Clock.systemUTC());
    }

    /** {@code clock}: o relogio que decide a janela de renovacao (os testes adiantam o tempo por ele). */
    RemoteMachine(MachineVault vault, MachineChannel channel, Optional<String> appVersion, java.time.Clock clock) {
        this.clock = clock;
        this.vault = vault;
        this.journal = vault.journal();
        this.channel = channel;
        this.appVersion = appVersion.map(v -> v + "+client").orElse("0.0.0+client.dev");
        Optional<MachineVault.Link> stored = vault.load();
        this.link = stored.orElse(null);
        // vinculo de outro endereco da Platform, ou arquivo que nao se le: a credencial NAO e usada; resta remover
        this.ended = stored.map(l -> !l.origin().equals(channel.origin())).orElse(vault.present());
    }

    public synchronized View view() {
        if (ended) {
            return new View(Status.ENDED, false, Optional.empty(), Optional.empty());
        }
        if (link == null) {
            return new View(vault.available() ? Status.NOT_ENABLED : Status.UNAVAILABLE, false, Optional.empty(), Optional.empty());
        }
        return new View(link.paused() ? Status.PAUSED : Status.RECEIVING, inContact, Optional.of(link.credentialExpiresAt()), Optional.ofNullable(published));
    }

    /**
     * Quem EXECUTA neste computador (entregue por quem monta o aplicativo: esta camada nao cria processos, nao abre pacotes
     * e nao conhece o motor de execucao). Sem ele, o aplicativo nao declara que executa e a Platform nao lhe entrega nada.
     */
    public interface Work {
        /** Ha uma execucao em andamento neste computador (de qualquer tipo). */
        boolean busy();

        /** O caminho da pasta de trabalho da tentativa (ainda nao criada). Vazio: nao ha onde trabalhar. */
        Optional<String> dir(String attemptId);

        /**
         * Confere o pacote baixado, o Python e as dependencias, e INICIA. Devolve o identificador da execucao neste computador.
         * Nao inicia: {@link WorkRefused}, com o motivo.
         */
        String start(Job job) throws WorkRefused;

        /** O que a execucao e agora. Vazio: este computador nao a conhece (mais). */
        Optional<Progress> progress(String run);

        /** Pede que a execucao pare. */
        void cancel(String run);

        /**
         * Apaga o CODIGO da tentativa: o ZIP, a copia extraida e a copia que foi executada ({@code run}: a execucao, ou nulo se
         * nada iniciou). O que a automacao gravou na saida fica.
         */
        void discard(String attemptId, String run);

        /**
         * Apaga o que a automacao gravou na pasta de saida da tentativa. So e chamado depois que a Platform CONFIRMOU o
         * recebimento de todos os arquivos (envio fechado, nenhum de fora).
         */
        void discardOutputs(String attemptId);

        /**
         * Ao abrir o aplicativo: apaga o CODIGO deixado por tentativas que nao sao a pendente ({@code keepAttemptId}, ou nulo) -
         * sobra de uma queda entre o desfecho e a limpeza. Nunca apaga pasta de saida: resultado sem confirmacao de recebimento
         * fica no computador. Devolve quantas pastas de tentativa foram limpas.
         */
        int sweep(String keepAttemptId);

        /** Os arquivos que a execucao gravou na pasta de saida dela, em ordem estavel (a mesma a cada chamada). */
        List<ResultFile> results(String attemptId, String run);

        /**
         * A pasta de saida da tentativa FICA neste computador, marcada como pendente com o motivo ({@code OUT_OF_LIMIT},
         * {@code NOT_CONFIRMED} ou {@code CHANGED_AFTER_SEND}). A marca data o inicio do prazo de guarda.
         */
        default void holdOutputs(String attemptId, String reason) {
        }

        /** As pastas de saida guardadas neste computador sem confirmacao de recebimento (fora a de {@code exceptAttemptId}). */
        default List<Held> held(String exceptAttemptId) {
            return List.of();
        }

        /** O prazo de guarda vigente, em dias: vale para {@link #held} e para a remocao em {@link #sweep}. */
        default void retentionDays(int days) {
        }

        /**
         * CONFERE, sem instalar nem executar nada, se as dependencias ({@code nome==versao}) estao no Python de {@code pythonLocalId}
         * (o identificador local, com o caminho; nao sai deste computador), na versao exata. Quem nao sabe conferir diz que nao
         * conferiu.
         */
        default Verified verify(String pythonLocalId, List<String> dependencies) {
            return new Verified("NOT_VERIFIED", List.of());
        }
    }

    /**
     * O resultado de uma conferencia de dependencias. {@code outcome}: {@code SATISFIED}, {@code MISSING} (com {@code missing}: os
     * itens pedidos que faltam ou estao em outra versao), {@code ENVIRONMENT_GONE} ou {@code NOT_VERIFIED}.
     */
    public record Verified(String outcome, List<String> missing) {
    }

    /**
     * Um pedido da Platform para conferir as dependencias de uma automacao no Python escolhido para ela nesta maquina (plano
     * 5.9). So a conferencia: nada e instalado, nenhum ambiente e criado ou trocado, nada e executado. A resposta diz so o que
     * FALTA do que foi perguntado - nunca o que esta instalado, nunca um caminho. Ambiente que nao esta mais aqui:
     * ENVIRONMENT_GONE, sem escolher outro. Qualquer falha ao conferir: NOT_VERIFIED. Falha ao responder: a Platform fica sem
     * resposta e diz "nao foi possivel verificar". Nunca lanca.
     */
    private void verify(MachineVault.Link current, MachineChannel.Check check, Work w) {
        if (check == null) {
            return;
        }
        Verified v;
        try {
            String python = localPython(check.pythonEnvironmentId());
            v = python == null ? new Verified("ENVIRONMENT_GONE", List.of()) : w == null ? new Verified("NOT_VERIFIED", List.of()) : w.verify(python, check.dependencies());
            if (v == null || !List.of("SATISFIED", "MISSING", "ENVIRONMENT_GONE", "NOT_VERIFIED").contains(v.outcome())
                    || ("MISSING".equals(v.outcome())) != (v.missing() != null && !v.missing().isEmpty())
                    || (v.missing() != null && !check.dependencies().containsAll(v.missing()))) {
                v = new Verified("NOT_VERIFIED", List.of());
            }
        } catch (RuntimeException | LinkageError x) {
            v = new Verified("NOT_VERIFIED", List.of());
        }
        try {
            channel.answerCheck(current.credential(), check.checkId(), v.outcome(), v.missing());
        } catch (Refused | RuntimeException x) {
            // sem resposta, a Platform diz que nao foi possivel verificar
        }
    }

    /**
     * Registros de uma tentativa que ficaram neste computador sem confirmacao de recebimento. Nada do conteudo. {@code reason}:
     * {@code SENDING} (o envio continua sendo tentado), {@code NOT_CONFIRMED} (a Platform nao confirmou e nao ha novo envio) ou
     * {@code LINK_ENDED} (o vinculo deste computador acabou: nao podem mais ser enviados).
     */
    public record HeldLogs(String attemptId, long sinceEpochSecond, long removeAtEpochSecond, String reason) {
    }

    /**
     * O que este computador REMOVEU pelo prazo de guarda sem a Platform ter confirmado o recebimento. {@code logs}: registros
     * (senao, arquivos de resultado). {@code count}: quanto havia aqui. {@code notice}: {@code PENDING} (a Platform ainda nao foi
     * avisada), {@code SENT} (foi) ou {@code IMPOSSIBLE} (nao ha mais como avisar).
     */
    public record Lost(String attemptId, boolean logs, int count, long lostAtEpochSecond, String notice) {
    }

    /** O prazo de guarda vigente, em dias (ver {@link MachineVault#retentionDays}). */
    public int retentionDays() {
        return vault.retentionDays();
    }

    /** As filas de registros guardadas sem confirmacao (fora a da tentativa pendente). Nunca lanca. */
    public List<HeldLogs> heldLogs() {
        try {
            MachineVault.Link current = usable();
            String keep = current == null ? null : journal.load(current.agentId()).map(AttemptJournal.Entry::attemptId).orElse(null);
            LossLedger ledger = vault.losses();
            return vault.heldLogQueues(keep).stream().map(q -> new HeldLogs(q.attemptId(), q.sinceEpochSecond(), q.removeAtEpochSecond(),
                    ledger.load(q.attemptId()).map(e -> switch (e.logsBlock()) {
                        case NONE -> e.token().isEmpty() ? "LINK_ENDED" : "SENDING";
                        case AUTHORIZATION -> "LINK_ENDED";
                        default -> "NOT_CONFIRMED";
                    }).orElse("NOT_CONFIRMED"))).toList();
        } catch (RuntimeException x) {
            return List.of();
        }
    }

    /** O que foi removido pelo prazo de guarda sem confirmacao de recebimento, e se a Platform ja sabe. Nunca lanca. */
    public List<Lost> lost() {
        try {
            List<Lost> out = new java.util.ArrayList<>();
            for (LossLedger.Entry e : vault.losses().all(clock.instant().getEpochSecond())) {
                if (e.logsLost()) {
                    out.add(new Lost(e.attemptId(), true, e.logLines(), e.logsLostAt(), e.logsNotice().name()));
                }
                if (e.resultsLost()) {
                    out.add(new Lost(e.attemptId(), false, e.resultFiles(), e.resultsLostAt(), e.resultsNotice().name()));
                }
            }
            return out;
        } catch (RuntimeException x) {
            return List.of();
        }
    }

    /**
     * Resultados de uma tentativa que ficaram neste computador. {@code dir}: a pasta, neste computador (nao sai daqui).
     * {@code removeAtEpochSecond}: a partir de quando a limpeza os remove.
     */
    public record Held(String attemptId, String reason, String dir, int files, long bytes, long sinceEpochSecond, long removeAtEpochSecond) {
    }

    /** A pasta, neste computador, onde estao os resultados guardados: a da tentativa, ou a que reune as tentativas se ha mais de uma. */
    public static String heldFolder(List<Held> held) {
        try {
            Path attempt = Path.of(held.get(0).dir()).getParent();
            return String.valueOf(held.size() == 1 || attempt == null ? attempt : attempt.getParent());
        } catch (RuntimeException x) {
            return "";
        }
    }

    /** O que ha de resultado guardado neste computador sem confirmacao de recebimento. Nunca lanca. */
    public List<Held> heldResults() {
        Work w = work;
        if (w == null) {
            return List.of();
        }
        try {
            MachineVault.Link current = usable();
            String keep = current == null ? null : journal.load(current.agentId()).map(AttemptJournal.Entry::attemptId).orElse(null);
            w.retentionDays(vault.retentionDays());
            return w.held(keep);
        } catch (RuntimeException x) {
            return List.of();
        }
    }

    /** {@code name}: relativo a pasta de saida, com {@code /}; e o que vai a Platform. {@code file}: o caminho neste computador; nao sai daqui. */
    public record ResultFile(String name, String file, long size) {
    }

    /** {@code pythonLocalId}: a identidade LOCAL do Python escolhido (com o caminho); nunca sai deste computador. */
    public record Job(String attemptId, String organizationId, String workspaceId, String automationId, String automationVersionId, String zip,
                      String sha256, String fingerprint, List<String> dependencies, String pythonLocalId, String dir, LogSink log) {

        /** Sem quem receba a saida do processo. */
        public Job(String attemptId, String organizationId, String workspaceId, String automationId, String automationVersionId, String zip,
                   String sha256, String fingerprint, List<String> dependencies, String pythonLocalId, String dir) {
            this(attemptId, organizationId, workspaceId, automationId, automationVersionId, zip, sha256, fingerprint, dependencies, pythonLocalId, dir, null);
        }
    }

    /**
     * Quem recebe a saida do processo da tentativa, linha a linha, enquanto ele roda. Tem de voltar logo e nao lanca: e a fila
     * local da entrega, nunca a rede.
     */
    public interface LogSink {
        void line(boolean error, long epochMillis, boolean truncated, String text);
    }

    /** {@code state}: RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT ou UNKNOWN (terminou sem que o fim fosse observado). */
    public record Progress(String state, Integer exitCode) {
    }

    /** {@code reason}: um motivo de recusa do contrato da maquina (PACKAGE_INVALID, ENVIRONMENT_GONE, DEPENDENCIES_MISSING...). */
    public static final class WorkRefused extends Exception {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public WorkRefused(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** O unico tipo de pacote que este aplicativo executa. */
    static final String RUNNABLE_RULE = "nekkora-agent/external-python-fingerprint/1";

    /** {@code true} se este aplicativo executa o que a Platform solicitar (declara a capacidade na presenca). */
    public boolean executes() {
        return work != null;
    }

    /** Liga a execucao do que a Platform solicitar (quem monta o aplicativo entrega quem executa). */
    public void setWork(Work work) {
        this.work = work;
    }

    /**
     * Uma consulta de entrega. Nunca lanca. Com uma tentativa no diario, segue com ela (mesmo com o recebimento pausado); sem
     * tentativa no diario, so pergunta a Platform se este aplicativo executa ({@link #setWork}).
     * <ol>
     * <li>a tentativa entregue e GRAVADA no diario (token protegido) antes de qualquer confirmacao; se nao der para gravar,
     * nada e confirmado e a Platform a entrega de novo, com token e epoca novos;</li>
     * <li>o recebimento e confirmado com o token e a epoca da entrega; repetir (resposta perdida, aplicativo fechado) nao
     * muda nada;</li>
     * <li>PREPARING: o pacote da tentativa e baixado (so chega inteiro e com o SHA-256 declarado) e o Python escolhido e
     * localizado NESTE computador; se ele nao existe mais, a tentativa e recusada - nenhum outro e escolhido;</li>
     * <li>STARTING: a Platform reconfere as condicoes da solicitacao; se algo caiu (ou o cancelamento foi pedido), NADA e
     * iniciado;</li>
     * <li>quem executa confere o pacote, o Python e as dependencias e inicia; a intencao de iniciar e gravada ANTES e o
     * identificador da execucao logo depois: se o aplicativo fechar entre os dois, a tentativa termina sem prova do resultado
     * e nada e lancado de novo;</li>
     * <li>RUNNING: a cada consulta o estado e repetido (renova o prazo) e o pedido de cancelamento e obedecido;</li>
     * <li>o desfecho e informado; so depois de a Platform aceitar, o diario e apagado e o codigo da tentativa e removido.</li>
     * </ol>
     * Token ou epoca que nao valem mais, tentativa que a Platform ja encerrou ou nao tem: o que estiver em execucao e parado, o
     * diario e apagado e nada e alterado la. Falha de rede: fica tudo como esta, para a proxima consulta - uma execucao em
     * andamento NAO e interrompida por falta de rede.
     */
    public void pull() {
        synchronized (operation) {
            MachineVault.Link current = usable();
            if (current == null) {
                // o vinculo acabou (revogado, vencido ou desabilitado na Platform): o que estiver em execucao para aqui
                abandonIfEnded();
                return;
            }
            AttemptJournal.Entry pending = journal.load(current.agentId()).orElse(null);
            if (pending == null && work == null) {
                return;
            }
            try {
                deliver(current, pending);
                if (pending != null) {
                    // com uma tentativa no diario a maquina nao pergunta por trabalho: o pedido de conferencia vem pela rota propria.
                    // Conferir nao e executar: nao mexe na execucao em andamento
                    verify(current, channel.pendingCheck(current.credential()), work);
                }
                // o que tentativas ja terminadas deixaram aqui sem confirmacao continua sendo enviado, a parte do diario
                deliverHeld(current, work, false, journal.load(current.agentId()).isPresent());
            } catch (Refused e) {
                if (e.problem() == Problem.LINK_ENDED || e.problem() == Problem.NOT_ENABLED) {
                    failed(e.problem());
                    abandonIfEnded();
                }
            }
        }
    }

    /**
     * O vinculo deste computador com a Platform ACABOU com uma tentativa ainda no diario: a credencial nao vale mais, entao nada
     * mais pode ser informado nem enviado, e este computador nao esta mais autorizado a executar trabalho da Platform. O processo
     * em execucao e parado de forma controlada; o que ele ja produziu (registros e pasta de saida) FICA neste computador, dito
     * como "nao pode mais ser enviado: vinculo encerrado", ate o prazo de guarda. Nada e enviado por outro caminho. A Platform,
     * do lado dela, ja encerrou a tentativa como sem confirmacao (ou como nao iniciada). Nunca lanca.
     */
    private void abandonIfEnded() {
        MachineVault.Link old;
        synchronized (this) {
            old = ended ? link : null;
        }
        if (old == null) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        try {
            AttemptJournal.Entry e = journal.load(old.agentId()).orElse(null);
            Work w = work;
            if (e != null) {
                if (w != null && e.run() != null) {
                    w.cancel(e.run());
                }
                LogQueue q = queue;
                int lines = -1;
                if (q != null && e.attemptId().equals(queueAttempt)) {
                    try {
                        q.flush();
                    } catch (IOException x) {
                        // o que nao foi gravado se perde
                    }
                    lines = q.lines();
                }
                boolean started = e.run() != null;
                if (started) {
                    vault.losses().logsHeld(e.attemptId(), "", e.epoch(), now, Math.max(0, lines), LossLedger.Block.AUTHORIZATION);
                    vault.losses().resultsHeld(e.attemptId(), "", e.epoch(), now, LossLedger.Block.AUTHORIZATION);
                    if (w != null) {
                        w.holdOutputs(e.attemptId(), "LINK_ENDED");
                    }
                }
                forget(e, w);
            }
            // o que tentativas anteriores deixaram guardado tambem nao pode mais ser enviado
            vault.losses().unlink(now);
        } catch (RuntimeException x) {
            // fica para a proxima consulta
        }
        changed();
    }

    private void deliver(MachineVault.Link current, AttemptJournal.Entry e) throws Refused {
        String credential = current.credential();
        if (e == null) {
            MachineChannel.Next next = channel.next(credential);
            // o pedido de conferencia de dependencias vem junto com a consulta de trabalho e nao depende dela
            verify(current, next.check(), work);
            MachineChannel.Offer o = next.offer();
            if (o == null) {
                return;
            }
            try {
                e = AttemptJournal.Entry.received(current.agentId(), o);
                journal.save(e);
            } catch (IOException | IllegalArgumentException x) {
                // nao gravou: nao confirma. A tentativa continua oferecida no servidor
                return;
            }
        }
        Work w = work;
        try {
            if (!e.acked()) {
                if (!channel.ack(credential, e.attemptId(), e.token(), e.epoch()).proceed()) {
                    // a Platform ja a encerrou (cancelada ou recusada antes de chegar aqui)
                    forget(e, w);
                    return;
                }
                e = e.withAck();
                keep(e);
            }
            if (e.report() == null && e.phase() == AttemptJournal.Phase.STARTING) {
                // o aplicativo fechou entre pedir o inicio e gravar a execucao: nao ha prova de nada, e nada e lancado de novo
                e = e.reporting("ENDED_UNKNOWN", null, null);
                keep(e);
            }
            if (e.report() == null && e.phase() == AttemptJournal.Phase.RECEIVED) {
                e = begin(current, e, w);
                if (e == null || e.report() == null) {
                    // acabou aqui, ou esta em execucao (o estado acabou de ser informado): o resto fica para a proxima consulta
                    return;
                }
            }
            if (e.report() == null && e.phase() == AttemptJournal.Phase.RUNNING) {
                // a fila dos registros e aberta ja aqui: se o aplicativo foi reaberto no meio da execucao, isso fica dito nela
                queue(current, e);
                Progress p = w == null ? null : w.progress(e.run()).orElse(null);
                if (p != null && "RUNNING".equals(p.state())) {
                    // repetir o estado renova o prazo; o pedido de cancelamento vem na resposta
                    if (channel.state(credential, e.attemptId(), e.token(), e.epoch(), "RUNNING", null, null).cancelRequested()) {
                        w.cancel(e.run());
                    }
                    // o que o processo ja escreveu vai indo, em lotes; falha aqui nao interrompe nada e e retomada depois
                    sendLogs(credential, current, e);
                    return;
                }
                String end = p == null || "UNKNOWN".equals(p.state()) ? "ENDED_UNKNOWN" : p.state();
                boolean coded = p != null && p.exitCode() != null && (end.equals("SUCCEEDED") || end.equals("FAILED") || end.equals("TIMED_OUT"));
                e = e.reporting(end, null, coded ? p.exitCode() : null);
                keep(e);
            }
            if (e.phase() != AttemptJournal.Phase.RESULTS) {
                channel.state(credential, e.attemptId(), e.token(), e.epoch(), e.report(), e.reason(), e.exitCode(), processAbsent(e, w));
                if (w == null || e.phase() != AttemptJournal.Phase.RUNNING) {
                    // nao chegou a executar: nao ha o que enviar
                    forget(e, w);
                    return;
                }
                // o desfecho esta na Platform; os arquivos de resultado vao depois e NAO o alteram
                e = e.results();
                keep(e);
            }
            // O desfecho esta na Platform: a tentativa esta resolvida e a maquina fica livre para outra. Resolver o estado NAO
            // encerra a entrega: os registros e a pasta de saida passam para a entrega continuada (o token da atribuicao vai junto,
            // protegido), que tenta ate a Platform confirmar, o prazo de guarda vencer ou surgir um impedimento. O prazo conta daqui.
            LogQueue open = queue;
            if (open != null && e.attemptId().equals(queueAttempt)) {
                try {
                    open.flush();
                } catch (IOException x) {
                    // o que esta na memoria ainda e enviado agora, logo abaixo
                }
            }
            vault.losses().enqueue(e.attemptId(), e.token(), e.epoch(), e.run(), clock.instant().getEpochSecond());
            try {
                w.holdOutputs(e.attemptId(), "SENDING");
            } catch (RuntimeException x) {
                // sem a marca, a limpeza a grava ao encontrar a pasta
            }
            // a primeira tentativa de envio e ja, com a fila ainda aberta neste processo
            String attempt = e.attemptId();
            forget(e, w);
            changed();
            deliverHeld(current, w, true, false);
            if (attempt.equals(queueAttempt)) {
                dropQueue(false);
            }
            changed();
        } catch (Refused x) {
            if (x.problem() == Problem.ATTEMPT_STALE || x.problem() == Problem.ATTEMPT_GONE || x.problem() == Problem.ATTEMPT_TRANSITION) {
                // esta entrega nao vale mais: o que ela iniciou aqui para, e nada e alterado na Platform
                if (w != null && e.run() != null) {
                    w.cancel(e.run());
                }
                forget(e, w);
                return;
            }
            throw x;
        }
    }

    /**
     * Da tentativa recebida ate a execucao iniciada. Devolve a entrada como ficou (em execucao, ou com a recusa a informar), ou
     * {@code null} se a tentativa acabou aqui (a Platform mandou nao seguir).
     */
    private AttemptJournal.Entry begin(MachineVault.Link current, AttemptJournal.Entry e, Work w) throws Refused {
        String credential = current.credential();
        if (w == null || !RUNNABLE_RULE.equals(e.packageFingerprintRule()) || e.workspaceId() == null || e.pythonEnvironmentId() == null) {
            return refuse(e, "CLIENT_INCOMPATIBLE");
        }
        if (w.busy()) {
            return refuse(e, "BUSY");
        }
        if (!channel.state(credential, e.attemptId(), e.token(), e.epoch(), "PREPARING", null, null).proceed()) {
            forget(e, w);
            return null;
        }
        Path dir;
        try {
            dir = w.dir(e.attemptId()).map(Path::of).orElse(null);
        } catch (java.nio.file.InvalidPathException x) {
            dir = null;
        }
        if (dir == null) {
            return refuse(e, "DISK_PRESSURE");
        }
        Path zip = dir.resolve("pacote.zip");
        String sha;
        try {
            w.discard(e.attemptId(), null);
            java.nio.file.Files.createDirectories(dir);
            sha = channel.download(credential, e.attemptId(), e.token(), e.epoch(), zip);
        } catch (IOException x) {
            return refuse(e, "DISK_PRESSURE");
        } catch (Refused x) {
            if (x.problem() == Problem.PACKAGE_UNAVAILABLE || x.problem() == Problem.STORAGE) {
                return refuse(e, x.problem() == Problem.STORAGE ? "DISK_PRESSURE" : "PACKAGE_INVALID");
            }
            throw x;
        }
        String python = localPython(e.pythonEnvironmentId());
        if (python == null) {
            // o Python escolhido nao esta mais neste computador: nenhum outro e escolhido
            return refuse(e, "ENVIRONMENT_GONE");
        }
        // imediatamente antes de iniciar, a Platform reconfere; cancelamento pedido ou condicao perdida: nada e iniciado
        if (!channel.state(credential, e.attemptId(), e.token(), e.epoch(), "STARTING", null, null).proceed()) {
            forget(e, w);
            return null;
        }
        e = e.starting();
        try {
            journal.save(e);
        } catch (IOException x) {
            // sem conseguir gravar a intencao, nao inicia: um reinicio nao saberia que algo foi lancado
            return refuse(e.reporting(null, null, null), "DISK_PRESSURE");
        }
        String run;
        // a fila local dos registros desta tentativa: o que o processo escrever entra nela (ja sem segredos) e vai a Platform
        LogQueue q = queue(current, e);
        try {
            run = w.start(new Job(e.attemptId(), current.organizationId(), e.workspaceId(), e.automationId(), e.automationVersionId(),
                    zip.toString(), sha, e.packageFingerprint(), e.dependencies(), python, dir.toString(), q == null ? null : q::add));
        } catch (WorkRefused x) {
            dropQueue(true);
            return refuse(e, x.reason());
        } catch (RuntimeException x) {
            // quem executa falhou sem dizer se lancou: sem prova de nada
            e = e.reporting("ENDED_UNKNOWN", null, null);
            keep(e);
            return e;
        }
        e = e.running(run);
        keep(e);
        if (channel.state(credential, e.attemptId(), e.token(), e.epoch(), "RUNNING", null, null).cancelRequested()) {
            w.cancel(run);
        }
        return e;
    }

    /**
     * Envia o que a execucao gravou na pasta de saida e fecha o envio. Limites (os da Platform): 50 arquivos, 20 MiB cada, 50 MiB
     * no total, nome de ate 200 caracteres. O que passa do limite NAO e enviado e e CONTADO: o fechamento diz quantos foram e
     * quantos ficaram de fora. Falha de rede: o envio e retomado na proxima consulta (repetir um arquivo ja enviado nao muda
     * nada). Se a Platform disser que o envio ja foi fechado, ou que esta entrega nao vale mais, para aqui.
     */
    /**
     * Devolve {@code null} quando a pasta de saida PODE ser apagada: cada arquivo foi confirmado pela Platform com o mesmo
     * SHA-256 e tamanho, o fechamento desta tentativa foi aceito com as mesmas contagens, nenhum arquivo ficou de fora, e o que
     * esta na pasta agora e exatamente o que foi confirmado. Senao, o motivo da pendencia.
     */
    private String sendResults(String credential, LossLedger.Entry e, Work w, long until) throws Refused {
        java.util.Map<String, String> confirmed = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> done = heldResultsDone.computeIfAbsent(e.attemptId(), k -> new java.util.HashMap<>());
        boolean sentOne = false;
        List<ResultFile> files;
        try {
            files = w == null ? List.of() : w.results(e.attemptId(), e.run());
        } catch (RuntimeException x) {
            files = List.of();
        }
        int sent = 0;
        int skipped = 0;
        long total = 0;
        for (ResultFile f : files) {
            boolean fits = sent < MachineChannel.MAX_RESULTS && f.size() <= MachineChannel.MAX_RESULT_BYTES
                    && total + f.size() <= MachineChannel.MAX_RESULTS_TOTAL_BYTES && f.name() != null && !f.name().isEmpty() && f.name().length() <= 200;
            if (!fits) {
                skipped++;
                continue;
            }
            String already = done.get(f.file());
            if (already != null) {
                // a Platform ja confirmou este arquivo numa consulta anterior deste processo: os bytes nao vao de novo. Se o
                // arquivo mudou desde entao, a ultima conferencia (antes de apagar a pasta) acusa
                confirmed.put(f.file(), already);
                sent++;
                total += f.size();
                continue;
            }
            if (sentOne && clock.millis() >= until) {
                // pelo menos um arquivo por consulta; passou da fatia, devolve a vez
                return IN_PROGRESS;
            }
            try {
                String sha = channel.sendResult(credential, e.attemptId(), e.token(), e.epoch(), sent + 1, f.name(), Path.of(f.file()));
                sentOne = true;
                confirmed.put(f.file(), sha);
                done.put(f.file(), sha);
                sent++;
                total += f.size();
            } catch (Refused x) {
                if (x.problem() == Problem.RESULT_LIMIT) {
                    // a Platform nao aceita este arquivo (limite ou nome): fica de fora, contado. Arquivo que nao pode ser LIDO aqui
                    // agora (em uso, por exemplo) nao e limite: a falha sobe e o envio e tentado de novo depois
                    skipped++;
                } else if (x.problem() == Problem.RESULT_CONFLICT) {
                    // o envio desta tentativa ja foi fechado (ou este numero ja tem outro arquivo): nao ha mais o que enviar,
                    // e nao ha confirmacao de que TUDO chegou
                    return files.isEmpty() ? null : "NOT_CONFIRMED";
                } else {
                    throw x;
                }
            } catch (java.nio.file.InvalidPathException x) {
                skipped++;
            }
        }
        try {
            if (!channel.completeResults(credential, e.attemptId(), e.token(), e.epoch(), sent, skipped)) {
                return files.isEmpty() ? null : "NOT_CONFIRMED";
            }
        } catch (Refused x) {
            if (x.problem() != Problem.RESULT_CONFLICT) {
                throw x;
            }
            return files.isEmpty() ? null : "NOT_CONFIRMED";
        }
        if (skipped > 0) {
            // arquivo que ficou de fora so existe neste computador
            return "OUT_OF_LIMIT";
        }
        // ultima conferencia antes de apagar: o que esta na pasta AGORA e exatamente o que a Platform confirmou
        List<ResultFile> now;
        try {
            now = w == null ? List.of() : w.results(e.attemptId(), e.run());
        } catch (RuntimeException x) {
            return files.isEmpty() ? null : "NOT_CONFIRMED";
        }
        if (now.size() != confirmed.size()) {
            return "CHANGED_AFTER_SEND";
        }
        for (ResultFile f : now) {
            String sha = confirmed.get(f.file());
            if (sha == null || !sha.equals(sha256(f.file()))) {
                return "CHANGED_AFTER_SEND";
            }
        }
        return null;
    }

    private static String sha256(String file) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(Path.of(file))));
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException x) {
            return null;
        }
    }

    private AttemptJournal.Entry refuse(AttemptJournal.Entry e, String reason) {
        AttemptJournal.Entry refused = e.reporting("REFUSED", reason, null);
        keep(refused);
        return refused;
    }

    /** A identidade local do Python cujo identificador opaco e o indicado, se ele ainda esta neste computador. */
    private String localPython(String environmentId) {
        PythonFinder finder = pythons;
        if (finder == null) {
            return null;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(vault.inventorySalt(), "HmacSHA256"));
            for (PythonSeen s : finder.find()) {
                if (s != null && s.localId() != null && environmentId.equals(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(s.localId().getBytes(java.nio.charset.StandardCharsets.UTF_8))))) {
                    return s.localId();
                }
            }
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException | LinkageError x) {
            // sem conseguir conferir, o ambiente nao e dado como presente
        }
        return null;
    }

    private void keep(AttemptJournal.Entry e) {
        try {
            journal.save(e);
        } catch (IOException x) {
            // o que ja estava gravado continua valendo: a etapa e repetida (sem efeito no servidor) na proxima abertura
        }
    }

    /** A tentativa esta resolvida com a Platform: o diario e apagado e o codigo dela e removido deste computador. */
    /** A fila de registros aberta neste processo e a tentativa dela (uma por vez). */
    private LogQueue queue;
    private String queueAttempt;

    /** A fila da tentativa (aberta uma vez por processo). Nulo: este computador nao consegue proteger a fila, entao nao ha fila. */
    private LogQueue queue(MachineVault.Link current, AttemptJournal.Entry e) {
        if (queue != null && e.attemptId().equals(queueAttempt)) {
            return queue;
        }
        try {
            queue = vault.logQueue(e.attemptId(), List.of(current.credential(), e.token()));
            queueAttempt = e.attemptId();
            if (queue.existed() && e.phase() == AttemptJournal.Phase.RUNNING && e.report() == null) {
                // o aplicativo foi reaberto no meio da tentativa: o que o processo escreveu desde entao nao foi lido por ninguem
                queue.note(clock.millis(), "O aplicativo deste computador foi reaberto durante a execução: a saída do processo a partir deste ponto "
                        + "não foi capturada.");
            }
            return queue;
        } catch (RuntimeException x) {
            queue = null;
            queueAttempt = null;
            return null;
        }
    }

    /** Solta a fila deste processo; {@code delete}: apaga o arquivo (confirmado pela Platform, ou a tentativa nao executou). */
    private void dropQueue(boolean delete) {
        if (queue != null && delete) {
            queue.delete();
        }
        queue = null;
        queueAttempt = null;
    }

    /**
     * DURANTE a execucao: envia a Platform parte do que a fila de registros tem e ainda nao foi confirmado - no maximo alguns
     * lotes por consulta. Qualquer falha fica para a proxima: registro nunca interrompe a execucao nem muda o resultado. O envio
     * do que faltar e o fechamento sao da entrega continuada ({@link #deliverHeld}), depois do desfecho.
     */
    private void sendLogs(String credential, MachineVault.Link current, AttemptJournal.Entry e) throws Refused {
        LogQueue q = queue(current, e);
        if (q == null) {
            return;
        }
        try {
            try {
                q.flush();
            } catch (IOException x) {
                // o que esta na memoria ainda pode ser enviado
            }
            for (int sent = 0; sent < 10; sent++) {
                List<LogQueue.Line> batch = q.batch();
                if (batch.isEmpty()) {
                    break;
                }
                q.acked(channel.sendLogs(credential, e.attemptId(), e.token(), e.epoch(), batch));
            }
        } catch (Refused x) {
            if (x.problem() == Problem.ATTEMPT_STALE || x.problem() == Problem.ATTEMPT_GONE || x.problem() == Problem.LINK_ENDED
                    || x.problem() == Problem.NOT_ENABLED) {
                throw x;
            }
            // limite, conflito ou falha passageira com a execucao em andamento: fica para o fechamento, depois do desfecho
        }
    }

    /** A proxima vez (segundos) em que a entrega continuada de cada tentativa e tentada, e a espera que ela ja acumulou. */
    private final java.util.Map<String, long[]> heldRetry = new java.util.HashMap<>();
    static final long HELD_RETRY_FIRST_SECONDS = 30;
    static final long HELD_RETRY_MAX_SECONDS = 900;
    /**
     * A FATIA de uma consulta (milissegundos): por quanto tempo, no maximo, UMA consulta - a maquina consulta a Platform a cada
     * {@link #PULL_SECONDS} s - fica enviando o que tentativas JA TERMINADAS deixaram guardado, somando todas elas, antes de
     * devolver a vez. Nao e prazo de entrega nem tempo limite de um envio: e o tamanho da vez. O que falta segue na consulta
     * seguinte, sem espera, de onde a Platform confirmou.
     *
     * <p>Por que existe: a presenca, a renovacao do prazo da tentativa em execucao, o pedido de cancelamento e a consulta de
     * trabalho novo passam pela mesma vez. Uma fila grande (dezenas de milhares de linhas) leva minutos para chegar a Platform;
     * enviada de uma so vez, seguraria tudo isso.
     *
     * <p>Dentro da fatia, a unidade e o LOTE: no maximo {@link LogQueue#BATCH_LINES} linhas e {@link LogQueue#BATCH_BYTES} bytes
     * por lote de registros; um arquivo de resultado por vez (ate {@link MachineChannel#MAX_RESULT_BYTES} bytes). Cada lote so
     * conta como entregue com a CONFIRMACAO da Platform (ela responde ate qual linha tem; o arquivo, com o SHA-256 que recebeu). A
     * fatia so e conferida ENTRE lotes: pelo menos um lote por consulta, e um lote em andamento nunca e cortado.
     *
     * <p>Com uma tentativa ATIVA neste computador (recebida, em preparo ou em execucao), o que ficou de tentativas anteriores
     * cede a vez: um lote de registros por consulta e nenhum arquivo de resultado - o estado da tentativa ativa e o pedido de
     * cancelamento sao tratados ANTES, em toda consulta, e esperam no maximo um lote.
     */
    long heldSendBudgetMillis = 10_000;
    /**
     * Arquivos de resultado de tentativas terminadas que a Platform ja confirmou NESTE processo (arquivo -> SHA-256 confirmado),
     * para a retomada entre consultas nao enviar os mesmos bytes de novo. Com o aplicativo reaberto o envio recomeca uma vez (a
     * Platform reconhece o arquivo que ja tem).
     */
    private final java.util.Map<String, java.util.Map<String, String>> heldResultsDone = new java.util.HashMap<>();
    /** Marca de {@link #sendResults}: a fatia acabou com arquivos por enviar; nada foi fechado. */
    private static final String IN_PROGRESS = "IN_PROGRESS";
    /**
     * As filas de registros de tentativas terminadas que ainda estao sendo enviadas, abertas neste processo. A fila so sabe em
     * memoria ate onde a Platform confirmou: reabri-la a cada consulta recomecaria o envio do inicio. Com o aplicativo reaberto,
     * o envio recomeca uma vez (a Platform ignora o que ja tem e diz qual e a ultima linha dela).
     */
    private final java.util.Map<String, LogQueue> heldQueues = new java.util.HashMap<>();

    /** Para testes: esquece as esperas da entrega continuada (a proxima consulta tenta de novo ja). */
    void forgetHeldWaits() {
        synchronized (operation) {
            heldRetry.clear();
        }
    }

    /**
     * A entrega continuada: para cada tentativa TERMINADA que deixou aqui registros ou resultados sem a confirmacao da Platform, e
     * sem impedimento, tenta de novo. Acaba, para cada tipo, de um de tres jeitos:
     * <ul>
     * <li>a Platform CONFIRMA (fechamento aceito com as mesmas contagens; cada arquivo com o mesmo SHA-256): so entao o que
     * estava guardado e apagado daqui;</li>
     * <li>um IMPEDIMENTO fica dito com o motivo e nao ha novo envio: a Platform fechou o envio com arquivos fora do limite
     * (eles so existem aqui), a Platform tem outra coisa nessas linhas ou outro fechamento, ou ela nao reconhece mais a
     * atribuicao. O que esta guardado fica ate o prazo de guarda;</li>
     * <li>o PRAZO DE GUARDA vence antes (a limpeza remove, registra a perda e avisa a Platform).</li>
     * </ul>
     * Falha passageira (rede, servidor, limite de requisicoes, arquivo que nao pode ser lido agora): tenta de novo depois, com
     * espera crescente (30 s ate 15 min). Credencial recusada: a falha sobe, e quem chama trata o fim do vinculo - nada e
     * enviado sem credencial valida. {@code now}: sem respeitar a espera (logo depois do desfecho).
     */
    private void deliverHeld(MachineVault.Link current, Work w, boolean now, boolean busy) throws Refused {
        if (w == null) {
            return;
        }
        // uma fatia para a consulta inteira (todas as tentativas guardadas); com tentativa ativa, so um lote de registros
        long until = busy ? Long.MIN_VALUE : clock.millis() + heldSendBudgetMillis;
        boolean started = false;
        long at = clock.instant().getEpochSecond();
        LossLedger ledger = vault.losses();
        boolean any = false;
        // fila que nao esta mais guardada (confirmada, impedida ou removida pelo prazo) sai da memoria
        heldQueues.keySet().removeIf(id -> ledger.load(id).filter(x -> x.logsHeld() && x.logsBlock() == LossLedger.Block.NONE).isEmpty());
        heldResultsDone.keySet().removeIf(id -> ledger.load(id).filter(x -> x.resultsHeld() && x.resultsBlock() == LossLedger.Block.NONE).isEmpty());
        for (LossLedger.Entry e : ledger.all(at)) {
            if (!e.deliverable()) {
                continue;
            }
            long[] wait = heldRetry.get(e.attemptId());
            if (!now && wait != null && at < wait[0]) {
                continue;
            }
            if (started && clock.millis() >= until) {
                // a fatia desta consulta acabou: as outras tentativas guardadas ficam para a seguinte
                break;
            }
            try {
                boolean logs = e.logsHeld() && e.logsBlock() == LossLedger.Block.NONE;
                started = started || logs;
                if (logs && !sendHeldLogs(current, e, ledger, until)) {
                    // a fatia de tempo desta consulta acabou com linhas por enviar: segue na proxima, sem espera; os registros
                    // vao antes dos resultados
                    heldRetry.remove(e.attemptId());
                    any = true;
                    continue;
                }
                e = ledger.load(e.attemptId()).orElse(null);
                if (e != null && e.resultsHeld() && e.resultsBlock() == LossLedger.Block.NONE) {
                    if (busy) {
                        // um arquivo pode levar minutos: com tentativa ativa, os resultados guardados esperam a maquina ficar livre
                        continue;
                    }
                    started = true;
                    if (!sendHeldResults(current.credential(), e, w, ledger, until)) {
                        heldRetry.remove(e.attemptId());
                        any = true;
                        continue;
                    }
                }
                heldRetry.remove(e == null ? "" : e.attemptId());
                any = true;
            } catch (Refused x) {
                String attempt = e == null ? "" : e.attemptId();
                if (x.problem() == Problem.LINK_ENDED || x.problem() == Problem.NOT_ENABLED) {
                    throw x;
                }
                if (x.problem() == Problem.ATTEMPT_STALE || x.problem() == Problem.ATTEMPT_GONE || x.problem() == Problem.ATTEMPT_TRANSITION) {
                    // a Platform nao reconhece mais esta atribuicao: nao ha novo envio, e o motivo fica dito
                    ledger.blocked(attempt, LossLedger.Block.AUTHORIZATION);
                    holdReason(w, attempt, "NOT_AUTHORIZED");
                    heldRetry.remove(attempt);
                    any = true;
                    continue;
                }
                long next = wait == null ? HELD_RETRY_FIRST_SECONDS : Math.min(wait[1] * 2, HELD_RETRY_MAX_SECONDS);
                heldRetry.put(attempt, new long[] {at + next, next});
            } catch (RuntimeException x) {
                // fica para a proxima consulta
            }
        }
        if (any && !now) {
            changed();
        }
    }

    private static void holdReason(Work w, String attemptId, String reason) {
        try {
            w.holdOutputs(attemptId, reason);
        } catch (RuntimeException x) {
            // a marca fica como estava: a pasta continua guardada
        }
    }

    /** Os resultados guardados de uma tentativa terminada: envia, fecha, e so com TUDO confirmado apaga a pasta. */
    private boolean sendHeldResults(String credential, LossLedger.Entry e, Work w, LossLedger ledger, long until) throws Refused {
        long at = clock.instant().getEpochSecond();
        String pending = sendResults(credential, e, w, until);
        if (IN_PROGRESS.equals(pending)) {
            // a fatia acabou com arquivos por enviar: nada foi fechado, nada foi apagado; segue na consulta seguinte
            return false;
        }
        heldResultsDone.remove(e.attemptId());
        if (pending == null) {
            try {
                w.discardOutputs(e.attemptId());
            } catch (RuntimeException x) {
                // a pasta fica para a proxima limpeza; a Platform ja tem tudo
            }
            ledger.resultsSettled(e.attemptId());
            return true;
        }
        // sem a confirmacao de TUDO, a pasta fica, com o motivo; nao ha o que enviar de novo
        ledger.resultsHeld(e.attemptId(), e.token(), e.epoch(), at, "OUT_OF_LIMIT".equals(pending) ? LossLedger.Block.LIMIT : LossLedger.Block.CONFLICT);
        holdReason(w, e.attemptId(), pending);
        return true;
    }

    /**
     * Os registros guardados de uma tentativa terminada: envia o que falta, fecha, e so com o fechamento confirmado apaga a fila.
     * {@code false}: a fatia de tempo desta consulta acabou e ainda ha linhas por enviar (nada foi fechado, nada foi apagado).
     */
    private boolean sendHeldLogs(MachineVault.Link current, LossLedger.Entry e, LossLedger ledger, long until) throws Refused {
        long at = clock.instant().getEpochSecond();
        boolean mine = e.attemptId().equals(queueAttempt) && queue != null;
        LogQueue q;
        try {
            LogQueue open = heldQueues.get(e.attemptId());
            q = mine ? queue : open != null ? open : vault.logQueue(e.attemptId(), List.of(current.credential(), e.token()));
            heldQueues.put(e.attemptId(), q);
        } catch (RuntimeException x) {
            // este computador nao consegue abrir a fila (outro usuario, arquivo cortado): nao ha o que enviar
            ledger.logsHeld(e.attemptId(), e.token(), e.epoch(), at, e.logLines(), LossLedger.Block.CONFLICT);
            return true;
        }
        if (!mine && !q.existed() && q.lines() == 0) {
            // nao ha fila (a tentativa nao capturou nada, ou a fila ja saiu): nada a esperar deste tipo
            heldQueues.remove(e.attemptId());
            ledger.logsSettled(e.attemptId());
            return true;
        }
        // quantas linhas a fila tem fica dito JA: se o prazo de guarda vencer com o envio ainda sendo tentado, a perda e
        // registrada (e avisada a Platform) com a quantidade verdadeira, nao com zero
        if (q.lines() != e.logLines()) {
            ledger.logsHeld(e.attemptId(), e.token(), e.epoch(), at, q.lines(), LossLedger.Block.NONE);
        }
        // o prazo de guarda conta do desfecho, nao da ultima tentativa de envio: a data do arquivo e preservada
        java.nio.file.attribute.FileTime since = vault.logQueueTime(e.attemptId());
        try {
            Boolean closed = closeLogs(current.credential(), q, e.attemptId(), e.token(), e.epoch(), until);
            if (closed == null) {
                return false;
            }
            heldQueues.remove(e.attemptId());
            if (closed) {
                q.delete();
                if (mine) {
                    dropQueue(false);
                }
                ledger.logsSettled(e.attemptId());
                return true;
            }
            ledger.logsHeld(e.attemptId(), e.token(), e.epoch(), at, q.lines(), LossLedger.Block.CONFLICT);
            return true;
        } finally {
            vault.logQueueTime(e.attemptId(), since);
        }
    }

    /**
     * Envia tudo o que a fila tem e ainda nao foi confirmado e fecha o envio. {@code true}: a Platform confirmou o fechamento com
     * as mesmas contagens. {@code false}: nao ha como confirmar (ela tem outra coisa nessas linhas, ou outro fechamento).
     * {@code null}: a fatia desta consulta ({@code until}; ver {@link #heldSendBudgetMillis}) acabou com linhas por enviar - nada
     * foi fechado; segue na consulta seguinte. Falha passageira: lanca, e a fila fica como esta.
     */
    private Boolean closeLogs(String credential, LogQueue q, String attemptId, String token, int epoch, long until) throws Refused {
        try {
            q.flush();
        } catch (IOException x) {
            // o que esta na memoria ainda pode ser enviado
        }
        boolean first = true;
        try {
            while (true) {
                List<LogQueue.Line> batch = q.batch();
                if (batch.isEmpty()) {
                    break;
                }
                if (!first && clock.millis() >= until) {
                    // pelo menos um lote por consulta; passou da fatia, devolve a vez
                    return null;
                }
                first = false;
                try {
                    q.acked(channel.sendLogs(credential, attemptId, token, epoch, batch));
                } catch (Refused x) {
                    if (x.problem() != Problem.LOG_LIMIT) {
                        throw x;
                    }
                    // a Platform chegou ao limite dela antes da fila (ela nao grava parte de um lote): o que ainda cabe vai
                    // linha a linha, e o resto conta como descartado
                    for (LogQueue.Line one : batch) {
                        try {
                            q.acked(channel.sendLogs(credential, attemptId, token, epoch, List.of(one)));
                        } catch (Refused y) {
                            if (y.problem() != Problem.LOG_LIMIT) {
                                throw y;
                            }
                            q.refusedFrom(one.sequence());
                            break;
                        }
                    }
                    break;
                }
            }
            return q.drained() && channel.completeLogs(credential, attemptId, token, epoch, q.lines(), q.dropped());
        } catch (Refused x) {
            if (x.problem() == Problem.LOG_CONFLICT) {
                return false;
            }
            throw x;
        }
    }

    /**
     * Junto de "terminou sem prova do resultado": este computador CONFERIU que o processo daquela tentativa nao esta mais em
     * execucao? So quando a execucao chegou a ser lancada aqui (ha o identificador dela) e o motor a conhece e diz que nao esta em
     * andamento - o motor so fecha uma execucao que ele nao viu terminar depois de conferir que o processo dela nao existe mais.
     * Quando o aplicativo fechou entre pedir o inicio e registrar a execucao, ou o motor nao a conhece, nao ha o que conferir: falso.
     */
    private static boolean processAbsent(AttemptJournal.Entry e, Work w) {
        if (!"ENDED_UNKNOWN".equals(e.report()) || e.phase() != AttemptJournal.Phase.RUNNING || w == null || e.run() == null) {
            return false;
        }
        try {
            return w.progress(e.run()).map(p -> !"RUNNING".equals(p.state())).orElse(false);
        } catch (RuntimeException x) {
            return false;
        }
    }

    private void forget(AttemptJournal.Entry e, Work w) {
        if (e.attemptId().equals(queueAttempt) && vault.losses().load(e.attemptId()).filter(LossLedger.Entry::logsHeld).isEmpty()) {
            dropQueue(false);
        }
        try {
            journal.clear();
        } catch (IOException x) {
            // fica para a proxima consulta: a Platform respondera que a tentativa ja terminou
        }
        if (w != null) {
            try {
                w.discard(e.attemptId(), e.run());
            } catch (RuntimeException x) {
                // a limpeza do codigo e tentada de novo na proxima tentativa nesta pasta
            }
        }
    }

    /** Onde o ambiente esta neste computador: o identificador local sem o prefixo de tipo ({@code computador:}, {@code pacote:}...). */
    private static String where(String localId) {
        int cut = localId.indexOf(':');
        // "c:\..." tambem tem dois-pontos: so e prefixo de tipo o que vem antes de um caminho com letra de unidade ou barra
        return cut > 1 && cut < localId.length() - 1 ? localId.substring(cut + 1) : localId;
    }

    /** Liga a publicacao do inventario de Python (quem monta o aplicativo entrega a descoberta). */
    public void setPythonFinder(PythonFinder finder) {
        this.pythons = finder;
    }

    /**
     * Publica agora o retrato do Python deste computador. Para a Platform vao so um identificador opaco por ambiente, a
     * versao, o tipo e o instante: nenhum caminho, nome de pasta ou lista de pacotes. Sem vinculo utilizavel, nao faz nada.
     * A descoberta (processos, com prazo) roda FORA da trava das operacoes, para nao segurar pausar ou desabilitar.
     */
    public View publishInventory() throws Refused {
        PythonFinder finder = pythons;
        if (finder == null || usable() == null) {
            return view();
        }
        String observedAt = java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).withNano(0).toString();
        List<PythonSeen> seen;
        try {
            seen = finder.find();
        } catch (RuntimeException | LinkageError e) {
            throw new Refused(Problem.UNEXPECTED);
        }
        synchronized (operation) {
            MachineVault.Link current = usable();
            if (current == null) {
                return view();
            }
            List<MachineChannel.Published> out = new java.util.ArrayList<>();
            List<PythonHere> here = new java.util.ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(vault.inventorySalt(), "HmacSHA256"));
                for (PythonSeen s : seen) {
                    if (s == null || s.localId() == null || s.version() == null || !PYTHON_VERSION.matcher(s.version()).matches() || out.size() >= MAX_ENVIRONMENTS) {
                        continue;
                    }
                    String id = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(s.localId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    if (ids.add(id)) {
                        out.add(new MachineChannel.Published(id, s.version(), s.virtual(), observedAt));
                        here.add(new PythonHere(id.substring(0, 6), s.version(), s.virtual(), where(s.localId())));
                    }
                }
            } catch (IOException | java.security.GeneralSecurityException e) {
                throw new Refused(Problem.STORAGE);
            }
            int count;
            try {
                count = channel.publishInventory(current.credential(), out);
            } catch (Refused e) {
                synchronized (this) {
                    inventoryNotBefore = clock.instant().plusSeconds(INVENTORY_RETRY_SECONDS);
                }
                if (e.problem() == Problem.LINK_ENDED || e.problem() == Problem.NOT_ENABLED) {
                    failed(e.problem());
                }
                throw e;
            }
            synchronized (this) {
                publishedHere = List.copyOf(here);
                published = count;
                inventoryNotBefore = clock.instant().plusSeconds(INVENTORY_EVERY_SECONDS);
            }
            changed();
            return view();
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Habilita este computador com o codigo criado na Platform. Quem chama ja obteve a confirmacao explicita da pessoa,
     * NESTE computador. Falha: nada fica guardado.
     */
    public View enable(String typedCode) throws Refused {
        synchronized (operation) {
            String code = typedCode == null ? "" : typedCode.strip();
            String secret;
            synchronized (this) {
                if (!vault.available()) {
                    throw new Refused(Problem.STORAGE);
                }
                if (link != null || ended) {
                    throw new Refused(Problem.ALREADY_ENABLED);
                }
                if (!CODE.matcher(code).matches()) {
                    throw new Refused(Problem.CODE_REJECTED);
                }
                if (!code.equals(pendingCode)) {
                    byte[] bytes = new byte[32];
                    random.nextBytes(bytes);
                    pendingCode = code;
                    pendingSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                }
                secret = pendingSecret;
            }
            MachineChannel.Redeemed r;
            try {
                r = channel.redeem(code, secret, appVersion);
            } catch (Refused e) {
                if (e.problem() != Problem.NETWORK && e.problem() != Problem.TIMEOUT && e.problem() != Problem.SERVER && e.problem() != Problem.RATE_LIMITED) {
                    // recusa definitiva: o segredo gerado para este codigo nao serve para mais nada
                    forgetPending();
                }
                throw e;
            }
            MachineVault.Link created;
            try {
                created = new MachineVault.Link(channel.origin(), r.organizationId(), r.agentId(), r.workerId(), r.credentialId(), r.credentialExpiresAt(),
                        secret, false);
                vault.save(created);
            } catch (IOException | IllegalArgumentException e) {
                // a Platform habilitou, mas este computador nao conseguiu guardar: o vinculo e encerrado la, para nao ficar
                // uma maquina habilitada que ninguem controla. Se nem isso for possivel, a Platform a vera sem contato.
                try {
                    channel.unlink("nka1." + r.organizationId() + "." + r.credentialId() + "." + secret, r.agentId(), UUID.randomUUID().toString());
                } catch (Refused ignored) {
                    // sem contato: fica para quem administra revogar na Platform
                }
                forgetPending();
                throw new Refused(Problem.STORAGE);
            }
            synchronized (this) {
                link = created;
                ended = false;
                inContact = true;
                pendingCode = null;
                pendingSecret = null;
            }
            changed();
            return view();
        }
    }

    public View pause() throws Refused {
        return receiving(true);
    }

    public View resume() throws Refused {
        return receiving(false);
    }

    private View receiving(boolean pause) throws Refused {
        synchronized (operation) {
            MachineVault.Link current = usable();
            if (current == null) {
                throw new Refused(Problem.NOT_ENABLED);
            }
            boolean paused;
            try {
                paused = channel.receiving(current.credential(), pause);
            } catch (Refused e) {
                failed(e.problem());
                throw e;
            }
            store(current.withPaused(paused), true);
            return view();
        }
    }

    /**
     * Desabilita este computador: encerra o vinculo na Platform e remove a credencial daqui. Sem contato com a Platform,
     * NADA e removido e a falha e devolvida: a pessoa tenta de novo, ou quem administra revoga a maquina na Platform.
     * Vinculo que a Platform ja encerrou (ou que este computador nao consegue usar): so a remocao local.
     */
    public View disable() throws Refused {
        synchronized (operation) {
            MachineVault.Link current;
            boolean onlyLocal;
            String op;
            synchronized (this) {
                current = link;
                onlyLocal = ended || current == null;
                if (pendingUnlink == null) {
                    pendingUnlink = UUID.randomUUID().toString();
                }
                op = pendingUnlink;
            }
            if (!onlyLocal && journal.load(current.agentId()).isPresent()) {
                // ha uma tentativa recebida e ainda nao resolvida com a Platform (em andamento, ou com o desfecho por informar):
                // desvincular agora a deixaria sem desfecho. A pessoa espera, ou pede o cancelamento na Platform; para so deixar
                // de receber trabalho novo, pausa. Quem administra continua podendo revogar a maquina na Platform
                throw new Refused(Problem.ATTEMPT_ACTIVE);
            }
            if (!onlyLocal) {
                try {
                    channel.unlink(current.credential(), current.agentId(), op);
                } catch (Refused e) {
                    if (e.problem() != Problem.LINK_ENDED) {
                        failed(e.problem());
                        throw e;
                    }
                }
            }
            try {
                vault.clear();
            } catch (IOException e) {
                synchronized (this) {
                    ended = true;
                }
                changed();
                throw new Refused(Problem.STORAGE);
            }
            synchronized (this) {
                link = null;
                ended = false;
                inContact = false;
                workerName = null;
                pendingUnlink = null;
                published = null;
                inventoryNotBefore = java.time.Instant.MIN;
            }
            changed();
            return view();
        }
    }

    /** Uma presenca. Sem vinculo utilizavel, nao faz nada. Nunca lanca. */
    public void beat() {
        synchronized (operation) {
            MachineVault.Link current = usable();
            if (current == null) {
                return;
            }
            // perto do vencimento (ou com uma troca interrompida), a credencial e renovada ANTES da presenca: se a troca ja
            // aconteceu no servidor e a resposta se perdeu, a credencial antiga nao serve mais para a presenca
            current = renewIfDue(current);
            if (current == null) {
                return;
            }
            try {
                MachineChannel.Presence p = channel.heartbeat(current.credential(), appVersion, work != null, journal.load(current.agentId()).isPresent() || (work != null && work.busy()));
                MachineVault.Link next = current.withPaused(p.paused());
                boolean renamed;
                synchronized (this) {
                    renamed = p.workerName() != null && !p.workerName().equals(workerName);
                    if (renamed) {
                        workerName = p.workerName();
                    }
                }
                if (renamed) {
                    changed();
                }
                try {
                    next = next.withExpiry(p.credentialExpiresAt());
                } catch (IllegalArgumentException e) {
                    // validade fora do formato: mantem a conhecida
                }
                store(next, !next.equals(current));
            } catch (Refused e) {
                failed(e.problem());
                return;
            }
        }
        // com a presenca aceita, o inventario de Python e publicado quando for a hora (fora da trava: a descoberta demora)
        boolean dueNow;
        synchronized (this) {
            dueNow = pythons != null && !clock.instant().isBefore(inventoryNotBefore);
        }
        if (dueNow) {
            try {
                publishInventory();
            } catch (Refused e) {
                // fica para a proxima; a presenca nao depende do inventario
            }
        }
    }

    /**
     * Renova a credencial se estiver na janela, ou conclui uma troca interrompida. Devolve o vinculo a usar agora (o novo,
     * se a troca concluiu; o atual, se nao era hora ou se a falha foi passageira) ou {@code null} se o vinculo acabou.
     * <ol>
     * <li>o segredo novo e gerado aqui e GRAVADO (protegido) antes de qualquer pedido;</li>
     * <li>a troca e pedida com a credencial atual; a resposta traz so o identificador e a validade da nova;</li>
     * <li>a credencial nova e gravada; so entao o segredo pendente e apagado.</li>
     * </ol>
     * Caiu no meio: na proxima vez, a troca e repetida com o MESMO segredo, e o servidor devolve a mesma credencial.
     */
    private MachineVault.Link renewIfDue(MachineVault.Link current) {
        Optional<String> pending = vault.pending(current.credentialId());
        java.time.Instant now = clock.instant();
        if (pending.isEmpty() && !due(current, now)) {
            return current;
        }
        synchronized (this) {
            if (now.isBefore(renewNotBefore)) {
                return current;
            }
        }
        String secret;
        if (pending.isPresent()) {
            secret = pending.get();
        } else {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try {
                vault.savePending(current.credentialId(), secret);
            } catch (IOException e) {
                // sem conseguir guardar o segredo novo, a troca nem e pedida: a credencial atual continua valendo
                retryLater(now);
                return current;
            }
        }
        MachineChannel.Redeemed r;
        try {
            r = channel.renew(current.credential(), secret,
                    new MachineChannel.Redeemed(current.organizationId(), current.agentId(), current.workerId(), current.credentialId(), current.credentialExpiresAt()));
        } catch (Refused e) {
            switch (e.problem()) {
                case LINK_ENDED, NOT_ENABLED -> {
                    // vencida, revogada ou a maquina nao esta mais habilitada: nunca volta a valer
                    ended();
                    return null;
                }
                case RENEWAL_NOT_DUE -> {
                    // o servidor diz que ainda nao e hora: o segredo gerado nao sera usado
                    try {
                        vault.clearPending();
                    } catch (IOException ignored) {
                        // fica para a proxima; o arquivo so vale para esta credencial
                    }
                    retryLater(now);
                    return current;
                }
                default -> {
                    retryLater(now);
                    return current;
                }
            }
        }
        MachineVault.Link renewed;
        try {
            renewed = current.renewed(r.credentialId(), r.credentialExpiresAt(), secret);
            vault.save(renewed);
        } catch (IOException | IllegalArgumentException e) {
            // a troca aconteceu no servidor, mas nao foi gravada aqui: o segredo pendente continua guardado e a troca e
            // repetida depois (o servidor devolve a mesma credencial)
            retryLater(now);
            failed(Problem.STORAGE);
            return null;
        }
        try {
            vault.clearPending();
        } catch (IOException ignored) {
            // o arquivo pendente e de outra credencial agora: nao e mais lido
        }
        synchronized (this) {
            link = renewed;
            renewNotBefore = java.time.Instant.MIN;
            renewWaitSeconds = RENEW_RETRY_FIRST_SECONDS;
        }
        changed();
        return renewed;
    }

    private static boolean due(MachineVault.Link link, java.time.Instant now) {
        try {
            return !java.time.OffsetDateTime.parse(link.credentialExpiresAt()).toInstant().isAfter(now.plus(java.time.Duration.ofDays(RENEW_WITHIN_DAYS)));
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    private synchronized void retryLater(java.time.Instant now) {
        renewNotBefore = now.plusSeconds(renewWaitSeconds);
        renewWaitSeconds = Math.min(renewWaitSeconds * 2, RENEW_RETRY_MAX_SECONDS);
    }

    /** Inicia a presenca periodica (uma agora, depois a cada minuto) enquanto o aplicativo estiver aberto. */
    public synchronized void start() {
        if (beats != null) {
            return;
        }
        beats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "presenca-remota");
            t.setDaemon(true);
            return t;
        });
        beats.scheduleWithFixedDelay(() -> {
            try {
                beat();
            } catch (RuntimeException | LinkageError e) {
                // a presenca nunca derruba o aplicativo
            }
        }, 0, BEAT_SECONDS, TimeUnit.SECONDS);
        beats.scheduleWithFixedDelay(() -> {
            try {
                if (sweep() > 0) {
                    changed();
                }
            } catch (RuntimeException | LinkageError e) {
                // a limpeza nunca derruba o aplicativo
            }
        }, 1, 1, TimeUnit.HOURS);
        beats.scheduleWithFixedDelay(() -> {
            try {
                pull();
            } catch (RuntimeException | LinkageError e) {
                // a consulta de entrega nunca derruba o aplicativo
            }
        }, 5, PULL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Ao abrir, e depois de hora em hora: limpa o codigo deixado por tentativas que nao sao a pendente e remove os resultados
     * guardados cujo prazo de guarda venceu. Nunca lanca; sem quem execute, nao faz nada. Devolve quantas pastas foram limpas.
     */
    public int sweep() {
        Work w = work;
        if (w == null) {
            return 0;
        }
        synchronized (operation) {
            MachineVault.Link current = usable();
            String keep = current == null ? null : journal.load(current.agentId()).map(AttemptJournal.Entry::attemptId).orElse(null);
            long now = clock.instant().getEpochSecond();
            LossLedger ledger = vault.losses();
            int cleaned = 0;
            try {
                // filas de registros de tentativas ja resolvidas, sem confirmacao, paradas ha mais que o prazo de guarda. A fila da
                // tentativa pendente (em andamento, ou ainda enviando) nunca entra na conta
                for (String attempt : vault.sweepLogQueues(keep, clock.instant())) {
                    ledger.logsLost(attempt, now);
                    cleaned++;
                }
                w.retentionDays(vault.retentionDays());
                List<Held> before = w.held(keep);
                cleaned += w.sweep(keep);
                java.util.Set<String> still = new java.util.HashSet<>();
                w.held(keep).forEach(h -> still.add(h.attemptId()));
                for (Held h : before) {
                    if (!still.contains(h.attemptId()) && h.removeAtEpochSecond() <= now) {
                        // a pasta de saida foi removida pelo prazo: fica o registro minimo (quantos arquivos havia e quando)
                        ledger.resultsLost(h.attemptId(), h.files(), now);
                    }
                }
            } catch (RuntimeException x) {
                // fica para a proxima limpeza
            }
            reportLosses(current, ledger, now);
            return cleaned;
        }
    }

    /**
     * Avisa a Platform de cada perda por prazo de guarda que ela ainda nao conhece. So com o vinculo utilizavel; falha de rede
     * fica para a proxima limpeza (de hora em hora, e ao abrir). Se a Platform nao reconhece mais a atribuicao, o aviso e dado
     * por impossivel e o registro local continua dizendo que houve a perda. Nunca lanca.
     */
    private void reportLosses(MachineVault.Link current, LossLedger ledger, long now) {
        if (current == null) {
            return;
        }
        boolean any = false;
        for (LossLedger.Entry e : ledger.all(now)) {
            if (!e.owes() || e.token().isEmpty()) {
                continue;
            }
            for (boolean logs : new boolean[] {true, false}) {
                boolean due = logs ? e.logsLost() && e.logsNotice() == LossLedger.Notice.PENDING : e.resultsLost() && e.resultsNotice() == LossLedger.Notice.PENDING;
                if (!due) {
                    continue;
                }
                try {
                    if (channel.reportLoss(current.credential(), e.attemptId(), e.token(), e.epoch(), logs, logs ? e.logLines() : e.resultFiles())) {
                        ledger.noticed(e.attemptId(), logs, LossLedger.Notice.SENT);
                        any = true;
                    }
                } catch (Refused x) {
                    if (x.problem() == Problem.ATTEMPT_STALE || x.problem() == Problem.ATTEMPT_GONE || x.problem() == Problem.ATTEMPT_TRANSITION) {
                        ledger.noticed(e.attemptId(), logs, LossLedger.Notice.IMPOSSIBLE);
                        any = true;
                    } else {
                        // sem rede, vinculo encerrado ou resposta inesperada: nada muda; a proxima limpeza tenta de novo
                        return;
                    }
                } catch (RuntimeException x) {
                    return;
                }
            }
        }
        if (any) {
            changed();
        }
    }

    /** Fechar o aplicativo NAO desabilita a maquina: so a presenca para, e a Platform a vera sem contato. */
    @Override
    public synchronized void close() {
        LogQueue open = queue;
        if (open != null) {
            try {
                open.flush();
            } catch (IOException | RuntimeException x) {
                // o que nao foi gravado se perde com o fechamento
            }
        }
        if (beats != null) {
            beats.shutdownNow();
            beats = null;
        }
    }

    private synchronized MachineVault.Link usable() {
        return ended ? null : link;
    }

    private synchronized void forgetPending() {
        pendingCode = null;
        pendingSecret = null;
    }

    private void store(MachineVault.Link next, boolean persist) {
        if (persist) {
            try {
                vault.save(next);
            } catch (IOException e) {
                // o recebimento vale no servidor; o que ficou no disco e so o ultimo conhecido
            }
        }
        boolean notify;
        synchronized (this) {
            notify = !next.equals(link) || !inContact;
            link = next;
            inContact = true;
        }
        if (notify) {
            changed();
        }
    }

    /** O vinculo acabou de vez (a propria renovacao foi recusada por credencial vencida, revogada ou maquina nao habilitada). */
    private void ended() {
        boolean notify;
        synchronized (this) {
            notify = !ended || inContact;
            ended = true;
            inContact = false;
        }
        if (notify) {
            changed();
        }
    }

    private void failed(Problem problem) {
        boolean notify;
        synchronized (this) {
            boolean wasEnded = ended;
            boolean wasInContact = inContact;
            // com uma troca de credencial interrompida, a recusa da credencial antiga nao e o fim do vinculo: a troca pode
            // ter acontecido no servidor. Quem decide e a repeticao da troca, na proxima presenca
            boolean renewing = link != null && vault.pending(link.credentialId()).isPresent();
            if ((problem == Problem.LINK_ENDED && !renewing) || problem == Problem.NOT_ENABLED) {
                ended = true;
            }
            // falha so deste computador (gravar): o contato com a Platform nao e o problema, mas o estado nao foi confirmado
            inContact = false;
            notify = wasEnded != ended || wasInContact;
        }
        if (notify) {
            changed();
        }
    }

    private void changed() {
        for (Runnable l : listeners) {
            l.run();
        }
    }
}
