package com.vladoose.nir.integration.corpus;

import com.vladoose.nir.integration.corpus.ImportCorpus.CorpusLot;
import com.vladoose.nir.integration.corpus.ImportCorpus.CorpusTender;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Замороженная копия фильтров на 2026-10-07 — только для отчёта „было“. Не править.
 *
 * <p>Дословно {@code MedicalRelevanceFilter} и {@code SkPharmacyRelevanceFilter} на коммите {@code d14ed654};
 * адаптеры повторяют, как их зовут {@code GoszakupImportService} и {@code SkPharmacyImportService}.
 */
public final class LegacyRelevanceFilters {

    private LegacyRelevanceFilters() {}

    /** Как GoszakupImportService: текст лота = name + " " + description. */
    public static boolean goszakup(CorpusTender t) {
        List<String> texts = t.lots().stream().map(LegacyRelevanceFilters::lotText).toList();
        return OldGoszakup.isRelevant(t.name(), texts);
    }

    public static boolean goszakupLot(CorpusLot l) {
        return OldGoszakup.isMedicalGoods(lotText(l));
    }

    /** Как SkPharmacyImportService: ступень 1 по имени объявления, затем лоты по именам. */
    public static boolean sk(CorpusTender t) {
        if (!OldSk.nameCandidate(t.name())) return false;
        return OldSk.isRelevant(t.name(), t.lots().stream().map(CorpusLot::name).toList());
    }

    public static boolean skLot(CorpusLot l) {
        return OldSk.isDeviceLot(l.name());
    }

    static String lotText(CorpusLot l) {
        return ((l.name() == null ? "" : l.name()) + " " + (l.description() == null ? "" : l.description())).trim();
    }

    /** Копия MedicalRelevanceFilter @ d14ed654. */
    static final class OldGoszakup {
        private static final List<String> POSITIVE = List.of(
                "узи", "ультразвук", "эхокардиограф", "рентген", "флюорограф", "маммограф", "ангиограф",
                "томограф", "мрт", "ивл", "вентиляц", "наркоз", "анестезиолог",
                "анализатор", "гематологич", "биохимич", "коагулометр", "центрифуг", "микроскоп",
                "стерилизатор", "автоклав", "эндоскоп", "гастроскоп", "колоноскоп", "бронхоскоп", "лапароскоп",
                "дефибрил", "монитор пациента", "прикроватн монитор", "кардиограф", "электрокардиограф", "экг",
                "спирометр", "инкубатор", "облучател", "рециркулятор", "бактерицидн", "физиотерап",
                "электрофорез", "магнитотерап", "отсасыватель", "аспиратор", "оксиметр", "пульсоксиметр",
                "тонометр", "глюкометр", "коагулятор", "ингалятор", "небулайзер", "негатоскоп", "дозатор",
                "концентратор кислород", "кислородн концентратор", "весы медицин", "холодильник медицин",
                "кровать функционал", "кушетк медицин",
                "стоматологическ", "дентальн", "хирургическ", "операционн стол", "операционн светильник",
                "перчат", "шприц", "катетер", "зонд медицин", "бинт", "пластыр", "электрод", "реагент",
                "тест-систем", "изделие медицинск", "изделия медицинск", "медицинского назначения",
                "расходн материал", "имплант", "протез", "шовн материал", "игл", "скальпел", "пробирк");

        private static final List<String> NEGATIVE = List.of(
                "услуг", "работы по", "обучен", "осмотр", "утилизац", "удаление", "отход", "ремонт",
                "монтаж", "обслуживан", "замер", "аренд", "страхован", "пошив", "стирк", "поверк", "метролог",
                "летательн", "беспилотн", "дрон", "потолок");

        static boolean isRelevant(String announcementName, List<String> lotTexts) {
            if (lotTexts != null && !lotTexts.isEmpty()) {
                return lotTexts.stream().anyMatch(OldGoszakup::isMedicalGoods);
            }
            return isMedicalGoods(announcementName);
        }

        static boolean isMedicalGoods(String text) {
            if (text == null || text.isBlank()) return false;
            String t = text.toLowerCase(Locale.ROOT);
            if (NEGATIVE.stream().anyMatch(t::contains)) return false;
            return POSITIVE.stream().anyMatch(t::contains);
        }
    }

    /** Копия SkPharmacyRelevanceFilter @ d14ed654. */
    static final class OldSk {
        private static final Pattern MED_NAME = Pattern.compile("(?iu)лекарствен|препарат|фармацевт|медикамент");
        private static final Pattern EQUIP_HINT = Pattern.compile("(?iu)техник|издели|оборудован|аппарат|инструмент|расходн|материал");
        private static final Pattern DEVICE = Pattern.compile("(?iu)"
                + "аппарат|томограф|установк|монитор|издели|инструмент|катетер|перчатк|шприц|светильник|дефибрилл"
                + "|насос|помп|стерилиз|автоклав|эндоскоп|узи|рентген|ивл|наркозн|кровать|кресл|весы|облучател"
                + "|ингалятор|электрокардиограф|\\bэкг\\b|дозатор|отсасыват|зонд|игл|бинт|бахил|маск|халат|салфетк"
                + "|шпател|скальпел|пинцет|зажим|ножниц|система|набор|комплект|стол\\b|тележк|штатив|облучател");
        private static final Pattern MEDICINE = Pattern.compile("(?iu)"
                + "таблетк|ампул|капсул|мазь|сироп|инъекц|порошок|суспензи|инфузи|раствор для|флакон|драже|гранул"
                + "|свеч|суппозитор|аэрозол|настойк|вакцин|сыворотк|инсулин|антибиотик|\\bмг\\b|мг/мл|\\bме\\b|\\bмкг\\b");

        static boolean nameCandidate(String announcementName) {
            String n = announcementName == null ? "" : announcementName;
            return !(MED_NAME.matcher(n).find() && !EQUIP_HINT.matcher(n).find());
        }

        static boolean isDeviceLot(String lotName) {
            String n = lotName == null ? "" : lotName;
            if (MEDICINE.matcher(n).find()) return false;
            return DEVICE.matcher(n).find();
        }

        static boolean isRelevant(String announcementName, List<String> lotNames) {
            if (lotNames == null || lotNames.isEmpty()) return nameCandidate(announcementName);
            return lotNames.stream().anyMatch(OldSk::isDeviceLot);
        }
    }
}
