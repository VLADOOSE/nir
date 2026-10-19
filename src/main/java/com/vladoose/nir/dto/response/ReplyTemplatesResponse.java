package com.vladoose.nir.dto.response;

import java.util.List;

/** Шаблоны рынка без подписи, подпись (как есть, с {{сотрудник}}) и состояние отправки с info@. */
public record ReplyTemplatesResponse(List<ReplyTemplateDto> templates, String signature, Sending sending) {
    public record Sending(boolean enabled, String mailbox, String reason) {}
}
