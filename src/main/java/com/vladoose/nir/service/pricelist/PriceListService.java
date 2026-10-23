package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PriceListPatch;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.supplierreply.MoneyText;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Прайсы поставщиков (спека 2026-10-23 §5): загрузка — разбор вне транзакции и черновик; правка шапки и строк по полю;
 * «Сохранить прайс»; удаление. Гард — прайс своего рынка (findById фильтр рынка не режет, §14), поставщик своего рынка.
 */
@Service
public class PriceListService {

    static final Set<ReplyFileKind> KINDS = EnumSet.of(ReplyFileKind.EXCEL, ReplyFileKind.WORD, ReplyFileKind.PDF, ReplyFileKind.IMAGE);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_DISCOUNT = new BigDecimal("99.99");

    private final PriceListRepository lists;
    private final DistributorRepository distributors;
    private final SiteProductRepository products;
    private final MedEquipmentRepository equipment;
    private final PriceListReader reader;
    private final PriceListWriter writer;
    private final PriceListLinker linker;

    public PriceListService(PriceListRepository lists, DistributorRepository distributors, SiteProductRepository products,
                            MedEquipmentRepository equipment, PriceListReader reader, PriceListWriter writer, PriceListLinker linker) {
        this.lists = lists;
        this.distributors = distributors;
        this.products = products;
        this.equipment = equipment;
        this.reader = reader;
        this.writer = writer;
        this.linker = linker;
    }

