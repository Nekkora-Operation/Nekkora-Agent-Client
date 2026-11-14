package com.nekkora.client.run;

import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.ExecutionPackage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Escolhas da pessoa para a proxima execucao (so em memoria; nada disto e gravado): pacote inspecionado e se esta
 * aprovado, Python conferido e pasta de saida. Diz o que ainda falta para poder iniciar: a interface so habilita
 * "Iniciar" quando nada falta. Textos sem caminhos completos (so nomes de arquivo ou pasta).
 *
 * <p>Valor imutavel: cada {@code with...} devolve outro. Portado de {@code com.nekkora.agent.exec.ExecutionSetup}
 * (Nekkora-Agent 9b1e7e1), so para o pacote Python externo (que nao tem arquivo de entrada).
 */
public record ExecutionSetup(ExecutionPackage pkg, ErrorCode packageProblem, boolean approved, PythonRuntime.Probe python, ErrorCode pythonProblem, Path output,
                             SessionProbe.Session session) {

    public static ExecutionSetup empty() {
        return new ExecutionSetup(null, null, false, null, null, null, null);
    }

    public ExecutionSetup withPackage(ExecutionPackage p, ErrorCode problem, boolean isApproved) {
        // outro pacote: o Python e conferido de novo contra o novo pacote
        return new ExecutionSetup(p, problem, isApproved, python, pythonProblem(p, python, pythonProblem), output, session);
    }

    public ExecutionSetup withApproved(boolean a) {
        return new ExecutionSetup(pkg, packageProblem, a, python, pythonProblem, output, session);
    }

    public ExecutionSetup withPython(PythonRuntime.Probe probe, ErrorCode problem) {
        return new ExecutionSetup(pkg, packageProblem, approved, probe, pythonProblem(pkg, probe, problem), output, session);
    }

    /** O ambiente escolhido nao atende ao pacote (ex.: dependencia ausente), conferido antes do inicio. */
    public ExecutionSetup withEnvironmentProblem(ErrorCode problem) {
        return new ExecutionSetup(pkg, packageProblem, approved, python, problem, output, session);
    }

    public ExecutionSetup withOutput(Path out) {
        return new ExecutionSetup(pkg, packageProblem, approved, python, pythonProblem, out, session);
    }

    public ExecutionSetup withSession(SessionProbe.Session s) {
        return new ExecutionSetup(pkg, packageProblem, approved, python, pythonProblem, output, s);
    }

    private static ErrorCode pythonProblem(ExecutionPackage p, PythonRuntime.Probe probe, ErrorCode current) {
        if (probe == null) return current;
        if (p == null) return null;
        try {
            PythonRuntime.requireCompatible(probe, p);
            return null;
        } catch (ExecRefused e) {
            return e.code();
        }
    }

    /** O que falta, em ordem (vazio = pode iniciar). So codigos da lista fechada. */
    public List<ErrorCode> missing() {
        List<ErrorCode> out = new ArrayList<>();
        if (session != null && !session.usable()) out.add(session.problem());
        if (pkg == null) out.add(packageProblem != null ? packageProblem : ErrorCode.EXEC_PACKAGE_NOT_FOUND);
        else if (!approved) out.add(ErrorCode.EXEC_NOT_APPROVED);
        if (python == null) out.add(pythonProblem != null ? pythonProblem : ErrorCode.EXEC_PYTHON_INVALID);
        else if (pythonProblem != null) out.add(pythonProblem);
        if (output == null) out.add(ErrorCode.EXEC_OUTPUT_INVALID);
        return out;
    }

    public boolean canStart() {
        return session != null && missing().isEmpty();
    }

    /**
     * O pedido tipado (so quando {@link #canStart()} e o contexto e do MESMO pacote escolhido). O contexto e o que fica
     * no registro da execucao, do inicio ao fim.
     */
    public Optional<RunController.Request> request(RunContext context, Duration timeout) {
        if (!canStart() || context == null || !context.fingerprint().equals(pkg.fingerprint())) return Optional.empty();
        return Optional.of(new RunController.Request(pkg.dir(), context, python.python(), output, timeout));
    }

    // ----- textos -----

    /** O FORMATO do pacote. Nunca afirma quem o gerou: o formato nao prova procedencia (ver {@link #originLabel}). */
    public static String kindLabel(ExecutionPackage p) {
        return switch (p.kind()) {
            case EXTERNAL_PYTHON -> "Pacote Python externo (sem framework Nekkora)";
        };
    }

    /**
     * Procedencia: o aplicativo nao tem como comprovar quem gerou um pacote local (sem assinatura nem atestado), entao diz
     * isso. O gerador que o pacote declara aparece como declaracao, nao como fato.
     */
    public static String originLabel(ExecutionPackage p) {
        return "Origem não verificada" + p.declaredGenerator().map(g -> " · gerador declarado pelo pacote: " + g).orElse(" · nenhum gerador declarado");
    }

    public static String sessionLabel(SessionProbe.Session s) {
        if (s == null) return "Verificando…";
        if (s.usable()) return "Sessão interativa do usuário (tela desbloqueada)";
        return s.problem() == ErrorCode.EXEC_SESSION_LOCKED ? "Tela bloqueada: a execução não começa" : s.problem().description();
    }

    /** O pedido de cancelamento e a situacao dele (pedir nao e confirmar). */
    public static String cancellationLabel(ExecutionRecord.Cancellation c) {
        String who = c.source() == ExecutionRecord.CancelSource.APPLICATION_SHUTDOWN ? "pelo encerramento do Agent" : "pela pessoa operadora";
        String when = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(c.requested());
        // a situacao vem primeiro: numa linha estreita, o que se corta e o detalhe do pedido
        return switch (c.outcome()) {
            case PENDING -> "Aguardando confirmação";
            case CONFIRMED -> "Confirmado";
            case NOT_EFFECTIVE -> "Não efetivado (o processo terminou antes)";
        } + " · pedido " + who + " às " + when;
    }

    /**
     * Onde um pacote Python externo grava, como o aplicativo faz de fato: uma subpasta por execucao dentro da pasta de
     * saida, informada ao script em {@code NEKKORA_SAIDA}; a pasta de trabalho e a copia conferida do pacote.
     */
    public static final String EXTERNAL_OUTPUT_LABEL = "Uma subpasta por execução, informada ao script em NEKKORA_SAIDA. A pasta de trabalho é a cópia "
            + "do pacote: um script que grava por caminho relativo grava nessa cópia, não na pasta de saída.";

    /** "1 min 05 s" (sem horario: so a duracao). */
    public static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        return s < 60 ? s + " s" : (s / 60) + " min " + String.format("%02d", s % 60) + " s";
    }

    /** Pasta do ambiente Python (a pasta acima de {@code Scripts} ou {@code bin}), so o nome. */
    public static String pythonFolder(Path python) {
        Path dir = python == null ? null : python.getParent();
        if (dir != null && dir.getFileName() != null && List.of("scripts", "bin").contains(dir.getFileName().toString().toLowerCase(java.util.Locale.ROOT))) {
            dir = dir.getParent();
        }
        return shortName(dir);
    }

    /** Nome do arquivo ou da pasta, nunca o caminho completo (a tela pode ser capturada). */
    public static String shortName(Path p) {
        return p == null || p.getFileName() == null ? "—" : p.getFileName().toString();
    }
}
