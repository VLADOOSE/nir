package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class TenderService {

    private final TenderRepository repository;
    private final ActivityApplyRepository activityApplyRepository;

    public TenderService(TenderRepository repository, ActivityApplyRepository activityApplyRepository) {
        this.repository = repository;
        this.activityApplyRepository = activityApplyRepository;
    }

    public List<Tender> findAll() {
        return repository.findBySource(Source.PUBLIC_TENDER);
    }

    public Tender findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Тендер не найден: id=" + id));
    }

    /**
     * Тендер текущего рынка для путей `/api/tenders/{id}` — карточка, правка, удаление (2026-10-18). `findById` — это
     * `em.find`, фильтр рынка его не режет (§14 CLAUDE.md): открытая карточка живёт в адресе (`/tenders?openId=`),
     * смена рынка перезагружает тот же адрес — и на РФ открывался KZ-тендер. Частная заявка здесь тоже «не найдена»:
     * у неё своя страница и свои пути (`/api/private-requests/{id}`), а форма тендера её правку или удаление мимо
     * её проверок испортила бы.
     */
    public Tender findPublic(Long id) {
        Tender t = findInMarket(id);
        if (t.getSource() != Source.PUBLIC_TENDER) throw new NotFoundException("Тендер не найден: id=" + id);
        return t;
    }

    /** Тендер или частная заявка текущего рынка (лоты по id тендера): чужой рынок — «не найден», как несуществующий. */
    public Tender findInMarket(Long id) {
        Tender t = findById(id);
        if (t.getMarket() != null && t.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Тендер не найден: id=" + id);
        }
        return t;
    }

    @Transactional
    public Tender save(Tender tender) {
        if (tender.getId() == null) {
            tender.setMarket(com.vladoose.nir.context.MarketContext.get());
            tender.setCurrency(com.vladoose.nir.context.MarketContext.get().currencyCode());
        }
        return repository.save(tender);
    }

    @Transactional
    public void deleteById(Long id) {
        if (activityApplyRepository.existsByTenderId(id)) {
            throw new BadRequestException("Невозможно удалить тендер: к нему привязаны заявки. Сначала удалите заявки.");
        }
        repository.deleteById(id);
    }

    public List<Tender> searchTenders(String status, Long facilityId, String equipType,
                                      BigDecimal minCost, BigDecimal maxCost,
                                      LocalDate dateFrom, LocalDate dateTo) {
        return repository.searchTenders(status, facilityId, equipType, minCost, maxCost, dateFrom, dateTo);
    }
}
