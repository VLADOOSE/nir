package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.service.aianswer.AiAnswerResult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** Тексты бота (спека 2026-10-21-tender-telegram-bot §6–8), чистые функции; всё чужое — экранировано (HTML). */
public final class BotTexts {

    public static final String NO_ACCESS = "Нет доступа: нужна роль администратора в АИС";
    public static final String ASK_BLOCK = "Ответьте на это сообщение блоком «Для АИС» из конца ответа ИИ — "
            + "у блока кода есть кнопка «Копировать».";
    public static final String NO_BLOCK = "Блок «Для АИС» не найден — скопируйте блок кода из конца ответа ИИ и пришлите его ответом.";
    public static final String BRIEF_CAPTION = "Вставьте в Claude или Gemini. В конце ответа ИИ напишет блок «Для АИС» — "
            + "пришлите его мне ответом (кнопка «✍️ Ответ ИИ»).";
    private static final int MAX_VISIBLE = 3800;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Место под строку «…и ещё N …» у раздела итога. */
    private static final int MORE = 80;
    /** Строка письма в итоге — не длиннее (письма видны всегда). */
    private static final int LETTER_MAX = 200;

    private BotTexts() {}

    public static String unknownUser(long telegramId) {
        return "Ваш Telegram ID " + telegramId + " — впишите его в «Пользователи» АИС (учётка администратора)";
    }

    /** «1 тендер», «3 тендера», «5 тендеров». */
    public static String tenders(int n) {
        int d = n % 10, h = n % 100;
        return n + " " + (d == 1 && h != 11 ? "тендер" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "тендера" : "тендеров");
    }

    /**
     * Итог разбора (≤ 3800 видимых знаков): письма видны всегда — это подписи кнопок «👁 N» (строка письма ≤ 200); лоты и
     * пометки — сколько поместится, остальное — «…и ещё N» (вывод ИИ у лотов — в АИС).
     */
    public static String summary(AiAnswerResult r) {
        String head = "🤖 Разобрал ответ ИИ — лотов: " + r.lots().size();
        List<String> letters = new ArrayList<>();
        if (r.letters().isEmpty()) {
            letters.add("📨 Писем к отправке нет");
        } else {
            letters.add("📨 Письма к отправке:");
            for (int i = 0; i < r.letters().size(); i++) {
                AiAnswerResult.LetterPlan p = r.letters().get(i);
                letters.add(TelegramText.oneLine((i + 1) + ". " + p.distributorName() + " (" + (p.manufacturer() ? "завод" : "доверенный")
                        + ") — " + String.join(", ", p.lotNames()) + (p.customer() == null ? "" : " · " + TelegramText.oneLine(p.customer(), 60)),
                        LETTER_MAX));
            }
        }
        List<String> extra = new ArrayList<>();
        if (!r.unparsed().isEmpty()) extra.add("⚠ Не понял строк: " + r.unparsed().size() + " — «" + r.unparsed().get(0) + "»");
        r.notes().forEach(n -> extra.add("• " + n));
        int room = MAX_VISIBLE - head.length() - 2;   // − перевод строки и пустая строка перед письмами
        List<String> lettersOut = new ArrayList<>();
        room -= fit(letters, room - 2 * MORE, lettersOut, k -> "…и ещё " + k + " — номера на кнопках ниже");
        List<String> lotsOut = new ArrayList<>();
        room -= fit(r.lots().stream().map(AiAnswerResult.LotResult::text).toList(), room - MORE, lotsOut,
                k -> "…и ещё " + lots(k) + " — вывод ИИ у лотов в АИС");
        List<String> extraOut = new ArrayList<>();
        fit(extra, room, extraOut, k -> "…и ещё пометок: " + k);
        List<String> all = new ArrayList<>();
        all.add(head);
        all.addAll(lotsOut);
        all.addAll(extraOut);
        all.add("");
        all.addAll(lettersOut);
        return String.join("\n", all.stream().map(TelegramText::html).toList());
    }

