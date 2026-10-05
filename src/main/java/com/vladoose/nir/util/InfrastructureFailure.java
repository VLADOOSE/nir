package com.vladoose.nir.util;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Set;

/**
 * Сбой инфраструктуры (база, диск, соединение), а не поломка конкретного письма или сообщения — по всей цепочке
 * причин: по типу исключения и классу SQLSTATE (08, 40, 53, 57, 58). Общий признак приёма WhatsApp и почты:
 * инфраструктурный сбой — пауза и повтор, а не «ядовитое» сообщение (CLAUDE.md §14).
 */
public final class InfrastructureFailure {

    /** Предел глубины цепочки причин — от циклов, которые не самоссылка. */
    private static final int MAX_CAUSE_DEPTH = 32;
    /** Классы SQLSTATE: 08 соединение, 40 откат (deadlock), 53 ресурсы (диск/память), 57 вмешательство, 58 система. */
    private static final Set<String> INFRA_SQLSTATE_CLASSES = Set.of("08", "40", "53", "57", "58");

    private InfrastructureFailure() {}

    public static boolean test(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < MAX_CAUSE_DEPTH; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataAccessResourceFailureException || t instanceof CannotCreateTransactionException
                    || t instanceof TransientDataAccessException || t instanceof RecoverableDataAccessException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().length() >= 2
                    && INFRA_SQLSTATE_CLASSES.contains(sql.getSQLState().substring(0, 2))) {
                return true;
            }
        }
        return false;
    }
}
