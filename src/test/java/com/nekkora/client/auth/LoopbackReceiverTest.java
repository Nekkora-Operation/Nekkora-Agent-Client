package com.nekkora.client.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.nekkora.client.auth.LoopbackReceiver.Code;
import com.nekkora.client.auth.LoopbackReceiver.Denied;
import com.nekkora.client.auth.LoopbackReceiver.Invalid;
import com.nekkora.client.auth.LoopbackReceiver.Outcome;
import com.nekkora.client.auth.LoopbackReceiver.Page;

/** O retorno local de verdade, exercitado com requisicoes HTTP reais a porta que ele abriu. */
class LoopbackReceiverTest {

    private static final String ISS = "https://platform.exemplo.test";
    private static final String CODE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final Pkce pkce = Pkce.generate();
    private final List<Outcome> outcomes = new CopyOnWriteArrayList<>();
    private volatile CompletableFuture<Page> page = CompletableFuture.completedFuture(Page.SIGNED_IN);
    private LoopbackReceiver receiver;

    private LoopbackReceiver open() throws Exception {
        receiver = LoopbackReceiver.open(pkce.state(), ISS, o -> {
            outcomes.add(o);
            return page;
        });
        return receiver;
    }

    @AfterEach
    void close() {
        if (receiver != null) {
            receiver.close();
        }
    }

    private String url(String query) {
        return receiver.redirectUri() + (query == null ? "" : "?" + query);
    }

    private String good() {
        return "code=" + CODE + "&state=" + pkce.state() + "&iss=" + URLEncoder.encode(ISS, StandardCharsets.UTF_8);
    }

