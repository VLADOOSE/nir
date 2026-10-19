package com.vladoose.nir.service.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.ExecutionMark;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.readyoffer.ReadyOfferHints;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * Запись исполнения сделки (спека 2026-10-19-deal-execution §6–§7, §9): поля договора и суммы, отметки, платежи,
 * поставщики, файлы. Гард — PrivateRequestService.findById (частная заявка текущего рынка, иначе 404). Ввод проверяется
 * до первой записи; отказ — 400 с русским текстом.
 */
@Service
public class DealExecutionService {

    /** distributorId пусто — платёж клиента. */
    public record PaymentInput(Long distributorId, BigDecimal amount, LocalDate paidOn, String note) {}

    private record Checked(BigDecimal amount, LocalDate paidOn, String note) {}

    private final PrivateRequestService privateRequests;
    private final DealExecutionRepository executions;
    private final DealSupplierRepository suppliers;
    private final DealPaymentRepository payments;
    private final DealFileRepository files;
    private final DistributorRepository distributors;
    private final DealExecutionLoader loader;
    private final ReadyOfferHints hints;
    private final ChatAttachmentRepository attachments;
    private final Clock clock = Clock.systemUTC();

    public DealExecutionService(PrivateRequestService privateRequests, DealExecutionRepository executions,
                                DealSupplierRepository suppliers, DealPaymentRepository payments, DealFileRepository files,
                                DistributorRepository distributors, DealExecutionLoader loader, ReadyOfferHints hints,
                                ChatAttachmentRepository attachments) {
        this.privateRequests = privateRequests;
        this.executions = executions;
        this.suppliers = suppliers;
        this.payments = payments;
        this.files = files;
        this.distributors = distributors;
        this.loader = loader;
        this.hints = hints;
        this.attachments = attachments;
    }

    /** Поле договора, суммы, предоплаты или «Со склада»; пустое значение — сброс (сумма — по КП, предоплата — 100 %). */
    @Transactional
    public Tender updateField(Long id, ExecutionField field, String value) {
        if (field == null) throw new BadRequestException("Не указано поле");
        Tender t = privateRequests.findById(id);
        String v = PrivateRequestService.blankToNull(value);
        Consumer<DealExecution> apply = switch (field) {
            case CONTRACT_KIND -> {
                ContractKind k = v == null ? null : ExecutionInput.kind(v);
                yield e -> e.setContractKind(k);
            }
            case CONTRACT_NUMBER -> {
                String n = ExecutionInput.text(v, ExecutionInput.NUMBER_MAX, "№ договора");
                yield e -> e.setContractNumber(n);
            }
            case CONTRACT_DATE -> {
                LocalDate d = v == null ? null : ExecutionInput.date(v, "Дата договора", today(t));
                yield e -> e.setContractDate(d);
            }
            case CONTRACT_NOTE -> {
                String n = ExecutionInput.text(v, ExecutionInput.NOTE_MAX, "Заметка");
                yield e -> e.setContractNote(n);
            }
            case PREPAY_PCT -> {
                BigDecimal p = v == null ? ExecutionInput.FULL_PREPAY : ExecutionInput.pct(v);
                yield e -> e.setPrepayPct(p);
            }
            case AMOUNT -> {
                BigDecimal a = v == null ? null : ExecutionInput.amount(v, "Сумма сделки");
                yield e -> e.setAmount(a);
            }
            case FROM_STOCK -> {
                boolean on = ExecutionInput.bool(v);
                if (on && supplierMarks(t.getId())) {
                    throw new BadRequestException("Сначала уберите отметки, платежи и файлы поставщиков");
                }
                yield e -> e.setFromStock(on);
            }
        };
        DealExecution e = ensureRow(t);
        apply.accept(e);
        e.setUpdatedBy(CurrentUser.name());
        return t;
    }

