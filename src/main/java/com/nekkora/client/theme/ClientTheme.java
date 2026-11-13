package com.nekkora.client.theme;

import java.util.Locale;

/**
 * Os DOIS temas do Client, com a paleta da Nekkora-lib ({@code src/tokens/themes.css}, commit em
 * {@code docs/design/tokens-e-ativos.md}). Nenhum valor e escolhido aqui: a paleta e copiada do tema da lib
 * ({@code data-nk-theme}) e os tokens derivados sao calculados com as mesmas formulas do bloco de modo (escuro ou claro)
 * daquele arquivo. O teste {@code ThemeTokensTest} le o CSS da lib e confere cada valor.
 *
 * <p>Sem AWT: as cores sao {@link Rgb}; a conversao para Swing fica em {@code ui}.
 */
public enum ClientTheme {

    /** {@code [data-nk-theme='sakura-dark']}, modo escuro. Padrao na primeira abertura. */
    SAKURA_DARK("sakura-dark", "Sakura Dark", true,
            "#1b0c1a", "#2d222f", "#4b2138", "#6d3c52", "#765d67", "#fadcd5", "#f8f2f7", "#f0a3b8", "#f5bacb", "#e48ca5"),

    /** {@code [data-nk-theme='light']} ("Nekkora Light"), modo claro. */
    NEKKORA_LIGHT("light", "Nekkora Light", false,
            "#f8f3f5", "#ffffff", "#f6e9ee", "#603c52", "#765067", "#48213b", "#2a1a28", "#ad3e61", "#983252", "#852c48");

    private final String libId;
    private final String label;
    private final boolean dark;
    private final Rgb bg;
    private final Rgb surface;
    private final Rgb card;
    private final Rgb primary;
    private final Rgb secondary;
    private final Rgb highlight;
    private final Rgb text;
    private final Rgb accent;
    private final Rgb accentHover;
    private final Rgb accentActive;

    ClientTheme(String libId, String label, boolean dark, String bg, String surface, String card, String primary, String secondary,
                String highlight, String text, String accent, String accentHover, String accentActive) {
        this.libId = libId;
        this.label = label;
        this.dark = dark;
        this.bg = Rgb.hex(bg);
        this.surface = Rgb.hex(surface);
        this.card = Rgb.hex(card);
        this.primary = Rgb.hex(primary);
        this.secondary = Rgb.hex(secondary);
        this.highlight = Rgb.hex(highlight);
        this.text = Rgb.hex(text);
        this.accent = Rgb.hex(accent);
        this.accentHover = Rgb.hex(accentHover);
        this.accentActive = Rgb.hex(accentActive);
    }

    public static final ClientTheme DEFAULT = SAKURA_DARK;

    /** Tema pelo identificador da lib; desconhecido, nulo ou de outro tema da lib: o padrao. */
    public static ClientTheme byLibId(String id) {
        if (id != null) {
            String v = id.trim().toLowerCase(Locale.ROOT);
            for (ClientTheme t : values()) {
                if (t.libId.equals(v)) {
                    return t;
                }
            }
        }
        return DEFAULT;
    }

    public ClientTheme other() {
        return this == SAKURA_DARK ? NEKKORA_LIGHT : SAKURA_DARK;
    }

    /** Valor de {@code data-nk-theme} na Nekkora-lib. */
    public String libId() {
        return libId;
    }

    public String label() {
        return label;
    }

    public boolean dark() {
        return dark;
    }

    // ------------------------------------------------------------------ paleta (--nk-color-*)

    public Rgb bg() {
        return bg;
    }

    public Rgb surface() {
        return surface;
    }

    public Rgb card() {
        return card;
    }

    public Rgb primary() {
        return primary;
    }

    public Rgb secondary() {
        return secondary;
    }

    public Rgb highlight() {
        return highlight;
    }

    public Rgb text() {
        return text;
    }

    public Rgb accent() {
        return accent;
    }

    public Rgb accentHover() {
        return accentHover;
    }

    public Rgb accentActive() {
        return accentActive;
    }

    // ------------------------------------------------------------------ derivados (bloco de modo da lib)

    /** {@code --nk-color-text-secondary}: escuro, text 64% + bg; claro (Nekkora Light), o valor fixo do tema. */
    public Rgb textSecondary() {
        return dark ? text.mix(64, bg) : Rgb.hex("#64566a");
    }

    /** {@code --nk-color-text-muted}: escuro, text 58% + bg; claro (Nekkora Light), o valor fixo do tema. */
    public Rgb textMuted() {
        return dark ? text.mix(58, bg) : Rgb.hex("#786c76");
    }

    /** {@code --nk-color-text-inverse}: rotulo sobre o acento. Escuro: o fundo; claro: branco. */
    public Rgb textInverse() {
        return dark ? bg : Rgb.hex("#ffffff");
    }

    /** {@code --nk-color-border} (text 9% escuro / 10% claro, sobre transparente), pintado sobre o fundo. */
    public Rgb border() {
        return text.over(dark ? 9 : 10, bg);
    }

    /** {@code --nk-color-surface-hover} (highlight 6% escuro / primary 6% claro), pintado sobre o fundo. */
    public Rgb surfaceHover() {
        return (dark ? highlight : primary).over(6, bg);
    }

    /** {@code --nk-color-accent-soft} (accent 14% escuro / 10% claro), pintado sobre o fundo: trilho do spinner. */
    public Rgb accentSoft() {
        return accent.over(dark ? 14 : 10, bg);
    }

    /** Cor do {@code --nk-focus-ring} (3 px de accent a 35% escuro / 25% claro), pintada sobre o fundo. */
    public Rgb focusRing() {
        return accent.over(dark ? 35 : 25, bg);
    }

    /** {@code --nk-color-danger-text}: texto de erro. */
    public Rgb dangerText() {
        return Rgb.hex(dark ? "#ff7d95" : "#c0264a");
    }

    // ------------------------------------------------------------------ fundacao (foundation.css), em px logicos

    public static final int FONT_SIZE_SM = 13;
    public static final int FONT_SIZE_MD = 14;
    public static final int SPACE_2 = 8;
    public static final int SPACE_3 = 12;
    public static final int SPACE_4 = 16;
    public static final int SPACE_6 = 24;
    public static final int SPACE_8 = 32;
    public static final int RADIUS_MD = 8;
    public static final int CONTROL_SM = 32;
    public static final int CONTROL_MD = 40;
    public static final int FOCUS_RING_WIDTH = 3;
    public static final int SPINNER_SIZE = 20;
    /** Opacidade do botao desabilitado ({@code .nk-button:disabled}) e do que esta carregando. */
    public static final float DISABLED_OPACITY = 0.45f;
    public static final float LOADING_OPACITY = 0.75f;
    /** Uma volta do spinner ({@code nk-spin 0.8s linear infinite}). */
    public static final int SPINNER_TURN_MS = 800;
}
