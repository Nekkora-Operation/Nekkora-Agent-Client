package com.nekkora.client.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.nekkora.client.auth.LoopbackReceiver.Code;
import com.nekkora.client.auth.LoopbackReceiver.Denied;
import com.nekkora.client.auth.LoopbackReceiver.Outcome;
import com.nekkora.client.auth.LoopbackReceiver.Page;
import com.nekkora.client.signin.SignInAttempts;
import com.nekkora.client.signin.SignInState.Notice;

/**
 * Entrada real pelo navegador (contrato desktop-login/1): Authorization Code com PKCE S256 e retorno local em
 * {@code 127.0.0.1} (RFC 8252). Cada tentativa:
 * <ol>
 * <li>gera {@code state} e verificador proprios ({@link Pkce});</li>
 * <li>abre o retorno local ({@link LoopbackReceiver}) e SO ENTAO o navegador padrao, na pagina de entrada da Platform;</li>
 * <li>espera o retorno ate o prazo da tentativa (5 min);</li>
 * <li>troca o codigo com a API por HTTPS, com o verificador, e confere a sessao recebida;</li>
 * <li>so depois dessa conferencia avisa {@code signedIn}. Um retorno, sozinho, nunca abre a tela inicial.</li>
 * </ol>
 * Cancelar, vencer o prazo, falhar ou fechar o aplicativo fecha o retorno local e torna a tentativa inerte: o que chegar
 * depois e descartado, e uma sessao que a API emita tarde demais e encerrada na hora.
 */
public final class BrowserSignIn implements SignInAttempts, AutoCloseable {

    /** Abre uma pagina no navegador padrao. */
    public interface Browser {
        void open(URI page) throws IOException;
    }

    /** Prazo da tentativa (igual ao do pedido na Platform) e de quanto em quanto a sessao pode ser reconferida. */
    public record Timing(Duration attempt, Duration minCheckInterval) {
        public static final Timing DEFAULT = new Timing(Duration.ofMinutes(5), Duration.ofSeconds(60));
    }

    private final PlatformOrigin origin;
    private final PlatformApi api;
    private final Browser browser;
    private final Timing timing;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger pendingRevocations = new AtomicInteger();

    public BrowserSignIn(PlatformOrigin origin, PlatformApi api, Browser browser, Timing timing) {
        this.origin = origin;
        this.api = api;
        this.browser = browser;
        this.timing = timing;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "entrada");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Attempt begin(Listener listener) {
        Running a = new Running(listener);
        scheduler.execute(a::start);
        return a;
    }

    /** Encerramento do aplicativo: espera, por pouco tempo, os encerramentos de sessao ja pedidos a Platform. */
    @Override
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (pendingRevocations.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        scheduler.shutdownNow();
    }

