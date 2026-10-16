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
import com.vladoose.nir.util.DocFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Файлы ответа поставщика и цены из них (спека 2026-10-15 §3.4): хранит файлы (один и тот же — один раз), подставляет
 * уверенные цены с 💡 в строки ответа (под блокировкой; ручную цену не трогает; свою прежнюю 💡 заменяет), снимает 💡
 * («Всё верно»), правит роли колонок и курс файла в чужой валюте (цены × курс), удаляет загруженные вручную файлы. Статус запроса после письма меняет приём почты
 * (markResponded), после загрузки и «Взять цены» — этот сервис. Гард рынка — явный (findById фильтр рынка не режет).
 */
@Service
public class SupplierReplyService {

    static final Set<String> CLOSED = Set.of("ACCEPTED", "REJECTED", "CLOSED");
    static final Set<String> RESPONDABLE = Set.of("CREATED", "SENT", "DECLINED", "RESPONDED");
    /** Курс — не больше 100 000 валюты запроса за единицу валюты файла (USD → KZT ≈ 500; колонка — NUMERIC(14,6)). */
    static final BigDecimal MAX_RATE = new BigDecimal("100000");

    /** Курс файла к валюте запроса: 1 currency = rate валюты рынка. */
    public record FileRate(String currency, BigDecimal rate) {}

    /**
     * Итог файлов письма — для строки уведомления (MailIngestWriter.fileLine). fileName — первый сохранённый файл,
     * parsedName — файл с разобранной таблицей (null — такого нет), unreadable — Excel / Word, который не разобрался,
     * closed — запрос закрыт: файлы сохранены, цены не трогали; rate — курс запроса, по которому подставлены цены.
     */
    public record ReplyOutcome(List<Long> fileIds, String fileName, String parsedName, int applied, int positions,
                               int manualKept, String foreignCurrency, boolean viewOnly, boolean image, String unreadable,
                               boolean closed, BigDecimal rate) {
        public static ReplyOutcome none() {
            return new ReplyOutcome(List.of(), null, null, 0, 0, 0, null, false, false, null, false, null);
        }
    }

    /** rate — курс, по которому подставлены цены в чужой валюте (null — пересчёта не было). */
    public record ApplyResult(int applied, int positions, int manualKept, String foreignCurrency, BigDecimal rate) {}

    /**
     * Итог курса: applied — цен подставлено (в том числе сверенных и выбранных, пошедших за курсом), removed — снято
     * (курс снят), keptManual — ручных цен не тронуто, closed — запрос закрыт: курс сохранён, цены не трогали.
     */
    public record RateResult(int applied, int removed, int keptManual, boolean closed) {}

