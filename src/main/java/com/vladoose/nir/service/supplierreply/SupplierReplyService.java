package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.ReplyColumnRole;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.service.PrivateRequestImportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Файлы ответа поставщика и цены из них (спека 2026-10-15 §3.4): хранит файлы (один и тот же — один раз), подставляет
 * уверенные цены с 💡 в строки ответа (под блокировкой; ручную цену не трогает; свою прежнюю 💡 заменяет), снимает 💡
 * («Всё верно»), правит роли колонок, удаляет загруженные вручную файлы. Статус запроса после письма меняет приём почты
 * (markResponded), после загрузки и «Взять цены» — этот сервис. Гард рынка — явный (findById фильтр рынка не режет).
 */
@Service
public class SupplierReplyService {

    static final Set<String> CLOSED = Set.of("ACCEPTED", "REJECTED", "CLOSED");
    static final Set<String> RESPONDABLE = Set.of("CREATED", "SENT", "DECLINED", "RESPONDED");

    /**
     * Итог файлов письма — для строки уведомления (MailIngestWriter.fileLine). fileName — первый сохранённый файл,
     * parsedName — файл с разобранной таблицей (null — такого нет), unreadable — Excel / Word, который не разобрался,
     * closed — запрос закрыт: файлы сохранены, цены не трогали.
     */
    public record ReplyOutcome(List<Long> fileIds, String fileName, String parsedName, int applied, int positions,
                               int manualKept, String foreignCurrency, boolean viewOnly, boolean image, String unreadable,
                               boolean closed) {
        public static ReplyOutcome none() {
            return new ReplyOutcome(List.of(), null, null, 0, 0, 0, null, false, false, null, false);
        }
    }

    public record ApplyResult(int applied, int positions, int manualKept, String foreignCurrency) {}

    private final SupplierReplyFileRepository files;
    private final PriceRequestItemRepository items;
    private final PriceRequestRepository requests;
    private final SupplierReplyFiles replyFiles;
    private final PrivateRequestImportService importService;

    public SupplierReplyService(SupplierReplyFileRepository files, PriceRequestItemRepository items,
                                PriceRequestRepository requests, SupplierReplyFiles replyFiles,
                                PrivateRequestImportService importService) {
        this.files = files;
        this.items = items;
        this.requests = requests;
        this.replyFiles = replyFiles;
        this.importService = importService;
    }

    /** Письмо: сохранить новые файлы и подставить цены из лучшей таблицы. */
    @Transactional
    public ReplyOutcome ingest(PriceRequest pr, List<PreparedFile> prepared, String uploadedBy) {
        List<SupplierReplyFile> stored = new ArrayList<>();
        for (PreparedFile p : prepared) {
            if (p.sha256() != null && files.existsByPriceRequestIdAndSha256(pr.getId(), p.sha256())) continue;
            stored.add(files.save(entity(pr.getId(), p, uploadedBy)));
        }
        if (stored.isEmpty()) return ReplyOutcome.none();
        List<Long> ids = stored.stream().map(SupplierReplyFile::getId).toList();
        Optional<SupplierReplyFile> best = stored.stream().filter(f -> f.getParseStatus() == ReplyParseStatus.PARSED)
                .max(Comparator.comparingInt((SupplierReplyFile f) -> f.getParsed().score())
                        .thenComparingInt(f -> f.getParsed().rows().size()));
        boolean view = stored.stream().anyMatch(f -> f.getKind() == ReplyFileKind.PDF || f.getKind() == ReplyFileKind.IMAGE);
        boolean image = view && stored.stream().noneMatch(f -> f.getKind() == ReplyFileKind.PDF);
        String unreadable = stored.stream()
                .filter(f -> (f.getKind() == ReplyFileKind.EXCEL || f.getKind() == ReplyFileKind.WORD)
                        && f.getParseStatus() != ReplyParseStatus.PARSED)
                .map(SupplierReplyFile::getFileName).findFirst().orElse(null);
        String name = stored.get(0).getFileName();
        String parsedName = best.map(SupplierReplyFile::getFileName).orElse(null);
        boolean closed = CLOSED.contains(pr.getStatus());
        if (best.isEmpty() || closed) {
            return new ReplyOutcome(ids, name, parsedName, 0, pr.getItems().size(), 0, null, view, image, unreadable, closed);
        }
        ApplyResult a = apply(pr, best.get());
        return new ReplyOutcome(ids, name, parsedName, a.applied(), a.positions(), a.manualKept(), a.foreignCurrency(),
                view, image, unreadable, false);
    }

    /** Письмо записано — файлам ставится ссылка на строку «Входящих». */
    @Transactional
    public void linkInbound(Collection<Long> fileIds, Long inboundId) {
        for (SupplierReplyFile f : files.findAllById(fileIds)) f.setInboundEmailId(inboundId);
    }