    /** Строки целиком, если влезают в room; иначе — сколько влезет и «…и ещё N». Возвращает занятое место. */
    private static int fit(List<String> lines, int room, List<String> out, IntFunction<String> more) {
        int total = lines.stream().mapToInt(l -> l.length() + 1).sum();
        if (total <= room) {
            out.addAll(lines);
            return total;
        }
        int used = 0;
        int i = 0;
        for (; i < lines.size(); i++) {
            int len = lines.get(i).length() + 1;
            if (used + len > room - MORE) break;
            out.add(lines.get(i));
            used += len;
        }
        String m = more.apply(lines.size() - i);
        out.add(m);
        return used + m.length() + 1;
    }

    /** «1 лот», «3 лота», «5 лотов». */
    static String lots(int n) {
        int d = n % 10, h = n % 100;
        return n + " " + (d == 1 && h != 11 ? "лот" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "лота" : "лотов");
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
                case MANUAL -> Button.cb("⏳ " + n + " · сам", "l:" + b.getId() + ":x");
                case HANDED -> Button.cb("✅ " + n + " · сам", "l:" + b.getId() + ":x");
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

    /**
     * Текст для получателя в WhatsApp («📋 Скопировать текст», не длиннее 256): номер запроса, позиции, больница, срок;
     * длинные позиции режутся первыми.
     */
    public static String whatsappText(long number, List<String> positions, String customer, LocalDate replyBy) {
        String company = brand(Market.KZ.companyShortName());   // «ТОО «West-Med»» → «West-Med»
        String head = "Здравствуйте! Это " + company + ", запрос цены № " + number + ": ";
        String who = customer == null || customer.isBlank() ? "" : " Конечный заказчик: " + TelegramText.oneLine(customer, 80) + ".";
        String tail = "." + who + " Просим закрепить проект за " + company
                + (replyBy == null ? "" : " и ответить до " + replyBy.format(DATE)) + ". Запрос во вложении.";
        int room = TelegramBotApi.COPY_MAX - head.length() - tail.length();
        // «… — 1 шт.» + «. Конечный заказчик…» — без двойной точки
        String pos = TelegramText.oneLine(String.join("; ", positions), Math.max(10, room)).replaceAll("\\.+$", "");
        return TelegramText.safeCut(head + pos + tail, TelegramBotApi.COPY_MAX);
    }

    private static String brand(String legal) {
        int a = legal.indexOf('«'), b = legal.lastIndexOf('»');
        return a >= 0 && b > a ? legal.substring(a + 1, b) : legal;
    }

    /** Подпись к PDF «📱 Отправлю сам»: как передать, текст для получателя, как подтвердить. */
    public static String manualCaption(String supplier, String text) {
        return TelegramText.html("📱 Передайте сами: нажмите на файл → «Поделиться» → WhatsApp → " + TelegramText.oneLine(supplier, 80)
                + ". Текст для получателя — кнопкой «📋 Скопировать текст»:\n\n" + text
                + "\n\nПотом нажмите «✅ Отправил». С рабочего номера, привязанного к АИС, отметится само.");
    }

    public static String handed(String supplier, String user, String lots) {
        return TelegramText.html("✅ Запрос " + supplier + " передан вручную · " + user + " (" + lots + ")");
    }

    public static String handedInWhatsapp(long number, String supplier, String chat, String time) {
        return TelegramText.html("✅ Запрос № " + number + " · " + supplier + " — передан в WhatsApp: "
                + TelegramText.oneLine(chat == null ? "—" : chat, 80) + " · " + time);
    }

    public static String failed(String supplier, String why) {
        return TelegramText.html("⚠ Письмо " + supplier + " не ушло: " + why
                + " — нажмите «✅ Отправить» ещё раз (уйдёт тот же запрос) или откройте в АИС");
    }
}
