package com.vladoose.nir.service.supplierreply;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.openxml4j.util.ZipSecureFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Копия .xlsx / .docx для разбора таблицы (только файлы ответа поставщика). POI принимает сжатую в сотни раз картинку
 * за zip-бомбу (ZipSecureFile: распаковано больше 100 КБ при сжатии сильнее чем в 100 раз — EMF в word/media), а
 * ослаблять его защиту для всех нельзя — ею закрыт и разбор смет клиник. Поэтому в копии:
 * <ul>
 *   <li>картинки и вложенные объекты ({@code …/media/…}, {@code …/embeddings/…}) — пустые: таблице они не нужны, а имена
 *   частей на месте — связи документа не ломаются; их содержимое даже не распаковывается;</li>
 *   <li>распакованный объём ВСЕХ остальных частей (не только XML: параметры принтера, макросы, шрифты) — своим
 *   подсчётом по фактически распакованным байтам, не по размерам из каталога архива: больше предела — null;</li>
 *   <li>части записаны без сжатия: предел уже проверен, а проверка сжатия у POI на них не сработает ложно.</li>
 * </ul>
 * До распаковки архив проверяется ({@link #checkArchive}): число частей, повторы имён, перекрытия. Не zip (.xls, .doc —
 * контейнер OLE2) — как есть. Битый архив — исключение (его ловит SupplierReplyFiles: «не открылся»).
 */
public final class OoxmlSlim {

    private static final Pattern DROPPED = Pattern.compile("(^|/)(media|embeddings)/[^/]");

    private OoxmlSlim() {}

    public static byte[] forParsing(byte[] b, long limit) throws IOException {
        if (b.length < 2 || b[0] != 'P' || b[1] != 'K') return b;
        try (ZipFile zip = new ZipFile(new SeekableInMemoryByteChannel(b))) {
            List<ZipArchiveEntry> entries = Collections.list(zip.getEntriesInPhysicalOrder());
            checkArchive(entries, b.length);
            ByteArrayOutputStream buf = new ByteArrayOutputStream(b.length);
            long total = 0, reserved = 0;
            try (ZipOutputStream out = new ZipOutputStream(buf)) {
                for (ZipArchiveEntry e : entries) {
                    if (e.isDirectory()) continue;
                    CRC32 crc = new CRC32();
                    ByteArrayOutputStream part;
                    if (dropped(e)) {
                        part = new ByteArrayOutputStream(0);
                    } else {
                        // буфер сразу размером из каталога, но всего таких — не больше предела: врущий каталог много не займёт
                        long want = Math.max(0, Math.min(e.getSize(), limit - reserved));
                        reserved += want;
                        part = new ByteArrayOutputStream((int) Math.min(want, Integer.MAX_VALUE - 8));
                        try (InputStream in = zip.getInputStream(e)) {
                            if (!copy(in, part, crc, limit - total)) return null;
                        }
                        total += part.size();
                    }
                    ZipEntry z = new ZipEntry(e.getName());
                    z.setMethod(ZipEntry.STORED);
                    z.setSize(part.size());
                    z.setCompressedSize(part.size());
                    z.setCrc(crc.getValue());
                    out.putNextEntry(z);
                    part.writeTo(out);
                    out.closeEntry();
                }
            }
            return buf.toByteArray();
        }
    }

    /**
     * Архив до распаковки — письмо не должно надолго занять единственный поток приёма почты: частей не больше, чем пускает
     * POI (ZipSecureFile.getMaxFileCount, 1000); имена частей не повторяются, в том числе регистром (CVE-2025-31672: Word
     * покажет одну копию, а разобрали бы другую — POI 5.4 такие архивы отвергает); сжатых байтов у читаемых частей не
     * больше, чем во всём файле — иначе записи каталога указывают на одни и те же данные, и распаковщик прошёл бы их
     * много раз, не тратя предел распакованного (пустые блоки deflate распаковываются в ничто). Нарушение — исключение.
     */
    static void checkArchive(List<ZipArchiveEntry> entries, long fileLength) throws IOException {
        if (entries.size() > ZipSecureFile.getMaxFileCount()) {
            throw new IOException("В архиве больше " + ZipSecureFile.getMaxFileCount() + " частей");
        }
        Set<String> names = new HashSet<>();
        long compressed = 0;
        for (ZipArchiveEntry e : entries) {
            if (e.isDirectory()) continue;
            if (!names.add(partKey(e.getName()))) throw new IOException("В архиве повторяется часть");
            if (dropped(e)) continue;
            long c = e.getCompressedSize();
            if (c < 0) throw new IOException("У части архива не указан размер");
            compressed += c;
            if (compressed > fileLength) throw new IOException("Части архива перекрываются");
        }
    }

    /** Имя части для сравнения: в OOXML регистр и ведущая «/» части не различают. */
    private static String partKey(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        return n.toLowerCase(Locale.ROOT);
    }

    private static boolean dropped(ZipArchiveEntry e) {
        return DROPPED.matcher(e.getName().toLowerCase(Locale.ROOT)).find();
    }

    /** Содержимое части — в out, с контрольной суммой; больше budget байт — false (дальше предела не читается). */
    private static boolean copy(InputStream in, ByteArrayOutputStream out, CRC32 crc, long budget) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        long n = 0;
        for (int r; (r = in.read(chunk)) > 0; ) {
            n += r;
            if (n > budget) return false;
            crc.update(chunk, 0, r);
            out.write(chunk, 0, r);
        }
        return true;
    }
}
