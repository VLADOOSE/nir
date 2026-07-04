package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.util.PdfTextExtractor;
import com.vladoose.nir.util.SpecConstraintExtractor;
import com.vladoose.nir.util.TechSpecExtractor;
import org.springframework.stereotype.Service;

/**
 * On-demand разбор «Технической спецификации» импортного лота: v3 Lots.Files → PDF → текст →
 * русская секция → габариты/вес → запись в лот (TechSpecWriter, транзакция только на запись).
 */
@Service
public class TechSpecService {

    public record ParseResult(TenderLot lot, boolean dimsFound, boolean weightFound,
                              boolean ambiguous, String source) {}

    private final TenderLotService tenderLotService;
    private final GoszakupClient client;
    private final TechSpecWriter writer;

    public TechSpecService(TenderLotService tenderLotService, GoszakupClient client, TechSpecWriter writer) {
        this.tenderLotService = tenderLotService;
        this.client = client;
        this.writer = writer;
    }

    public ParseResult parse(Long lotId) {
        TenderLot lot = tenderLotService.findById(lotId);
        // findById = em.find обходит фильтр рынка → явный гард
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        String anno = lot.getTender().getSourceExtId();
        if (anno == null || anno.isBlank()) {
            throw new BadRequestException("ТЗ доступно только у импортированных с goszakup тендеров");
        }
        if (!client.isConfigured()) {
            throw new BadRequestException("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
        }

        LotTechSpecRef ref;
        byte[] pdf;
        try {
            ref = client.fetchLotTechSpec(anno, lot.getEquipName());
            if (ref == null || ref.filePath() == null) {
                throw new NotFoundException("Файл «Техническая спецификация» не найден у лота на goszakup");
            }
            pdf = client.downloadFile(ref.filePath());
        } catch (IllegalStateException e) {
            throw new UpstreamException("goszakup недоступен: " + e.getMessage(), e);
        }
        if (pdf == null) { // 404 при скачивании (hash протух/файл удалён)
            throw new NotFoundException("Файл «Техническая спецификация» недоступен для скачивания на goszakup");
        }

        String fullText = PdfTextExtractor.extract(pdf);
        String text = TechSpecExtractor.russianSection(fullText);
        if (text == null || text.isBlank()) {
            throw new UnprocessableException("Не удалось извлечь текст из PDF техспецификации");
        }

        SpecConstraintExtractor.SpecConstraints c = SpecConstraintExtractor.extract(text);
        TenderLot saved = writer.apply(lotId, text, c);
        return new ParseResult(saved, c.maxLengthMm() != null, c.maxWeightKg() != null,
                ref.ambiguous(), ref.originalName());
    }
}
