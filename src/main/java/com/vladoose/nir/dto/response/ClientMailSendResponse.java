package com.vladoose.nir.dto.response;

import java.util.List;

/** Итог письма: recorded = false — письмо ушло, но в АИС не записалось (warning — что сказать человеку). */
public record ClientMailSendResponse(boolean recorded, List<Long> chatIds, List<Long> messageIds, List<Long> offersSent,
                                     String warning) {}
