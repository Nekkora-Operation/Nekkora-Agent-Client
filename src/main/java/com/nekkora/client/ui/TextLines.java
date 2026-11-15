package com.nekkora.client.ui;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

/** Quebra de texto por palavras numa largura; palavra maior que a linha e partida, nunca cortada da tela. */
final class TextLines {

    private TextLines() {
    }

    static List<String> wrap(FontMetrics fm, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            while (fm.stringWidth(word) > width && word.length() > 1) {
                // palavra mais larga que a linha: fecha a linha atual e parte a palavra no que couber
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                int cut = 1;
                while (cut < word.length() && fm.stringWidth(word.substring(0, cut + 1)) <= width) {
                    cut++;
                }
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && fm.stringWidth(candidate) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