    /** Поставка или документы: дата и заметка; дата пуста — отметка снимается вместе с заметкой. */
    @Transactional
    public Tender setMark(Long id, ExecutionMark mark, String on, String note) {
        if (mark == null) throw new BadRequestException("Неизвестная отметка");
        Tender t = privateRequests.findById(id);
        LocalDate date = blank(on) ? null
                : ExecutionInput.date(on, mark == ExecutionMark.DELIVERY ? "Дата поставки" : "Дата документов", today(t));
        String n = date == null ? null : ExecutionInput.text(note, ExecutionInput.NOTE_MAX, "Заметка");
        DealExecution e = ensureRow(t);
        if (mark == ExecutionMark.DELIVERY) {
            e.setDeliveredOn(date);
            e.setDeliveryNote(n);
        } else {
            e.setDocsOn(date);
            e.setDocsNote(n);
        }
        e.setUpdatedBy(CurrentUser.name());
        return t;
    }

    @Transactional
    public Tender addPayment(Long id, PaymentInput in) {
        Tender t = privateRequests.findById(id);
        Checked c = check(t, in);
        Long supplierRow = null;
        if (in.distributorId() != null) {
            DealSupplier s = ensureSupplier(t, in.distributorId());
            if (s.isExcluded()) throw new BadRequestException("Поставщик отмечен «Не заказываем» — платёж ему не вносится");
            supplierRow = s.getId();
        }
        payments.save(DealPayment.builder().tenderId(t.getId()).dealSupplierId(supplierRow).amount(c.amount())
                .paidOn(c.paidOn()).note(c.note()).createdBy(CurrentUser.name()).build());
        return t;
    }

    /** Сумма, дата, заметка; кому платёж — не меняется. */
    @Transactional
    public Tender updatePayment(Long id, Long paymentId, PaymentInput in) {
        Tender t = privateRequests.findById(id);
        DealPayment p = payment(t, paymentId);
        Checked c = check(t, in);
        p.setAmount(c.amount());
        p.setPaidOn(c.paidOn());
        p.setNote(c.note());
        return t;
    }

    /** Файлы платежа уходят каскадом. */
    @Transactional
    public Tender deletePayment(Long id, Long paymentId) {
        Tender t = privateRequests.findById(id);
        payments.delete(payment(t, paymentId));
        return t;
    }

    private Checked check(Tender t, PaymentInput in) {
        if (in == null) throw new BadRequestException("Нет данных платежа");
        return new Checked(ExecutionInput.amount(in.amount(), "Сумма платежа"),
                ExecutionInput.date(in.paidOn(), "Дата платежа", today(t)),
                ExecutionInput.text(in.note(), ExecutionInput.NOTE_MAX, "Заметка"));
    }

    /** Платёж этой сделки, иначе 404. */
    DealPayment payment(Tender t, Long paymentId) {
        if (paymentId == null) throw new NotFoundException("Платёж не найден");
        return payments.findById(paymentId).filter(p -> p.getTenderId().equals(t.getId()))
                .orElseThrow(() -> new NotFoundException("Платёж не найден"));
    }

    DealExecution ensureRow(Tender t) {
        return executions.findById(t.getId())
                .orElseGet(() -> executions.save(DealExecution.builder().tenderId(t.getId()).build()));
    }

    /** Строка поставщика сделки; поставщик главного принятого КП заводится строкой (валюта рынка), чужой — 404. */
    DealSupplier ensureSupplier(Tender t, Long distributorId) {
        if (distributorId == null) throw new BadRequestException("Не указан поставщик");
        return suppliers.findByTenderIdAndDistributorId(t.getId(), distributorId).orElseGet(() -> {
            if (!loader.load(t).inOffer(distributorId)) throw new NotFoundException("Поставщика нет в сделке");
            return suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(distributorId)
                    .currency(market(t).currencyCode()).build());
        });
    }

    private boolean supplierMarks(Long tenderId) {
        return suppliers.existsByTenderIdAndOrderedOnIsNotNull(tenderId) || suppliers.existsByTenderIdAndShippedOnIsNotNull(tenderId)
                || payments.existsByTenderIdAndDealSupplierIdIsNotNull(tenderId)
                || files.existsByTenderIdAndStage(tenderId, DealFileStage.SUPPLIER);
    }

    LocalDate today(Tender t) {
        return ExecutionInput.today(clock, market(t).zone());
    }

    static Market market(Tender t) {
        return t.getMarket() == null ? MarketContext.get() : t.getMarket();
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
