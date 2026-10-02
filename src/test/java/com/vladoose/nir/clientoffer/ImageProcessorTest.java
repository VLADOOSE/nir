package com.vladoose.nir.clientoffer;

import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.ImageProcessor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Скан печати → чистый PNG (спека §7.2): фон убирается, чернила остаются, размеры проверяются до декодирования. */
class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static int alpha(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) & 0xFF;
    }

    @Test
    void removesWhiteBackgroundKeepsInkAndTrims() throws Exception {
        BufferedImage out = decode(processor.process(KpTestSupport.circleOnWhiteJpeg(400), true));
        assertThat(out.getColorModel().hasAlpha()).isTrue();
        // круг диаметром 200 — поля обрезаны
        assertThat(out.getWidth()).isBetween(190, 212);
        assertThat(out.getHeight()).isBetween(190, 212);
        assertThat(alpha(out, 0, 0)).isZero();                       // угол квадрата вокруг круга — бывшая бумага
        int center = out.getRGB(out.getWidth() / 2, out.getHeight() / 2);
        assertThat((center >>> 24) & 0xFF).isEqualTo(255);            // чернила непрозрачны
        assertThat(center & 0xFF).isGreaterThan((center >> 16) & 0xFF); // и остались синими
    }

    @Test
    void withoutRemovalKeepsWhitePaper() throws Exception {
        BufferedImage out = decode(processor.process(KpTestSupport.circleOnWhiteJpeg(400), false));
        assertThat(out.getWidth()).isEqualTo(400);
        assertThat(alpha(out, 0, 0)).isEqualTo(255);
    }

    @Test
    void keepsExistingTransparency() throws Exception {
        BufferedImage src = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = src.createGraphics();
        g.setColor(new Color(235, 235, 235));   // светло-серый, почти как бумага — но прозрачность уже есть
        g.fillRect(25, 25, 50, 50);
        g.dispose();
        BufferedImage out = decode(processor.process(KpTestSupport.png(src), true));
        assertThat(out.getWidth()).isEqualTo(50);
        assertThat(alpha(out, 25, 25)).isEqualTo(255);
    }

    /**
     * Прозрачный PNG с тёмными чернилами (подпись, вырезанная заранее). Прозрачные пиксели после перевода в ARGB —
     * чёрные, поэтому без проверки прозрачности «бумагой» стал бы чёрный цвет и чернила стёрлись бы целиком.
     * Светло-серый квадрат из теста выше эту поломку не видит: от чёрного он далёк при любом исходе.
     */
    @Test
    void keepsDarkInkOfTransparentPng() throws Exception {
        BufferedImage src = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = src.createGraphics();
        g.setColor(new Color(20, 20, 20));
        g.fillRect(25, 25, 50, 50);
        g.dispose();
        BufferedImage out = decode(processor.process(KpTestSupport.png(src), true));
        assertThat(out.getWidth()).isEqualTo(50);
        assertThat(alpha(out, 25, 25)).isEqualTo(255);
    }

    @Test
    void scalesDownLongSide() throws Exception {
        BufferedImage src = new BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, 3000, 1500);
        g.dispose();
        BufferedImage out = decode(processor.process(KpTestSupport.png(src), false));
        assertThat(out.getWidth()).isEqualTo(1200);
        assertThat(out.getHeight()).isEqualTo(600);
    }

    @Test
    void rejectsHugeDimensionsBeforeDecoding() {
        byte[] wide = KpTestSupport.png(new BufferedImage(6000, 10, BufferedImage.TYPE_INT_RGB));
        assertThatThrownBy(() -> processor.process(wide, false)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5000");
    }

    /**
     * «Бомба»: в заголовке 30000×30000, пиксельных данных нет. Отказ должен прийти по одному заголовку; начни обработка
     * сперва декодировать — ответом было бы «не удалось прочитать» (а у настоящей бомбы — гигабайты памяти).
     */
    @Test
    void rejectsBombByHeaderAlone() {
        assertThatThrownBy(() -> processor.process(headerOnlyPng(30000, 30000), true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("5000");
    }

    /** PNG из заголовка IHDR (8 бит, RGB) и IEND — без пиксельных данных. */
    private static byte[] headerOnlyPng(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17).put("IHDR".getBytes(StandardCharsets.US_ASCII))
                .putInt(width).putInt(height).put(new byte[]{8, 2, 0, 0, 0});
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        return ByteBuffer.allocate(8 + 4 + 17 + 4 + 12)
                .put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})
                .putInt(13).put(ihdr.array()).putInt((int) crc.getValue())
                .putInt(0).put("IEND".getBytes(StandardCharsets.US_ASCII)).putInt(0xAE426082)
                .array();
    }

    @Test
    void rejectsNonImagesAndGif() throws Exception {
        assertThatThrownBy(() -> processor.process("не картинка".getBytes(StandardCharsets.UTF_8), true))
                .isInstanceOf(BadRequestException.class);
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "gif", gif);
        assertThatThrownBy(() -> processor.process(gif.toByteArray(), true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("PNG или JPEG");
    }

    @Test
    void rejectsEmptyAndTooBig() {
        assertThatThrownBy(() -> processor.process(new byte[0], true)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> processor.process(new byte[ImageProcessor.MAX_BYTES + 1], true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("5 МБ");
    }
}
