package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.LotText;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Релевантность тендера goszakup: оставляем медицинские ТОВАРЫ (аппараты + расходка),
 * отсеиваем услуги (медотходы, медосмотр, обучение, ремонт) и не-медицину (дроны, «аппарат акима»).
 * Судим по ЛОТАМ, а не по имени объявления; тендер релевантен при ≥1 медтоварном лоте.
 * Термины — общий словарь {@link MedicalGoodsVocabulary} (только {@code DEVICE_STRONG}; общие слова
 * «аппарат»/«система» goszakup не использует, лекарственного вето нет).
 */
public final class MedicalRelevanceFilter {

    private static final Logger log = LoggerFactory.getLogger(MedicalRelevanceFilter.class);

    private MedicalRelevanceFilter() {}

    /** Тендер релевантен, если ≥1 лот — медтовар. Пустые лоты (сеть/404) → судим по имени объявления. */
    public static boolean isRelevant(String announcementName, List<LotText> lots) {
        if (lots != null && !lots.isEmpty()) return lots.stream().anyMatch(MedicalRelevanceFilter::isMedicalLot);
        return isMedicalLot(new LotText(announcementName, null));
    }

    /**
     * Маркеры услуг — только в НАЗВАНИИ лота: описание поставки изделия обычно перечисляет «монтаж, обучение
     * персонала» и раньше убивало лот. Термины изделий — в названии и описании вместе (стемы многословного
     * терма могут стоять в разных полях: «Тележка» / «медицинская»). Сильный маркер услуги отсекает всегда,
     * слабый — только без термина изделия в названии.
     *
     * <p>Исключение до маркеров услуг: тара для сбора медицинских отходов (КБУ, «Контейнер | для сбора
     * медицинских отходов», «Коробка для сбора и хранения медицинских отходов») — товар, хотя «отход» — сильный
     * маркер услуги. Только если в названии нет «услуг»/«вывоз»/«работы по»: «Вывоз медицинских отходов» и
     * «Услуги по утилизации отходов в контейнерах» остаются услугами.
     */
    public static boolean isMedicalLot(LotText lot) {
        if (lot == null) return false;
        String name = lot.name() == null ? "" : lot.name();
        String descr = lot.description() == null ? "" : lot.description();
        boolean deviceInName = MedicalGoodsVocabulary.hasDeviceStrong(name);
        if (isMedicalWasteContainer(name, descr)) return true;
        if (MedicalGoodsVocabulary.hasServiceStrong(name)) {
            if (deviceInName) log.debug("goszakup: лот «{}» — изделие и услуга, считаем услугой", name);
            return false;
        }
        if (MedicalGoodsVocabulary.hasServiceWeak(name)) {
            if (!deviceInName) return false;
            log.debug("goszakup: лот «{}» — изделие и слабый маркер услуги, считаем изделием", name);
        }
        return deviceInName || MedicalGoodsVocabulary.hasDeviceStrong((name + " " + descr).trim());
    }

    private static boolean isMedicalWasteContainer(String name, String descr) {
        return MedicalGoodsVocabulary.matchesAny(name, MedicalGoodsVocabulary.WASTE_CONTAINER)
                && !MedicalGoodsVocabulary.matchesAny(name, MedicalGoodsVocabulary.WASTE_SERVICE)
                && MedicalGoodsVocabulary.matchesAny((name + " " + descr).trim(), MedicalGoodsVocabulary.MEDICAL_WASTE);
    }
}
