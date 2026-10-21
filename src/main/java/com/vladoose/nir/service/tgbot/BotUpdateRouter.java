package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Нажатия и ответы боту (спека 2026-10-21-tender-telegram-bot §8): только группа TELEGRAM_CHAT_ID, только администратор АИС
 * с этим Telegram ID; действие — от его имени (BotRunAs). Сбой одного действия — свой текст в ответ, цикл живёт.
 */
@Component
public class BotUpdateRouter {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateRouter.class);

    private final TelegramBotApi api;
    private final UserAccountRepository users;
    private final BotRunAs runAs;
    private final BotBriefSender briefs;
    private final BotAnswerHandler answers;
    private final BotLetterActions letters;

    public BotUpdateRouter(TelegramBotApi api, UserAccountRepository users, BotRunAs runAs, BotBriefSender briefs,
                           BotAnswerHandler answers, BotLetterActions letters) {
        this.api = api;
        this.users = users;
        this.runAs = runAs;
        this.briefs = briefs;
        this.answers = answers;
        this.letters = letters;
    }

    public void handle(BotUpdate u) {
        if (u.callback() != null) onCallback(u.callback());
        else if (u.message() != null) onMessage(u.message());
    }

    private void onCallback(BotUpdate.Callback c) {
        if (c.chatId() != api.chatId()) return;
        UserAccount user = allowed(c.fromId(), text -> api.answerCallback(c.id(), text, true));
        if (user == null) return;
        String[] p = c.data() == null ? new String[0] : c.data().split(":");
        long id;
        try {
            id = p.length == 3 ? Long.parseLong(p[1]) : -1;
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id < 0) {
            api.answerCallback(c.id(), "Кнопка устарела", false);
            return;
        }
        long target = id;
        run(user, c.messageId(), () -> {
            switch (p[0] + ":" + p[2]) {
                case "d:ai" -> briefs.sendBrief(c, target);
                case "d:ans" -> briefs.askAnswer(c, target);
                case "l:v" -> letters.view(c, target);
                case "l:s" -> letters.send(c, target);
                case "l:b" -> letters.back(c, target);
                case "l:x" -> api.answerCallback(c.id(), "Письмо уже обработано", false);
                default -> api.answerCallback(c.id(), "Кнопка устарела", false);
            }
        });
    }

    private void onMessage(BotUpdate.Incoming m) {
        if (m.chatId() != api.chatId() || m.replyToFromId() == null || m.replyToFromId() != api.botId()) return;
        UserAccount user = allowed(m.fromId(), text -> api.sendMessage(TelegramText.html(text), true, null, m.messageId()));
        if (user == null) return;
        run(user, m.messageId(), () -> answers.handle(m));
    }

    /** Учётка АИС с этим Telegram ID и ролью администратора; нет — объяснение нажавшему и null. */
    private UserAccount allowed(long telegramId, Consumer<String> explain) {
        UserAccount u = users.findByTelegramUserId(telegramId).orElse(null);
        if (u == null) {
            explain.accept(BotTexts.unknownUser(telegramId));
            return null;
        }
        if (!"ROLE_ADMIN".equals(u.getRole())) {
            explain.accept(BotTexts.NO_ACCESS);
            return null;
        }
        return u;
    }

    private void run(UserAccount user, long replyTo, Runnable work) {
        try {
            runAs.run(user, work);
        } catch (BadRequestException | NotFoundException e) {
            api.sendMessage(TelegramText.html("Не получилось: " + e.getMessage() + " — повторите или откройте в АИС"), true, null, replyTo);
        } catch (RuntimeException e) {
            log.warn("Кнопки бота: действие не выполнено: {}", e.getClass().getSimpleName());
            api.sendMessage(TelegramText.html("Не получилось (" + e.getClass().getSimpleName() + ") — повторите или откройте в АИС"),
                    true, null, replyTo);
        }
    }
}
