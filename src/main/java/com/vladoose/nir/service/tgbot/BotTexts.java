package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.service.aianswer.AiAnswerResult;

import java.util.ArrayList;
import java.util.List;

/** Тексты бота (спека 2026-10-21-tender-telegram-bot §6–8), чистые функции; всё чужое — экранировано (HTML). */
public final class BotTexts {

    public static final String NO_ACCESS = "Нет доступа: нужна роль администратора в АИС";
    public static final String ASK_BLOCK = "Ответьте на это сообщение блоком «Для АИС» из конца ответа ИИ — "
            + "у блока кода есть кнопка «Копировать».";
    public static final String NO_BLOCK = "Блок «Для АИС» не найден — скопируйте блок кода из конца ответа ИИ и пришлите его ответом.";
    public static final String BRIEF_CAPTION = "Вставьте в Claude или Gemini. В конце ответа ИИ напишет блок «Для АИС» — "
            + "пришлите его мне ответом (кнопка «✍️ Ответ ИИ»).";
    private static final int MAX_VISIBLE = 3800;

    private BotTexts() {}

    public static String unknownUser(long telegramId) {
        return "Ваш Telegram ID " + telegramId + " — впишите его в «Пользователи» АИС (учётка администратора)";
    }

    /** «1 тендер», «3 тендера», «5 тендеров». */
    public static String tenders(int n) {
        int d = n % 10, h = n % 100;
        return n + " " + (d == 1 && h != 11 ? "тендер" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "тендера" : "тендеров");
    }

    public static String summary(AiAnswerResult r) {
        List<String> lines = new ArrayList<>();
        lines.add("🤖 Разобрал ответ ИИ — лотов: " + r.lots().size());
        r.lots().forEach(l -> lines.add(l.text()));
        if (!r.unparsed().isEmpty()) lines.add("⚠ Не понял строк: " + r.unparsed().size() + " — «" + r.unparsed().get(0) + "»");
        r.notes().forEach(n -> lines.add("• " + n));
        lines.add("");
        if (r.letters().isEmpty()) {
            lines.add("📨 Писем к отправке нет");
        } else {
            lines.add("📨 Письма к отправке:");
            for (int i = 0; i < r.letters().size(); i++) {
                AiAnswerResult.LetterPlan p = r.letters().get(i);
                lines.add((i + 1) + ". " + p.distributorName() + " (" + (p.manufacturer() ? "завод" : "доверенный") + ") — "
                        + String.join(", ", p.lotNames()) + (p.customer() == null ? "" : " · " + TelegramText.oneLine(p.customer(), 60)));
            }
        }
        StringBuilder out = new StringBuilder();
        int visible = 0;
        for (String line : lines) {
            if (visible + line.length() + 1 > MAX_VISIBLE) { out.append("\n…"); break; }
            if (!out.isEmpty()) out.append('\n');
            out.append(TelegramText.html(line));
            visible += line.length() + 1;
        }
        return out.toString();
    }

    /** Кнопки списка писем: «👁 N» — посмотреть; отправленные и закрытые — без действия. По 4 в ряд. */
    public static List<List<Button>> listKeyboard(List<BotLetter> drafts) {
        List<List<Button>> rows = new ArrayList<>();
        List<Button> row = new ArrayList<>();
        for (BotLetter b : drafts) {
            String n = String.valueOf(b.getLineNo());
            Button btn = switch (b.getStatus()) {
                case DRAFT, FAILED -> Button.cb("👁 " + n, "l:" + b.getId() + ":v");
                case SENDING -> Button.cb("⏳ " + n, "l:" + b.getId() + ":x");
                case SENT -> Button.cb("✅ " + n, "l:" + b.getId() + ":x");
                case ELSEWHERE -> Button.cb("✅ " + n + " · АИС", "l:" + b.getId() + ":x");
                case CANCELLED -> Button.cb("✕ " + n, "l:" + b.getId() + ":x");
            };
            row.add(btn);
            if (row.size() == 4) { rows.add(row); row = new ArrayList<>(); }
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }

    /** Подпись к фото бланка (≤ 1024 знаков после разбора разметки): номер письма, кому, тема, начало текста. */
    public static String caption(int n, int total, String supplier, boolean maker, String email, String subject, String body) {
        String head = "Письмо " + n + " из " + total + " · " + supplier + " (" + (maker ? "завод" : "доверенный") + ")\n"
                + "Кому: " + TelegramText.oneLine(email, 200) + "\n" + "Тема: " + TelegramText.oneLine(subject, 200) + "\n\n";
        String rest = body == null ? "" : body.strip();
        int room = 1000 - head.length();
        if (rest.length() > room) rest = TelegramText.safeCut(rest, Math.max(0, room - 1)) + "…";
        return TelegramText.html(head + rest);
    }

    public static String sent(String supplier, String user, String lots) {
        return TelegramText.html("✅ Письмо " + supplier + " отправлено · " + user + " (" + lots + ")");
    }

    public static String failed(String supplier, String why) {
        return TelegramText.html("⚠ Письмо " + supplier + " не ушло: " + why
                + " — нажмите «✅ Отправить» ещё раз (уйдёт тот же запрос) или откройте в АИС");
    }
}
