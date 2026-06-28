package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Управляемый фейк: страницы по cursor, лоты и subject по ключу. Без сети. */
public class FakeGoszakupClient implements GoszakupClient {
    public boolean configured = true;
    /** cursor (null=первая) → страница. nextPage внутри dto задаёт следующий cursor. */
    public final Map<String, TrdBuyPageDto> pages = new HashMap<>();
    public final Map<String, List<LotDto>> lotsByAnno = new HashMap<>();
    public final Map<String, SubjectDto> subjectsByBin = new HashMap<>();

    @Override public boolean isConfigured() { return configured; }
    @Override public TrdBuyPageDto fetchTrdBuyPage(String cursor) {
        TrdBuyPageDto p = pages.get(cursor);
        if (p != null) return p;
        TrdBuyPageDto empty = new TrdBuyPageDto(); empty.setItems(new ArrayList<>()); return empty;
    }
    @Override public List<LotDto> fetchLots(String numberAnno) {
        return lotsByAnno.getOrDefault(numberAnno, List.of());
    }
    @Override public SubjectDto fetchSubject(String bin) { return subjectsByBin.get(bin); }

    // --- builders для тестов ---
    public static TrdBuyDto buy(String anno, String name, int status, String customerBin, String publishIso, String endIso) {
        TrdBuyDto d = new TrdBuyDto();
        d.setId((long) anno.hashCode()); d.setNumberAnno(anno); d.setNameRu(name);
        d.setRefBuyStatusId(status); d.setCustomerBin(customerBin);
        d.setPublishDate(publishIso); d.setEndDate(endIso);
        d.setTotalSum(new java.math.BigDecimal("1000000")); return d;
    }
    public TrdBuyPageDto page(String cursor, String nextPage, TrdBuyDto... items) {
        TrdBuyPageDto p = new TrdBuyPageDto();
        p.setItems(new ArrayList<>(List.of(items))); p.setNextPage(nextPage);
        pages.put(cursor, p); return p;
    }
}
