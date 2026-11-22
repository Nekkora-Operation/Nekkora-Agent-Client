package com.nekkora.client;

import java.io.IOException;
import java.io.InputStream;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * O que o build gravou sobre este aplicativo. A UNICA fonte e o {@code pom.xml}: o Maven escreve a versao do projeto em
 * {@code build.properties}, que vai dentro do JAR, e e dele que a tela le. Vale igual no executavel distribuido e na
 * execucao pelo Maven. O ano do build e gravado do mesmo jeito, a cada build.
 * <p>
 * Sem esse arquivo, ou com ele sem preencher (execucao direta numa IDE, sem o Maven), nao ha versao: a tela diz
 * "Versao de desenvolvimento" em vez de apresentar um numero de release que ninguem gerou, e o ano e o do momento da
 * execucao (que, nesse caso, e o proprio "build").
 * <p>
 * LICENCIAMENTO-002: o pacote tambem grava o commit (40 hexadecimais) e se a pasta tinha alteracoes locais. Um build comum
 * nao grava commit; entao a tela mostra so a versao, nunca um commit inventado.
 */
public record BuildInfo(Optional<String> version, Optional<Integer> year, Optional<String> commit, boolean dirty) {

    public BuildInfo(Optional<String> version, Optional<Integer> year) {
        this(version, year, Optional.empty(), false);
    }

    static final String RESOURCE = "build.properties";
    /** Numero de versao como o do projeto: 0.1.0, 1.2, 1.4.0-rc.1. Nunca um marcador por preencher. */
    private static final Pattern VERSION = Pattern.compile("^\\d{1,4}(\\.\\d{1,4}){1,3}(-[0-9A-Za-z][0-9A-Za-z.]{0,23})?$");

    /** Execucao sem os metadados do build. */
    public static final BuildInfo DEVELOPMENT = new BuildInfo(Optional.empty(), Optional.empty());
    private static final Pattern YEAR = Pattern.compile("^2\\d{3}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");

    /** O que o build deste aplicativo gravou; {@link #DEVELOPMENT} se nao gravou nada. */
    public static BuildInfo load() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return DEVELOPMENT;
            }
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return DEVELOPMENT;
        }
        return of(p);
    }

    static BuildInfo of(Properties p) {
        String v = p.getProperty("version", "").trim();
        String y = p.getProperty("year", "").trim();
        String c = p.getProperty("commit", "").trim();
        boolean ok = COMMIT.matcher(c).matches();
        return new BuildInfo(VERSION.matcher(v).matches() ? Optional.of(v) : Optional.empty(),
                YEAR.matcher(y).matches() ? Optional.of(Integer.valueOf(y)) : Optional.empty(),
                ok ? Optional.of(c) : Optional.empty(), ok && "true".equals(p.getProperty("dirty", "").trim()));
    }

    /** Os 7 primeiros caracteres do commit, como no nome do pacote. */
    public Optional<String> commitShort() {
        return commit.map(c -> c.substring(0, 7));
    }

    /** Ano dos direitos: o do build; sem metadados, o ano corrente (UTC). */
    public int yearOr(Year now) {
        return year.orElse(now.getValue());
    }

    public int yearOrNow() {
        return yearOr(Year.now(ZoneOffset.UTC));
    }
}