    /** Закупка строки: цена нашей колонки × (100 − скидка) / 100 одной дробью до 0,01; цены нет — null. */
    public static BigDecimal ourPrice(List<BigDecimal> prices, Integer column, BigDecimal discountPct) {
        if (column == null || prices == null || column < 0 || column >= prices.size() || prices.get(column) == null) return null;
        BigDecimal d = discountPct == null ? BigDecimal.ZERO : discountPct;
        return prices.get(column).multiply(HUNDRED.subtract(d)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public PriceList guard(Long id) {
        PriceList p = lists.findById(id).orElseThrow(() -> new NotFoundException("Прайс не найден: " + id));
        if (p.getMarket() != MarketContext.get()) throw new NotFoundException("Прайс не найден: " + id);
        return p;
    }

    private Distributor supplier(Long id) {
        if (id == null) throw new BadRequestException("Выберите поставщика");
        Distributor d = distributors.findById(id).orElse(null);
        if (d == null || d.getMarket() != MarketContext.get()) throw new BadRequestException("Поставщик не найден");
        return d;
    }

    /** Загрузка (не транзакция: разбор PDFBox / POI — вне неё): тот же файл поставщика — прежний прайс (спека §5.1). */
    public Long upload(Long distributorId, String fileName, String mime, byte[] bytes, String user) {
        return create(supplier(distributorId), fileName, mime, bytes, null, user);
    }

    Long create(Distributor d, String fileName, String mime, byte[] bytes, Long inboundEmailId, String user) {
        if (bytes == null || bytes.length == 0) throw new BadRequestException("Файл пустой");
        if (bytes.length > PriceListReader.PARSE_MAX) throw new BadRequestException("Файл больше 15 МБ — уменьшите его и загрузите снова");
        String name = SupplierReplyFiles.fileName(fileName);
        ReplyFileKind kind = SupplierReplyFiles.kind(name, mime, bytes);
        if (!KINDS.contains(kind)) throw new BadRequestException("Прайс — файл Excel, Word, PDF или картинка");
        String sha = SupplierReplyFiles.sha256(bytes);
        Optional<PriceList> same = lists.findByDistributorIdAndSha256(d.getId(), sha);
        if (same.isPresent()) return same.get().getId();
        ParsedPriceList parsed = reader.read(name, kind, bytes);
        return writer.create(d, name, mime, bytes, sha, kind, parsed, inboundEmailId, user);
    }

    @Transactional
    public void patch(Long id, PriceListPatch in, String user) {
        PriceList p = guard(id);
        String v = in == null || in.value() == null ? null : in.value().strip();
        if (v != null && v.isEmpty()) v = null;
        switch (in == null || in.field() == null ? "" : in.field()) {
            case "DISTRIBUTOR" -> p.setDistributor(supplier(v == null ? null : parseLong(v)));
            case "LIST_DATE" -> {
                LocalDate d = date(v);
                if (d != null && p.getValidTo() != null && p.getValidTo().isBefore(d)) throw new BadRequestException("«Действует до» раньше даты прайса");
                p.setListDate(d);
            }
            case "VALID_TO" -> {
                LocalDate d = date(v);
                if (d != null && p.getListDate() != null && d.isBefore(p.getListDate())) throw new BadRequestException("«Действует до» раньше даты прайса");
                p.setValidTo(d);
            }
            case "CURRENCY" -> {
                String c = v == null ? null : v.toUpperCase(Locale.ROOT);
                if (c == null || !MoneyText.CHOOSABLE.contains(c)) throw new BadRequestException("Валюта — KZT, RUB, USD, EUR или CNY");
                p.setCurrency(c);
            }
            case "OUR_COLUMN" -> {
                int c = v == null ? -1 : (int) parseLong(v);
                if (c < 0 || c >= p.getColumns().size()) throw new BadRequestException("Нет такой колонки цены");
                p.setOurColumn(c);
                recompute(p);
            }
            case "DISCOUNT_PCT" -> {
                BigDecimal d = v == null ? BigDecimal.ZERO : MoneyText.amount(v);
                if (d == null || d.signum() < 0 || d.compareTo(MAX_DISCOUNT) > 0 || d.stripTrailingZeros().scale() > 2) {
                    throw new BadRequestException("Скидка — от 0 до 99,99 %");
                }
                p.setDiscountPct(d.setScale(2, RoundingMode.HALF_UP));
                recompute(p);
            }
            case "TERMS" -> {
                if (v != null && v.length() > 1000) throw new BadRequestException("Условия — не длиннее 1000 символов");
                p.setTerms(v);
            }
            default -> throw new BadRequestException("Неизвестное поле: " + (in == null ? null : in.field()));
        }
        p.setUpdatedAt(OffsetDateTime.now());
    }

    @Transactional
    public void patchRow(Long id, Long rowId, PriceListPatch in) {
        PriceList p = guard(id);
        PriceListItem r = row(p, rowId);
        String v = in == null || in.value() == null ? null : in.value().strip();
        if (v != null && v.isEmpty()) v = null;
        String field = in == null || in.field() == null ? "" : in.field();
        if (field.startsWith("PRICE:")) {
            int c = (int) parseLong(field.substring(6));
            if (c < 0 || c >= p.getColumns().size()) throw new BadRequestException("Нет такой колонки цены");
            BigDecimal price = v == null ? null : MoneyText.amount(v);
            if (v != null && (price == null || price.signum() < 0)) throw new BadRequestException("Цена — число не меньше нуля");
            List<BigDecimal> prices = new ArrayList<>(r.getPrices());
            while (prices.size() < p.getColumns().size()) prices.add(null);
            prices.set(c, price == null ? null : price.setScale(2, RoundingMode.HALF_UP));
            r.setPrices(prices);
            r.setOurPrice(ourPrice(prices, p.getOurColumn(), p.getDiscountPct()));
        } else {
            switch (field) {
                case "SECTION" -> r.setSection(v == null ? null : PriceTextLines.cut(v, 255));
                case "NAME" -> {
                    if (v == null) throw new BadRequestException("Наименование не может быть пустым");
                    r.setName(PriceTextLines.cut(v, 1000));
                    linker.link(r, p.getMarket());
                }
                case "MODEL" -> {
                    r.setModel(v == null ? null : PriceTextLines.cut(v, 100));
                    linker.link(r, p.getMarket());
                }
                case "ARTICLE" -> r.setArticle(v == null ? null : PriceTextLines.cut(v, 100));
                case "REG_TEXT" -> {
                    r.setRegText(v == null ? null : PriceTextLines.cut(v, 500));
                    r.setRegNumber(PriceListRegs.number(v));
                    r.setNoReg(PriceListRegs.none(v));
                }
                case "SITE_PRODUCT" -> {
                    Long pid = v == null ? null : parseLong(v);
                    if (pid != null) {
                        SiteProduct s = products.findById(pid).orElse(null);
                        if (s == null || s.getMarket() != p.getMarket() || s.getRemovedAt() != null) throw new BadRequestException("Товар не найден");
                    }
                    r.setSiteProductId(pid);
                    if (pid != null) r.setMedEquipmentId(null);
                }
                case "EQUIPMENT" -> {
                    Long eid = v == null ? null : parseLong(v);
                    if (eid != null) {
                        MedEquipment e = equipment.findById(eid).orElse(null);
                        if (e == null || e.getMarket() != p.getMarket()) throw new BadRequestException("Позиция каталога не найдена");
                    }
                    r.setMedEquipmentId(eid);
                    if (eid != null) r.setSiteProductId(null);
                }
                default -> throw new BadRequestException("Неизвестное поле: " + field);
            }
        }
        p.setUpdatedAt(OffsetDateTime.now());
    }

    @Transactional
    public Long addRow(Long id) {
        PriceList p = guard(id);
        int n = p.getItems().stream().mapToInt(PriceListItem::getLineNo).max().orElse(0);
        PriceListItem it = PriceListItem.builder().priceList(p).lineNo(n + 1).name("Новая строка")
                .prices(new ArrayList<>(Collections.nCopies(Math.max(p.getColumns().size(), 1), (BigDecimal) null))).build();
        if (p.getColumns().isEmpty()) {
            p.getColumns().add(new PriceListColumn(0, "Цена"));
            p.setOurColumn(0);
        }
        p.getItems().add(it);
        lists.saveAndFlush(p);
        return it.getId();
    }

    @Transactional
    public void deleteRow(Long id, Long rowId) {
        PriceList p = guard(id);
        PriceListItem r = row(p, rowId);
        p.getItems().remove(r);
        p.setUpdatedAt(OffsetDateTime.now());
    }

    @Transactional
    public void activate(Long id, String user) {
        PriceList p = guard(id);
        p.setStatus(PriceListStatus.ACTIVE);
        p.setActivatedBy(user);
        p.setActivatedAt(OffsetDateTime.now());
    }

    @Transactional
    public void delete(Long id) {
        lists.delete(guard(id));
    }

    private void recompute(PriceList p) {
        for (PriceListItem r : p.getItems()) r.setOurPrice(ourPrice(r.getPrices(), p.getOurColumn(), p.getDiscountPct()));
    }

    private static PriceListItem row(PriceList p, Long rowId) {
        return p.getItems().stream().filter(r -> r.getId().equals(rowId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Строка прайса не найдена: " + rowId));
    }

    private static LocalDate date(String v) {
        if (v == null) return null;
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Дата — ГГГГ-ММ-ДД");
        }
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Ожидалось число: " + v);
        }
    }
}
