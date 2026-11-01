package com.loanverify.security;

public final class Roles {
    /** Applicants manage their own loan applications. */
    public static final String APPLICANT = "applicant";
    /** Operators control the provider simulator. */
    public static final String ADMIN = "admin";

    private Roles() {
    }
}
