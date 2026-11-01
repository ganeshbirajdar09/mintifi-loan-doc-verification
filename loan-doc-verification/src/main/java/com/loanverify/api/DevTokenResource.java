package com.loanverify.api;

import com.loanverify.config.AppConfig;
import com.loanverify.security.Roles;
import io.smallrye.jwt.build.Jwt;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Set;

/**
 * LOCAL DEVELOPMENT ONLY. Issues signed JWTs so the API can be tried without an identity provider.
 * Disabled unless {@code app.dev-auth.enabled=true} (on in dev/test profiles and in docker-compose).
 * In a real deployment tokens come from the organisation's IdP and this endpoint stays off.
 */
@Path("/dev/token")
@PermitAll
public class DevTokenResource {

    @Inject
    AppConfig config;

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    public record TokenRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_\\-.@]+$") String subject,
            @NotNull Set<@Pattern(regexp = "^(applicant|admin)$") String> roles) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public TokenResponse issue(@Valid @NotNull TokenRequest request) {
        if (!config.devAuth().enabled()) {
            throw ApiException.notFound("Resource");
        }
        long ttl = config.devAuth().tokenTtl().toSeconds();
        String token = Jwt.issuer(issuer)
                .audience("loan-verification-api")
                .subject(request.subject())
                .groups(request.roles().isEmpty() ? Set.of(Roles.APPLICANT) : request.roles())
                .issuedAt(Instant.now())
                .expiresIn(ttl)
                .sign();
        return new TokenResponse(token, "Bearer", ttl);
    }
}
