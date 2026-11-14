package com.nekkora.client.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.nekkora.client.nav.Automation;
import com.nekkora.client.nav.Directory;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Profile;
import com.nekkora.client.nav.Workspace;

/**
 * As consultas da pessoa a Platform (contrato desktop-navigation/1), com o token da sessao do aplicativo. So leitura do
 * que o servidor autoriza a cada chamada: o aplicativo nao declara organizacao, papel nem conta, e nao guarda nada disto
 * em disco.
 * <p>
 * Uma resposta so e aceita na forma exata do contrato; fora dela e {@link Failure#INVALID}, nunca uma lista "pelo que
 * deu para ler". {@code 401} encerra a sessao do aplicativo ({@code onSessionEnded}).
 */
final class PlatformDirectory implements Directory {

    private final PlatformApi api;
    private final Supplier<PlatformApi.Token> token;
    private final Runnable onSessionEnded;

    PlatformDirectory(PlatformApi api, Supplier<PlatformApi.Token> token, Runnable onSessionEnded) {
        this.api = api;
        this.token = token;
        this.onSessionEnded = onSessionEnded;
    }

    @Override
    public Profile profile() throws Failed {
        Map<String, Object> o = get("/api/v1/desktop/me");
        if (!(o.get("email") instanceof String email) || email.isBlank()) {
            throw new Failed(Failure.INVALID);
        }
        Object name = o.get("displayName");
        if (name != null && !(name instanceof String)) {
            throw new Failed(Failure.INVALID);
        }
        return new Profile(email, (String) name);
    }

    @Override
    public List<Organization> organizations() throws Failed {
        Map<String, Object> o = get("/api/v1/desktop/organizations");
        if (!(o.get("items") instanceof List<?> items)) {
            throw new Failed(Failure.INVALID);
        }
        List<Organization> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || name.isBlank()) {
                throw new Failed(Failure.INVALID);
            }
            UUID id = uuid(m.get("organizationId"));
            Object handle = m.get("handle");
            if (!seen.add(id) || (handle != null && !(handle instanceof String))) {
                throw new Failed(Failure.INVALID);
            }
            boolean awaiting;
            if ("AGUARDANDO_ATIVACAO".equals(m.get("activationState"))) {
                awaiting = true;
            } else if ("ATIVADA".equals(m.get("activationState"))) {
                awaiting = false;
            } else {
                throw new Failed(Failure.INVALID);
            }
            out.add(new Organization(id, name, (String) handle, awaiting));
        }
        return out;
    }

    /** Teto de paginas lidas de uma lista (paginas de 100): acima disso a tela avisa que a lista esta incompleta. */
    static final int MAX_PAGES = 10;
    private static final java.util.regex.Pattern CURSOR = java.util.regex.Pattern.compile("^[A-Za-z0-9._~-]{1,1024}$");

    @Override
    public List<Workspace> workspaces(UUID organization) throws Failed {
        List<Workspace> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> o = get("/api/v1/desktop/organizations/" + organization + "/workspaces?limit=100" + (cursor == null ? "" : "&cursor=" + cursor));
            for (Object item : items(o)) {
                if (!(item instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || name.isBlank()) {
                    throw new Failed(Failure.INVALID);
                }
                UUID id = uuid(m.get("workspaceId"));
                if (!seen.add(id)) {
                    throw new Failed(Failure.INVALID);
                }
                out.add(new Workspace(id, name));
            }
            cursor = next(o);
            if (cursor == null) {
                return out;
            }
        }
        return out;
    }

    @Override
    public Automation.Listing automations(UUID organization, UUID workspace) throws Failed {
        List<Automation> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Automation.Capabilities capabilities = null;
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> o = get("/api/v1/desktop/organizations/" + organization + "/workspaces/" + workspace + "/automations?limit=100"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            if (!(o.get("capabilities") instanceof Map<?, ?> c) || !(c.get("rename") instanceof Boolean rename) || !(c.get("execute") instanceof Boolean execute)) {
                throw new Failed(Failure.INVALID);
            }
            // as capacidades valem como vieram na ULTIMA pagina lida (a mais recente)
            capabilities = new Automation.Capabilities(rename, execute);
            for (Object item : items(o)) {
                if (!(item instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || name.isBlank()) {
                    throw new Failed(Failure.INVALID);
                }
                UUID id = uuid(m.get("automationId"));
                if (!seen.add(id)) {
                    throw new Failed(Failure.INVALID);
                }
                out.add(new Automation(id, name, published(m.get("latestPublishedVersion"))));
            }
            cursor = next(o);
            if (cursor == null) {
                return new Automation.Listing(out, capabilities, false);
            }
        }
        return new Automation.Listing(out, capabilities, true);
    }

    private static Automation.Published published(Object value) throws Failed {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> v) || !(v.get("versionNumber") instanceof Double n) || n != Math.rint(n) || n < 1 || n > Integer.MAX_VALUE) {
            throw new Failed(Failure.INVALID);
        }
        String name = null;
        String version = null;
        String fingerprint = null;
        Object pkg = v.get("package");
        if (pkg != null) {
            if (!(pkg instanceof Map<?, ?> p)) {
                throw new Failed(Failure.INVALID);
            }
            name = text(p.get("name"));
            version = text(p.get("version"));
            fingerprint = text(p.get("fingerprint"));
        }
        return new Automation.Published(uuid(v.get("automationVersionId")), n.intValue(), name, version, fingerprint);
    }

    private static String text(Object value) throws Failed {
        if (value == null) {
            return null;
        }
        if (value instanceof String s && s.length() <= 300) {
            return s.isBlank() ? null : s;
        }
        throw new Failed(Failure.INVALID);
    }

    private static List<?> items(Map<String, Object> o) throws Failed {
        if (o.get("items") instanceof List<?> items && items.size() <= 100) {
            return items;
        }
        throw new Failed(Failure.INVALID);
    }

    /** O cursor da proxima pagina, opaco: e devolvido ao servidor como veio, e so se tiver a forma de um cursor. */
    private static String next(Map<String, Object> o) throws Failed {
        Object c = o.get("nextCursor");
        if (c == null) {
            return null;
        }
        if (c instanceof String s && CURSOR.matcher(s).matches()) {
            return s;
        }
        throw new Failed(Failure.INVALID);
    }

    static UUID uuid(Object value) throws Failed {
        if (value instanceof String s && s.length() == 36) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                throw new Failed(Failure.INVALID);
            }
        }
        throw new Failed(Failure.INVALID);
    }

    Map<String, Object> get(String path) throws Failed {
        PlatformApi.Token t = token.get();
        if (t == null) {
            throw new Failed(Failure.SESSION_ENDED);
        }
        try {
            return api.read(path, t);
        } catch (Failed f) {
            if (f.failure() == Failure.SESSION_ENDED) {
                onSessionEnded.run();
            }
            throw f;
        }
    }
}
