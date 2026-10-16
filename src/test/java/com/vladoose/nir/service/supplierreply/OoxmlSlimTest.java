package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyParseStatus;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * КП «Рикта» (.docx) не открывался: POI принимал сжатую в сотни раз EMF-картинку (word/media) за zip-бомбу. Разбор идёт
 * по копии без картинок и вложенных объектов, части — без сжатия; распакованный объём всех частей — своим подсчётом.
 */
class OoxmlSlimTest {

    /** Картинка, сжатая в сотни раз (EMF / BMP в КП и в логотипе бланка): у POI — «Zip bomb detected!». */
    static final byte[] EMF = new byte[300_000];

    static PreparedFile prepare(String name, byte[] bytes) {
        return new SupplierReplyFiles(null, null).prepare(name, null, bytes, Map.of());
    }

    @Test
    void docxWithCompressibleEmf_isParsed_globalZipSecurityUntouched() throws Exception {
        XWPFDocument d = new XWPFDocument();
        ReplyTableReaderTest.table(d, List.of(List.of("№", "Наименование", "Кол-во", "Цена, тг"),
                List.of("1", "Аппарат магнитоинфракрасный лазерный", "1", "52 800,00")));
        d.addPictureData(EMF, Document.PICTURE_TYPE_EMF);
        byte[] docx = ReplyTableReaderTest.docx(d);

        PreparedFile p = prepare("КП.docx", docx);

        assertThat(p.status()).as(p.note()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(ReplyRowValues.of(p.table()).get(0).price()).isEqualByComparingTo("52800");
        assertThat(p.bytes()).isEqualTo(docx);   // хранится присланный файл, а не копия для разбора
        // защита POI от zip-бомб не ослаблена для всех (умолчания POI 5.2.5): ею закрыт и разбор смет клиник (D1)
        assertThat(ZipSecureFile.getMinInflateRatio()).isEqualTo(0.01);
        assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(0xFFFFFFFFL);
    }

    /** Логотип-EMF бывает и в Excel (xl/media). */
    @Test
    void xlsxWithCompressibleLogo_isParsed() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("КП");
            String[][] rows = {{"Наименование", "Кол-во", "Цена, тг"}, {"Кушетка смотровая", "2", "95 000"}};
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.addPicture(EMF, Workbook.PICTURE_TYPE_EMF);
            wb.write(out);
            xlsx = out.toByteArray();
        }
        PreparedFile p = prepare("КП.xlsx", xlsx);
        assertThat(p.status()).as(p.note()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(ReplyRowValues.of(p.table()).get(0).price()).isEqualByComparingTo("95000");
    }

    /** Предел — по всем частям, а не только XML: двоичная часть (параметры принтера, макросы) тоже в памяти POI. */
    @Test
    void limitCountsNonXmlParts() throws Exception {
        byte[] base = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Кушетка", "95 000")));
        long xml = 0;
        for (Map.Entry<String, byte[]> e : entries(base).entrySet()) if (e.getKey().endsWith(".xml")) xml += e.getValue().length;
        Map<String, byte[]> parts = entries(base);
        String types = new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8).replace("<Default ",
                "<Default Extension=\"bin\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.printerSettings\"/><Default ");
        parts.put("[Content_Types].xml", types.getBytes(StandardCharsets.UTF_8));
        parts.put("xl/printerSettings/printerSettings1.bin", noise(20_000));
        byte[] withBin = zip(parts, ZipEntry.DEFLATED);

