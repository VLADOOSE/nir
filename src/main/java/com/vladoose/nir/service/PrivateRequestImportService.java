package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.ImportCommitRequest;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class PrivateRequestImportService {

    private final LineExtractor extractor;
    private final HeaderSynonymRepository synonymRepository;
    private final PrivateRequestService privateRequestService;

    public PrivateRequestImportService(LineExtractor extractor,
                                       HeaderSynonymRepository synonymRepository,
                                       PrivateRequestService privateRequestService) {
        this.extractor = extractor;
        this.synonymRepository = synonymRepository;
        this.privateRequestService = privateRequestService;
    }

    public ImportPreviewResponse preview(byte[] content, String filename) {
        return extractor.extract(content, filename, learnedMap());
    }

    @Transactional
    public Tender commit(ImportCommitRequest dto) {
        if (dto.getMappings() != null) {
            for (ColumnMapping m : dto.getMappings()) {
                saveSynonym(m.getHeader(), m.getField());
            }
        }
        PrivateRequestCreate create = new PrivateRequestCreate();
        create.setClientFacilityId(dto.getClientFacilityId());
        create.setNote(dto.getNote());
        create.setLines(dto.getLines());
        return privateRequestService.createFromLines(create);
    }

    private void saveSynonym(String header, LineField field) {
        if (header == null || field == null || field == LineField.IGNORE) return;
        String norm = header.trim().toLowerCase();
        if (norm.isEmpty()) return;
        HeaderSynonym existing = synonymRepository.findByHeaderNorm(norm).orElse(null);
        if (existing == null) {
            synonymRepository.save(HeaderSynonym.builder().headerNorm(norm).field(field).build());
        } else if (existing.getField() != field) {
            existing.setField(field);
            synonymRepository.save(existing);
        }
    }

    private Map<String, LineField> learnedMap() {
        Map<String, LineField> map = new HashMap<>();
        for (HeaderSynonym s : synonymRepository.findAll()) {
            map.put(s.getHeaderNorm(), s.getField());
        }
        return map;
    }
}