    /** «Ответил»: статус, дата ответа и (если есть) текст письма в примечании запроса; закрытые запросы не трогает. */
    public static void markResponded(PriceRequest pr, String displayText) {
        if (!RESPONDABLE.contains(pr.getStatus())) return;
        pr.setStatus("RESPONDED");
        pr.setResponseDate(LocalDate.now());
        if (displayText != null) pr.setNote(displayText.length() <= 4000 ? displayText : displayText.substring(0, 4000));
    }

    public PriceRequest guard(Long prId) {
        PriceRequest pr = requests.findById(prId).orElseThrow(() -> new NotFoundException("Запрос цен не найден: " + prId));
        if (pr.getMarket() != null && pr.getMarket() != MarketContext.get()) throw new NotFoundException("Запрос цен не найден: " + prId);
        return pr;
    }

    /** Файл, загруженный вручную (КП из WhatsApp, письмо без метки): сохранить и, если таблица разобрана, подставить. */
    @Transactional
    public SupplierReplyFile upload(Long prId, String name, String mime, byte[] bytes, String user) {
        PriceRequest pr = guard(prId);
        if (bytes == null || bytes.length == 0) throw new BadRequestException("Файл пустой");
        PreparedFile p = replyFiles.prepare(name, mime, bytes);
        if (files.existsByPriceRequestIdAndSha256(pr.getId(), p.sha256())) {
            throw new BadRequestException("Этот файл уже есть в ответе поставщика");
        }
        SupplierReplyFile f = files.save(entity(pr.getId(), p, user == null ? "admin" : user));
        if (f.getParseStatus() == ReplyParseStatus.PARSED && !CLOSED.contains(pr.getStatus())) {
            if (apply(pr, f).applied() > 0) markResponded(pr, null);
        }
        return f;
    }

    /** «Взять цены из этого файла». */
    @Transactional
    public ApplyResult applyFile(Long prId, Long fileId) {
        PriceRequest pr = guard(prId);
        SupplierReplyFile f = fileOf(pr, fileId);
        if (f.getParseStatus() != ReplyParseStatus.PARSED || f.getParsed() == null) {
            throw new BadRequestException("У файла нет разобранной таблицы — введите цены по документу");
        }
        if (CLOSED.contains(pr.getStatus())) throw new BadRequestException("Запрос закрыт — цены не подставляются");
        ApplyResult a = apply(pr, f);
        if (a.applied() > 0) markResponded(pr, null);
        return a;
    }

    /** Роли колонок от оператора: таблица файла правится, словарь учится, цены из файла пересчитываются. */
    @Transactional
    public void setColumns(Long prId, Long fileId, List<ReplyColumnRole> roles) {
        PriceRequest pr = guard(prId);
        SupplierReplyFile f = fileOf(pr, fileId);
        ReplyTable t = f.getParsed();
        if (t == null) throw new BadRequestException("У файла нет разобранной таблицы");
        Map<Integer, LineField> map = new HashMap<>();
        List<ColumnMapping> learn = new ArrayList<>();
        for (ReplyColumnRole r : roles == null ? List.<ReplyColumnRole>of() : roles) {
            if (r.role() == null || r.index() < 0 || r.index() >= t.columns().size()) {
                throw new BadRequestException("Неверная колонка: " + r.index());
            }
            map.put(r.index(), r.role());
            ColumnMapping m = new ColumnMapping();
            m.setHeader(t.columns().get(r.index()).header());
            m.setField(r.role());
            learn.add(m);
        }
        f.setParsed(t.withRoles(map));
        importService.learn(learn);
        revertAuto(pr.getId(), f.getId(), false);
        if (!CLOSED.contains(pr.getStatus()) && apply(pr, f).applied() > 0) markResponded(pr, null);
    }

    /** Удалить загруженный вручную файл; несверенные цены из него снимаются. Файл из письма — часть ответа, не удаляется. */
    @Transactional
    public void deleteFile(Long prId, Long fileId) {
        PriceRequest pr = guard(prId);
        SupplierReplyFile f = fileOf(pr, fileId);
        if (f.getUploadedBy() == null) throw new BadRequestException("Файл из письма не удаляется — он часть ответа поставщика");
        revertAuto(pr.getId(), f.getId(), true);
        items.flush();
        files.delete(f);
    }

    /** «Всё верно» — снять 💡 со всех цен запроса. */
    @Transactional
    public int verify(Long prId) {
        guard(prId);
        return items.clearAuto(prId);
    }

