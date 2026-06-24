package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class PrivateRequestService {

    private final TenderRepository tenderRepository;
    private final TenderLotRepository tenderLotRepository;
    private final FacilityRepository facilityRepository;
    private final RegistryMatchService registryMatchService;

    public PrivateRequestService(TenderRepository tenderRepository,
                                 TenderLotRepository tenderLotRepository,
                                 FacilityRepository facilityRepository,
                                 RegistryMatchService registryMatchService) {
        this.tenderRepository = tenderRepository;
        this.tenderLotRepository = tenderLotRepository;
        this.facilityRepository = facilityRepository;
        this.registryMatchService = registryMatchService;
    }

    /** Шов приёма: создаёт частную заявку (tender source=PRIVATE_REQUEST) + лоты из строк.
     *  Ручной ввод сейчас; авто-парсер почты (блок D) вызовет этот же метод. */
    @Transactional
    public Tender createFromLines(PrivateRequestCreate dto) {
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new BadRequestException("Нужна хотя бы одна строка");
        }
        Facility client = facilityRepository.findById(dto.getClientFacilityId())
                .orElseThrow(() -> new NotFoundException("Клиент не найден: id=" + dto.getClientFacilityId()));

        Tender t = Tender.builder()
                .tenderNumber(nextNumber())
                .facility(client)
                .status("NEW")
                .source(Source.PRIVATE_REQUEST)
                .description(dto.getNote())
                .build();
        // market проставит @PrePersist-листенер из активного рынка
        Tender saved = tenderRepository.save(t);

        int lotNo = 1;
        for (PrivateRequestCreate.Line line : dto.getLines()) {
            TenderLot lot = TenderLot.builder()
                    .tender(saved)
                    .lotNumber(lotNo++)
                    .equipName(line.getName())
                    .manufact(line.getManufact())
                    .quantity(line.getQuantity())
                    .build();
            saved.getLots().add(tenderLotRepository.save(lot));
        }
        return saved;
    }

    public List<Tender> findAll() {
        return tenderRepository.findBySource(Source.PRIVATE_REQUEST);
    }

    public Tender findById(Long id) {
        Tender t = tenderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена: id=" + id));
        if (t.getSource() != Source.PRIVATE_REQUEST) {
            throw new NotFoundException("Заявка не найдена: id=" + id);
        }
        return t;
    }

    /** Реестр-статус по каждой строке заявки (через примитив реестра). */
    public List<PrivateRequestLineResponse> linesWithRegistration(Long tenderId) {
        Tender t = findById(tenderId);
        List<PrivateRequestLineResponse> result = new ArrayList<>();
        for (TenderLot lot : t.getLots()) {
            PrivateRequestLineResponse r = new PrivateRequestLineResponse();
            r.setLotId(lot.getId());
            r.setName(lot.getEquipName());
            r.setManufact(lot.getManufact());
            r.setQuantity(lot.getQuantity());
            List<RegistryCandidateResponse> cands =
                    registryMatchService.findCandidates(lot.getEquipName(), lot.getManufact(), 1);
            if (!cands.isEmpty()) {
                r.setTopCandidate(cands.get(0));
                r.setRegistrationStatus("REGISTERED");
            } else {
                r.setRegistrationStatus("NOT_FOUND");
            }
            result.add(r);
        }
        return result;
    }

    private String nextNumber() {
        int year = OffsetDateTime.now(ZoneOffset.UTC).getYear();
        long count = tenderRepository.findBySource(Source.PRIVATE_REQUEST).size();
        return String.format("ЧЗ-%d-%04d", year, count + 1);
    }
}
