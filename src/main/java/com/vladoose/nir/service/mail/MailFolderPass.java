package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.util.InfrastructureFailure;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Проход по одной папке ящика — общий у zakup@ и info@ (спека 2026-10-05-zakup-mail-telegram §3): курсор по UID;
 * первый запуск (курсора нет или сменился UIDVALIDITY) — только окно, затем курсор на последнее письмо, снятое ДО
 * окна (письмо, пришедшее во время прохода, уйдёт следующим, а не перепрыгнется); сбои — §3.4: битое письмо —
 * короткая строка и дальше, обрыв связи или недоступная база — стоп, курсор на последнем записанном.
 * Транзакций проход не держит: запись и курсор — транзакционные бины за {@link Target}.
 */
public final class MailFolderPass {

    /** Причина остановки — свой текст для ответа «Проверить почту» (спека §3.4). */
    public static final String CONNECTION_LOST = "связь с почтой оборвалась";

    /** Что проход делает с письмами папки. */
    public interface Target<R> {
        /** Курсор папки; пусто — первый запуск. Сбой базы — исключение (проход стоп). */
        Optional<MailCursor> cursor();

        /** Запись письма — одна транзакция; не записалось — исключение. */
        R write(ParsedMail m, long uidValidity);

        /** Короткая строка вместо письма, которое не разобралось или не записалось. */
        void writeBroken(BrokenMail b, long uidValidity);

        /** Первый запуск: курсор — на последнее письмо папки, снятое до окна (0 — папка пуста). */
        void moveCursorTo(long uidValidity, long uid);

        /** Письмо записано — для итога прохода. */
        void written(R result);

        /** Записана короткая строка — для итога прохода. */
        void broken();
    }

    private MailFolderPass() {}

    /**
     * null — проход дошёл до конца; иначе — почему остановлен. where — приставка строк лога: у zakup@ пусто, у info@ —
     * ящик и папка (UID у каждой папки свой). stackTraces — разовый сбой письма в лог со стеком (zakup@); у ящика
     * клиентов — false: только классы исключений, в их тексте бывают имена файлов и адреса клиентов (персональные данные).
     */
    public static <R> String run(MailboxSession s, Target<R> t, Instant windowStart, int maxPerPass, Clock clock,
                                 Logger log, String where, boolean stackTraces) throws MessagingException {
        long uidValidity = s.uidValidity();
        Optional<MailCursor> cursor = t.cursor().filter(c -> c.getUidValidity() == uidValidity);
        if (cursor.isPresent()) {
            for (long uid : s.uidsAfter(cursor.get().getLastUid(), maxPerPass)) {
                String stopped = processOne(s, t, uidValidity, uid, clock, log, where, stackTraces);
                if (stopped != null) return stopped;
            }
            return null;
        }
        long max = s.maxUid();
        for (long uid : s.uidsReceivedSince(windowStart, maxPerPass)) {
            if (uid > max) continue;
            String stopped = processOne(s, t, uidValidity, uid, clock, log, where, stackTraces);
            if (stopped != null) return stopped;
        }
        t.moveCursorTo(uidValidity, max);
        return null;
    }

    private static <R> String processOne(MailboxSession s, Target<R> t, long uidValidity, long uid, Clock clock,
                                         Logger log, String where, boolean stackTraces) {
        ParsedMail m;
        try {
            m = s.fetch(uid);
        } catch (Exception e) {
            if (!s.isAlive()) {
                log.warn("{}Связь с ящиком оборвалась на письме UID {}: {}", where, uid, e.getClass().getSimpleName());
                return CONNECTION_LOST;
            }
            return writeBroken(t, s.envelope(uid, e), e, "не разобрано", uidValidity, clock, log, where, stackTraces);
        }
        if (m == null) return null;                                 // письмо удалили между поиском и чтением
        R written;
        try {
            written = t.write(m, uidValidity);
        } catch (RuntimeException e) {
            if (InfrastructureFailure.test(e)) {
                log.warn("{}База недоступна на письме UID {} — проход остановлен, повтор следующим: {}", where, uid,
                        e.getClass().getSimpleName());
                return dbDown(e);
            }
            return writeBroken(t, new BrokenMail(uid, m.messageId(), m.from(), m.subject(), m.receivedAt(),
                    e.getClass().getSimpleName()), e, "не записано", uidValidity, clock, log, where, stackTraces);
        }
        t.written(written);                                         // вне try: записанное письмо коротко не пишем
        return null;
    }

    /**
     * Короткая строка вместо письма, которое не разобралось или не записалось (cause). Не записалась и она (не база) —
     * ещё раз, минимальная: без отправителя, темы и Message-ID — в полях письма и бывает причина (символ, который база
     * не хранит). Записалась — письмо пройдено, сбой разовый: в лог со стеком. Не записалась — проход стоп, курсор на
     * месте: то же письмо упадёт и на следующем проходе, раз в минуту, поэтому в лог без стека — классы и UID.
     */
    private static <R> String writeBroken(Target<R> t, BrokenMail b, Exception cause, String what, long uidValidity,
                                          Clock clock, Logger log, String where, boolean stackTraces) {
        RuntimeException withFields = tryWriteBroken(t, b, uidValidity);
        RuntimeException failure = withFields;
        if (withFields != null && !InfrastructureFailure.test(withFields)) {
            failure = tryWriteBroken(t, new BrokenMail(b.uid(), null, null, null,
                    b.receivedAt() != null ? b.receivedAt() : OffsetDateTime.now(clock), b.errorClass()), uidValidity);
        }
        if (failure == null) {
            // со стеком — объект исключения последним аргументом; без стека — только его класс
            Object detail = stackTraces ? cause : cause.getClass().getSimpleName();
            if (withFields == null) {
                log.warn("{}Письмо UID {} {} — записано коротко" + (stackTraces ? "" : " ({})"), where, b.uid(), what, detail);
            } else {
                log.warn("{}Письмо UID {} {} — записано коротко, без полей письма (с ними не записалось: {})"
                        + (stackTraces ? "" : " ({})"), where, b.uid(), what, withFields.getClass().getSimpleName(), detail);
            }
            t.broken();
            return null;
        }
        log.warn("{}Письмо UID {} {} ({}), короткая строка не записалась ({}) — проход остановлен, повтор следующим",
                where, b.uid(), what, cause.getClass().getSimpleName(), failure.getClass().getSimpleName());
        return InfrastructureFailure.test(failure) ? dbDown(failure)
                : "письмо UID " + b.uid() + " не записалось (" + failure.getClass().getSimpleName() + ")";
    }

    /** null — записано; иначе — чем не записалось. */
    private static <R> RuntimeException tryWriteBroken(Target<R> t, BrokenMail b, long uidValidity) {
        try {
            t.writeBroken(b, uidValidity);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** «база данных недоступна (класс)» — свой текст: в исключениях базы бывают SQL и значения. */
    public static String dbDown(Exception e) {
        return "база данных недоступна (" + e.getClass().getSimpleName() + ")";
    }
}
