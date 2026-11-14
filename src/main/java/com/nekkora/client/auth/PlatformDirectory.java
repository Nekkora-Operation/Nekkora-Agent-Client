package com.nekkora.client.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.nekkora.client.nav.Directory;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Profile;

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
