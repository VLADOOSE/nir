package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MatchRequest {

    public enum Preset { BALANCED, MAX_PROFIT, RELIABILITY, CUSTOM }

    private Preset preset = Preset.BALANCED;

    @Valid
    private Weights weights;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Weights {
        @PositiveOrZero(message = "Вес должен быть >= 0")
        private int price;
        @PositiveOrZero(message = "Вес должен быть >= 0")
        private int margin;
        @PositiveOrZero(message = "Вес должен быть >= 0")
        private int track;
        @PositiveOrZero(message = "Вес должен быть >= 0")
        private int dim;
    }

    /** Возвращает нормализованные веса [price, margin, track, dim] с Σ=1.0. */
    public double[] resolveWeights() {
        int[] raw = switch (preset) {
            case BALANCED    -> new int[] { 25, 25, 25, 25 };
            case MAX_PROFIT  -> new int[] { 35, 40, 15, 10 };
            case RELIABILITY -> new int[] { 15, 15, 50, 20 };
            case CUSTOM -> {
                if (weights == null) {
                    throw new IllegalArgumentException("weights required when preset=CUSTOM");
                }
                int[] w = new int[] { weights.price, weights.margin, weights.track, weights.dim };
                int sum = w[0] + w[1] + w[2] + w[3];
                if (sum == 0) {
                    throw new IllegalArgumentException("at least one weight must be > 0");
                }
                yield w;
            }
        };
        double sum = raw[0] + raw[1] + raw[2] + raw[3];
        return new double[] { raw[0] / sum, raw[1] / sum, raw[2] / sum, raw[3] / sum };
    }
}
