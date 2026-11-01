package com.loanverify.api;

import com.loanverify.domain.ProviderOutcome;
import com.loanverify.provider.SimulatedVerificationProvider;
import com.loanverify.provider.SimulatedVerificationProvider.DeliveryReport;
import com.loanverify.provider.SimulatedVerificationProvider.JobState;
import com.loanverify.provider.SimulatedVerificationProvider.JobView;
import com.loanverify.provider.SimulatedVerificationProvider.Settings;
import com.loanverify.config.AppConfig;
import com.loanverify.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Optional;

/**
 * Admin controls for the provider simulator: change how unreliable it is, inspect what it received, and
 * trigger callbacks by hand (MANUAL mode) to reproduce races. Requires the admin role, and returns 404
 * when the simulator is disabled.
 */
@Path("/simulator")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN)
public class SimulatorResource {

    @Inject
    SimulatedVerificationProvider simulator;

    @Inject
    AppConfig config;

    public record DeliverRequest(@NotNull ProviderOutcome outcome,
                                 @Min(1) @Max(20) Integer times,
                                 @Size(max = 500) String reason) {
    }

    @GET
    @Path("/settings")
    public Settings settings() {
        enabled();
        return simulator.settings();
    }

    @PUT
    @Path("/settings")
    public Settings update(@NotNull Settings settings) {
        enabled();
        simulator.updateSettings(settings);
        return simulator.settings();
    }

    @DELETE
    @Path("/settings")
    public Settings reset() {
        enabled();
        simulator.reset();
        return simulator.settings();
    }

    @GET
    @Path("/jobs")
    public List<JobView> jobs(@QueryParam("state") JobState state,
                              @QueryParam("limit") @DefaultValue("100") @Min(1) @Max(1000) int limit) {
        enabled();
        return simulator.jobs(Optional.ofNullable(state)).stream().limit(limit).toList();
    }

    @GET
    @Path("/jobs/{externalRef}")
    public JobView job(@PathParam("externalRef") String externalRef) {
        enabled();
        return simulator.job(externalRef).orElseThrow(() -> ApiException.notFound("Simulator job"));
    }

    /** Sends a signed callback for the job now. {@code times > 1} redelivers the same event. */
    @POST
    @Path("/jobs/{externalRef}/callbacks")
    public DeliveryReport deliver(@PathParam("externalRef") String externalRef, @Valid @NotNull DeliverRequest request) {
        enabled();
        simulator.job(externalRef).orElseThrow(() -> ApiException.notFound("Simulator job"));
        int times = request.times() == null ? 1 : request.times();
        return simulator.deliverNow(externalRef, request.outcome(), times, request.reason());
    }

    private void enabled() {
        if (!config.simulator().enabled()) {
            throw ApiException.notFound("Simulator");
        }
    }
}
