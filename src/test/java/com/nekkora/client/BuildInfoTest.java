package com.nekkora.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.nekkora.client.ui.SignInText;

/** A versao mostrada vem de uma unica fonte, o pom.xml, pelos metadados que o build grava (AGENT-CLIENT-LOGIN-002 T02). */
public class BuildInfoTest {

    /** {@code <version>} do projeto, lido do proprio pom.xml (o primeiro depois de {@code <artifactId>} do projeto). */
    public static String pomVersion() throws IOException {
        Matcher m = Pattern.compile("<artifactId>nekkora-agent-client</artifactId>\\s*<version>([^<]+)</version>").matcher(Files.readString(Path.of("pom.xml")));
        assertTrue(m.find(), "versao do projeto no pom.xml");
        return m.group(1).trim();
    }

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    void aVersaoDoBuildEADoPom() throws IOException {
        // o arquivo que vai dentro do JAR foi preenchido pelo Maven com a versao do projeto
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(BuildInfo.RESOURCE)) {
            p.load(in);
        }
        assertEquals(pomVersion(), p.getProperty("version"), "build.properties preenchido pelo build");
        assertEquals(Optional.of(pomVersion()), BuildInfo.load().version());
        assertEquals("Versão " + pomVersion(), SignInText.version(BuildInfo.load()));
    }

    @Test
    void oArquivoDeOrigemNaoFixaVersaoNenhuma() throws IOException {
        String fonte = Files.readString(Path.of("src/main/resources/com/nekkora/client/build.properties"));
        assertTrue(fonte.contains("version=${project.version}"), "a versao e um marcador que o build preenche");
        assertFalse(Pattern.compile("(?m)^version=\\d").matcher(fonte).find(), "nenhum numero escrito a mao");
    }

    @Test
    void nenhumCodigoDoAplicativoEscreveAVersao() throws IOException {
        String v = pomVersion();
        List<String> achados = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String c = Files.readString(f);
                if (c.contains("\"" + v + "\"") || Pattern.compile("\"Vers[aã]o \\d").matcher(c).find()) {
                    achados.add(f.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), achados, "a versao so vem dos metadados do build");
    }

    @Test
    void semMetadadosEExecucaoDeDesenvolvimentoNuncaUmNumeroInventado() {
        for (Properties p : List.of(props(), props("version", ""), props("version", "   "), props("version", "${project.version}"),
                props("version", "@project.version@"), props("version", "desconhecida"), props("version", "v1"), props("version", "1"),
                props("version", "0.1.0 (203b766)"), props("version", "203b766"), props("version", "0.1.0+203b766"),
                props("version", "0.1.0\nplatform=https://exemplo.test"), props("version", "0.1.0-" + "x".repeat(40)))) {
            BuildInfo b = BuildInfo.of(p);
            assertEquals(Optional.empty(), b.version(), String.valueOf(p));
            assertEquals("Versão de desenvolvimento", SignInText.version(b), String.valueOf(p));
        }
        assertEquals("Versão de desenvolvimento", SignInText.version(BuildInfo.DEVELOPMENT));
    }

    // ------------------------------------------------------------------ direitos (T03)

    @Test
    void oAnoDosDireitosEODoBuild() throws IOException {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(BuildInfo.RESOURCE)) {
            p.load(in);
        }
        int agora = java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
        int gravado = Integer.parseInt(p.getProperty("year"));
        // este build acabou de ser feito (a virada do ano entre o build e o teste e a unica folga)
        assertTrue(gravado == agora || gravado == agora - 1, "ano gravado pelo build: " + gravado);
        assertEquals(Optional.of(gravado), BuildInfo.load().year());
        assertEquals("© " + gravado + " Nekkora. Todos os direitos reservados.", SignInText.rights(BuildInfo.load()));
    }

    @Test
    void oAnoVemDosMetadadosENaoDoRelogioDeQuemAbre() {
        BuildInfo antigo = BuildInfo.of(props("version", "0.1.0", "year", "2031"));
        assertEquals(2031, antigo.yearOr(java.time.Year.of(2040)), "aberto anos depois, o ano continua o do build");
        assertEquals("© 2031 Nekkora. Todos os direitos reservados.", SignInText.rights(antigo));
    }

    @Test
    void semAnoNosMetadadosValeOAnoCorrenteNuncaUmFixo() {
        for (Properties p : List.of(props(), props("year", ""), props("year", "${build.year}"), props("year", "${maven.build.timestamp}"),
                props("year", "26"), props("year", "1999"), props("year", "20261113"), props("year", "2026-11-13T00:00:00Z"), props("year", "dois mil"))) {
            BuildInfo b = BuildInfo.of(p);
            assertEquals(Optional.empty(), b.year(), String.valueOf(p));
            assertEquals(2043, b.yearOr(java.time.Year.of(2043)), String.valueOf(p));
        }
        assertEquals("© " + java.time.Year.now(java.time.ZoneOffset.UTC).getValue() + " Nekkora. Todos os direitos reservados.",
                SignInText.rights(BuildInfo.DEVELOPMENT));
    }

    @Test
    void nenhumAnoEscritoNoCodigoDaInterfaceNemNoArquivoDeOrigem() throws IOException {
        List<String> achados = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                // sem comentarios: o que importa e o que o programa mostra
                String c = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                if (Pattern.compile("\"[^\"\\n]*\\b20\\d\\d\\b[^\"\\n]*\"").matcher(c).find()) {
                    achados.add(f.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), achados, "nenhum ano fixo em texto do aplicativo");
        String fonte = Files.readString(Path.of("src/main/resources/com/nekkora/client/build.properties"));
        assertTrue(fonte.contains("year=${build.year}"));
        assertFalse(Pattern.compile("(?m)^year=\\d").matcher(fonte).find(), "nenhum ano escrito a mao");
    }

    @Test
    void aLinhaDeDireitosSoIdentificaANekkora() {
        String t = SignInText.rights(BuildInfo.of(props("year", "2030")));
        assertEquals("© 2030 Nekkora. Todos os direitos reservados.", t);
        assertFalse(t.matches("(?is).*(ltda|s\\.?a\\.?\\b|cnpj|®|™|https?://|www\\.|licen[cç]a|termos|\\d{2}\\.\\d{3}\\.\\d{3}).*"), t);
    }

    @Test
    void versoesValidasSaoMostradasComoVieram() {
        for (String v : List.of("0.1.0", "1.0", "2.10.3", "1.4.0-rc.1", "0.2.0-SNAPSHOT", "10.20.30.40")) {
            assertEquals(Optional.of(v), BuildInfo.of(props("version", " " + v + " ")).version());
            assertEquals("Versão " + v, SignInText.version(BuildInfo.of(props("version", v))));
        }
    }
}
