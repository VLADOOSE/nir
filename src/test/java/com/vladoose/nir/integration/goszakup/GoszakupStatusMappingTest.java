package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Маппинг ref_buy_status_id → доменный статус (словарь /v2/refs/ref_buy_status, снят токеном). */
class GoszakupStatusMappingTest {

    @Test
    void publishedStatuses_mapToActive() {
        // 210..245 — «Опубликовано…»: приём заявок/ценовых предложений идёт
        for (int id : new int[]{210, 220, 230, 240, 245}) {
            assertThat(GoszakupTenderWriter.mapStatus(id)).as("status %d", id).isEqualTo("ACTIVE");
        }
    }

    @Test
    void reviewAndFinishedStatuses_mapToCompleted() {
        // 250..350+ — рассмотрение/протоколы/итоги: подать заявку уже нельзя
        for (int id : new int[]{250, 280, 310, 330, 350, 460, 510}) {
            assertThat(GoszakupTenderWriter.mapStatus(id)).as("status %d", id).isEqualTo("COMPLETED");
        }
    }

    @Test
    void cancelledStatuses_mapToCancelled() {
        // 410 отказ, 420 приостановлено, 430 отменено
        for (int id : new int[]{410, 420, 430}) {
            assertThat(GoszakupTenderWriter.mapStatus(id)).as("status %d", id).isEqualTo("CANCELLED");
        }
    }

    @Test
    void nullStatus_defaultsToActive() {
        assertThat(GoszakupTenderWriter.mapStatus(null)).isEqualTo("ACTIVE");
    }
}
