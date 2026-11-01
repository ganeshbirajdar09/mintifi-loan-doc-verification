package com.loanverify.security;

import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** The authenticated caller. The JWT subject is the owner id stored on every application. */
@RequestScoped
public class CurrentUser {

    @Inject
    JsonWebToken jwt;

    public String id() {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank() || sub.length() > 128) {
            throw new UnauthorizedException("Token has no usable subject");
        }
        return sub;
    }
}
