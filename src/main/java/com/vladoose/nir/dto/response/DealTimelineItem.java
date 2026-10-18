package com.vladoose.nir.dto.response;

import java.time.OffsetDateTime;

/** Строка ленты сделки. dateOnly — время неизвестно (дата в полдень по поясу рынка); message — у сообщений чата. */
public record DealTimelineItem(OffsetDateTime at, boolean dateOnly, String kind, String text, String author,
                               String linkType, Long linkId, ChatMessageResponse message) {}
