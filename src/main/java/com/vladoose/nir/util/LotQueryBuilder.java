package com.vladoose.nir.util;

import com.vladoose.nir.util.LotQueryTokenizer.WeightedToken;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Сбор поискового запроса из лота. Единственное место, где решается, какой текст идёт в подбор.
 *
 * <p>Запрос делится надвое, и это ключевое решение всей схемы:
 * <ul>
 *   <li><b>identity</b> — чем изделие <i>является</i> (имя лота). Только эти токены
 *       ОТБИРАЮТ кандидатов.</li>
 *   <li><b>qualifier</b> — каким оно должно <i>быть</i> (описание/ТЗ). Эти токены только
 *       ПЕРЕРАНЖИРУЮТ уже отобранное и охват не расширяют.</li>
 * </ul>
 *
 * <p>Почему так: названия реестра описывают товар, а не его функцию. Замер 2026-08-01 —
 * пуск описания в отбор раздул «Спектрофотометр» с 3 кандидатов до 278 и утопил верный ответ.
 *
 * <p>Дефект, который здесь исправлен: раньше в подбор шёл только результат
 * {@link TechSpecExtractor#characteristics(String)}, а он null без якоря разобранного PDF —
 * то есть у 224 лотов из 225 описание {@code description_ru} выбрасывалось целиком.
 */
public final class LotQueryBuilder {

    public record LotQuery(List<WeightedToken> identity, List<String> qualifier, boolean techSpecParsed) {}

    private LotQueryBuilder() {}

    public static LotQuery build(String equipName, String requiredSpec) {
        String chars = TechSpecExtractor.characteristics(requiredSpec);
        boolean techSpecParsed = chars != null;
        // якорь есть → берём блок характеристик (точнее: отрезана закупочная шапка);
        // якоря нет → берём requiredSpec как есть — это description_ru, единственное различающее
        String qualifierText = techSpecParsed ? chars : requiredSpec;

        List<WeightedToken> identity = LotQueryTokenizer.tokenize(equipName, null);

        Set<String> identityTokens = new LinkedHashSet<>();
        for (WeightedToken t : identity) identityTokens.add(t.token());

        List<String> qualifier = new ArrayList<>();
        for (WeightedToken t : LotQueryTokenizer.tokenize(qualifierText, null)) {
            if (!identityTokens.contains(t.token())) qualifier.add(t.token());
        }
        return new LotQuery(identity, qualifier, techSpecParsed);
    }
}
