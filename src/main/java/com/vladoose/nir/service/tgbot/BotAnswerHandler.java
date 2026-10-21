package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.service.aianswer.AiAnswerResult;
import com.vladoose.nir.service.aianswer.AiAnswerService;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.List;

/** Ответ боту блоком «Для АИС» (спека 2026-10-21-tender-telegram-bot §6–7): разбор, черновики писем, одно сообщение-итог. */
@Component
public class BotAnswerHandler {

    private final AiAnswerService answers;
    private final BotLetterStore store;
    private final TelegramBotApi api;
    private final BotPrompts prompts;

    public BotAnswerHandler(AiAnswerService answers, BotLetterStore store, TelegramBotApi api, BotPrompts prompts) {
        this.answers = answers;
        this.store = store;
        this.api = api;
        this.prompts = prompts;
    }

    public void handle(BotUpdate.Incoming m) {
        String text = m.text() == null ? "" : m.text();
        AiAnswerResult r = text.isBlank() ? null : answers.apply(text);
        if (r == null || r.isEmpty()) {
            if (prompts.contains(m.replyToMessageId())) api.sendMessage(BotTexts.NO_BLOCK, true, null, m.messageId());
            return;
        }
        List<BotLetter> drafts = store.createDrafts(r.letters(), CurrentUser.label());
        long listId = api.sendMessage(BotTexts.summary(r), true, BotTexts.listKeyboard(drafts), m.messageId());
        store.attachList(drafts.stream().map(BotLetter::getId).toList(), listId);
    }
}
