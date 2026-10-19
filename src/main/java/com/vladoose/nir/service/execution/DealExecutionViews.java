package com.vladoose.nir.service.execution;

import com.vladoose.nir.dto.response.DealExecutionView;
import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.entity.DealExecution;
import com.vladoose.nir.entity.DealPayment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.vladoose.nir.service.execution.DealExecutionData.positive;

/** Вид раздела «Исполнение сделки» из загрузки и метаданных файлов (спека 2026-10-19-deal-execution §9). */
public final class DealExecutionViews {

    private DealExecutionViews() {}

    public static DealExecutionView toView(DealExecutionData d, List<DealFileMeta> files) {
        ExecutionSnapshot s = d.snapshot();
        DealExecution e = d.row();
        DealExecutionData.MainOffer m = d.mainOffer();
        Map<Long, Long> distributorByRow = new HashMap<>();
        for (DealExecutionData.SupplierLine l : d.supplierLines()) {
            if (l.rowId() != null) distributorByRow.put(l.rowId(), l.distributorId());
        }
        boolean own = e != null && e.getAmount() != null;
        return new DealExecutionView(true, d.currency(), s.amount(), !own && s.amount() != null,
                m == null ? null : positive(m.total()), m == null ? null : m.id(), m == null ? null : m.label(),
                e == null || e.getContractKind() == null ? null : e.getContractKind().name(),
                e == null ? null : e.getContractNumber(), e == null ? null : e.getContractDate(),
                e == null ? null : e.getContractNote(),
                s.prepayPct(), s.prepayAmount(), s.clientPaid(),
                d.clientPayments().stream().map(DealExecutionViews::payment).toList(), s.fromStock(),
                d.supplierLines().stream().map(DealExecutionViews::supplier).toList(),
                d.unassignedCount() == 0 ? null : new DealExecutionView.Unassigned(d.unassignedCount(), d.unassignedPurchase()),
                e == null ? null : e.getDeliveredOn(), e == null ? null : e.getDeliveryNote(),
                e == null ? null : e.getDocsOn(), e == null ? null : e.getDocsNote(),
                files.stream().map(f -> new DealExecutionView.FileRef(f.id(), f.stage().name(), f.paymentId(),
                        f.dealSupplierId() == null ? null : distributorByRow.get(f.dealSupplierId()), f.name(), f.size(),
                        f.uploadedBy(), f.uploadedAt())).toList());
    }

    private static DealExecutionView.Payment payment(DealPayment p) {
        return new DealExecutionView.Payment(p.getId(), p.getAmount(), p.getPaidOn(), p.getNote(), p.getCreatedBy());
    }

    private static DealExecutionView.Supplier supplier(DealExecutionData.SupplierLine l) {
        return new DealExecutionView.Supplier(l.rowId(), l.distributorId(), l.name(), l.fromOffer(), l.manual(), l.excluded(),
                l.currency(), l.amount(), l.amountFromOffer(), l.offerAmount(), l.orderedOn(), l.orderNote(), l.paid(),
                l.payments().stream().map(DealExecutionViews::payment).toList(), l.shippedOn(), l.shipNote());
    }
}
