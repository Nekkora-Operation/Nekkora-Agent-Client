package com.nekkora.client.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Os tokens do Client sao os da Nekkora-lib: este teste le o {@code themes.css} e o {@code foundation.css} DA LIB
 * (copia literal do commit registrado em docs/design/tokens-e-ativos.md, com o SHA-256 conferido aqui) e compara cada
 * valor da paleta, das derivacoes de modo e da fundacao com o que o aplicativo usa. Nenhuma cor nasce no Client.
 */
class ThemeTokensTest {

    static final String THEMES_SHA256 = "6b3609f02b0cd912934544315c8dc70fe218b14cfafd4047f9e2814f49920e3f";
    static final String FOUNDATION_SHA256 = "b635b3ba9205d1ea4e7a1711362672d9fd4c14c7add32ac86d380b426d82e397";

    static String resource(String name) throws IOException {
        try (InputStream in = ThemeTokensTest.class.getResourceAsStream("/nekkora-lib/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String sha256(String name) throws Exception {
        try (InputStream in = ThemeTokensTest.class.getResourceAsStream("/nekkora-lib/" + name)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }

    /** Declaracoes {@code --nk-...: valor;} do bloco cujo seletor e EXATAMENTE {@code [data-nk-theme='id']}. */
    static Map<String, String> palette(String css, String id) {
        Matcher block = Pattern.compile("(?m)^\\[data-nk-theme='" + Pattern.quote(id) + "'\\] \\{\\n(.*?)^\\}", Pattern.DOTALL).matcher(css);
        assertTrue(block.find(), "paleta de " + id);
        return declarations(block.group(1));
    }

    /** Bloco de MODO (derivacoes) que lista o tema entre os seletores. */
    static Map<String, String> mode(String css, String id) {
        Matcher block = Pattern.compile("(?m)^((?:[^\\n{}]*,\\n)+[^\\n{}]*) \\{\\n(.*?)^\\}", Pattern.DOTALL).matcher(css);
        while (block.find()) {
            if (block.group(1).contains("[data-nk-theme='" + id + "']")) {
                return declarations(block.group(2));
            }
        }
        throw new AssertionError("bloco de modo de " + id);
    }

    static Map<String, String> declarations(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher d = Pattern.compile("(--nk-[a-z0-9-]+):\\s*([^;]+);").matcher(body.replaceAll("/\\*.*?\\*/", ""));
        while (d.find()) {
            m.put(d.group(1), d.group(2).trim().replaceAll("\\s+", " "));
        }
        return m;
    }

    @Test
    void osArquivosSaoOsDaLibNoCommitRegistrado() throws Exception {
        assertEquals(THEMES_SHA256, sha256("themes.css"));
        assertEquals(FOUNDATION_SHA256, sha256("foundation.css"));
    }

    @Test
    void soExistemOsDoisTemasENoPadraoSakuraDark() {
        assertEquals(2, ClientTheme.values().length);
        assertEquals(ClientTheme.SAKURA_DARK, ClientTheme.DEFAULT);
        assertEquals("Sakura Dark", ClientTheme.SAKURA_DARK.label());
        assertEquals("Nekkora Light", ClientTheme.NEKKORA_LIGHT.label());
        assertEquals(ClientTheme.NEKKORA_LIGHT, ClientTheme.SAKURA_DARK.other());
        assertEquals(ClientTheme.SAKURA_DARK, ClientTheme.NEKKORA_LIGHT.other());
        // os outros temas da lib (e os do Agent) nao existem aqui: caem no padrao
        for (String outro : new String[] {"dark", "midnight-blue", "porcelain-light", "sage-light", "midnight-dark", "", null, "LIGHT "}) {
            ClientTheme t = ClientTheme.byLibId(outro);
            assertEquals("LIGHT ".equals(outro) ? ClientTheme.NEKKORA_LIGHT : ClientTheme.DEFAULT, t, String.valueOf(outro));
        }
    }

    @Test
    void paletaIgualADaLib() throws Exception {
        String css = resource("themes.css");
        for (ClientTheme t : ClientTheme.values()) {
            Map<String, String> p = palette(css, t.libId());
            assertEquals(p.get("--nk-color-bg"), t.bg().toHex(), t + " bg");
            assertEquals(p.get("--nk-color-surface"), t.surface().toHex(), t + " surface");
            assertEquals(p.get("--nk-color-card"), t.card().toHex(), t + " card");
            assertEquals(p.get("--nk-color-primary"), t.primary().toHex(), t + " primary");
            assertEquals(p.get("--nk-color-secondary"), t.secondary().toHex(), t + " secondary");
            assertEquals(p.get("--nk-color-highlight"), t.highlight().toHex(), t + " highlight");
            assertEquals(p.get("--nk-color-text"), t.text().toHex(), t + " text");
            assertEquals(p.get("--nk-color-accent"), t.accent().toHex(), t + " accent");
            assertEquals(p.get("--nk-color-accent-hover"), t.accentHover().toHex(), t + " accent-hover");
            assertEquals(p.get("--nk-color-accent-active"), t.accentActive().toHex(), t + " accent-active");
        }
    }

    /** As formulas de modo do CSS, lidas do arquivo, dao os mesmos valores que o aplicativo calcula. */
    @Test
    void derivadosSeguemAsFormulasDoModoDaLib() throws Exception {
        String css = resource("themes.css");
        for (ClientTheme t : ClientTheme.values()) {
            Map<String, String> modo = mode(css, t.libId());
            Map<String, String> paleta = palette(css, t.libId());
            assertEquals(t.dark() ? "dark" : "light", scheme(css, t.libId()), t + " modo");

            assertEquals(resolved(modo, paleta, "--nk-color-text-secondary", t), t.textSecondary().toHex(), t + " text-secondary");
            assertEquals(resolved(modo, paleta, "--nk-color-text-muted", t), t.textMuted().toHex(), t + " text-muted");
            assertEquals(resolved(modo, paleta, "--nk-color-text-inverse", t), t.textInverse().toHex(), t + " text-inverse");
            assertEquals(resolved(modo, paleta, "--nk-color-border", t), t.border().toHex(), t + " border");
            assertEquals(resolved(modo, paleta, "--nk-color-surface-hover", t), t.surfaceHover().toHex(), t + " surface-hover");
            assertEquals(resolved(modo, paleta, "--nk-color-accent-soft", t), t.accentSoft().toHex(), t + " accent-soft");
            assertEquals(resolved(modo, paleta, "--nk-color-danger-text", t), t.dangerText().toHex(), t + " danger-text");
            // anel de foco: "0 0 0 3px color-mix(in srgb, var(--nk-color-accent) N%, transparent)"
            Matcher ring = Pattern.compile("^0 0 0 (\\d+)px (color-mix\\(.*\\))$").matcher(modo.get("--nk-focus-ring"));
            assertTrue(ring.matches(), modo.get("--nk-focus-ring"));
            assertEquals(ClientTheme.FOCUS_RING_WIDTH, Integer.parseInt(ring.group(1)));
            assertEquals(eval(ring.group(2), paleta, t), t.focusRing().toHex(), t + " focus-ring");
        }
    }

    /** {@code color-scheme} do bloco de modo que lista o tema. */
    static String scheme(String css, String id) {
        Matcher block = Pattern.compile("(?m)^((?:[^\\n{}]*,\\n)+[^\\n{}]*) \\{\\n(.*?)^\\}", Pattern.DOTALL).matcher(css);
        while (block.find()) {
            if (block.group(1).contains("[data-nk-theme='" + id + "']")) {
                Matcher m = Pattern.compile("color-scheme: (dark|light);").matcher(block.group(2));
                assertTrue(m.find());
                return m.group(1);
            }
        }
        throw new AssertionError("bloco de modo de " + id);
    }

    /** O valor do tema (paleta) prevalece sobre o do modo, como na cascata do CSS (a paleta vem depois no arquivo). */
    static String resolved(Map<String, String> modo, Map<String, String> paleta, String token, ClientTheme t) {
        String v = paleta.getOrDefault(token, modo.get(token));
        assertFalse(v == null, token);
        return eval(v, paleta, t);
    }

    /** Avalia {@code #hex}, {@code var(--x)} e {@code color-mix(in srgb, A p%, B|transparent)} (transparente = sobre o fundo). */
    static String eval(String v, Map<String, String> paleta, ClientTheme t) {
        v = v.trim();
        if (v.matches("#[0-9a-fA-F]{6}")) {
            return v.toLowerCase();
        }
        Matcher var = Pattern.compile("^var\\((--nk-[a-z0-9-]+)\\)$").matcher(v);
        if (var.matches()) {
            return eval(paleta.get(var.group(1)), paleta, t);
        }
        Matcher mix = Pattern.compile("^color-mix\\(in srgb, (.+?) ([0-9.]+)%, (.+)\\)$").matcher(v);
        assertTrue(mix.matches(), "valor nao suportado: " + v);
        Rgb a = Rgb.hex(eval(mix.group(1), paleta, t));
        double p = Double.parseDouble(mix.group(2));
        String other = mix.group(3).trim();
        Rgb b = "transparent".equals(other) ? t.bg() : Rgb.hex(eval(other, paleta, t));
        return a.mix(p, b).toHex();
    }

    @Test
    void fundacaoIgualADaLib() throws Exception {
        Map<String, String> f = declarations(resource("foundation.css"));
        assertEquals("0.75rem", f.get("--nk-font-size-xs"));
        assertEquals(12, ClientTheme.FONT_SIZE_XS);
        assertEquals("0.8125rem", f.get("--nk-font-size-sm"));
        assertEquals(13, ClientTheme.FONT_SIZE_SM);
        assertEquals("0.875rem", f.get("--nk-font-size-md"));
        assertEquals(14, ClientTheme.FONT_SIZE_MD);
        assertEquals(rem(f.get("--nk-space-2")), ClientTheme.SPACE_2);
        assertEquals(rem(f.get("--nk-space-3")), ClientTheme.SPACE_3);
        assertEquals(rem(f.get("--nk-space-4")), ClientTheme.SPACE_4);
        assertEquals(rem(f.get("--nk-space-6")), ClientTheme.SPACE_6);
        assertEquals(rem(f.get("--nk-space-8")), ClientTheme.SPACE_8);
        assertEquals(f.get("--nk-radius-md"), ClientTheme.RADIUS_MD + "px");
        assertEquals(f.get("--nk-control-sm"), ClientTheme.CONTROL_SM + "px");
        assertEquals(f.get("--nk-control-md"), ClientTheme.CONTROL_MD + "px");
        assertTrue(f.get("--nk-font-family").startsWith("'Inter', 'Segoe UI'"), "a pilha de fontes da lib tem Segoe UI depois da Inter");
    }

    static int rem(String v) {
        return (int) Math.round(Double.parseDouble(v.replace("rem", "")) * 16);
    }

    /** Contraste (WCAG 2.x) dos pares que a tela de entrada usa, nos dois temas. */
    @Test
    void contrasteDosTextosDaTelaDeEntrada() {
        for (ClientTheme t : ClientTheme.values()) {
            assertTrue(Rgb.contrast(t.text(), t.bg()) >= 7.0, t + " texto sobre o fundo");
            assertTrue(Rgb.contrast(t.textSecondary(), t.bg()) >= 4.5, t + " texto secundario (aguardando, cancelar)");
            assertTrue(Rgb.contrast(t.dangerText(), t.bg()) >= 4.5, t + " mensagem de erro");
            assertTrue(Rgb.contrast(t.textInverse(), t.accent()) >= 4.5, t + " rotulo do Entrar");
            assertTrue(Rgb.contrast(t.textInverse(), t.accentHover()) >= 4.5, t + " rotulo do Entrar (hover)");
            assertTrue(Rgb.contrast(t.textInverse(), t.accentActive()) >= 4.5, t + " rotulo do Entrar (pressionado)");
            assertTrue(Rgb.contrast(t.accent(), t.bg()) >= 3.0, t + " botao e spinner sobre o fundo (componente grafico)");
        }
    }
}
