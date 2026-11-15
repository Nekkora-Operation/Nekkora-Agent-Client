package com.nekkora.client.theme;

/**
 * Cor sRGB opaca, sem depender de AWT, com as duas operacoes que os tokens derivados da Nekkora-lib usam
 * ({@code color-mix(in srgb, A p%, B)} e {@code color-mix(in srgb, A p%, transparent)} sobre um fundo conhecido).
 */
public record Rgb(int r, int g, int b) {

    public Rgb {
        if ((r | g | b) < 0 || r > 255 || g > 255 || b > 255) {
            throw new IllegalArgumentException("componente fora de 0-255");
        }
    }

    /** {@code #rrggbb}. */
    public static Rgb hex(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("cor invalida: " + hex);
        }
        int v = Integer.parseInt(hex.substring(1), 16);
        return new Rgb(v >> 16 & 0xff, v >> 8 & 0xff, v & 0xff);
    }

    /** {@code color-mix(in srgb, this percent%, other)}: interpolacao linear por componente. */
    public Rgb mix(double percent, Rgb other) {
        double p = percent / 100.0;
        return new Rgb(round(r * p + other.r * (1 - p)), round(g * p + other.g * (1 - p)), round(b * p + other.b * (1 - p)));
    }

    /**
     * {@code color-mix(in srgb, this percent%, transparent)} pintado sobre {@code background}: a mistura com transparente
     * mantem a cor e reduz o alfa, entao sobre um fundo opaco o resultado e a mesma interpolacao.
     */
    public Rgb over(double alphaPercent, Rgb background) {
        return mix(alphaPercent, background);
    }

    public String toHex() {
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /** Luminancia relativa da WCAG 2.x. */
    public double luminance() {
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
    }

    /** Razao de contraste da WCAG 2.x entre duas cores opacas (1 a 21). */
    public static double contrast(Rgb a, Rgb b) {
        double la = a.luminance();
        double lb = b.luminance();
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double linear(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int round(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
