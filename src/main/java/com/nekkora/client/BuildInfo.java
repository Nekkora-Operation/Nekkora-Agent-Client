package com.nekkora.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * O que o build gravou sobre este aplicativo. A UNICA fonte e o {@code pom.xml}: o Maven escreve a versao do projeto em
 * {@code build.properties}, que vai dentro do JAR, e e dele que a tela le. Vale igual no executavel distribuido e na
 * execucao pelo Maven.
 * <p>
 * Sem esse arquivo, ou com ele sem preencher (execucao direta numa IDE, sem o Maven), nao ha versao: a tela diz
 * "Versao de desenvolvimento" em vez de apresentar um numero de release que ninguem gerou.
 */
public record BuildInfo(Optional<String> version) {

    static final String RESOURCE = "build.properties";
    /** Numero de versao como o do projeto: 0.1.0, 1.2, 1.4.0-rc.1. Nunca um marcador por preencher. */
    private static final Pattern VERSION = Pattern.compile("^\\d{1,4}(\\.\\d{1,4}){1,3}(-[0-9A-Za-z][0-9A-Za-z.]{0,23})?$");

    /** Execucao sem os metadados do build. */
    public static final BuildInfo DEVELOPMENT = new BuildInfo(Optional.empty());

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
        return new BuildInfo(VERSION.matcher(v).matches() ? Optional.of(v) : Optional.empty());
    }
}
