import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Monta o .ico do executavel a partir dos PNGs OFICIAIS do icone (Nekkora-lib, brand/export/app-icon), sem redesenhar
 * nem reamostrar: cada PNG entra inteiro como uma imagem do .ico (formato com PNG embutido, aceito pelo Windows).
 *
 * <p>Uso: {@code java tools/IconFromPng.java <saida.ico> <png-16> <png-24> ...}
 */
public final class IconFromPng {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("uso: IconFromPng <saida.ico> <png...>");
        }
        List<byte[]> images = new ArrayList<>();
        List<int[]> sizes = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            byte[] png = Files.readAllBytes(Path.of(args[i]));
            // cabecalho PNG: assinatura (8) + tamanho do IHDR (4) + "IHDR" (4) + largura (4) + altura (4)
            ByteBuffer b = ByteBuffer.wrap(png, 16, 8).order(ByteOrder.BIG_ENDIAN);
            int w = b.getInt();
            int h = b.getInt();
            if (w != h || w > 256) {
                throw new IllegalArgumentException(args[i] + ": o icone precisa ser quadrado, de ate 256 px");
            }
            images.add(png);
            sizes.add(new int[] {w, h});
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * images.size()).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        int offset = header.capacity();
        for (int i = 0; i < images.size(); i++) {
            int s = sizes.get(i)[0];
            header.put((byte) (s == 256 ? 0 : s)).put((byte) (s == 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            header.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.write(header.array());
            for (byte[] png : images) {
                d.write(png);
            }
        }
        Files.write(Path.of(args[0]), out.toByteArray());
    }
}
