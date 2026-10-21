package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.dto.response.AiBriefResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductDigest;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.repository.ProductDigestRepository;
import com.vladoose.nir.service.productwatch.ProductAiBrief;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** «📋 Для ИИ» и «✍️ Ответ ИИ» у сообщения-пачки (спека 2026-10-21-tender-telegram-bot §5, §3 п. 2–4). */
@Component
public class BotBriefSender {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final TelegramBotApi api;
    private final ProductDigestRepository digests;
    private final ProductAiBrief brief;
    private final BotPrompts prompts;

    public BotBriefSender(TelegramBotApi api, ProductDigestRepository digests, ProductAiBrief brief, BotPrompts prompts) {
        this.api = api;
        this.digests = digests;
        this.brief = brief;
        this.prompts = prompts;
    }

    public void sendBrief(BotUpdate.Callback c, long digestId) {
        ProductDigest d = digests.findById(digestId).orElse(null);
        if (d == null) {
            api.answerCallback(c.id(), "Пачка не найдена — откройте тендеры в АИС", true);
            return;
        }
        api.answerCallback(c.id(), "Собираю текст для ИИ…", false);
        AiBriefResponse r = brief.forTenders(d.getTenderIds());
        String name = "Для ИИ — " + BotTexts.tenders(r.tenders()) + ", " + LocalDateTime.now(Market.KZ.zone()).format(STAMP) + ".txt";
        long mid = api.sendDocument(name, r.text().getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8",
                BotTexts.BRIEF_CAPTION, List.of(List.of(Button.cb("✍️ Ответ ИИ", "d:" + digestId + ":ans"))), c.messageId());
        prompts.add(mid);
    }

    public void askAnswer(BotUpdate.Callback c, long digestId) {
        api.answerCallback(c.id(), null, false);
        prompts.add(api.sendForceReply(BotTexts.ASK_BLOCK, c.messageId(), "Вставьте блок «Для АИС»"));
    }
}
