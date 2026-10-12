package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RfqPreviewRequest;
import com.vladoose.nir.dto.request.RfqSendRequest;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.PriceRequestSendService;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.service.PrivateRequestService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Окно «Запросить цены» частной заявки (спека §6.2): каждому поставщику — одно письмо только с его позициями, через
 * единый канал отправки (метка [КП-id] в теме, исход письма в запросе). Сначала проверяется ВЕСЬ запрос — позиции своей
 * заявки, поставщики своего рынка, дата, — и только потом уходят письма: отказ на третьем поставщике не оставит двух
 * отправленных писем без итога.
 */
@Service
public class PrivateRequestRfqService {

    private final PrivateRequestService privateRequestService;
    private final PriceRequestSendService sendService;
    private final DistributorService distributorService;

    public PrivateRequestRfqService(PrivateRequestService privateRequestService, PriceRequestSendService sendService,
                                    DistributorService distributorService) {
        this.privateRequestService = privateRequestService;
        this.sendService = sendService;
        this.distributorService = distributorService;
    }

    /** Письмо, которое уйдёт поставщику, — без сохранения запроса (тема без метки). */
    public KpPreviewResponse preview(Long requestId, RfqPreviewRequest req) {
        Tender t = privateRequestService.findById(requestId);
        checkReplyBy(req.getReplyBy());
        Distributor d = distributor(req.getDistributorId());
        return sendService.preview(t.getId(), List.of(d.getId()), items(lots(t, req.getLotIds())),
                SendOptions.replyBy(req.getReplyBy()));
    }

    public List<SendResult> send(Long requestId, RfqSendRequest req) {
        Tender t = privateRequestService.findById(requestId);
        if (req.getSuppliers() == null || req.getSuppliers().isEmpty()) {
            throw new BadRequestException("Не выбраны поставщики");
        }
        checkReplyBy(req.getReplyBy());
        Map<Long, List<TenderLot>> plan = new LinkedHashMap<>();
        for (RfqSendRequest.Supplier s : req.getSuppliers()) {
            Distributor d = distributor(s.getDistributorId());
            if (s.getLotIds() == null || s.getLotIds().isEmpty()) {
                throw new BadRequestException("У поставщика «" + d.getName() + "» не выбраны позиции");
            }
            List<TenderLot> lots = lots(t, s.getLotIds());
            plan.merge(d.getId(), lots, (a, b) -> {
                List<TenderLot> merged = new ArrayList<>(a);
                for (TenderLot l : b) if (!merged.contains(l)) merged.add(l);
                return merged;
            });
        }
        SendOptions options = SendOptions.replyBy(req.getReplyBy());
        List<SendResult> results = new ArrayList<>();
        plan.forEach((distributorId, lots) ->
                results.addAll(sendService.send(t.getId(), List.of(distributorId), items(lots), null, null, options)));
        return results;
    }

    private Distributor distributor(Long id) {
        if (id == null) throw new BadRequestException("Не указан поставщик");
        Distributor d = distributorService.findById(id);
        if (d.getMarket() != null && d.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Поставщик не найден: " + id);
        }
        return d;
    }

    private static List<TenderLot> lots(Tender t, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new BadRequestException("Не выбраны позиции");
        Map<Long, TenderLot> own = new HashMap<>();
        for (TenderLot l : t.getLots()) own.put(l.getId(), l);
        List<TenderLot> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            TenderLot l = own.get(id);
            if (l == null) throw new BadRequestException("Позиция " + id + " не из этой заявки");
            out.add(l);
        }
        return out;
    }

    private static List<SendItem> items(List<TenderLot> lots) {
        return lots.stream().map(l -> new SendItem(l.getId(), null, l.getQuantity())).toList();
    }

    private static void checkReplyBy(LocalDate replyBy) {
        if (replyBy != null && replyBy.isBefore(LocalDate.now(MarketContext.get().zone()))) {
            throw new BadRequestException("Дата «Ответить до» уже прошла");
        }
    }
}
