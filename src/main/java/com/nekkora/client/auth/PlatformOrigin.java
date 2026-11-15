package com.nekkora.client.auth;

import java.net.URI;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Onde a entrada acontece. Nunca vem de link, arquivo aberto pela pessoa, argumento ou resposta da rede:
 * <ul>
 * <li><b>producao</b> (padrao): {@link #PRODUCTION}, para o navegador e para a API;</li>
 * <li><b>desenvolvimento</b>: so pelas propriedades do launcher {@code nekkora.client.platformOrigin} (pagina aberta no
 * navegador, que tambem e o emissor esperado no retorno) e {@code nekkora.client.apiOrigin} (API). As duas precisam ser
 * deste computador ({@code localhost} com porta), e a API e sempre HTTPS: o certificado e conferido pelo repositorio de
 * confianca da JVM, sem excecao.</li>
 * </ul>
 * Qualquer outro valor e recusado na partida ({@link IllegalStateException}); o aplicativo nao cai para um endereco
 * escolhido por terceiros.
 */
public record PlatformOrigin(String browser, String api) {

    public static final String PRODUCTION = "https://platform.nekkora.com";
    public static final String BROWSER_PROPERTY = "nekkora.client.platformOrigin";
    public static final String API_PROPERTY = "nekkora.client.apiOrigin";

    private static final Pattern DEV_BROWSER = Pattern.compile("^https?://localhost:[1-9][0-9]{1,4}$");
    private static final Pattern DEV_API = Pattern.compile("^https://localhost:[1-9][0-9]{1,4}$");

    public static PlatformOrigin resolve() {
        return resolve(System.getProperties());
    }

    static PlatformOrigin resolve(Properties p) {
        String browser = trimmed(p.getProperty(BROWSER_PROPERTY));
        String api = trimmed(p.getProperty(API_PROPERTY));
        if (browser == null && api == null) {
            return new PlatformOrigin(PRODUCTION, PRODUCTION);
        }
        if (browser == null || !DEV_BROWSER.matcher(browser).matches()) {
            throw new IllegalStateException("origem da Platform nao permitida");
        }
        if (api == null) {
            api = browser;
        }
        if (!DEV_API.matcher(api).matches()) {
            throw new IllegalStateException("origem da API nao permitida (so HTTPS neste computador)");
        }
        return new PlatformOrigin(browser, api);
    }

    private static String trimmed(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public boolean production() {
        return PRODUCTION.equals(browser) && PRODUCTION.equals(api);
    }

    /** Pagina de entrada do aplicativo na Web. */
    public URI authorizePage() {
        return URI.create(browser + "/entrar/aplicativo");
    }

    public URI apiPath(String path) {
        return URI.create(api + path);
    }
}
