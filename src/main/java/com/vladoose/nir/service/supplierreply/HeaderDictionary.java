package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Обучаемый словарь заголовков (header_synonym) — общий с разбором смет клиник. */
@Component
public class HeaderDictionary {
    private final HeaderSynonymRepository repository;

    public HeaderDictionary(HeaderSynonymRepository repository) {
        this.repository = repository;
    }

    public Map<String, LineField> learned() {
        Map<String, LineField> out = new HashMap<>();
        for (HeaderSynonym s : repository.findAll()) out.put(s.getHeaderNorm(), s.getField());
        return out;
    }
}
