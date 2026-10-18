package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyParseStatus;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.Units;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFChart;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * КП поставщика (.docx) с EMF-картинкой не открывался: POI принимал сжатую в сотни раз картинку (word/media) за
 * zip-бомбу. Разбор идёт по копии без картинок и вложенных объектов, части — без сжатия; распакованный объём всех частей
 * — своим подсчётом; архив проверяется до распаковки (число частей, повторы имён, перекрытия).
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
                List.of("1", "Облучатель ультрафиолетовый настенный", "1", "18 450,00")));
        d.addPictureData(EMF, Document.PICTURE_TYPE_EMF);
        byte[] docx = ReplyTableReaderTest.docx(d);

        PreparedFile p = prepare("КП.docx", docx);

        assertThat(p.status()).as(p.note()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(ReplyRowValues.of(p.table()).get(0).price()).isEqualByComparingTo("18450");
        assertThat(p.bytes()).isEqualTo(docx);   // хранится присланный файл, а не копия для разбора
        // защита POI от zip-бомб не ослаблена для всех (умолчания POI 5.2.5): ею закрыт и разбор смет клиник (D1)
        assertThat(ZipSecureFile.getMinInflateRatio()).isEqualTo(0.01);
        assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(0xFFFFFFFFL);
    }

    /** Диаграмма с книгой данных во вложении (word/embeddings): вложение пустое, документ всё равно открывается. */
    @Test
    void docxWithChartWorkbook_isParsed() throws Exception {
        XWPFDocument d = new XWPFDocument();
        ReplyTableReaderTest.table(d, List.of(List.of("Наименование", "Кол-во", "Цена, тг"), List.of("Кушетка смотровая", "2", "95 000")));
        XWPFChart chart = d.createChart(5 * Units.EMU_PER_CENTIMETER, 5 * Units.EMU_PER_CENTIMETER);
        XDDFChartData data = chart.createData(ChartTypes.PIE, null, null);
        data.addSeries(XDDFDataSourcesFactory.fromArray(new String[]{"2025", "2026"}),
                XDDFDataSourcesFactory.fromArray(new Double[]{1.0, 2.0}));
        chart.plot(data);
        byte[] docx = ReplyTableReaderTest.docx(d);
        assertThat(entries(docx).keySet()).anyMatch(n -> n.startsWith("word/embeddings/"));

        PreparedFile p = prepare("КП.docx", docx);
        assertThat(p.status()).as(p.note()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(ReplyRowValues.of(p.table()).get(0).price()).isEqualByComparingTo("95000");
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

    /**
     * Каталог архива указывает тысячами записей (разные имена) на одни и те же байты: частей больше, чем пускает POI
     * (ZipSecureFile.getMaxFileCount, 1000), — «не открылся» до распаковки, за миллисекунды. Данные здесь крошечные,
     * перекрытие предел сжатых байтов не превышает — держит именно число частей.
     */
    @ParameterizedTest
    @ValueSource(ints = {1001, 5000})
    void tooManyParts_failFast(int count) throws Exception {
        byte[] zip = sameData(count, Raw.stored("xl/x.xml", "<a/>".getBytes(StandardCharsets.UTF_8)));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> OoxmlSlim.forParsing(zip, 1_000_000)).isInstanceOf(IOException.class);
        PreparedFile p = prepare("КП.xlsx", zip);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(p.status()).isEqualTo(ReplyParseStatus.FAILED);
        assertThat(ms).as("мс").isLessThan(1000);
    }

    /**
     * Записи каталога (в пределах 1000) указывают на один и тот же блок: ≈ 1 МБ пустых блоков deflate — распаковываются
     * в ничто, предел распакованного не тратится, а распаковщик прошёл бы их сотни раз (письмо надолго заняло бы
     * единственный поток приёма почты). Сжатых данных у частей больше, чем весь файл, — «не открылся», без распаковки.
     */
    @Test
    void overlappingParts_failFast() throws Exception {
        byte[] block = emptyDeflateBlocks(800_000);
        byte[] zip = sameData(500, new Raw("word/x.xml", ZipEntry.DEFLATED, block, 0, 0));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> OoxmlSlim.forParsing(zip, 1_000_000)).isInstanceOf(IOException.class);
        PreparedFile p = prepare("КП.docx", zip);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(p.status()).isEqualTo(ReplyParseStatus.FAILED);
        assertThat(ms).as("мс").isLessThan(1000);
    }

    /** Одна такая запись (без перекрытия) — как обычная часть: проверка не ломает настоящие архивы. */
    @Test
    void singleEmptyBlocksPart_isRead() throws Exception {
        byte[] zip = sameData(1, new Raw("word/x.xml", ZipEntry.DEFLATED, emptyDeflateBlocks(1000), 0, 0));
        assertThat(entries(OoxmlSlim.forParsing(zip, 1_000_000))).containsOnlyKeys("word/x.xml");
    }

    /**
     * Две части с одним именем (CVE-2025-31672: Word покажет одну копию, а разобрали бы другую; POI 5.4 такие архивы
     * отвергает) — «не открылся», а не первая из двух.
     */
    @Test
    void duplicatePartName_failed() throws Exception {
        Raw a = Raw.stored("word/document.xml", "<w:document>A</w:document>".getBytes(StandardCharsets.UTF_8));
        Raw b = Raw.stored("word/document.xml", "<w:document>B</w:document>".getBytes(StandardCharsets.UTF_8));
        byte[] zip = rawZip(List.of(a, b), List.of(Map.entry("word/document.xml", 0), Map.entry("word/document.xml", 1)));
        assertThatThrownBy(() -> OoxmlSlim.forParsing(zip, 1_000_000)).isInstanceOf(IOException.class);
        assertThat(prepare("КП.docx", zip).status()).isEqualTo(ReplyParseStatus.FAILED);
    }

    /** Имена частей OOXML регистр не различают: «Word/Document.xml» — та же часть, что «word/document.xml». */
    @Test
    void duplicatePartNameOtherCase_failed() throws Exception {
        Raw a = Raw.stored("word/document.xml", "<w:document>A</w:document>".getBytes(StandardCharsets.UTF_8));
        Raw b = Raw.stored("Word/Document.xml", "<w:document>B</w:document>".getBytes(StandardCharsets.UTF_8));
        byte[] zip = rawZip(List.of(a, b), List.of(Map.entry("word/document.xml", 0), Map.entry("Word/Document.xml", 1)));
        assertThatThrownBy(() -> OoxmlSlim.forParsing(zip, 1_000_000)).isInstanceOf(IOException.class);
    }

    /** Запись архива «как есть»: имя, метод, сжатые байты, CRC и размер распакованного. */
    record Raw(String name, int method, byte[] data, long crc, long size) {
        static Raw stored(String name, byte[] content) {
            CRC32 crc = new CRC32();
            crc.update(content);
            return new Raw(name, ZipEntry.STORED, content, crc.getValue(), content.length);
        }
    }

    /** Архив, где каталог указывает count записями (имена «…1», «…2») на одни и те же данные. */
    static byte[] sameData(int count, Raw data) {
        List<Map.Entry<String, Integer>> dir = new ArrayList<>();
        String base = data.name().replace(".xml", "");
        for (int i = 1; i <= count; i++) dir.add(Map.entry(count == 1 ? data.name() : base + i + ".xml", 0));
        return rawZip(List.of(data), dir);
    }

    /**
     * Архив байт за байтом: locals — записи с данными (локальный заголовок + данные, по порядку), directory — записи
     * центрального каталога: имя и номер записи с данными, на которую она указывает (одну можно указать много раз).
     */
    static byte[] rawZip(List<Raw> locals, List<Map.Entry<String, Integer>> directory) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] offsets = new int[locals.size()];
        for (int i = 0; i < locals.size(); i++) {
            Raw r = locals.get(i);
            offsets[i] = out.size();
            byte[] name = r.name().getBytes(StandardCharsets.UTF_8);
            ByteBuffer h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(0x04034b50).putShort((short) 20).putShort((short) 0x0800).putShort((short) r.method())
                    .putShort((short) 0).putShort((short) 0x21).putInt((int) r.crc()).putInt(r.data().length)
                    .putInt((int) r.size()).putShort((short) name.length).putShort((short) 0);
            out.writeBytes(h.array());
            out.writeBytes(name);
            out.writeBytes(r.data());
        }
        int cdStart = out.size();
        for (Map.Entry<String, Integer> e : directory) {
            Raw r = locals.get(e.getValue());
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            ByteBuffer c = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
            c.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0x0800)
                    .putShort((short) r.method()).putShort((short) 0).putShort((short) 0x21).putInt((int) r.crc())
                    .putInt(r.data().length).putInt((int) r.size()).putShort((short) name.length).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(offsets[e.getValue()]);
            out.writeBytes(c.array());
            out.writeBytes(name);
        }
        int cdSize = out.size() - cdStart;
        ByteBuffer end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) directory.size())
                .putShort((short) directory.size()).putInt(cdSize).putInt(cdStart).putShort((short) 0);
        out.writeBytes(end.array());
        return out.toByteArray();
    }

    /**
     * Сжатые данные deflate из n пустых блоков (фиксированный Хаффман, 10 бит на блок: BFINAL, BTYPE = 01, конец блока
     * 0000000) и последнего пустого — распаковываются в ноль байт, а распаковщик проходит их все.
     */
    static byte[] emptyDeflateBlocks(int n) {
        byte[] out = new byte[((n + 1) * 10 + 7) / 8];
        int pos = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n) out[pos >> 3] |= (byte) (1 << (pos & 7));   // BFINAL у последнего
            pos++;
            out[pos >> 3] |= (byte) (1 << (pos & 7));               // BTYPE = 01: младший бит — 1
            pos += 2;
            pos += 7;                                               // символ 256 (конец блока) — семь нулей
        }
        return out;
    }

    /** Сжатие deflate без обёртки zlib (как в zip). */
    static byte[] deflate(byte[] content) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        d.setInput(content);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
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
