package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.KatoRefDto;
import com.vladoose.nir.integration.goszakup.dto.KatoRefPageDto;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyV3PageDto;

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
    public final java.util.Set<String> failingSubjectBins = new java.util.HashSet<>();
    /** after (null=первая) → v3-страница региона. */
    public final Map<Long, TrdBuyV3PageDto> v3Pages = new HashMap<>();
    /** orgBin → одностраничная лента организации. */
    public final Map<String, TrdBuyV3PageDto> orgPages = new HashMap<>();
    public final List<String> orgBinsQueried = new ArrayList<>();
    /** cursor (null=первая) → страница справочника КАТО. */
    public final Map<String, KatoRefPageDto> katoPages = new HashMap<>();
    public int trdBuyFetches = 0;
    public int katoFetches = 0;
    public List<String> lastKatoFilter;

    @Override public boolean isConfigured() { return configured; }
    @Override public TrdBuyPageDto fetchTrdBuyPage(String cursor) {
        trdBuyFetches++;
        TrdBuyPageDto p = pages.get(cursor);
        if (p != null) return p;
        TrdBuyPageDto empty = new TrdBuyPageDto(); empty.setItems(new ArrayList<>()); return empty;
    }
    @Override public TrdBuyV3PageDto fetchTrdBuyPageByKato(List<String> katoCodes, Long after) {
        lastKatoFilter = katoCodes;
        TrdBuyV3PageDto p = v3Pages.get(after);
        if (p != null) return p;
        TrdBuyV3PageDto empty = new TrdBuyV3PageDto(); empty.setItems(new ArrayList<>()); return empty;
    }
    @Override public TrdBuyV3PageDto fetchTrdBuyPageByOrgBin(String orgBin, Long after) {
        orgBinsQueried.add(orgBin);
        TrdBuyV3PageDto p = (after == null) ? orgPages.get(orgBin) : null;
        if (p != null) return p;
        TrdBuyV3PageDto empty = new TrdBuyV3PageDto(); empty.setItems(new ArrayList<>()); return empty;
    }
    @Override public KatoRefPageDto fetchKatoPage(String cursor) {
        katoFetches++;
        KatoRefPageDto p = katoPages.get(cursor);
        if (p != null) return p;
        KatoRefPageDto empty = new KatoRefPageDto(); empty.setItems(new ArrayList<>()); return empty;
    }
    @Override public List<LotDto> fetchLots(String numberAnno) {
        return lotsByAnno.getOrDefault(numberAnno, List.of());
    }
    @Override public SubjectDto fetchSubject(String bin) {
        if (failingSubjectBins.contains(bin)) throw new RuntimeException("fake subject failure: " + bin);
        return subjectsByBin.get(bin);
    }

    /** ключ: numberAnno + "|" + lotNameRu → ссылка на техспеку (отсутствие ключа = null). */
    public final Map<String, LotTechSpecRef> techSpecByKey = new HashMap<>();
    /** filePath → байты файла. */
    public final Map<String, byte[]> filesByUrl = new HashMap<>();

    @Override public LotTechSpecRef fetchLotTechSpec(String numberAnno, String lotNameRu) {
        return techSpecByKey.get(numberAnno + "|" + lotNameRu);
    }
    @Override public byte[] downloadFile(String url) {
        return filesByUrl.get(url); // как живой клиент: 404 → null (файл недоступен)
    }

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
    public TrdBuyV3PageDto v3Page(Long after, Long nextAfter, TrdBuyDto... items) {
        TrdBuyV3PageDto p = new TrdBuyV3PageDto();
        p.setItems(new ArrayList<>(List.of(items))); p.setNextAfter(nextAfter);
        v3Pages.put(after, p); return p;
    }
    public TrdBuyV3PageDto orgPage(String orgBin, TrdBuyDto... items) {
        TrdBuyV3PageDto p = new TrdBuyV3PageDto();
        p.setItems(new ArrayList<>(List.of(items))); p.setNextAfter(null);
        orgPages.put(orgBin, p); return p;
    }
    public KatoRefPageDto katoPage(String cursor, String nextPage, KatoRefDto... items) {
        KatoRefPageDto p = new KatoRefPageDto();
        p.setItems(new ArrayList<>(List.of(items))); p.setNextPage(nextPage);
        katoPages.put(cursor, p); return p;
    }
    public static KatoRefDto kato(String ab, String cd, String ef, String hij) {
        KatoRefDto k = new KatoRefDto();
        k.setAb(ab); k.setCd(cd); k.setEf(ef); k.setHij(hij);
        return k;
    }
}
