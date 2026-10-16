package com.vladoose.nir.service.supplierreply;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Копия .xlsx / .docx для разбора таблицы (только файлы ответа поставщика). POI принимает сжатую в сотни раз картинку
 * за zip-бомбу (ZipSecureFile: распаковано больше 100 КБ при сжатии сильнее чем в 100 раз — КП «Рикта»: EMF в
 * word/media), а ослаблять его защиту для всех нельзя — ею закрыт и разбор смет клиник. Поэтому в копии:
 * <ul>
 *   <li>картинки и вложенные объекты ({@code …/media/…}, {@code …/embeddings/…}) — пустые: таблице они не нужны, а имена
 *   частей на месте — связи документа не ломаются; их содержимое даже не распаковывается;</li>
 *   <li>распакованный объём ВСЕХ остальных частей (не только XML: параметры принтера, макросы, шрифты) — своим
 *   подсчётом по фактически распакованным байтам, не по размерам из каталога архива: больше предела — null;</li>
 *   <li>части записаны без сжатия: предел уже проверен, а проверка сжатия у POI на них не сработает ложно.</li>
 * </ul>
 * Не zip (.xls, .doc — контейнер OLE2) — как есть. Битый архив — исключение (его ловит SupplierReplyFiles: «не открылся»).
 */
final class OoxmlSlim {

    private static final Pattern DROPPED = Pattern.compile("(^|/)(media|embeddings)/[^/]");

    private OoxmlSlim() {}

    static byte[] forParsing(byte[] b, long limit) throws IOException {
        if (b.length < 2 || b[0] != 'P' || b[1] != 'K') return b;
        ByteArrayOutputStream buf = new ByteArrayOutputStream(b.length);
        long total = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(new SeekableInMemoryByteChannel(b)); ZipOutputStream out = new ZipOutputStream(buf)) {
            for (Enumeration<ZipArchiveEntry> en = zip.getEntriesInPhysicalOrder(); en.hasMoreElements(); ) {
                ZipArchiveEntry e = en.nextElement();
                if (e.isDirectory() || !names.add(e.getName())) continue;
                byte[] data;
                if (DROPPED.matcher(e.getName().toLowerCase(Locale.ROOT)).find()) {
                    data = new byte[0];
                } else {
                    try (InputStream in = zip.getInputStream(e)) {
                        data = read(in, limit - total);
                    }
                    if (data == null) return null;
                    total += data.length;
                }
                ZipEntry z = new ZipEntry(e.getName());
                z.setMethod(ZipEntry.STORED);
                z.setSize(data.length);
                z.setCompressedSize(data.length);
                CRC32 crc = new CRC32();
                crc.update(data);
                z.setCrc(crc.getValue());
                out.putNextEntry(z);
                out.write(data);
                out.closeEntry();
            }
        }
        return buf.toByteArray();
    }

    /** Всё содержимое части; больше budget байт — null (читается не дальше предела). */
    private static byte[] read(InputStream in, long budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        long n = 0;
        for (int r; (r = in.read(chunk)) > 0; ) {
            n += r;
            if (n > budget) return null;
            out.write(chunk, 0, r);
        }
        return out.toByteArray();
    }
}
