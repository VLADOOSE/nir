package com.vladoose.nir.service;

import com.vladoose.nir.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Скан печати, подписи или логотип → чистый PNG (спека client-kp-constructor §7.2). Размеры проверяются по
 * заголовку ДО декодирования (защита от «бомб»); фон = медиана цвета краёв (белая, желтоватая или серая бумага),
 * всё близкое к нему становится прозрачным с мягким краем, чернила сохраняют цвет; пустые поля обрезаются;
 * длинная сторона ≤ 1200 px; на выходе всегда перекодированный PNG — метаданные и EXIF обработку не переживают.
 */
@Component
public class ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_SIDE_IN = 5000;
    public static final int MAX_SIDE_OUT = 1200;
    private static final Set<String> FORMATS = Set.of("png", "jpeg");
    /** Отличие от цвета бумаги (макс. по каналам): ≤ NEAR — прозрачно, ≥ FAR — непрозрачно, между — край. */
    static final int NEAR = 28;
    static final int FAR = 80;
    /** Ответ на любую нечитаемую картинку — битый файл или сбой декодера на скане: что делать оператору. */
    static final String UNREADABLE = "Картинку не удалось прочитать — сохраните скан как JPEG или PNG и загрузите снова";

    public byte[] process(byte[] input, boolean removeBackground) {
        if (input == null || input.length == 0) throw new BadRequestException("Файл пустой");
        if (input.length > MAX_BYTES) throw new BadRequestException("Картинка больше 5 МБ");
        BufferedImage image = decode(input);
        if (removeBackground && !hasTransparency(image)) removeBackground(image);
        return png(scaleDown(trim(image)));
    }

    /**
     * Картинка → ARGB. Декодер ImageIO на сканах бросает и непроверяемые исключения — ICC-профиль сканера, который не
     * применить к пикселям (IllegalArgumentException, CMMException), битые данные: это тоже «не удалось прочитать», 400
     * с советом, а не 500. Свои BadRequestException (формат, размеры) уходят как есть.
     */
    private static BufferedImage decode(byte[] input) {
        try {
            return toArgb(read(input));
        } catch (BadRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Картинка не декодирована: {}", e.toString());
            throw new BadRequestException(UNREADABLE);
        }
    }

    private static BufferedImage read(byte[] input) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = in == null ? Collections.emptyIterator() : ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new BadRequestException("Нужна картинка PNG или JPEG");
            ImageReader reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException("Нужна картинка PNG или JPEG");
                }
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SIDE_IN || h > MAX_SIDE_IN) {
                    throw new BadRequestException("Картинка больше " + MAX_SIDE_IN + " px по стороне");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BadRequestException(UNREADABLE);
        }
    }

    private static BufferedImage toArgb(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Есть заметная прозрачность (>1% пикселей) — готовый PNG, фон не трогаем. */
    private static boolean hasTransparency(BufferedImage img) {
        long transparent = 0;
        long total = (long) img.getWidth() * img.getHeight();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) < 250) transparent++;
            }
        }
        return transparent * 100 > total;
    }

    private static void removeBackground(BufferedImage img) {
        int[] bg = borderMedian(img);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int dist = Math.max(Math.abs(r - bg[0]), Math.max(Math.abs(g - bg[1]), Math.abs(b - bg[2])));
                int alpha = dist <= NEAR ? 0 : dist >= FAR ? 255 : (dist - NEAR) * 255 / (FAR - NEAR);
                int oldAlpha = (argb >>> 24) & 0xFF;
                img.setRGB(x, y, (Math.min(alpha, oldAlpha) << 24) | (argb & 0x00FFFFFF));
            }
        }
    }

    /** Медиана цвета по краям картинки — цвет бумаги. */
    private static int[] borderMedian(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int n = 2 * w + 2 * Math.max(0, h - 2);
        int[][] ch = new int[3][n];
        int i = 0;
        for (int x = 0; x < w; x++) {
            i = put(ch, i, img.getRGB(x, 0));
            if (h > 1) i = put(ch, i, img.getRGB(x, h - 1));
        }
        for (int y = 1; y < h - 1; y++) {
            i = put(ch, i, img.getRGB(0, y));
            if (w > 1) i = put(ch, i, img.getRGB(w - 1, y));
        }
        int[] median = new int[3];
        for (int c = 0; c < 3; c++) {
            int[] values = Arrays.copyOf(ch[c], i);
            Arrays.sort(values);
            median[c] = values[values.length / 2];
        }
        return median;
    }

    private static int put(int[][] ch, int i, int argb) {
        ch[0][i] = (argb >> 16) & 0xFF;
        ch[1][i] = (argb >> 8) & 0xFF;
        ch[2][i] = argb & 0xFF;
        return i + 1;
    }

    private static BufferedImage trim(BufferedImage img) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) throw new BadRequestException("На картинке ничего не осталось — загрузите без удаления фона");
        BufferedImage out = new BufferedImage(maxX - minX + 1, maxY - minY + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, -minX, -minY, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scaleDown(BufferedImage img) {
        int longSide = Math.max(img.getWidth(), img.getHeight());
        if (longSide <= MAX_SIDE_OUT) return img;
        double k = (double) MAX_SIDE_OUT / longSide;
        int w = Math.max(1, (int) Math.round(img.getWidth() * k));
        int h = Math.max(1, (int) Math.round(img.getHeight() * k));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] png(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG не записан", e);
        }
    }
}
