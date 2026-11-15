package com.nekkora.client.run;

import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.JsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/**
 * O sinal OPCIONAL que o script publica e o desfecho de um pacote Python externo. Codigo de saida 0 sem sinal e "termino
 * sem erro sinalizado" ({@code AGT-EXT-001}), nunca sucesso de negocio; o sinal explicito do script e opcional e chega
 * por um arquivo cujo caminho o processo recebe em {@link #SIGNAL_ENV}.
 *
 * <p>Parte de sinal e desfecho de {@code com.nekkora.agent.exec.ExternalLaunch} (Nekkora-Agent 9b1e7e1): nomes, esquema
 * e classificacao sem mudanca. O lancador do agendador do cliente e a autorizacao da Platform NAO foram portados.
 */
public final class ExternalSignal {
    private ExternalSignal() {}

    public static final String SIGNAL_FILE = "sinal.json";
    public static final String SIGNAL_SCHEMA = "nekkora-agent/external-signal";
    public static final String SIGNAL_ENV = "NEKKORA_SINAL";
    /** Variavel com a pasta de saida desta execucao. */
    public static final String OUTPUT_ENV = "NEKKORA_SAIDA";

    /** Desfecho: codigo 0 nunca e, sozinho, sucesso de negocio. */
    public record Outcome(ExecutionState state, ErrorCode code) {}

    public enum Signal { NONE, BUSINESS_SUCCESS, BUSINESS_FAILURE }

    public static Outcome classify(int exitCode, Signal s) {
        if (s == Signal.BUSINESS_FAILURE) return new Outcome(ExecutionState.FAILED, ErrorCode.EXT_BUSINESS_FAILURE);
        if (exitCode != 0) return new Outcome(ExecutionState.FAILED, ErrorCode.EXT_EXIT_NONZERO);
        if (s == Signal.BUSINESS_SUCCESS) return new Outcome(ExecutionState.SUCCEEDED, null);
        return new Outcome(ExecutionState.SUCCEEDED, ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL);
    }

    /** O sinal do script: so o esquema fechado conta; qualquer outra coisa = sem sinal (nada e presumido). */
    public static Signal readSignal(Path f) {
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 4096) return Signal.NONE;
            Map<String, Object> m = JsonReader.object(Files.readString(f, StandardCharsets.UTF_8));
            if (!SIGNAL_SCHEMA.equals(JsonReader.text(m, "schema")) || !"1.0.0".equals(JsonReader.text(m, "version"))) return Signal.NONE;
            String o = JsonReader.text(m, "outcome");
            return "BUSINESS_SUCCESS".equals(o) ? Signal.BUSINESS_SUCCESS : "BUSINESS_FAILURE".equals(o) ? Signal.BUSINESS_FAILURE : Signal.NONE;
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return Signal.NONE;
        }
    }
}
