package com.nekkora.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Fronteiras do Client, conferidas no codigo-fonte e no pom:
 * <ul>
 * <li>nenhum codigo, classe ou dependencia do Nekkora Agent (administrativo): nada de importacao, empacotamento,
 * execucao, catalogo, pareamento, Worker ou supervisao;</li>
 * <li>uma unica dependencia de execucao (FlatLaf);</li>
 * <li>regras do fluxo, do armazenamento e do tema sem interface grafica;</li>
 * <li>nenhum login simulado no codigo do aplicativo; nenhum segredo, endereco de laboratorio ou certificado embarcado.</li>
 * </ul>
 */
class ArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path RESOURCES = Path.of("src/main/resources");

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String code(Path file) throws IOException {
        // sem comentarios: as regras valem para o codigo, e os comentarios explicam o que NAO existe
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    void nenhumCodigoDoAgentAdministrativo() throws IOException {
        // pacotes e classes do Nekkora Agent, e os nomes das funcoes que nao pertencem ao Client
        Pattern proibido = Pattern.compile("com\\.nekkora\\.agent\\b|com\\.nekkora\\.ui\\b|com\\.nekkora\\.tokens\\b"
                + "|ImportFlow|ImportDialog|PackageHistory|MachineClient|PlatformService|ExecutionController|ApprovalStore|RemoteCatalog"
                + "|AgentBootstrap|AgentApp|ProcessBuilder|Runtime\\.getRuntime\\(\\)\\.exec");
        Pattern funcao = Pattern.compile("(?i)\\b(pareamento|pairing|worker|catalog|cat[aá]logo|importar|empacot|supervis|automa[cç][aã]o|automation|licen[cç]a|upload)\\w*");
        List<String> achados = new ArrayList<>();
        for (Path f : javaFiles(MAIN)) {
            String c = code(f);
            Matcher m = proibido.matcher(c);
            while (m.find()) {
                achados.add(f.getFileName() + ": " + m.group());
            }
            Matcher fn = funcao.matcher(c);
            while (fn.find()) {
                achados.add(f.getFileName() + ": " + fn.group());
            }
        }
        assertEquals(List.of(), achados);
    }

    @Test
    void pacotesDoAplicativoSaoSoOsEsperados() throws IOException {
        Set<String> pacotes = new TreeSet<>();
        for (Path f : javaFiles(MAIN)) {
            Matcher m = Pattern.compile("(?m)^package ([a-z.]+);").matcher(Files.readString(f));
            assertTrue(m.find(), f.toString());
            pacotes.add(m.group(1));
        }
        Set<String> permitidos = Set.of("com.nekkora.client", "com.nekkora.client.theme", "com.nekkora.client.storage", "com.nekkora.client.signin",
                "com.nekkora.client.ui", "com.nekkora.client.auth");
        for (String p : pacotes) {
            assertTrue(permitidos.contains(p), "pacote fora da lista: " + p);
        }
    }

    @Test
    void umaUnicaDependenciaDeExecucao() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher dep = Pattern.compile("(?s)<dependency>(.*?)</dependency>").matcher(pom);
        Set<String> execucao = new TreeSet<>();
        while (dep.find()) {
            String d = dep.group(1);
            if (!d.contains("<scope>test</scope>")) {
                Matcher a = Pattern.compile("<groupId>(.*?)</groupId>\\s*<artifactId>(.*?)</artifactId>").matcher(d);
                assertTrue(a.find());
                execucao.add(a.group(1) + ":" + a.group(2));
            }
        }
        assertEquals(Set.of("com.formdev:flatlaf"), execucao);
        assertFalse(pom.contains("nekkora-agent<"), "o Client nao depende do JAR do Nekkora Agent");
        assertFalse(pom.contains("<systemPath>"), "nenhum JAR local embarcado");
    }

    /** Fluxo, armazenamento e tema nao conhecem Swing/AWT nem rede; so {@code auth} fala com a rede e abre o navegador. */
    @Test
    void camadasSemInterfaceNaoImportamSwingNemRede() throws IOException {
        List<String> achados = new ArrayList<>();
        for (Path f : javaFiles(MAIN)) {
            String pkg = f.getParent().getFileName().toString();
            String c = code(f);
            boolean semTela = Set.of("theme", "storage", "signin", "auth").contains(pkg);
            if (semTela && Pattern.compile("import (javax\\.swing|com\\.formdev|com\\.nekkora\\.client\\.ui)").matcher(c).find()) {
                achados.add(f.getFileName() + ": interface grafica numa camada sem tela");
            }
            if (!"auth".equals(pkg) && Pattern.compile("java\\.net\\.http|HttpURLConnection|java\\.net\\.ServerSocket|com\\.sun\\.net|java\\.awt\\.Desktop").matcher(c).find()) {
                achados.add(f.getFileName() + ": rede ou navegador fora de auth");
            }
            if (Set.of("theme", "storage", "signin").contains(pkg) && c.contains("java.awt.")) {
                achados.add(f.getFileName() + ": AWT numa camada sem tela");
            }
        }
        assertEquals(List.of(), achados);
    }

    @Test
    void nenhumLoginSimuladoNoAplicativo() throws IOException {
        for (Path f : javaFiles(MAIN)) {
            String nome = f.getFileName().toString().toLowerCase(Locale.ROOT);
            assertFalse(nome.contains("fake") || nome.contains("mock") || nome.contains("simulad") || nome.contains("stub"), f.toString());
            String c = code(f).toLowerCase(Locale.ROOT);
            assertFalse(c.contains("success=true") || c.contains("\"success\""), f + ": a entrada nunca e decidida por um parametro de sucesso");
        }
    }

    @Test
    void nadaDeLaboratorioNemSegredoEmbarcado() throws IOException {
        List<String> achados = new ArrayList<>();
        Pattern suspeito = Pattern.compile("(?i)localhost|127\\.0\\.0\\.1:\\d|frontlab|client_secret|BEGIN (CERTIFICATE|PRIVATE KEY)|password\\s*=|senha\\s*=");
        for (Path f : javaFiles(MAIN)) {
            Matcher m = suspeito.matcher(code(f));
            while (m.find()) {
                achados.add(f.getFileName() + ": " + m.group());
            }
        }
        try (Stream<Path> s = Files.walk(RESOURCES)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                assertTrue(n.endsWith(".png") || n.endsWith(".txt") || n.endsWith(".properties"), "recurso inesperado: " + f);
                assertFalse(n.endsWith(".p12") || n.endsWith(".pem") || n.endsWith(".jks") || n.endsWith(".cfg"), f.toString());
            }
        }
        assertEquals(List.of(), achados);
    }
}
