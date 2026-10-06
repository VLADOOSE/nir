package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.integration.MedicalGoodsVocabulary;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Фильтр релевантности СК-Ф под West-Med: берём медизделия/медтехнику, отсекаем лекарства.
 * Две ступени как у goszakup: (1) по имени объявления — дёшево, до fetch лотов; (2) по именам лотов.
 * Термины — в общем словаре {@link MedicalGoodsVocabulary} (единственный источник для обеих площадок).
 */
public final class SkPharmacyRelevanceFilter {

    private SkPharmacyRelevanceFilter() {}

    /** Явные лекарства по имени объявления. */
    private static final Pattern MED_NAME = Pattern.compile("(?iU)лекарствен|препарат|фармацевт|медикамент");
    /** Подсказки, что это оборудование/изделия (перебивают MED_NAME). */
    private static final Pattern EQUIP_HINT = Pattern.compile("(?iU)техник|издели|оборудован|аппарат|инструмент|расходн|материал");

    /** Имя объявления, которого достаточно: «…медицинской техники», «…медицинских изделий». */
    private static final List<String> MEDICAL_PROCUREMENT = List.of("медицинск техник", "медицинск издели");

    /** Ступень 1: стоит ли тянуть лоты (не явные ли лекарства по имени объявления). */
    public static boolean nameCandidate(String announcementName) {
        String n = announcementName == null ? "" : announcementName;
        return !(MED_NAME.matcher(n).find() && !EQUIP_HINT.matcher(n).find());
    }

    /**
     * Лот = медизделие/техника. Сильное изделие («шприц», «анализатор») перебивает лекарственное вето
     * («Шприцы инъекционные»); слабое слово («набор», «система») — только без вето («Набор для инфузий» — нет).
     * Сильный термин-прилагательное («стоматологический») при лекарственной форме не засчитывается:
     * «Артикаин стоматологический раствор для инъекций» — лекарство. Тара для медотходов — изделие.
     */
    public static boolean isDeviceLot(String lotName) {
        String n = lotName == null ? "" : lotName;
        if (MedicalGoodsVocabulary.hasDeviceStrongOverDrugForm(n)) return true;
        if (MedicalGoodsVocabulary.isMedicalWasteContainer(n, null)) return true;
        if (MedicalGoodsVocabulary.hasMedicine(n)) return false;
        return MedicalGoodsVocabulary.hasDeviceWeak(n);
    }

    /**
     * Тендер релевантен: объявление названо закупом медтехники/медизделий, либо ≥1 device-лот.
     * Пустые лоты (сеть/404) → фолбэк по имени объявления.
     */
    public static boolean isRelevant(String announcementName, List<String> lotNames) {
        if (MedicalGoodsVocabulary.matchesAny(announcementName, MEDICAL_PROCUREMENT)) return true;
        if (lotNames == null || lotNames.isEmpty()) return nameCandidate(announcementName);
        return lotNames.stream().anyMatch(SkPharmacyRelevanceFilter::isDeviceLot);
    }
}
