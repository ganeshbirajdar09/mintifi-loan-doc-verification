package com.loanverify.api;

import com.loanverify.api.dto.ErrorResponse;
import com.loanverify.service.DbErrors;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.List;

/**
 * Maps exceptions to a consistent JSON error body. Internal details (stack traces, SQL, class names) never
 * reach the client. Unexpected errors get logged with a reference the client can quote.
 */
public class ExceptionMappers {

    private static final Logger LOG = Logger.getLogger(ExceptionMappers.class);

    @ServerExceptionMapper
    public Response api(ApiException e) {
        return json(e.status(), new ErrorResponse(e.status(), e.code(), e.getMessage()));
    }

    @ServerExceptionMapper
    public Response validation(ConstraintViolationException e) {
        List<ErrorResponse.FieldError> fields = e.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .sorted((a, b) -> a.field().compareTo(b.field()))
                .toList();
        return json(400, new ErrorResponse(400, "VALIDATION_FAILED", "Request validation failed", fields));
    }

    @ServerExceptionMapper
    public Response optimisticLock(OptimisticLockException e) {
        return json(409, new ErrorResponse(409, "CONCURRENT_MODIFICATION",
                "The resource was modified concurrently. Retry the request."));
    }

    @ServerExceptionMapper
    public Response webApplication(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        if (status >= 500) {
            LOG.error("Web application error", e);
        }
        String code = switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "HTTP_" + status;
        };
        String message = status == 400 ? "Malformed request" : e.getResponse().getStatusInfo().getReasonPhrase();
        return json(status, new ErrorResponse(status, code, message));
    }

    @ServerExceptionMapper
    public Response unreadableJson(com.fasterxml.jackson.core.JsonProcessingException e) {
        // Don't echo parser messages back: they can contain fragments of the payload.
        String detail = e instanceof com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException u
                ? "Unknown field '" + u.getPropertyName() + "'"
                : "Malformed or invalid JSON body";
        return json(400, new ErrorResponse(400, "MALFORMED_REQUEST", detail));
    }

    @ServerExceptionMapper
    public Response unauthorized(io.quarkus.security.UnauthorizedException e) {
        return json(401, new ErrorResponse(401, "UNAUTHORIZED", "Authentication required"));
    }

    @ServerExceptionMapper
    public Response authFailed(io.quarkus.security.AuthenticationFailedException e) {
        return json(401, new ErrorResponse(401, "UNAUTHORIZED", "Authentication required"));
    }

    @ServerExceptionMapper
    public Response forbidden(io.quarkus.security.ForbiddenException e) {
        return json(403, new ErrorResponse(403, "FORBIDDEN", "Not allowed"));
    }

    @ServerExceptionMapper
    public Response unexpected(RuntimeException e) {
        if (DbErrors.isUniqueViolation(e)) {
            return json(409, new ErrorResponse(409, "CONFLICT",
                    "The request conflicts with a concurrent change. Retry the request."));
        }
        if (DbErrors.isLockFailure(e)) {
            return json(409, new ErrorResponse(409, "CONCURRENT_MODIFICATION",
                    "The resource was modified concurrently. Retry the request."));
        }
        String ref = java.util.UUID.randomUUID().toString();
        LOG.errorf(e, "Unhandled error (ref=%s)", ref);
        return json(500, new ErrorResponse(500, "INTERNAL_ERROR", "Unexpected error. Reference: " + ref));
    }

    private static Response json(int status, ErrorResponse body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static String lastNode(String path) {
        // "create.request.applicantName" -> "applicantName"
        int i = path.lastIndexOf('.');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
