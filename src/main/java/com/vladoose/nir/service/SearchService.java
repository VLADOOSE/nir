package com.vladoose.nir.service;

import com.vladoose.nir.dto.TenderSearchRequest;
import com.vladoose.nir.dto.TenderSearchResult;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class SearchService {

    // TODO: подключить репозитории тендеров/лотов и реализовать реальный поиск
    public List<TenderSearchResult> search(TenderSearchRequest req) {
        // Небольшой stub: пока возвращаем пустой список
        return Collections.emptyList();
    }
}
