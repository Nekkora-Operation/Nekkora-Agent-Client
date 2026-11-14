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
    /** Por organizacao e por ambiente: o que a "Platform" devolve. Chave ausente = fora do alcance (NOT_FOUND). */
    public final java.util.Map<UUID, List<Workspace>> workspaces = new java.util.concurrent.ConcurrentHashMap<>();
    public final java.util.Map<UUID, Automation.Listing> automations = new java.util.concurrent.ConcurrentHashMap<>();
    public volatile Failure workspacesFailure;
    public volatile Failure automationsFailure;
    public final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
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

    @Override
    public List<Workspace> workspaces(UUID organization) throws Failed {
        calls.add("workspaces " + organization);
        if (workspacesFailure != null) {
            throw new Failed(workspacesFailure);
        }
        List<Workspace> list = workspaces.get(organization);
        if (list == null) {
            throw new Failed(Failure.NOT_FOUND);
        }
        return list;
    }

    @Override
    public Automation.Listing automations(UUID organization, UUID workspace) throws Failed {
        calls.add("automations " + organization + " " + workspace);
        if (automationsFailure != null) {
            throw new Failed(automationsFailure);
        }
        Automation.Listing listing = automations.get(workspace);
        if (listing == null) {
            throw new Failed(Failure.NOT_FOUND);
        }
        return listing;
    }

    /** Renomear: quem pode (por organizacao) e o que a "Platform" respondeu. */
    public volatile Failure renameFailure;
    public final List<String> renames = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public String rename(UUID organization, UUID automation, String name) throws Failed {
        calls.add("rename " + organization + " " + automation);
        if (renameFailure != null) {
            throw new Failed(renameFailure);
        }
        String saved = name.trim();
        for (java.util.Map.Entry<UUID, Automation.Listing> e : automations.entrySet()) {
            List<Automation> items = new ArrayList<>();
            boolean found = false;
            for (Automation a : e.getValue().items()) {
                found |= a.id().equals(automation);
                items.add(a.id().equals(automation) ? new Automation(a.id(), saved, a.published()) : a);
            }
            if (found) {
                e.setValue(new Automation.Listing(items, e.getValue().capabilities(), false));
                renames.add(automation + "=" + saved);
                return saved;
            }
        }
        throw new Failed(Failure.NOT_FOUND);
    }

    public static Workspace ws(int n, String name) {
        return new Workspace(id(1000 + n), name);
    }

    public static Automation automation(int n, String name, Integer published, String packageVersion) {
        return new Automation(id(2000 + n), name, published == null ? null
                : new Automation.Published(id(3000 + n), published, "pacote-" + n, packageVersion, "sha256:" + String.format("%064d", n)));
    }

    public static Automation.Listing listing(boolean rename, boolean execute, Automation... items) {
        return new Automation.Listing(List.of(items), new Automation.Capabilities(rename, execute), false);
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
