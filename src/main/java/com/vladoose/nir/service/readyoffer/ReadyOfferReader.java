package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.HeaderDictionary;
import com.vladoose.nir.service.supplierreply.OoxmlSlim;
import com.vladoose.nir.service.supplierreply.ReplyPdfPages;
import com.vladoose.nir.service.supplierreply.ReplyTableReader;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import com.vladoose.nir.util.PdfTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Файл готового КП → итог разбора (спека 2026-10-19 §4): Word / Excel — все таблицы, строки, «ИТОГО», номер и дата из
 * текста; PDF — страницы и номер с датой по первой странице; картинка — только номер по имени файла. Любой сбой — FAILED
 * с причиной, а не исключение: КП всё равно заводится с файлом (§8). Разбор Word / Excel — по копии без картинок
 * (OoxmlSlim), как у ответов поставщиков.
 */
@Component
public class ReadyOfferReader {

    private static final Logger log = LoggerFactory.getLogger(ReadyOfferReader.class);

    public record Result(ReplyFileKind kind, ReplyParseStatus status, String note, List<ReplyTable> tables,
                         List<OfferFileLine> lines, BigDecimal fileTotal, String foreignCurrency, OfferHeader header,
                         Integer pages) {}

    private final HeaderDictionary dictionary;
    private final ReplyPdfPages pdf;

    @Autowired
    public ReadyOfferReader(HeaderDictionary dictionary, ReplyPdfPages pdf) {
        this.dictionary = dictionary;
        this.pdf = pdf;
    }

    public Result read(String fileName, String mime, byte[] bytes, String marketCurrency) {
        return read(fileName, mime, bytes, marketCurrency, dictionary.learned());
    }

    Result read(String fileName, String mime, byte[] bytes, String marketCurrency, Map<String, LineField> learned) {
        ReplyFileKind kind = SupplierReplyFiles.kind(fileName, mime, bytes);
        OfferHeader byName = OfferHeader.find(List.of(fileName));
        try {
            switch (kind) {
                case EXCEL, WORD -> {
                    byte[] parse = bytes.length > SupplierReplyFiles.PARSE_MAX ? null
                            : OoxmlSlim.forParsing(bytes, SupplierReplyFiles.PARTS_MAX);
                    if (parse == null) {
                        return empty(kind, ReplyParseStatus.NOT_PARSED, "Файл слишком большой для разбора — впишите цены по документу",
                                byName, null);
                    }
                    OfferHeader header = merge(OfferHeader.find(ReplyTableReader.documentLines(parse, kind)), byName);
                    List<ReplyTableReader.ReadTable> all = ReplyTableReader.readAll(parse, kind, learned);
                    if (all.isEmpty()) {
                        return empty(kind, ReplyParseStatus.NO_TABLE, "Таблица с наименованием и ценой не найдена — впишите цены по документу",
                                header, null);
                    }
                    OfferFileLines.Lines lines = OfferFileLines.of(all, marketCurrency);
                    return new Result(kind, ReplyParseStatus.PARSED, null, all.stream().map(ReplyTableReader.ReadTable::table).toList(),
                            lines.lines(), lines.fileTotal(), lines.foreignCurrency(), header, null);
                }
                case PDF -> {
                    int pages = pdf.pageCount(bytes);
                    String text = PdfTextExtractor.firstPage(bytes);
                    OfferHeader header = merge(text == null ? OfferHeader.NONE : OfferHeader.find(List.of(text.split("\\R"))), byName);
                    return empty(kind, ReplyParseStatus.NOT_PARSED, null, header, pages);
                }
                default -> {
                    return empty(kind, ReplyParseStatus.NOT_PARSED, null, byName, null);
                }
            }
        } catch (Exception | Error e) {
            log.warn("Файл готового КП не разобран ({}): {}", kind, e.getClass().getSimpleName());
            return empty(kind, ReplyParseStatus.FAILED,
                    kind == ReplyFileKind.PDF ? "PDF не открылся — скачайте файл" : "Файл не открылся: защищён паролем или повреждён",
                    byName, null);
        }
    }

    private static Result empty(ReplyFileKind kind, ReplyParseStatus status, String note, OfferHeader header, Integer pages) {
        return new Result(kind, status, note, List.of(), List.of(), null, null, header, pages);
    }

    /** Номер и дата из документа; недостающее — из имени файла. */
    static OfferHeader merge(OfferHeader doc, OfferHeader name) {
        return new OfferHeader(doc.number() != null ? doc.number() : name.number(),
                doc.date() != null ? doc.date() : name.date());
    }
}
