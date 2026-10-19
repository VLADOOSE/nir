package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.mail.MailFile;
import com.vladoose.nir.service.mail.ParsedMail;
import com.vladoose.nir.util.KpToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Файлы ответа поставщика — вне транзакции письма (спека §3.1, решение плана 5): вид, SHA-256, таблица Excel / Word,
 * число страниц PDF. Любой сбой разбора — пометка FAILED, а не исключение: битый файл не роняет приём почты.
 */
@Component
public class SupplierReplyFiles {

    public static final int PARSE_MAX = 5 * 1024 * 1024;
    /**
     * Распакованные части книги / документа, кроме картинок и вложенных объектов (OoxmlSlim): 15 МБ — это десятки тысяч
     * ячеек, КП на 40 позиций занимает сотни КБ.
     */
    public static final long PARTS_MAX = 15L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(SupplierReplyFiles.class);

    private final HeaderDictionary dictionary;
    private final ReplyPdfPages pdf;
    private final long partsMax;

    @Autowired
    public SupplierReplyFiles(HeaderDictionary dictionary, ReplyPdfPages pdf) {
        this(dictionary, pdf, PARTS_MAX);
    }

    /** Тесты: свой предел распакованных частей. */
    SupplierReplyFiles(HeaderDictionary dictionary, ReplyPdfPages pdf, long partsMax) {
        this.dictionary = dictionary;
        this.pdf = pdf;
        this.partsMax = partsMax;
    }

    /** Файлы письма с меткой [КП-…] в теме; прочим письмам разбор файлов не нужен. */
    public List<PreparedFile> prepareFor(ParsedMail m) {
        if (m.files().isEmpty() || KpToken.parse(m.subject()).isEmpty()) return List.of();
        Map<String, LineField> learned = dictionary.learned();
        return m.files().stream().map(f -> f.stored() ? prepare(f.name(), f.mimeType(), f.content(), learned) : tooLarge(f)).toList();
    }

    public PreparedFile prepare(String name, String mime, byte[] bytes) {
        return prepare(name, mime, bytes, dictionary.learned());
    }

    PreparedFile prepare(String name, String mime, byte[] bytes, Map<String, LineField> learned) {
        String n = fileName(name);
        ReplyFileKind kind = kind(n, mime, bytes);
        String sha = sha256(bytes);
        try {
            switch (kind) {
                case EXCEL, WORD -> {
                    // POI держит книгу целиком в памяти: прайс-лист на десятки тысяч строк при -Xmx1g прода опасен;
                    // разбор — по копии без картинок (OoxmlSlim), хранится присланный файл
                    byte[] parse = bytes.length > PARSE_MAX ? null : OoxmlSlim.forParsing(bytes, partsMax);
                    if (parse == null) {
                        return file(n, mime, bytes, sha, kind, ReplyParseStatus.NOT_PARSED,
                                "Файл слишком большой для разбора — откройте его кнопкой «Скачать»", null, null);
                    }
                    Optional<ReplyTable> t = ReplyTableReader.read(parse, kind, learned);
                    return t.isPresent()
                            ? file(n, mime, bytes, sha, kind, ReplyParseStatus.PARSED, null, t.get(), null)
                            : file(n, mime, bytes, sha, kind, ReplyParseStatus.NO_TABLE,
                                    "Таблица с наименованием и ценой не найдена — введите цены по документу", null, null);
                }
                case PDF -> {
                    return file(n, mime, bytes, sha, kind, ReplyParseStatus.NOT_PARSED, null, null, pdf.pageCount(bytes));
                }
                default -> {
                    return file(n, mime, bytes, sha, kind, ReplyParseStatus.NOT_PARSED, null, null, null);
                }
            }
        } catch (Exception | Error e) {
            log.warn("Файл ответа поставщика не разобран ({}): {}", kind, e.getClass().getSimpleName());
            return file(n, mime, bytes, sha, kind, ReplyParseStatus.FAILED,
                    kind == ReplyFileKind.PDF ? "PDF не открылся — скачайте файл" : "Файл не открылся: защищён паролем или повреждён",
                    null, null);
        }
    }

    static PreparedFile tooLarge(MailFile f) {
        String n = fileName(f.name());
        return new PreparedFile(n, f.mimeType(), null, null, null, kind(n, f.mimeType(), new byte[0]),
                ReplyParseStatus.TOO_LARGE, "Файл больше предела — откройте его в почте", null, null);
    }

    /** Имя файла для хранения: без пути (старые браузеры шлют «C:\…\КП.xlsx»), без U+0000 (PostgreSQL его не хранит). */
    public static String fileName(String name) {
        String n = name == null ? "" : name.replace("\0", "").replaceAll(".*[/\\\\]", "").trim();
        return n.isEmpty() ? "файл" : cut(n, 255);
    }

    public static ReplyFileKind kind(String name, String mime, byte[] b) {
        String n = name.toLowerCase(Locale.ROOT), m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (n.endsWith(".xlsx") || n.endsWith(".xls") || m.contains("spreadsheet") || m.equals("application/vnd.ms-excel")) return ReplyFileKind.EXCEL;
        if (n.endsWith(".docx") || n.endsWith(".doc") || m.contains("wordprocessing") || m.equals("application/msword")) return ReplyFileKind.WORD;
        if (n.endsWith(".pdf") || m.equals("application/pdf") || (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F')) return ReplyFileKind.PDF;
        if (n.matches(".*\\.(jpe?g|png|webp|gif)$") || m.matches("image/(jpeg|png|webp|gif)")) return ReplyFileKind.IMAGE;
        return ReplyFileKind.OTHER;
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedFile file(String n, String mime, byte[] b, String sha, ReplyFileKind kind, ReplyParseStatus st,
                                     String note, ReplyTable t, Integer pages) {
        return new PreparedFile(n, mime == null ? null : cut(mime, 100), (long) b.length, b, sha, kind, st, note, t, pages);
    }

    static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
