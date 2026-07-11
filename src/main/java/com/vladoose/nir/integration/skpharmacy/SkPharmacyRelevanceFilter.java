package com.vladoose.nir.integration.skpharmacy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Фильтр релевантности СК-Ф под West-Med: берём медизделия/медтехнику, отсекаем лекарства.
 * Две ступени как у goszakup: (1) по имени объявления — дёшево, до fetch лотов; (2) по именам лотов.
 * Термины подбираются итеративно по живым прогонам (как MedicalRelevanceFilter).
 */
public final class SkPharmacyRelevanceFilter {

    private SkPharmacyRelevanceFilter() {}

    /** Явные лекарства по имени объявления. */
    private static final Pattern MED_NAME = Pattern.compile("(?iu)лекарствен|препарат|фармацевт|медикамент");
    /** Подсказки, что это оборудование/изделия (перебивают MED_NAME). */
    private static final Pattern EQUIP_HINT = Pattern.compile("(?iu)техник|издели|оборудован|аппарат|инструмент|расходн|материал");

    /** Лот = медизделие/техника. */
    private static final Pattern DEVICE = Pattern.compile("(?iu)"
            + "аппарат|томограф|установк|монитор|издели|инструмент|катетер|перчатк|шприц|светильник|дефибрилл"
            + "|насос|помп|стерилиз|автоклав|эндоскоп|узи|рентген|ивл|наркозн|кровать|кресл|весы|облучател"
            + "|ингалятор|электрокардиограф|\\bэкг\\b|дозатор|отсасыват|зонд|игл|бинт|бахил|маск|халат|салфетк"
            + "|шпател|скальпел|пинцет|зажим|ножниц|система|набор|комплект|стол\\b|тележк|штатив|облучател");
    /** Лекарство (дисквалифицирует лот). */
    private static final Pattern MEDICINE = Pattern.compile("(?iu)"
            + "таблетк|ампул|капсул|мазь|сироп|инъекц|порошок|суспензи|инфузи|раствор для|флакон|драже|гранул"
            + "|свеч|суппозитор|аэрозол|настойк|вакцин|сыворотк|инсулин|антибиотик|\\bмг\\b|мг/мл|\\bме\\b|\\bмкг\\b");

    /** Ступень 1: стоит ли тянуть лоты (не явные ли лекарства по имени объявления). */
    public static boolean nameCandidate(String announcementName) {
        String n = announcementName == null ? "" : announcementName;
        return !(MED_NAME.matcher(n).find() && !EQUIP_HINT.matcher(n).find());
    }

    /** Лот = медизделие/техника (device И не лекарство). */
    public static boolean isDeviceLot(String lotName) {
        String n = lotName == null ? "" : lotName;
        if (MEDICINE.matcher(n).find()) return false;
        return DEVICE.matcher(n).find();
    }

    /** Тендер релевантен: ≥1 device-лот. Пустые лоты (сеть/404) → фолбэк по имени объявления. */
    public static boolean isRelevant(String announcementName, List<String> lotNames) {
        if (lotNames == null || lotNames.isEmpty()) return nameCandidate(announcementName);
        return lotNames.stream().anyMatch(SkPharmacyRelevanceFilter::isDeviceLot);
    }
}