    /** Цены файла по прежнему курсу, которые никто не правил: skip — их не трогает сопоставление. */
    private record Follow(Set<Long> skip, int moved, int removed, int kept) {}

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
        for (SupplierReplyFile f : stored) inheritRate(pr, f);
        boolean closed = CLOSED.contains(pr.getStatus());
        if (best.isEmpty() || closed) {
            return new ReplyOutcome(ids, name, parsedName, 0, pr.getItems().size(), 0, null, view, image, unreadable, closed, null);
        }
        ApplyResult a = apply(pr, best.get());
        return new ReplyOutcome(ids, name, parsedName, a.applied(), a.positions(), a.manualKept(), a.foreignCurrency(),
                view, image, unreadable, false, a.rate());
    }

    /**
     * Исправленное КП в той же чужой валюте (второе письмо, загрузка вручную) берёт курс, уже вписанный в этом запросе
     * (последний): иначе оператор вписывал бы его заново на каждое письмо (решение ревью ветки курса).
     */
    private void inheritRate(PriceRequest pr, SupplierReplyFile f) {
        if (f.getParsed() == null || f.getRate() != null) return;
        String market = pr.getMarket() == null ? null : pr.getMarket().currencyCode();
        String cur = tableCurrency(ReplyRowValues.of(f.getParsed()), f.getParsed());
        if (cur == null || market == null || cur.equals(market)) return;
        List<Object[]> rates = files.findRatesOfRequest(pr.getId(), cur);
        if (rates.isEmpty()) return;
        f.setRate((BigDecimal) rates.get(0)[0]);
        f.setRateCurrency(cur);
        f.setRateSetAt((OffsetDateTime) rates.get(0)[1]);
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
        inheritRate(pr, f);
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

    /**
     * Роли колонок от оператора: таблица файла правится, цены из файла пересчитываются. Словарь учится только на
     * колонках, роль которых оператор сменил: экран присылает роли всех колонок, и догадки по нетронутым стали бы
     * «выученными» — в том числе для смет клиник (словарь общий). У закрытого запроса цены не трогаются (спека §3.4).
     */
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
            if (r.role() == t.columns().get(r.index()).role()) continue;
            ColumnMapping m = new ColumnMapping();
            m.setHeader(t.columns().get(r.index()).header());
            m.setField(r.role());
            learn.add(m);
        }
        f.setParsed(t.withRoles(map));
        importService.learn(learn);
        if (CLOSED.contains(pr.getStatus())) return;
        revertAuto(pr.getId(), f.getId(), false);
        if (apply(pr, f).applied() > 0) markResponded(pr, null);
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

    /**
     * Курс пересчёта цен файла в валюту запроса (поле на экране «Ответ поставщика», 2026-10-16): запоминается у файла,
     * цены из файла пересчитываются — свои 💡 заново; цены по прежнему курсу, которые никто не правил (сверенные «Всё
     * верно», выбранные «Другая строка ▾»), идут за курсом со своей строкой и снова 💡; правленные руками не трогаются;
     * null — курс снят, цены по нему уходят. У закрытого запроса курс запоминается, цены не трогаются (как роли колонок,
     * спека §3.4).
     */
    @Transactional
    public RateResult setRate(Long prId, Long fileId, BigDecimal rate) {
        PriceRequest pr = guard(prId);
        SupplierReplyFile f = fileOf(pr, fileId);
        if (f.getParsed() == null) throw new BadRequestException("У файла нет разобранной таблицы — курс не к чему применить");
        String market = pr.getMarket() == null ? null : pr.getMarket().currencyCode();
        List<ReplyRowValues> rows = ReplyRowValues.of(f.getParsed());
        String cur = tableCurrency(rows, f.getParsed());
        if (cur == null || market == null || cur.equals(market)) {
            throw new BadRequestException("Цены в файле — в валюте запроса, курс не нужен");
        }
        if (MoneyText.MIXED.equals(cur)) {
            throw new BadRequestException("Валюта цен в файле не ясна — в документе названы разные валюты: курс не применить, впишите цены вручную");
        }
        if (rate != null) {
            if (rate.signum() <= 0) throw new BadRequestException("Курс — число больше нуля");
            if (rate.compareTo(MAX_RATE) > 0) throw new BadRequestException("Курс слишком большой — не больше 100 000");
            if (rate.stripTrailingZeros().scale() > 6) throw new BadRequestException("Курс — не больше 6 знаков после запятой");
        }
        FileRate old = fileRate(f.getRate(), f.getRateCurrency(), cur, market);
        f.setRate(rate);
        f.setRateCurrency(rate == null ? null : cur);
        f.setRateSetAt(rate == null ? null : OffsetDateTime.now());
        if (CLOSED.contains(pr.getStatus())) return new RateResult(0, 0, 0, true);
        int reverted = revertAuto(pr.getId(), f.getId(), false);
        Follow follow = follow(pr, f, rows, old, rate == null ? null : new FileRate(cur, rate), market);
        ApplyResult a = rate == null ? null : apply(pr, f, follow.skip());
        int applied = (a == null ? 0 : a.applied()) + follow.moved();
        if (applied > 0) markResponded(pr, null);
        return new RateResult(applied, rate == null ? reverted + follow.removed() : follow.removed(),
                (a == null ? 0 : a.manualKept()) + follow.kept(), false);
    }

    /**
     * Цены файла по прежнему курсу, которые никто не правил (равны пересчёту своей строки): идут за новым курсом со
     * своей строкой — снова 💡 (новую цену ещё никто не сверял); тот же курс — остаются как есть; курс снят — уходят.
     * Цена, правленная руками (не равна пересчёту), не трогается. Все такие позиции сопоставление дальше не трогает.
     */
    private Follow follow(PriceRequest pr, SupplierReplyFile f, List<ReplyRowValues> rows, FileRate old, FileRate now, String market) {
        Set<Long> skip = new HashSet<>();
        if (old == null) return new Follow(skip, 0, 0, 0);
        Map<Integer, ReplyRowValues> byRow = new HashMap<>();
        for (ReplyRowValues r : rows) byRow.put(r.fileRow(), r);
        int moved = 0, removed = 0, kept = 0;
        for (PriceRequestItem it : items.lockByPriceRequestId(pr.getId())) {
            if (!f.getId().equals(it.getResponseFileId()) || it.isResponseAuto() || it.getResponsePrice() == null) continue;
            ReplyRowValues r = byRow.get(it.getResponseFileRow());
            if (r == null || r.price() == null || !converted(r, market, old)) continue;
            skip.add(it.getId());
            BigDecimal was = convert(r.price(), old.rate());
            if (was == null || was.compareTo(it.getResponsePrice()) != 0) {
                kept++;
                continue;
            }
            BigDecimal next = now == null ? null : convert(r.price(), now.rate());
            if (next != null && next.compareTo(it.getResponsePrice()) == 0) continue;   // тот же курс — сверенное остаётся сверенным
            if (next == null) {
                clear(it);
                removed++;
                continue;
            }
            it.setResponsePrice(next);
            it.setResponseAuto(true);
            it.setResponseNote(note(f.getFileName(), r, next, now, market));
            moved++;
        }
        return new Follow(skip, moved, removed, kept);
    }

    /** «Всё верно» — снять 💡 со всех цен запроса. */
    @Transactional
    public int verify(Long prId) {
        guard(prId);
        return items.clearAuto(prId);
    }

    ApplyResult apply(PriceRequest pr, SupplierReplyFile f) {
        return apply(pr, f, Set.of());
    }

    /** skip — позиции, которые сопоставление не трогает (цены по прежнему курсу, пошедшие за курсом). */
    private ApplyResult apply(PriceRequest pr, SupplierReplyFile f, Set<Long> skip) {
        List<PriceRequestItem> its = items.lockByPriceRequestId(pr.getId());
        List<ReplyRowValues> rows = ReplyRowValues.of(f.getParsed());
        String market = pr.getMarket() == null ? null : pr.getMarket().currencyCode();
        String cur = tableCurrency(rows, f.getParsed());
        FileRate rate = fileRate(f.getRate(), f.getRateCurrency(), cur, market);
        if (cur != null && market != null && !cur.equals(market) && rate == null) return new ApplyResult(0, its.size(), 0, cur, null);
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
        boolean byRate = false;
        for (ReplyMatcher.Match m : ReplyMatcher.match(pos, mrows)) {
            if (m.kind() != ReplyMatcher.Kind.AUTO || skip.contains(m.itemId())) continue;
            ReplyRowValues r = byRow.get(m.fileRow());
            if (r == null || r.price() == null) continue;
            // валюта строки (колонка «Валюта», «€» в ячейке) — не рынка: не подставлять, даже если таблица в валюте рынка;
            // валюта курса файла — пересчитать по курсу
            BigDecimal price = marketPrice(r, market, rate);
            if (price == null) continue;
            PriceRequestItem it = byId.get(m.itemId());
            if (it.getResponsePrice() != null && !it.isResponseAuto()) {
                manual++;
                continue;
            }
            it.setResponsePrice(price);
            it.setResponseAuto(true);
            it.setResponseFileId(f.getId());
            it.setResponseFileRow(r.fileRow());
            it.setResponseNote(note(f.getFileName(), r, price, converted(r, market, rate) ? rate : null, market));
            byRate |= converted(r, market, rate);
            applied++;
        }
        return new ApplyResult(applied, its.size(), manual, null, byRate ? rate.rate() : null);
    }

    /** Несверенные цены из файла — снять (сколько сняли); unlinkAll — ещё и связь ручных цен с этим файлом (файл удаляется). */
    private int revertAuto(Long prId, Long fileId, boolean unlinkAll) {
        int n = 0;
        for (PriceRequestItem it : items.lockByPriceRequestId(prId)) {
            if (!fileId.equals(it.getResponseFileId())) continue;
            if (it.isResponseAuto()) {
                clear(it);
                n++;
            } else if (unlinkAll) {
                it.setResponseFileId(null);
                it.setResponseFileRow(null);
            }
        }
        return n;
    }

    private static void clear(PriceRequestItem it) {
        it.setResponsePrice(null);
        it.setResponseNote(null);
        it.setResponseAuto(false);
        it.setResponseFileId(null);
        it.setResponseFileRow(null);
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

    private static String note(String fileName, ReplyRowValues r, BigDecimal price, FileRate rate, String market) {
        String name = fileName.length() <= 120 ? fileName : fileName.substring(0, 120);
        StringBuilder s = new StringBuilder("Из файла «").append(name).append("», строка ").append(r.fileRow()).append('.');
        if (rate != null) {   // пересчитано по курсу: исходная цена и курс — чтобы сверить с документом
            s.append(" Цена ").append(DocFormat.money(r.price())).append(' ').append(unit(r.currency())).append(" × курс ")
                    .append(rateText(rate.rate())).append(" = ").append(DocFormat.money(price)).append(' ').append(unit(market));
            if (s.charAt(s.length() - 1) != '.') s.append('.');   // «руб.» — без второй точки
        }
        if (r.priceComputed()) s.append(" Цена посчитана из суммы.");
        if (r.note() != null) s.append(" Примечание поставщика: «").append(r.note().length() <= 300 ? r.note() : r.note().substring(0, 300)).append("».");
        return s.toString();
    }

    /** Курс файла к валюте запроса: вписан для валюты файла, а она не валюта запроса; иначе null. */
    public static FileRate fileRate(BigDecimal rate, String rateCurrency, String tableCurrency, String market) {
        if (rate == null || tableCurrency == null || market == null || tableCurrency.equals(market)) return null;
        return tableCurrency.equals(rateCurrency) ? new FileRate(tableCurrency, rate) : null;
    }

    /** Строка в валюте курса (не запроса) — её цена пересчитывается. */
    public static boolean converted(ReplyRowValues r, String market, FileRate rate) {
        return rate != null && r.currency() != null && r.currency().equals(rate.currency()) && !r.currency().equals(market);
    }

    /** Цена строки в валюте запроса: своя (или валюта не названа) — как есть; валюта курса — × курс; другая чужая — null. */
    public static BigDecimal marketPrice(ReplyRowValues r, String market, FileRate rate) {
        if (r.price() == null) return null;
        if (r.currency() == null || market == null || r.currency().equals(market)) return r.price();
        return converted(r, market, rate) ? convert(r.price(), rate.rate()) : null;
    }

    /** Цена × курс до тиына / копейки (HALF_UP); не помещается в NUMERIC(15,2) — null (не подставлять). */
    static BigDecimal convert(BigDecimal price, BigDecimal rate) {
        BigDecimal v = price.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return v.precision() - v.scale() > 13 ? null : v;
    }

    /** «6», «5,85555» — курс без лишних нулей. */
    public static String rateText(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String unit(String code) {
        return "CNY".equals(code) ? "юань" : MoneyText.label(code);
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
