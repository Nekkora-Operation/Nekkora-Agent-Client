package com.nekkora.client.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nekkora.client.signin.SignInState.Notice;

/**
 * As chamadas do aplicativo a Platform, por HTTPS de verdade contra a Platform simulada: so a resposta exata do contrato
 * e aceita; cada falha tem a propria classificacao; certificado nao confiavel nunca e aceito.
 */
class PlatformApiTest {

    private FakePlatform platform;
    private PlatformApi api;
    private final Pkce pkce = Pkce.generate();
    private static final String REDIRECT = "http://127.0.0.1:45678/callback";

    @BeforeEach
    void up() throws Exception {
        platform = new FakePlatform();
        api = new PlatformApi(platform.origin(), platform.clientTrust, Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    @AfterEach
    void down() {
        platform.close();
    }

    private String code() {
        String redirect = platform.authorize(URI.create(platform.issuer() + "/entrar/aplicativo?response_type=code&client_id=nekkora-agent-client&redirect_uri="
                + java.net.URLEncoder.encode(REDIRECT, java.nio.charset.StandardCharsets.UTF_8) + "&state=" + pkce.state() + "&code_challenge=" + pkce.challenge()
                + "&code_challenge_method=S256&scope=identity"));
        return FakePlatform.query(URI.create(redirect).getRawQuery()).get("code");
    }

    private Notice failure(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(PlatformApi.Failure.class, call).notice();
    }

    @Test
    void trocaESessaoPeloContrato() throws Exception {
        PlatformApi.Token t = api.exchange(code(), pkce.verifier(), REDIRECT);
        assertEquals(900, t.expiresInSeconds());
        assertFalse(t.toString().contains(t.value()), "o token nao aparece em toString");
        PlatformApi.Account a = api.session(t);
        assertEquals("ana@empresa.test", a.email());
        // o que foi para a rede: formulario, verificador PKCE, sem cookie (a Platform simulada recusa cookie com 418)
        String body = platform.tokenBodies.get(0);
        Map<String, String> f = FakePlatform.query(body);
        assertEquals("authorization_code", f.get("grant_type"));
        assertEquals("nekkora-agent-client", f.get("client_id"));
        assertEquals(REDIRECT, f.get("redirect_uri"));
        assertEquals(pkce.verifier(), f.get("code_verifier"));
        assertFalse(f.containsKey("client_secret"), "cliente publico: nenhum segredo");
        assertEquals(5, f.size());
        assertTrue(api.revoke(t));
        assertEquals(Notice.SESSION_EXPIRED, failure(() -> api.session(t)));
        assertTrue(api.revoke(t), "encerrar de novo nao e erro");
    }

    @Test
    void verificadorErradoECodigoReusadoSaoRecusadosPeloServidor() throws Exception {
        String c1 = code();
        assertEquals(Notice.INVALID_RETURN, failure(() -> api.exchange(c1, Pkce.generate().verifier(), REDIRECT)));
        assertEquals(Notice.INVALID_RETURN, failure(() -> api.exchange(c1, pkce.verifier(), REDIRECT)), "o codigo queimou");
        String c2 = code();
        assertEquals(Notice.INVALID_RETURN, failure(() -> api.exchange(c2, pkce.verifier(), "http://127.0.0.1:45679/callback")), "redirect diferente");
        String c3 = code();
        PlatformApi.Token t = api.exchange(c3, pkce.verifier(), REDIRECT);
        assertEquals(Notice.INVALID_RETURN, failure(() -> api.exchange(c3, pkce.verifier(), REDIRECT)), "codigo de uso unico");
        assertEquals(Notice.SESSION_EXPIRED, failure(() -> api.session(t)), "o reuso revogou a sessao emitida");
    }

    @Test
    void certificadoNaoConfiavelNuncaEAceito() throws Exception {
        // contexto padrao da JVM: o certificado da Platform simulada nao esta no repositorio de confianca
        PlatformApi semConfianca = new PlatformApi(platform.origin(), null, Duration.ofSeconds(3), Duration.ofSeconds(3));
        String c = code();
        assertEquals(Notice.TLS, failure(() -> semConfianca.exchange(c, pkce.verifier(), REDIRECT)));
        assertEquals(0, platform.tokenBodies.size(), "nada foi enviado: o codigo e o verificador nao sairam do aplicativo");
        assertEquals(Notice.TLS, failure(() -> semConfianca.session(new PlatformApi.Token(FakePlatform.token(), 900))));
        assertFalse(semConfianca.revoke(new PlatformApi.Token(FakePlatform.token(), 900)));
    }

    @Test
    void apiSoPorHttps() {
        Properties p = new Properties();
        p.setProperty(PlatformOrigin.BROWSER_PROPERTY, "http://localhost:5178");
        p.setProperty(PlatformOrigin.API_PROPERTY, "http://localhost:5178");
        assertThrows(IllegalStateException.class, () -> PlatformOrigin.resolve(p));
        assertThrows(IllegalArgumentException.class, () -> new PlatformApi(new PlatformOrigin("http://localhost:5178", "http://localhost:5178")));
    }

    @Test
    void semConexaoETempoEsgotado() throws Exception {
        String c = code();
        platform.tokenDelayMillis = 2500;
        PlatformApi rapido = new PlatformApi(platform.origin(), platform.clientTrust, Duration.ofSeconds(2), Duration.ofMillis(400));
        assertEquals(Notice.TIMEOUT, failure(() -> rapido.exchange(c, pkce.verifier(), REDIRECT)));
        PlatformOrigin origin = platform.origin();
        platform.close();
        PlatformApi fora = new PlatformApi(origin, platform.clientTrust, Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertEquals(Notice.NETWORK, failure(() -> fora.exchange("x".repeat(43), pkce.verifier(), REDIRECT)));
    }

    @Test
    void cadaStatusTemASuaClassificacao() {
        Object[][] casos = {{500, Notice.SERVER}, {502, Notice.SERVER}, {503, Notice.SERVER}, {429, Notice.RATE_LIMITED}, {404, Notice.UNAVAILABLE},
                {400, Notice.INVALID_RETURN}, {401, Notice.INVALID_RETURN}, {403, Notice.INVALID_RETURN}, {302, Notice.INVALID_RETURN}};
        for (Object[] c : casos) {
            platform.tokenStatus = (Integer) c[0];
            platform.tokenBody = "{\"error\":\"x\"}";
            assertEquals(c[1], failure(() -> api.exchange("a".repeat(43), pkce.verifier(), REDIRECT)), "troca " + c[0]);
        }
        PlatformApi.Token t = new PlatformApi.Token(FakePlatform.token(), 900);
        Object[][] sessao = {{401, Notice.SESSION_EXPIRED}, {500, Notice.SERVER}, {429, Notice.RATE_LIMITED}, {404, Notice.UNAVAILABLE}, {403, Notice.SERVER}};
        for (Object[] c : sessao) {
            platform.sessionStatus = (Integer) c[0];
            platform.sessionBody = "{\"error\":\"x\"}";
            assertEquals(c[1], failure(() -> api.session(t)), "sessao " + c[0]);
        }
    }

    /** 200 com corpo fora do contrato nunca vira sessao. */
    @Test
    void respostaDeSucessoMalformadaNaoEAceita() {
        String tok = FakePlatform.token();
        String[] ruins = {
                "",
                "{}",
                "nao e json",
                "{\"success\":true}",
                "[\"" + tok + "\"]",
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":900}", // sem escopo
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"identity organizations\"}", // escopo maior
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"admin\"}",
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"mac\",\"expires_in\":900,\"scope\":\"identity\"}",
                "{\"access_token\":\"curto\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"identity\"}",
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":86400,\"scope\":\"identity\"}", // prazo acima do teto
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":0,\"scope\":\"identity\"}",
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":\"900\",\"scope\":\"identity\"}",
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"identity\",\"refresh_token\":\"" + tok + "\"}",
                "{\"access_token\":\"" + tok + "\",\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"identity\"}",
                "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"identity\"} sobra"};
        for (String body : ruins) {
            platform.tokenStatus = 200;
            platform.tokenBody = body;
            assertEquals(Notice.INVALID_RETURN, failure(() -> api.exchange("a".repeat(43), pkce.verifier(), REDIRECT)), body);
        }
        platform.tokenBody = "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"identity\"}";
        platform.tokenContentType = "text/html";
        assertEquals(Notice.INVALID_RETURN, failure(() -> api.exchange("a".repeat(43), pkce.verifier(), REDIRECT)), "tipo de conteudo");

        PlatformApi.Token t = new PlatformApi.Token(tok, 900);
        String[] sessoes = {
                "{}",
                "{\"account\":{\"email\":\"ana@empresa.test\"},\"scope\":\"identity\"}",
                "{\"account\":{\"email\":\"ana@empresa.test\"},\"scope\":\"admin\",\"expiresIn\":100}",
                "{\"account\":{\"email\":\"sem-arroba\"},\"scope\":\"identity\",\"expiresIn\":100}",
                "{\"account\":{\"email\":\"ana@empresa.test\",\"role\":\"OWNER\"},\"scope\":\"identity\",\"expiresIn\":100}",
                "{\"account\":{\"email\":\"ana@empresa.test\"},\"scope\":\"identity\",\"expiresIn\":100,\"organizations\":[]}",
                "{\"account\":\"ana@empresa.test\",\"scope\":\"identity\",\"expiresIn\":100}"};
        for (String body : sessoes) {
            platform.sessionStatus = 200;
            platform.sessionBody = body;
            assertEquals(Notice.INVALID_RETURN, failure(() -> api.session(t)), body);
        }
    }

    @Test
    void origemSoProducaoOuEsteComputador() {
        assertEquals(new PlatformOrigin("https://platform.nekkora.com", "https://platform.nekkora.com"), PlatformOrigin.resolve(new Properties()));
        assertTrue(PlatformOrigin.resolve(new Properties()).production());
        assertEquals("https://platform.nekkora.com/entrar/aplicativo", PlatformOrigin.resolve(new Properties()).authorizePage().toString());
        Properties dev = new Properties();
        dev.setProperty(PlatformOrigin.BROWSER_PROPERTY, "http://localhost:5178");
        dev.setProperty(PlatformOrigin.API_PROPERTY, "https://localhost:47843");
        assertEquals(new PlatformOrigin("http://localhost:5178", "https://localhost:47843"), PlatformOrigin.resolve(dev));
        assertFalse(PlatformOrigin.resolve(dev).production());
        String[][] ruins = {{"https://exemplo.test", null}, {"https://platform.nekkora.com.exemplo.test", null}, {"https://localhost", null},
                {"http://localhost:5178/", "https://localhost:47843"}, {"http://localhost:5178", "https://exemplo.test:443"},
                {"http://localhost:5178", "http://localhost:47843"}, {"http://localhost:5178@exemplo.test", "https://localhost:47843"},
                {"http://127.0.0.1:5178", "https://localhost:47843"}, {null, "https://localhost:47843"}, {"https://platform.nekkora.com", "https://localhost:1"},
                {"file:///c:/x", null}, {"http://localhost:5178", "https://localhost:47843/api"}};
        for (String[] r : ruins) {
            Properties p = new Properties();
            if (r[0] != null) {
                p.setProperty(PlatformOrigin.BROWSER_PROPERTY, r[0]);
            }
            if (r[1] != null) {
                p.setProperty(PlatformOrigin.API_PROPERTY, r[1]);
            }
            assertThrows(IllegalStateException.class, () -> PlatformOrigin.resolve(p), r[0] + " | " + r[1]);
        }
    }

    @Test
    void pkceEJson() {
        Pkce a = Pkce.generate();
        Pkce b = Pkce.generate();
        assertTrue(a.state().matches("[A-Za-z0-9_-]{43}") && a.verifier().matches("[A-Za-z0-9_-]{43}") && a.challenge().matches("[A-Za-z0-9_-]{43}"));
        assertFalse(a.state().equals(b.state()) || a.verifier().equals(b.verifier()) || a.state().equals(a.verifier()));
        // vetor do apendice B da RFC 7636
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertFalse(a.toString().contains(a.verifier()) || a.toString().contains(a.state()));
        assertTrue(Pkce.same("abc", "abc"));
        assertFalse(Pkce.same("abc", "abd") || Pkce.same("abc", null) || Pkce.same(null, null));
        assertEquals("b", Json.object("{\"a\":\"b\"}").get("a"));
        assertEquals("\u00e1\n", Json.object("{\"a\":\"\\u00e1\\n\"}").get("a"));
        for (String ruim : new String[] {"{\"a\":1,}", "{'a':1}", "{\"a\":01}", "{\"a\":\"b\"", "{\"a\":\"\\x\"}", "[[[[[[[[[[1]]]]]]]]]]", "{\"a\":1}{\"b\":2}"}) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(ruim), ruim);
        }
    }
}
