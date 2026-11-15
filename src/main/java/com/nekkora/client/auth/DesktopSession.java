package com.nekkora.client.auth;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.nekkora.client.nav.Directory;
import com.nekkora.client.signin.SignInAttempts;
import com.nekkora.client.signin.SignInState.Notice;

/**
 * Sessao do Client: o token da Platform, SO em memoria, pelo prazo que a Platform informou (15 min, sem renovacao).
 * Nada dela vai para disco, registro ou cofre; ao encerrar o processo, deixa de existir.
 *
 * <p>Fim da sessao: pelo prazo (temporizador local), ou quando a Platform deixa de reconhece-la numa conferencia
 * ({@link #check()}: logout no navegador, troca de senha, etc.). A conferencia acontece quando a janela volta a ficar
 * ativa, no maximo uma vez por intervalo; nao ha consulta continua. Falha de rede na conferencia nao encerra a sessao: ela
 * continua ate o prazo.
 */
final class DesktopSession implements SignInAttempts.Session {

    private final BrowserSignIn owner;
    private final PlatformApi api;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<PlatformApi.Token> token;
    private final String email;
    private final long minCheckNanos;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicLong lastCheck = new AtomicLong(System.nanoTime());
    private final AtomicReference<Runnable> onEnded = new AtomicReference<>();
    private final ScheduledFuture<?> expiry;

    DesktopSession(BrowserSignIn owner, PlatformApi api, ScheduledExecutorService scheduler, PlatformApi.Token token, PlatformApi.Account account,
                   Duration minCheckInterval) {
        this.owner = owner;
        this.api = api;
        this.scheduler = scheduler;
        this.token = new AtomicReference<>(token);
        this.email = account.email();
        this.minCheckNanos = minCheckInterval.toNanos();
        // o menor dos dois prazos que a Platform informou; relogio monotonico (mudar a hora do Windows nao estende)
        long seconds = Math.min(token.expiresInSeconds(), account.expiresInSeconds());
        this.expiry = scheduler.schedule(this::expired, seconds, TimeUnit.SECONDS);
    }

    /** A conta que entrou, como a Platform informou. */
    String email() {
        return email;
    }

    /** As consultas desta sessao. Um {@code 401} em qualquer uma encerra a sessao, como na conferencia. */
    @Override
    public Directory directory() {
        return new PlatformDirectory(api, token::get, this::expired);
    }

    @Override
    public void onEnded(Runnable callback) {
        onEnded.set(callback);
        if (ended.get() && onEnded.compareAndSet(callback, null)) {
            callback.run(); // acabou antes de alguem ouvir
        }
    }

    @Override
    public void check() {
        PlatformApi.Token t = token.get();
        long now = System.nanoTime();
        long last = lastCheck.get();
        if (t == null || ended.get() || now - last < minCheckNanos || !lastCheck.compareAndSet(last, now) || !checking.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(() -> {
                try {
                    api.session(t);
                } catch (PlatformApi.Failure f) {
                    if (f.notice() == Notice.SESSION_EXPIRED) {
                        expired();
                    }
                    // rede, tempo, servidor: nao se conclui nada; a sessao segue ate o prazo
                } finally {
                    checking.set(false);
                }
            });
        } catch (RuntimeException e) {
            checking.set(false);
        }
    }

    /** Prazo vencido ou sessao nao reconhecida: descarta o token e avisa uma vez. */
    private void expired() {
        if (ended.compareAndSet(false, true)) {
            token.set(null);
            expiry.cancel(false);
            Runnable r = onEnded.getAndSet(null);
            if (r != null) {
                r.run();
            }
        }
    }

    @Override
    public void end() {
        PlatformApi.Token t = token.getAndSet(null);
        boolean first = ended.compareAndSet(false, true);
        expiry.cancel(false);
        onEnded.set(null);
        if (t != null && first) {
            owner.revokeLater(t);
        }
    }

    @Override
    public String toString() {
        return "DesktopSession[...]";
    }
}
