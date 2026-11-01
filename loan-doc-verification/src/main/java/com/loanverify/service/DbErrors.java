package com.loanverify.service;

import java.sql.SQLException;

/** Helpers to classify database errors by PostgreSQL SQLSTATE, regardless of how deeply they are wrapped. */
public final class DbErrors {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private DbErrors() {
    }

    public static boolean isUniqueViolation(Throwable t) {
        return hasSqlState(t, UNIQUE_VIOLATION);
    }

    public static boolean isLockFailure(Throwable t) {
        return hasSqlState(t, SERIALIZATION_FAILURE) || hasSqlState(t, DEADLOCK_DETECTED)
                || chainContains(t, jakarta.persistence.OptimisticLockException.class)
                || chainContains(t, org.hibernate.StaleStateException.class);
    }

    private static boolean hasSqlState(Throwable t, String state) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (state.equals(s.getSQLState())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean chainContains(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