    @Test
    void escutaSoNoLoopbackEmPortaEfemera() throws Exception {
        LoopbackReceiver r = open();
        assertTrue(r.address().isLoopbackAddress());
        assertEquals("127.0.0.1", r.address().getHostAddress(), "IP literal, nao 0.0.0.0 nem nome de host");
        assertFalse(r.address().isAnyLocalAddress());
        assertTrue(r.port() >= 1024 && r.port() <= 65535);
        assertEquals("http://127.0.0.1:" + r.port() + "/callback", r.redirectUri());
        // nao atende pelas outras interfaces da maquina
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (!a.isLoopbackAddress() && a.getAddress().length == 4 && ni.isUp()) {
                    try (Socket s = new Socket()) {
                        s.connect(new java.net.InetSocketAddress(a, r.port()), 300);
                        throw new AssertionError("o retorno local atendeu em " + a);
                    } catch (java.io.IOException esperado) {
                        // recusado ou sem resposta: nao esta escutando fora do loopback
                    }
                }
            }
        }
        LoopbackReceiver outro = LoopbackReceiver.open(Pkce.generate().state(), ISS, o -> page);
        try {
            assertNotEquals(r.port(), outro.port(), "cada tentativa tem a propria porta");
        } finally {
            outro.close();
        }
    }

    @Test
    void retornoValidoEntregaOCodigoUmaVezESoEntaoDizLoginConcluido() throws Exception {
        open();
        FakeBrowser.Visit v = FakeBrowser.get(url(good()));
        assertEquals(200, v.status());
        assertTrue(v.body().contains("Login concluído") && v.body().contains("Você pode voltar ao Nekkora Agent."));
        assertEquals(List.of(new Code(CODE)), outcomes);
        // a pagina nao leva credencial, script nem recurso externo, e nao deve ser guardada
        assertFalse(v.body().contains(CODE) || v.body().contains(pkce.state()) || v.body().contains("<script") || v.body().contains("http"));
        assertEquals("no-store", v.response().headers().firstValue("Cache-Control").orElse(""));
        assertEquals("no-referrer", v.response().headers().firstValue("Referrer-Policy").orElse(""));
        assertTrue(v.response().headers().firstValue("Content-Security-Policy").orElse("").startsWith("default-src 'none'"));
        // recarregar a aba (mesmo retorno de novo) nao entrega o codigo outra vez
        FakeBrowser.Visit again = FakeBrowser.get(url(good()));
        assertEquals(400, again.status());
        assertEquals(1, outcomes.size());
    }

    @Test
    void paginaSoDizConcluidoSeAApiConfirmou() throws Exception {
        page = CompletableFuture.completedFuture(Page.FAILED);
        open();
        FakeBrowser.Visit v = FakeBrowser.get(url(good()));
        assertEquals(200, v.status());
        assertTrue(v.body().contains("Não foi possível confirmar o login"));
        assertFalse(v.body().contains("Login concluído"));
    }

    @Test
    void recusaNoNavegadorComACorrelacaoCertaEEntregueComoRecusa() throws Exception {
        open();
        FakeBrowser.Visit v = FakeBrowser.get(url("error=access_denied&state=" + pkce.state() + "&iss=" + URLEncoder.encode(ISS, StandardCharsets.UTF_8)));
        assertEquals(200, v.status());
        assertTrue(v.body().contains("Entrada cancelada"));
        assertEquals(1, outcomes.size());
        assertInstanceOf(Denied.class, outcomes.get(0));
    }

    @Test
    void retornoInvalidoNaoTerminaATentativaLegitima() throws Exception {
        open();
        String iss = "&iss=" + URLEncoder.encode(ISS, StandardCharsets.UTF_8);
        List<String> ruins = new ArrayList<>(List.of(
                "code=" + CODE + iss, // state ausente
                "code=" + CODE + "&state=" + Pkce.generate().state() + iss, // state de outra tentativa
                "code=" + CODE + "&state=" + pkce.state() + "x" + iss, // state quase igual
                "code=" + CODE + "&state=" + pkce.state() + "&state=" + pkce.state() + iss, // state repetido
                "code=" + CODE + "&code=" + CODE + "&state=" + pkce.state() + iss, // codigo repetido
                "code=" + CODE + "&state=" + pkce.state(), // sem emissor
                "code=" + CODE + "&state=" + pkce.state() + "&iss=" + URLEncoder.encode("https://outro.exemplo.test", StandardCharsets.UTF_8), // outro emissor
                good() + "&success=true", // parametro a mais
                "success=true", // "sucesso" sem nada
                "state=" + pkce.state())); // so o state: sem emissor
        for (String q : ruins) {
            FakeBrowser.Visit v = FakeBrowser.get(url(q));
            assertEquals(400, v.status(), q);
            assertFalse(v.body().contains("Login concluído"), q);
        }
        assertEquals(400, FakeBrowser.get(url(null)).status(), "sem consulta");
        assertEquals(List.of(), outcomes, "nenhum retorno invalido decidiu a tentativa");
        // e a tentativa legitima ainda pode ser concluida
        assertEquals(200, FakeBrowser.get(url(good())).status());
        assertEquals(List.of(new Code(CODE)), outcomes);
    }

    @Test
    void stateCertoComConteudoSemSentidoEncerraComoRetornoInvalido() throws Exception {
        open();
        String iss = "&iss=" + URLEncoder.encode(ISS, StandardCharsets.UTF_8);
        FakeBrowser.Visit v = FakeBrowser.get(url("code=curto&state=" + pkce.state() + iss));
        assertFalse(v.body().contains("Login concluído"));
        assertEquals(1, outcomes.size());
        assertInstanceOf(Invalid.class, outcomes.get(0));
    }

    @Test
    void codigoEErroJuntosOuErroDesconhecidoNaoSaoCodigoNemCancelamento() throws Exception {
        open();
        String iss = "&iss=" + URLEncoder.encode(ISS, StandardCharsets.UTF_8);
        FakeBrowser.get(url("code=" + CODE + "&error=access_denied&state=" + pkce.state() + iss));
        assertInstanceOf(Invalid.class, outcomes.get(0));
    }

    @Test
    void outroCaminhoOuMetodoNaoAfetaATentativa() throws Exception {
        open();
        String base = "http://127.0.0.1:" + receiver.port();
        assertEquals(404, FakeBrowser.get(base + "/").status());
        assertEquals(404, FakeBrowser.get(base + "/callback/extra?" + good()).status());
        assertEquals(404, FakeBrowser.get(base + "/callbackx?" + good()).status());
        assertEquals(404, FakeBrowser.get(base + "/favicon.ico").status());
        assertEquals(405, FakeBrowser.send(HttpRequest.newBuilder(URI.create(url(good()))).POST(HttpRequest.BodyPublishers.noBody())).status());
        assertEquals(405, FakeBrowser.send(HttpRequest.newBuilder(URI.create(url(good()))).method("PUT", HttpRequest.BodyPublishers.noBody())).status());
        assertEquals(List.of(), outcomes);
        assertEquals(200, FakeBrowser.get(url(good())).status());
    }

    @Test
    void excessoDeRequisicoesInvalidasEncerraComoRetornoInvalido() throws Exception {
        open();
        for (int i = 0; i < LoopbackReceiver.MAX_INVALID; i++) {
            assertEquals(List.of(), outcomes, "antes do limite a tentativa continua");
            assertEquals(400, FakeBrowser.get(url("code=" + CODE + "&state=errado" + i)).status());
        }
        assertEquals(1, outcomes.size());
        assertInstanceOf(Invalid.class, outcomes.get(0));
        // depois de encerrada, nem o retorno certo decide mais nada
        assertEquals(400, FakeBrowser.get(url(good())).status());
        assertEquals(1, outcomes.size());
    }

    @Test
    void fecharLiberaAPorta() throws Exception {
        open();
        int port = receiver.port();
        assertEquals(200, FakeBrowser.get(url(good())).status());
        receiver.close();
        receiver.close();
        assertEquals(-1, FakeBrowser.get("http://127.0.0.1:" + port + "/callback?" + good()).status(), "ninguem escutando depois de fechar");
        receiver = null;
    }

    @Test
    void consultaEstrita() {
        assertNull(LoopbackReceiver.query(null));
        assertNull(LoopbackReceiver.query(""));
        assertNull(LoopbackReceiver.query("code"));
        assertNull(LoopbackReceiver.query("=x"));
        assertNull(LoopbackReceiver.query("code=a&code=b"));
        assertNull(LoopbackReceiver.query("code=a&outro=b"));
        assertNull(LoopbackReceiver.query("code=a&"));
        assertNull(LoopbackReceiver.query("code=%ZZ"), "escape invalido");
        assertNull(LoopbackReceiver.query("code=" + "a".repeat(3000)));
        assertEquals("a b", LoopbackReceiver.query("code=a%20b").get("code"));
    }
}
