package com.nekkora.client.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * Navegador SIMULADO dos testes: registra a pagina que o aplicativo mandou abrir e, como uma pessoa faria, "autoriza" na
 * Platform simulada e segue o redirecionamento ate o retorno local, com uma requisicao HTTP de verdade.
 * {@code decide} troca o que a pessoa (ou um atacante) faz com o pedido.
 */
final class FakeBrowser implements BrowserSignIn.Browser {

    record Visit(int status, String body, HttpResponse<String> response) {
    }

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    final FakePlatform platform;
    final List<URI> opened = new CopyOnWriteArrayList<>();
    final List<CompletableFuture<Visit>> visits = new CopyOnWriteArrayList<>();
    /** (pagina aberta, retorno que a Platform devolveria) -> endereco que o navegador visita; nulo = a pessoa nao faz nada. */
    volatile BiFunction<URI, String, String> decide = (page, redirect) -> redirect;
    volatile IOException failToOpen;

    FakeBrowser(FakePlatform platform) {
        this.platform = platform;
    }

    @Override
    public void open(URI page) throws IOException {
        if (failToOpen != null) {
            throw failToOpen;
        }
        opened.add(page);
        String target = decide.apply(page, platform.authorize(page));
        if (target != null) {
            visits.add(CompletableFuture.supplyAsync(() -> get(target)));
        }
    }

    URI lastPage() {
        return opened.get(opened.size() - 1);
    }

    /** Uma visita avulsa ao retorno local, como qualquer programa deste computador poderia fazer. */
    static Visit get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    static Visit send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = HTTP.send(b.timeout(Duration.ofSeconds(40)).build(), HttpResponse.BodyHandlers.ofString());
            return new Visit(r.statusCode(), r.body(), r);
        } catch (IOException e) {
            return new Visit(-1, e.getClass().getSimpleName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Visit(-1, "interrompido", null);
        }
    }
}
