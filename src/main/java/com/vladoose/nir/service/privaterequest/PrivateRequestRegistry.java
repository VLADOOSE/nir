package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.LineRegStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.service.RegistryMatchService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * РУ позиции частной заявки (спека §5.3): подсказка считается сама (только KZ — на РФ реестра нет), подтверждает человек.
 */
@Component
public class PrivateRequestRegistry {

    /**
     * Порог подсказки для пары «наименование + бренд». Скор findCandidates = 0.6·производитель + 0.4·наименование:
     * точное совпадение бренда даёт 0.6 само по себе, поэтому порог выше 0.6 требует ещё и похожести наименования —
     * иначе «РУ?» стояло бы у любой позиции известного бренда (тест brandAloneIsNotASuggestion). Позиция без бренда
     * набирает максимум 0.4 и подсказки не получает — её РУ определится, когда выбрана модель.
     * <p>
     * Замер 2026-10-12 (PrivateRequestRegistryCalibrationTest, REG_CALIBRATION=1; 20 типовых строк — товары westmed.kz и
     * то, что называют клиники; реестр nirdb 14 072 записи): верный топ-1 у 7 строк (наименьший скор 0.753 —
     * «ЭМАЛЕД 100П»), неверный — у 8 (наибольший 0.963 — «BeneHeart R12» → «BeneHeart R900, R700…»), у 5 кандидатов нет
     * или бренда нет (0.4). Порог их НЕ разделяет: соседняя модель того же бренда набирает больше, чем верная запись.
     * Разделяет проверка кода модели ({@link #mentionsModel}): с ней выше 0.75 остаются ровно 7 верных и ни одной
     * неверной. Поэтому 0.75 оставлен как нижняя граница, а решающая проверка — код модели; подсказка остаётся
     * подсказкой, подтверждает человек.
     */
    public static final double SUGGEST_MIN = 0.75;

    public static final int CANDIDATES_LIMIT = 5;

    private final RegistryMatchService matcher;
    private final MedRegistryRepository registryRepository;

    public PrivateRequestRegistry(RegistryMatchService matcher, MedRegistryRepository registryRepository) {
        this.matcher = matcher;
        this.registryRepository = registryRepository;
    }

    /** Подсказка по наименованию и бренду; РФ — статус пустой. Сбрасывает подтверждение и «не подлежит». */
    public void suggest(TenderLot lot) {
        if (lot.getTender().getMarket() != Market.KZ) {
            lot.setRegStatus(null);
            lot.setRegNumber(null);
            lot.setRegScore(null);
            return;
        }
        // лучший кандидат не выше порога или другой модели — смотрим следующих: записи отсортированы по скору
        RegistryCandidateResponse best = matcher.findCandidates(lot.getEquipName(), lot.getManufact(), CANDIDATES_LIMIT)
                .stream()
                .filter(c -> c.getScore() != null && c.getScore() >= SUGGEST_MIN && mentionsModel(lot.getEquipName(), c.getName()))
                .findFirst().orElse(null);
        if (best != null) {
            lot.setRegStatus(LineRegStatus.SUGGESTED);
            lot.setRegNumber(best.getRegNumber());
            lot.setRegScore(BigDecimal.valueOf(best.getScore()).setScale(3, RoundingMode.HALF_UP));
        } else {
            lot.setRegStatus(LineRegStatus.NOT_FOUND);
            lot.setRegNumber(null);
            lot.setRegScore(null);
        }
    }

    /** Подтвердить: без номера — текущую подсказку; с номером — выбранную запись (она должна быть в реестре). */
    public void confirm(TenderLot lot, String regNumber) {
        String number = regNumber == null || regNumber.isBlank() ? null : regNumber.trim();
        if (number == null) {
            if (lot.getRegStatus() != LineRegStatus.SUGGESTED || lot.getRegNumber() == null) {
                throw new BadRequestException("Подтверждать нечего: у позиции нет подсказки РУ");
            }
            number = lot.getRegNumber();
        }
        final String wanted = number;
        MedRegistry rec = registryRepository.findByRegNumber(wanted)
                .orElseThrow(() -> new BadRequestException("РУ не найдено в реестре: " + wanted));
        boolean sameAsSuggestion = rec.getRegNumber().equals(lot.getRegNumber());
        lot.setRegStatus(LineRegStatus.CONFIRMED);
        lot.setRegNumber(rec.getRegNumber());
        if (!sameAsSuggestion) lot.setRegScore(null);
    }

    public void notRequired(TenderLot lot) {
        lot.setRegStatus(LineRegStatus.NOT_REQUIRED);
        lot.setRegNumber(null);
        lot.setRegScore(null);
    }

    /**
     * Код модели позиции (слово с буквами и цифрами: «R12», «ОБПЕ-450», «100П») встречается в наименовании записи
     * реестра. Скор не отличает соседние модели одного бренда: точный бренд даёт 0.6, а «BeneHeart R12» и
     * «BeneHeart R900, R700…» почти совпадают по триграммам (0.963 на живом реестре) — без этой проверки «РУ?» звало бы
     * подтвердить чужое РУ, а подтверждённое РУ уходит в письмо поставщику и в КП. Нет кода модели — проверки нет.
     */
    public static boolean mentionsModel(String lineName, String recordName) {
        List<String> codes = modelCodes(lineName);
        if (codes.isEmpty()) return true;
        String record = squash(recordName);
        return codes.stream().anyMatch(record::contains);
    }

    /** Слова наименования, в которых есть и буква, и цифра, — без знаков препинания и пробелов. */
    static List<String> modelCodes(String name) {
        if (name == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String word : name.split("\\s+")) {
            String w = squash(word);
            if (w.length() >= 2 && w.chars().anyMatch(Character::isDigit) && w.chars().anyMatch(Character::isLetter)) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * Нижний регистр, только буквы и цифры; латинские буквы, совпадающие по начертанию с кириллицей, — кириллицей
     * («BM-25» латиницей и «ВМ-25» кириллицей — одна модель).
     */
    static String squash(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (!Character.isLetterOrDigit(c)) continue;
            int i = LATIN_LOOKALIKE.indexOf(c);
            b.append(i >= 0 ? CYRILLIC_LOOKALIKE.charAt(i) : c);
        }
        return b.toString();
    }

    private static final String LATIN_LOOKALIKE = "abcehkmoptxy";
    private static final String CYRILLIC_LOOKALIKE = "авсенкмортху";

    /** Варианты для окошка «Не то — найти в реестре». РФ — пусто. */
    public List<RegistryCandidateResponse> candidates(TenderLot lot) {
        if (lot.getTender().getMarket() != Market.KZ) return List.of();
        return matcher.findCandidates(lot.getEquipName(), lot.getManufact(), CANDIDATES_LIMIT);
    }
}
