package com.loanverify.api;

import com.loanverify.api.dto.ApplicationResponse;
import com.loanverify.api.dto.CreateApplicationRequest;
import com.loanverify.api.dto.DocumentMetadataRequest;
import com.loanverify.api.dto.DocumentResponse;
import com.loanverify.api.dto.VerificationProgressResponse;
import com.loanverify.api.dto.VerificationRequestResponse;
import com.loanverify.domain.Document;
import com.loanverify.domain.DocumentCategory;
import com.loanverify.security.CurrentUser;
import com.loanverify.security.Roles;
import com.loanverify.service.ApplicationService;
import com.loanverify.service.DocumentService;
import com.loanverify.service.IdempotencyService;
import com.loanverify.service.VerificationService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.UUID;

@Path("/api/v1/applications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.APPLICANT)
public class ApplicationResource {

    @Inject
    CurrentUser user;

    @Inject
    ApplicationService applications;

    @Inject
    DocumentService documents;

    @Inject
    VerificationService verification;

    @Inject
    IdempotencyService idempotency;

    @Context
    UriInfo uri;

    // ------------------------------------------------------------------ applications

    @POST
    public Response create(@HeaderParam("Idempotency-Key") String idempotencyKey,
                           @Valid @NotNull CreateApplicationRequest request) {
        String owner = user.id();
        var outcome = idempotency.execute(owner, "create-application", idempotencyKey, request, 201,
                ApplicationResponse.class, () -> applications.create(owner, request));
        return Response.status(outcome.status())
                .location(uri.getAbsolutePathBuilder().path(outcome.body().id().toString()).build())
                .header("Idempotent-Replayed", outcome.replayed())
                .entity(outcome.body())
                .build();
    }

    @GET
    public List<ApplicationResponse> list(@QueryParam("limit") @DefaultValue("50") @Min(1) @Max(200) int limit) {
        return applications.list(user.id(), limit);
    }

    @GET
    @Path("/{applicationId}")
    public ApplicationResponse get(@PathParam("applicationId") UUID applicationId) {
        return applications.get(user.id(), applicationId);
    }

    // ------------------------------------------------------------------ documents

    @POST
    @Path("/{applicationId}/documents")
    public Response addDocument(@PathParam("applicationId") UUID applicationId,
                                @Valid @NotNull DocumentMetadataRequest request) {
        Document doc = documents.add(user.id(), applicationId, request);
        return Response.created(documentUri(applicationId, doc))
                .tag(etag(doc))
                .entity(DocumentResponse.from(doc))
                .build();
    }

    /**
     * Replace the current document of a category with a new version. Send {@code If-Match: "<version>"} to make
     * the replace conditional on the version you last saw. Re-sending the same file reference and hash is a no-op.
     */
    @PUT
    @Path("/{applicationId}/documents/{category}")
    public Response replaceDocument(@PathParam("applicationId") UUID applicationId,
                                    @PathParam("category") DocumentCategory category,
                                    @HeaderParam("If-Match") String ifMatch,
                                    @Valid @NotNull DocumentMetadataRequest request) {
        if (request.category() != null && request.category() != category) {
            throw ApiException.badRequest("CATEGORY_MISMATCH", "Body category does not match the path");
        }
        var result = documents.replace(user.id(), applicationId, category, request, parseIfMatch(ifMatch));
        Response.ResponseBuilder rb = result.created()
                ? Response.created(documentUri(applicationId, result.document()))
                : Response.ok();
        return rb.tag(etag(result.document())).entity(DocumentResponse.from(result.document())).build();
    }

    @GET
    @Path("/{applicationId}/documents")
    public List<DocumentResponse> listDocuments(@PathParam("applicationId") UUID applicationId,
                                                @QueryParam("includeHistory") @DefaultValue("false") boolean includeHistory) {
        return documents.list(user.id(), applicationId, includeHistory).stream().map(DocumentResponse::from).toList();
    }

    @GET
    @Path("/{applicationId}/documents/by-id/{documentId}")
    public Response getDocument(@PathParam("applicationId") UUID applicationId,
                                @PathParam("documentId") UUID documentId) {
        Document doc = documents.get(user.id(), applicationId, documentId);
        return Response.ok(DocumentResponse.from(doc)).tag(etag(doc)).build();
    }

    // ------------------------------------------------------------------ verification

    /** Queue verification for every current document that needs it. Safe to call repeatedly. */
    @POST
    @Path("/{applicationId}/verification-requests")
    public Response requestVerification(@PathParam("applicationId") UUID applicationId) {
        VerificationRequestResponse body = verification.request(user.id(), applicationId);
        return Response.accepted(body).build();
    }

    @GET
    @Path("/{applicationId}/verification")
    public VerificationProgressResponse progress(@PathParam("applicationId") UUID applicationId) {
        return verification.progress(user.id(), applicationId);
    }

    // ------------------------------------------------------------------ helpers

    private java.net.URI documentUri(UUID applicationId, Document doc) {
        return uri.getBaseUriBuilder().path("api/v1/applications").path(applicationId.toString())
                .path("documents/by-id").path(doc.id.toString()).build();
    }

    private static EntityTag etag(Document d) {
        return new EntityTag(Integer.toString(d.version));
    }

    static Integer parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            return null;
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_IF_MATCH", "If-Match must be a document version such as \"2\"");
        }
    }
}