        assertThat(new SupplierReplyFiles(null, null, xml + 1000).prepare("kp.xlsx", null, withBin, Map.of()).status())
                .isEqualTo(ReplyParseStatus.NOT_PARSED);
        assertThat(new SupplierReplyFiles(null, null, xml + 30_000).prepare("kp.xlsx", null, withBin, Map.of()).status())
                .isEqualTo(ReplyParseStatus.PARSED);
    }

    /**
     * XML-часть, сжатая в сотни раз (раздутые стили Excel — тысячи одинаковых записей): в копии части без сжатия, проверка
     * сжатия у POI не срабатывает ложно.
     */
    @Test
    void repetitiveXmlPart_isParsed() throws Exception {
        Map<String, byte[]> parts = entries(ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Кушетка", "95 000"))));
        parts.put("customXml/item1.xml", ("<a>" + "<b/>".repeat(100_000) + "</a>").getBytes(StandardCharsets.UTF_8));
        PreparedFile p = prepare("kp.xlsx", zip(parts, ZipEntry.DEFLATED));
        assertThat(p.status()).as(p.note()).isEqualTo(ReplyParseStatus.PARSED);
    }

    /** Картинки и вложенные объекты — пустые (имена на месте: связи не ломаются), остальное — как было, без сжатия. */
    @Test
    void mediaAndEmbeddingsEmptied_restKept_stored() throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        parts.put("word/document.xml", "<w:document>Цена, тг</w:document>".getBytes(StandardCharsets.UTF_8));
        parts.put("word/media/image1.emf", EMF);
        parts.put("xl/embeddings/oleObject1.bin", noise(5_000));
        parts.put("docProps/thumbnail.jpeg", noise(300));
        byte[] slim = OoxmlSlim.forParsing(zip(parts, ZipEntry.DEFLATED), 1_000_000);

        Map<String, byte[]> out = new LinkedHashMap<>();
        List<Integer> methods = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(slim))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                methods.add(e.getMethod());
                out.put(e.getName(), in.readAllBytes());
            }
        }
        assertThat(out.keySet()).containsExactlyElementsOf(parts.keySet());
        assertThat(out.get("word/media/image1.emf")).isEmpty();
        assertThat(out.get("xl/embeddings/oleObject1.bin")).isEmpty();
        assertThat(out.get("word/document.xml")).isEqualTo(parts.get("word/document.xml"));
        assertThat(out.get("docProps/thumbnail.jpeg")).isEqualTo(parts.get("docProps/thumbnail.jpeg"));
        assertThat(methods).containsOnly(ZipEntry.STORED);
    }

    /** Предел — по фактически распакованным байтам: заявленный в каталоге архива размер может врать. */
    @Test
    void limitCountsActualBytes_notDeclared() throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("xl/sharedStrings.xml", new byte[50_000]);
        byte[] lying = declareSize(zip(parts, ZipEntry.DEFLATED), "xl/sharedStrings.xml", 10);

        assertThat(OoxmlSlim.forParsing(lying, 1_000)).isNull();
        assertThat(OoxmlSlim.forParsing(lying, 60_000)).isNotNull();
    }

    /** Не zip (.xls, .doc) — как есть. */
    @Test
    void ole2PassesAsIs() throws Exception {
        byte[] doc = ReplyTableReaderTest.res("price-table.doc");
        assertThat(OoxmlSlim.forParsing(doc, 1)).isSameAs(doc);
    }

    static Map<String, byte[]> entries(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }

    static byte[] zip(Map<String, byte[]> parts, int method) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buf)) {
            out.setMethod(method);
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return buf.toByteArray();
    }

    /** Байты, которые почти не сжимаются. */
    static byte[] noise(int n) {
        byte[] b = new byte[n];
        long x = 42;
        for (int i = 0; i < n; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            b[i] = (byte) (x >>> 56);
        }
        return b;
    }

    /** Поправить размер записи в центральном каталоге архива (распакованный, 4 байта со смещения 24). */
    static byte[] declareSize(byte[] zip, String name, int size) {
        byte[] b = zip.clone();
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + 46 + n.length <= b.length; i++) {
            if (b[i] != 'P' || b[i + 1] != 'K' || b[i + 2] != 1 || b[i + 3] != 2) continue;
            int len = (b[i + 28] & 0xff) | (b[i + 29] & 0xff) << 8;
            if (len != n.length || !new String(b, i + 46, len, StandardCharsets.UTF_8).equals(name)) continue;
            for (int k = 0; k < 4; k++) b[i + 24 + k] = (byte) (size >>> (8 * k));
            return b;
        }
        throw new IllegalStateException("нет записи " + name);
    }
}
