package com.nekkora.client.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Platform SIMULADA para os testes da navegacao (fica em src/test e nao entra no aplicativo): o teste decide o que cada
 * consulta devolve. {@link Manual} e um executor que so roda quando o teste manda, para ensaiar respostas atrasadas sem
 * depender de tempo.
 */
public final class FakeDirectory implements Directory {

    public volatile Profile profile = new Profile("ana@empresa.test", "Ana Souza");
    public volatile Failure profileFailure;
    public volatile List<Organization> organizations = List.of();
    public volatile Failure organizationsFailure;
    public volatile RuntimeException crash;
    public final AtomicInteger profileCalls = new AtomicInteger();
    public final AtomicInteger organizationCalls = new AtomicInteger();

    @Override
    public Profile profile() throws Failed {
        profileCalls.incrementAndGet();
        if (profileFailure != null) {
            throw new Failed(profileFailure);
        }
        return profile;
    }

    @Override
    public List<Organization> organizations() throws Failed {
        organizationCalls.incrementAndGet();
        if (crash != null) {
            throw crash;
        }
        if (organizationsFailure != null) {
            throw new Failed(organizationsFailure);
        }
        return organizations;
    }

    /** Identificador estavel a partir de um numero, para os testes compararem ordem e identidade. */
    public static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n));
    }

    public static Organization org(int n, String name) {
        return new Organization(id(n), name, "org-" + n, false);
    }

    /** {@code n} organizacoes ja na ordem da Platform (nome e, no empate, identificador). */
    public static List<Organization> orgs(int n) {
        List<Organization> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(org(i, String.format("Organização %02d", i)));
        }
        return out;
    }

    /** Executor que guarda as tarefas ate o teste mandar rodar. */
    public static final class Manual implements Executor {
        public final List<Runnable> pending = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            pending.add(command);
        }

        /** Roda tudo o que esta pendente agora (nao o que for agendado durante a rodada). */
        public void runAll() {
            List<Runnable> now;
            synchronized (this) {
                now = new ArrayList<>(pending);
                pending.clear();
            }
            now.forEach(Runnable::run);
        }

        public synchronized Runnable take(int index) {
            return pending.remove(index);
        }

        public synchronized int size() {
            return pending.size();
        }
    }
}