    ApplyResult apply(PriceRequest pr, SupplierReplyFile f) {
        List<PriceRequestItem> its = items.lockByPriceRequestId(pr.getId());
        List<ReplyRowValues> rows = ReplyRowValues.of(f.getParsed());
        String market = pr.getMarket() == null ? null : pr.getMarket().currencyCode();
        String cur = tableCurrency(rows, f.getParsed());
        if (cur != null && market != null && !cur.equals(market)) return new ApplyResult(0, its.size(), 0, cur);
        Map<Integer, ReplyRowValues> byRow = new HashMap<>();
        List<ReplyMatcher.Row> mrows = new ArrayList<>();
        int order = 0;
        for (ReplyRowValues r : rows) {
            if (!r.participates()) continue;
            byRow.put(r.fileRow(), r);
            mrows.add(new ReplyMatcher.Row(r.fileRow(), order++, r.matchText(), r.quantity() == null ? null : r.quantity().value()));
        }
        Map<Long, PriceRequestItem> byId = new HashMap<>();
        List<ReplyMatcher.Position> pos = new ArrayList<>();
        for (int i = 0; i < its.size(); i++) {
            PriceRequestItem it = its.get(i);
            byId.put(it.getId(), it);
            pos.add(new ReplyMatcher.Position(it.getId(), i + 1, positionText(it), it.getRequestedQuantity()));
        }
        int applied = 0, manual = 0;
        for (ReplyMatcher.Match m : ReplyMatcher.match(pos, mrows)) {
            if (m.kind() != ReplyMatcher.Kind.AUTO) continue;
            ReplyRowValues r = byRow.get(m.fileRow());
            if (r == null || r.price() == null) continue;
            PriceRequestItem it = byId.get(m.itemId());
            if (it.getResponsePrice() != null && !it.isResponseAuto()) {
                manual++;
                continue;
            }
            it.setResponsePrice(r.price());
            it.setResponseAuto(true);
            it.setResponseFileId(f.getId());
            it.setResponseFileRow(r.fileRow());
            it.setResponseNote(note(f.getFileName(), r));
            applied++;
        }
        return new ApplyResult(applied, its.size(), manual, null);
    }

    /** Несверенные цены из файла — снять; unlinkAll — ещё и связь ручных цен с этим файлом (файл удаляется). */
    private void revertAuto(Long prId, Long fileId, boolean unlinkAll) {
        for (PriceRequestItem it : items.lockByPriceRequestId(prId)) {
            if (!fileId.equals(it.getResponseFileId())) continue;
            if (it.isResponseAuto()) {
                it.setResponsePrice(null);
                it.setResponseNote(null);
                it.setResponseAuto(false);
                it.setResponseFileId(null);
                it.setResponseFileRow(null);
            } else if (unlinkAll) {
                it.setResponseFileId(null);
                it.setResponseFileRow(null);
            }
        }
    }

    /** Наименование, бренд позиции и предложенная модель — то, что видит поставщик в письме. */
    public static String positionText(PriceRequestItem i) {
        TenderLot l = i.getTenderLot();
        MedEquipment e = i.getMedEquipment();
        return Stream.of(l == null ? null : l.getEquipName(), l == null ? null : l.getManufact(),
                        e == null ? null : e.getName(), e == null ? null : e.getManufact())
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" "));
    }

    /** Валюта таблицы: самая частая у строк с ценой, иначе по шапке. */
    static String tableCurrency(List<ReplyRowValues> rows, ReplyTable t) {
        return rows.stream().filter(r -> r.price() != null && r.currency() != null)
                .collect(Collectors.groupingBy(ReplyRowValues::currency, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(t.currency());
    }

    private static String note(String fileName, ReplyRowValues r) {
        String name = fileName.length() <= 120 ? fileName : fileName.substring(0, 120);
        StringBuilder s = new StringBuilder("Из файла «").append(name).append("», строка ").append(r.fileRow()).append('.');
        if (r.priceComputed()) s.append(" Цена посчитана из суммы.");
        if (r.note() != null) s.append(" Примечание поставщика: «").append(r.note().length() <= 300 ? r.note() : r.note().substring(0, 300)).append("».");
        return s.toString();
    }

    private SupplierReplyFile fileOf(PriceRequest pr, Long fileId) {
        return files.findById(fileId).filter(f -> pr.getId().equals(f.getPriceRequestId()))
                .orElseThrow(() -> new NotFoundException("Файл ответа не найден: " + fileId));
    }

    private static SupplierReplyFile entity(Long prId, PreparedFile p, String uploadedBy) {
        return SupplierReplyFile.builder().priceRequestId(prId).fileName(p.name()).mimeType(p.mimeType()).sizeBytes(p.size())
                .sha256(p.sha256()).data(p.bytes()).kind(p.kind()).parseStatus(p.status())
                .parseNote(p.note() == null ? null : (p.note().length() <= 300 ? p.note() : p.note().substring(0, 300)))
                .parsed(p.table()).pageCount(p.pageCount()).uploadedBy(uploadedBy).build();
    }
}
