package com.nekkora.client.ui;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import com.nekkora.client.theme.ClientTheme;

/**
 * Logos oficiais da Nekkora-lib ({@code brand/export}, PNG exportado dos SVGs; origem e hashes em
 * docs/design/tokens-e-ativos.md). Nada e redesenhado: o aplicativo so escolhe o arquivo e o reduz mantendo a proporcao.
 * Convencao da lib: {@code -dark} e para fundo ESCURO (traco claro); {@code -light}, para fundo claro.
 */
public final class BrandAssets {

    /** Alturas, em px, dos arquivos do lockup (simbolo + nome) embarcados. */
    static final int[] LOCKUP_HEIGHTS = {128, 256, 512};
    static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();

    private BrandAssets() {
    }

    static String lockupResource(ClientTheme theme, int height) {
        return "brand/nekkora-lockup-" + (theme.dark() ? "dark" : "light") + "-" + height + ".png";
    }

    /** O menor arquivo do lockup com pelo menos {@code devicePixels} de altura (o maior, se nenhum bastar). */
    public static BufferedImage lockup(ClientTheme theme, int devicePixels) {
        int chosen = LOCKUP_HEIGHTS[LOCKUP_HEIGHTS.length - 1];
        for (int h : LOCKUP_HEIGHTS) {
            if (h >= devicePixels) {
                chosen = h;
                break;
            }
        }
        return load(lockupResource(theme, chosen));
    }

    /** Icone do aplicativo em todos os tamanhos, para a janela e a barra de tarefas. */
    public static List<Image> appIcons() {
        List<Image> icons = new ArrayList<>();
        for (int s : ICON_SIZES) {
            icons.add(load("brand/nekkora-app-icon-" + s + ".png"));
        }
        return icons;
    }

    static BufferedImage load(String resource) {
        return CACHE.computeIfAbsent(resource, r -> {
            try (InputStream in = BrandAssets.class.getResourceAsStream("/com/nekkora/client/" + r)) {
                if (in == null) {
                    throw new IllegalStateException("ativo ausente: " + r);
                }
                return ImageIO.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