    void revokeLater(PlatformApi.Token token) {
        pendingRevocations.incrementAndGet();
        try {
            scheduler.execute(() -> {
                try {
                    api.revoke(token);
                } finally {
                    pendingRevocations.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            pendingRevocations.decrementAndGet(); // aplicativo fechando: a sessao acaba sozinha no prazo
        }
    }

    private final class Running implements Attempt {
        private final Listener listener;
        private final Pkce pkce = Pkce.generate();
        private final AtomicBoolean over = new AtomicBoolean();
        private volatile LoopbackReceiver receiver;
        private volatile ScheduledFuture<?> deadline;

        Running(Listener listener) {
            this.listener = listener;
        }

        void start() {
            if (over.get()) {
                return;
            }
            LoopbackReceiver r;
            try {
                r = LoopbackReceiver.open(pkce.state(), origin.browser(), this::returned);
            } catch (IOException | RuntimeException e) {
                finish(l -> l.failed(Notice.RETURN_UNAVAILABLE));
                return;
            }
            receiver = r;
            if (over.get()) {
                r.close(); // cancelada enquanto o retorno abria
                return;
            }
            deadline = scheduler.schedule(() -> finish(l -> l.failed(Notice.EXPIRED)), timing.attempt().toMillis(), TimeUnit.MILLISECONDS);
            try {
                browser.open(authorizeUri(r.redirectUri()));
            } catch (IOException | RuntimeException e) {
                finish(l -> l.failed(Notice.BROWSER_FAILED));
                return;
            }
            if (!over.get()) {
                listener.browserOpened();
            }
        }

        private URI authorizeUri(String redirectUri) {
            return URI.create(origin.authorizePage() + "?response_type=code&client_id=" + PlatformApi.CLIENT_ID + "&redirect_uri=" + enc(redirectUri)
                    + "&state=" + enc(pkce.state()) + "&code_challenge=" + enc(pkce.challenge()) + "&code_challenge_method=S256&scope="
                    + PlatformApi.SCOPE);
        }

        /** Chamado pelo retorno local, uma unica vez, com o que o navegador trouxe para ESTA tentativa. */
        private CompletableFuture<Page> returned(Outcome outcome) {
            CompletableFuture<Page> page = new CompletableFuture<>();
            if (outcome instanceof Denied) {
                // recusa confirmada: veio pelo retorno local com o state e o emissor DESTA tentativa
                finish(l -> l.failed(Notice.DECLINED));
                page.complete(Page.FAILED);
            } else if (outcome instanceof Code code) {
                String redirectUri = receiver.redirectUri();
                scheduler.execute(() -> page.complete(exchange(code.value(), redirectUri)));
            } else {
                finish(l -> l.failed(Notice.INVALID_RETURN));
                page.complete(Page.FAILED);
            }
            return page;
        }

        /** Troca o codigo e confere a sessao. So aqui, com as duas respostas da API, existe entrada. */
        private Page exchange(String code, String redirectUri) {
            if (over.get()) {
                return Page.FAILED;
            }
            PlatformApi.Token token;
            PlatformApi.Account account;
            try {
                token = api.exchange(code, pkce.verifier(), redirectUri);
            } catch (PlatformApi.Failure f) {
                // resultado certo ou incerto (rede, tempo): o codigo nao e reapresentado; a pessoa recomeça
                finish(l -> l.failed(f.notice()));
                return Page.FAILED;
            }
            try {
                account = api.session(token);
            } catch (PlatformApi.Failure f) {
                revokeLater(token);
                finish(l -> l.failed(f.notice() == Notice.SESSION_EXPIRED ? Notice.INVALID_RETURN : f.notice()));
                return Page.FAILED;
            }
            DesktopSession session = new DesktopSession(BrowserSignIn.this, api, scheduler, token, account, timing.minCheckInterval());
            if (!finish(l -> l.signedIn(session))) {
                // cancelada, vencida ou aplicativo fechando enquanto a API respondia: a sessao nao e usada
                session.end();
                return Page.FAILED;
            }
            return Page.SIGNED_IN;
        }

        /** Termina a tentativa uma unica vez: fecha o retorno local e o prazo, e so entao avisa. */
        private boolean finish(Consumer<Listener> notice) {
            if (!over.compareAndSet(false, true)) {
                return false;
            }
            release();
            notice.accept(listener);
            return true;
        }

        private void release() {
            ScheduledFuture<?> d = deadline;
            if (d != null) {
                d.cancel(false);
            }
            LoopbackReceiver r = receiver;
            if (r != null) {
                // fora da thread do proprio retorno: ela ainda vai responder ao navegador
                try {
                    scheduler.schedule(r::close, 2, TimeUnit.SECONDS);
                } catch (RuntimeException e) {
                    r.close();
                }
            }
        }

        @Override
        public void cancel() {
            if (over.compareAndSet(false, true)) {
                ScheduledFuture<?> d = deadline;
                if (d != null) {
                    d.cancel(false);
                }
                LoopbackReceiver r = receiver;
                if (r != null) {
                    r.close();
                }
            }
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
