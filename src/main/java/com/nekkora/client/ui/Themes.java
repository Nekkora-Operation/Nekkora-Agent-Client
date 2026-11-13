package com.nekkora.client.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.nekkora.client.theme.ClientTheme;
import com.nekkora.client.theme.Rgb;

/**
 * Aplica um {@link ClientTheme} ao Swing (FlatLaf como base, com as cores do tema) e guarda o tema atual para os
 * componentes proprios, que pintam direto com os tokens. Mapeamento token -> componente: docs/design/tokens-e-ativos.md.
 */
public final class Themes {

    private static volatile ClientTheme current = ClientTheme.DEFAULT;
    private static final List<Consumer<ClientTheme>> LISTENERS = new CopyOnWriteArrayList<>();

    private Themes() {
    }

    public static ClientTheme current() {
        return current;
    }

    public static void addListener(Consumer<ClientTheme> listener) {
        LISTENERS.add(listener);
    }

    /** Instala o tema antes da primeira janela, ou troca com as janelas abertas. Na fila de eventos. */
    public static void apply(ClientTheme theme) {
        current = theme;
        FlatLaf.setGlobalExtraDefaults(defaults(theme));
        if (theme.dark()) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
        }
        LISTENERS.forEach(l -> l.accept(theme));
    }

    /** Chaves do FlatLaf alimentadas pelos tokens (janela, barra de titulo, dica). O resto e pintado pelos componentes. */
    static Map<String, String> defaults(ClientTheme t) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("@background", t.bg().toHex());
        d.put("@foreground", t.text().toHex());
        d.put("@accentColor", t.accent().toHex());
        d.put("TitlePane.background", t.bg().toHex());
        d.put("TitlePane.inactiveBackground", t.bg().toHex());
        d.put("TitlePane.foreground", t.text().toHex());
        d.put("TitlePane.inactiveForeground", t.textMuted().toHex());
        d.put("TitlePane.unifiedBackground", "false");
        d.put("TitlePane.embeddedForeground", t.textSecondary().toHex());
        d.put("MenuBar.background", t.bg().toHex());
        d.put("MenuBar.borderColor", t.bg().toHex());
        d.put("RootPane.background", t.bg().toHex());
        d.put("Panel.background", t.bg().toHex());
        d.put("ToolTip.background", t.surface().toHex());
        d.put("ToolTip.foreground", t.text().toHex());
        d.put("ToolTip.border", "4,8,4,8," + t.border().toHex());
        return d;
    }

    public static Color color(Rgb c) {
        return new Color(c.r(), c.g(), c.b());
    }

    /**
     * Fonte da interface: a pilha da lib e {@code 'Inter', 'Segoe UI', system-ui}. A Inter da lib e distribuida em WOFF2,
     * que o Java nao carrega; o Client usa o proximo nome da pilha, Segoe UI (do Windows), e cai na fonte logica do
     * sistema fora dele. {@code medium} (peso 500 da lib): Segoe UI Semibold, a mais proxima disponivel.
     */
    public static Font font(int size, boolean medium) {
        Set<String> families = Families.NAMES;
        if (medium && families.contains("Segoe UI Semibold")) {
            return new Font("Segoe UI Semibold", Font.PLAIN, size);
        }
        if (families.contains("Segoe UI")) {
            return new Font("Segoe UI", medium ? Font.BOLD : Font.PLAIN, size);
        }
        return new Font(Font.SANS_SERIF, medium ? Font.BOLD : Font.PLAIN, size);
    }

    private static final class Families {
        static final Set<String> NAMES = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    }
}
