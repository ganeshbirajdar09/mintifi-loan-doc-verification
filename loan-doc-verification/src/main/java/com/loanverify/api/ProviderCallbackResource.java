package com.loanverify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanverify.api.dto.ErrorResponse;
import com.loanverify.domain.CallbackDisposition;
import com.loanverify.provider.CallbackSigner;
import com.loanverify.provider.ProviderCallback;
import com.loanverify.service.CallbackService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Webhook the verification provider calls. Not JWT-protected: the provider authenticates by HMAC-signing
 * the raw body (see {@link CallbackSigner}).
 * <p>
 * Every authenticated, well-formed callback gets 200, including duplicates, stale and unknown refs. That
 * tells the provider to stop retrying. The disposition is returned and stored for audit.
 */
@Path("/provider/callbacks")
@PermitAll
public class ProviderCallbackResource {

    private static final Logger LOG = Logger.getLogger(ProviderCallbackResource.class);

    @Inject
    CallbackSigner signer;

    @Inject
    CallbackService callbacks;

    @Inject
    ObjectMapper mapper;

    @Inject
    Validator validator;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receive(@HeaderParam(CallbackSigner.TIMESTAMP_HEADER) String timestamp,
                            @HeaderParam(CallbackSigner.SIGNATURE_HEADER) String signature,
                            String rawBody) {
        // Read as String: Quarkus REST hands String bodies over untouched (Jackson does not claim them),
        // so these are exactly the bytes the provider signed.
        byte[] body = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
        if (!signer.verify(timestamp, signature, body, Instant.now())) {
            LOG.warn("Rejected provider callback with missing/invalid signature or timestamp");
            return error(401, "INVALID_SIGNATURE", "Missing or invalid callback signature");
        }

        ProviderCallback cb;
        try {
            cb = mapper.readValue(body, ProviderCallback.class);
        } catch (Exception e) {
            return error(400, "MALFORMED_CALLBACK", "Callback body is not valid JSON for this schema");
        }
        Set<ConstraintViolation<ProviderCallback>> violations = validator.validate(cb);
        if (!violations.isEmpty()) {
            return error(400, "MALFORMED_CALLBACK", "Callback body failed validation");
        }

        CallbackDisposition disposition = callbacks.handle(cb);
        return Response.ok(Map.of("eventId", cb.eventId(), "disposition", disposition.name())).build();
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(status, code, message)).build();
    }
}
