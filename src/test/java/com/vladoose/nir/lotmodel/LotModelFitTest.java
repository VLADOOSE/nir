package com.vladoose.nir.lotmodel;

import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.service.RegistryDetailService;
import com.vladoose.nir.service.lotmodel.LotModelFit;
import com.vladoose.nir.service.lotmodel.LotModelReader;
import com.vladoose.nir.service.lotmodel.LotModelViews;
import com.vladoose.nir.service.lotmodel.ModelSnapshot;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import com.vladoose.nir.service.productwatch.fit.ProductCards;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Сверка модели в работе (спека §6): карточка есть — сверка; карточки РУ нет — загрузить; ndda.kz недоступен — MISSING. */
class LotModelFitTest {

    static final LotModelFitResponse.Model MODEL = new LotModelFitResponse.Model(1L, "BeneHeart D3", "Mindray", "REGISTRY", null,
            null, "РК-1", "ACTIVE", null, true);
    static final String TEXT = "масса не более 8 кг; энергия разряда не менее 200 Дж";
    static final LotModelViews VIEWS = new LotModelViews("", null);

    static ProductCard card(String techChars) {
        return ProductCards.ofRegistry(MedRegistry.builder().regNumber("РК-1").name("BeneHeart D3").producer("Mindray")
                .techChars(techChars).unlimited(true).build(), LocalDate.of(2026, 10, 20));
    }

    static ModelSnapshot snap(ProductCard card, boolean needsDetail) {
        return new ModelSnapshot(TEXT, card, needsDetail, "РК-1", MODEL, "ИИ", "vlad", null);
    }

    @Test
    void cardReady_fitComputed() {
        LotModelReader reader = mock(LotModelReader.class);
        when(reader.read(5L)).thenReturn(snap(card("Масса не более 6 кг"), false));
        LotModelFitResponse r = new LotModelFit(reader, mock(RegistryDetailService.class), VIEWS).fit(5L);
        assertThat(r.card()).isEqualTo("OK");
        assertThat(r.fit().met()).isGreaterThanOrEqualTo(1);
        assertThat(r.note()).isEqualTo("ИИ");
        assertThat(r.model().name()).isEqualTo("BeneHeart D3");
    }

    @Test
    void cardNotLoaded_fetchedThenReread() {
        LotModelReader reader = mock(LotModelReader.class);
        RegistryDetailService details = mock(RegistryDetailService.class);
        when(reader.read(5L)).thenReturn(snap(card(null), true), snap(card("Масса не более 6 кг"), false));
        LotModelFitResponse r = new LotModelFit(reader, details, VIEWS).fit(5L);
        verify(details).detail("РК-1");
        assertThat(r.card()).isEqualTo("OK");
        assertThat(r.fit().met()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void nddaDown_missingWithoutFit_pinNotBroken() {
        LotModelReader reader = mock(LotModelReader.class);
        RegistryDetailService details = mock(RegistryDetailService.class);
        when(reader.read(5L)).thenReturn(snap(card(null), true));
        when(details.detail("РК-1")).thenThrow(new RuntimeException("timeout"));
        LotModelFit fit = new LotModelFit(reader, details, VIEWS);
        LotModelFitResponse r = fit.fit(5L);
        assertThat(r.card()).isEqualTo("MISSING");
        assertThat(r.fit()).isNull();
        assertThat(r.error()).isEqualTo("Карточка РУ недоступна — повторите позже");
        assertThat(fit.prefetchCard("РК-1")).isFalse();
    }

    @Test
    void noModel_none_noCard_none() {
        LotModelReader reader = mock(LotModelReader.class);
        when(reader.read(5L)).thenReturn(new ModelSnapshot(TEXT, null, false, null, null, null, null, null),
                new ModelSnapshot(TEXT, null, false, null, MODEL, null, null, null));
        LotModelFit fit = new LotModelFit(reader, mock(RegistryDetailService.class), VIEWS);
        LotModelFitResponse none = fit.fit(5L);
        assertThat(none.card()).isEqualTo("NONE");
        assertThat(none.model()).isNull();
        LotModelFitResponse noCard = fit.fit(5L);
        assertThat(noCard.card()).isEqualTo("NONE");
        assertThat(noCard.model()).isNotNull();
    }
}
