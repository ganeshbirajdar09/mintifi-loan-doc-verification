package com.loanverify.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanverify.config.AppConfig;
import com.loanverify.domain.ProviderOutcome;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process stand-in for the external verification provider. It behaves like an unreliable real provider:
 * <ul>
 *   <li>rejects some submissions outright (we must retry with backoff),</li>
 *   <li>answers asynchronously after a random delay, so results arrive out of order,</li>
 *   <li>sometimes delivers the same callback more than once,</li>
 *   <li>sometimes never answers (we must time out and re-submit),</li>
 *   <li>signs callbacks with HMAC and POSTs them over real HTTP to our callback endpoint.</li>
 * </ul>
 * In MANUAL mode nothing is answered automatically. Callbacks are triggered through the admin API or tests,
 * which makes race conditions reproducible.
 * <p>
 * Filename hints (AUTO mode) give deterministic demos: a filename containing {@code reject} is rejected,
 * {@code drop} is never answered, {@code dup} is delivered three times, {@code slow} uses the max delay.
 */
@ApplicationScoped
public class SimulatedVerificationProvider implements VerificationProvider {

    private static final Logger LOG = Logger.getLogger(SimulatedVerificationProvider.class);
    private static final int MAX_TRACKED_JOBS = 5_000;
    private static final int MAX_DELIVERY_RETRIES = 5;

    public enum Mode { AUTO, MANUAL }

    public record Settings(Mode mode, long minDelayMs, long maxDelayMs, double submitFailureRate,
                           double dropRate, double duplicateRate, double rejectRate) {

        public Settings {
            if (mode == null) throw new IllegalArgumentException("mode is required");
            if (minDelayMs < 0 || maxDelayMs < minDelayMs || maxDelayMs > 600_000) {
                throw new IllegalArgumentException("require 0 <= minDelayMs <= maxDelayMs <= 600000");
            }
            for (double rate : new double[]{submitFailureRate, dropRate, duplicateRate, rejectRate}) {
                if (rate < 0 || rate > 1) throw new IllegalArgumentException("rates must be between 0 and 1");
            }
        }
    }

    public enum JobState { AWAITING_MANUAL, SCHEDULED, DROPPED, DELIVERED }

    /** What the simulator knows about a submission. Exposed read-only through the admin API. */
    public record JobView(String externalRef, String category, String filename, JobState state,
                          ProviderOutcome plannedOutcome, int timesSubmitted, int deliveriesAttempted,
                          Instant firstSubmittedAt, Instant lastSubmittedAt) {
    }

    /** Outcome of a manual delivery: HTTP status of each POST to the callback endpoint (-1 on I/O error). */
    public record DeliveryReport(String externalRef, String eventId, ProviderOutcome outcome, List<Integer> statuses) {
    }

    private static final class Job {
        final String externalRef;
        final ProviderSubmission submission;
        final Instant firstSubmittedAt;
        volatile Instant lastSubmittedAt;
        volatile int timesSubmitted;
        volatile int deliveriesAttempted;
        volatile JobState state;
        volatile ProviderOutcome plannedOutcome;

        Job(ProviderSubmission submission, Instant now) {
            this.externalRef = submission.externalRef();
            this.submission = submission;
            this.firstSubmittedAt = now;
            this.lastSubmittedAt = now;
        }

        JobView view() {
            return new JobView(externalRef, submission.category(), submission.filename(), state, plannedOutcome,
                    timesSubmitted, deliveriesAttempted, firstSubmittedAt, lastSubmittedAt);
        }
    }

    @Inject
    AppConfig config;

    @Inject
    CallbackSigner signer;

    @Inject
    ObjectMapper mapper;

    private final AtomicReference<Settings> settings = new AtomicReference<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private HttpClient http;

    @PostConstruct
    void init() {
        settings.set(configuredSettings());
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "provider-simulator");
            t.setDaemon(true);
            return t;
        });
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ provider API

    @Override
    public void submit(ProviderSubmission submission) throws ProviderException {
        if (!config.simulator().enabled()) {
            throw new ProviderException("No verification provider configured (simulator disabled)");
        }
        Settings s = settings.get();
        if (chance(s.submitFailureRate())) {
            throw new ProviderException("Simulated provider error: 503 Service Unavailable");
        }

        Instant now = Instant.now();
        Job job = jobs.compute(submission.externalRef(), (ref, existing) -> {
            Job j = existing != null ? existing : new Job(submission, now);
            j.timesSubmitted++;
            j.lastSubmittedAt = now;
            return j;
        });
        evictIfNeeded();

        if (s.mode() == Mode.MANUAL) {
            if (job.state != JobState.DELIVERED) {
                job.state = JobState.AWAITING_MANUAL;
            }
            return;
        }
        planAutomaticResponse(job, s);
    }

    // ------------------------------------------------------------------ admin / test API

    public Settings settings() {
        return settings.get();
    }

    public void updateSettings(Settings newSettings) {
        settings.set(newSettings);
        LOG.infof("Simulator settings updated: %s", newSettings);
    }

    /** Restores the settings from configuration and forgets all tracked jobs. */
    public void reset() {
        settings.set(configuredSettings());
        jobs.clear();
    }

    private Settings configuredSettings() {
        AppConfig.Simulator s = config.simulator();
        return new Settings(Mode.valueOf(s.mode().toUpperCase(Locale.ROOT)), s.minDelay().toMillis(),
                s.maxDelay().toMillis(), s.submitFailureRate(), s.dropRate(), s.duplicateRate(), s.rejectRate());
    }

    public List<JobView> jobs(Optional<JobState> state) {
        List<JobView> out = new ArrayList<>();
        for (Job j : jobs.values()) {
            if (state.isEmpty() || state.get() == j.state) {
                out.add(j.view());
            }
        }
        out.sort(Comparator.comparing(JobView::lastSubmittedAt).reversed());
        return out;
    }

    public Optional<JobView> job(String externalRef) {
        return Optional.ofNullable(jobs.get(externalRef)).map(Job::view);
    }

    /**
     * Delivers a callback for {@code externalRef} right now, {@code times} times with the same event id
     * (a redelivery), synchronously. Works for refs the simulator never saw, which lets tests send
     * callbacks for unknown or forged references.
     */
    public DeliveryReport deliverNow(String externalRef, ProviderOutcome outcome, int times, String reason) {
        if (times < 1 || times > 20) {
            throw new IllegalArgumentException("times must be between 1 and 20");
        }
        String eventId = newEventId();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            statuses.add(post(externalRef, eventId, outcome, reason));
        }
        Job job = jobs.get(externalRef);
        if (job != null) {
            job.state = JobState.DELIVERED;
            job.plannedOutcome = outcome;
        }
        return new DeliveryReport(externalRef, eventId, outcome, statuses);
    }

    /** Delivers a pre-built raw body with caller-chosen headers. Used by security tests. */
    public int postRaw(String body, String timestampHeader, String signatureHeader) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.simulator().callbackUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (timestampHeader != null) b.header(CallbackSigner.TIMESTAMP_HEADER, timestampHeader);
        if (signatureHeader != null) b.header(CallbackSigner.SIGNATURE_HEADER, signatureHeader);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ internals

    private void planAutomaticResponse(Job job, Settings s) {
        String name = job.submission.filename() == null ? "" : job.submission.filename().toLowerCase(Locale.ROOT);
        boolean drop = name.contains("drop") || chance(s.dropRate());
        if (drop) {
            job.state = JobState.DROPPED;
            LOG.debugf("Simulator dropping %s", job.externalRef);
            return;
        }
        ProviderOutcome outcome = name.contains("reject") || chance(s.rejectRate())
                ? ProviderOutcome.REJECTED : ProviderOutcome.VERIFIED;
        String reason = outcome == ProviderOutcome.REJECTED ? "Document could not be validated (simulated)" : null;
        int deliveries = name.contains("dup") ? 3 : (chance(s.duplicateRate()) ? 2 : 1);
        long baseDelay = name.contains("slow") ? s.maxDelayMs() : randomDelay(s);

        job.plannedOutcome = outcome;
        job.state = JobState.SCHEDULED;
        String eventId = newEventId();
        for (int i = 0; i < deliveries; i++) {
            long delay = i == 0 ? baseDelay : baseDelay + randomDelay(s);
            scheduleDelivery(job, eventId, outcome, reason, delay, 0);
        }
    }

    private void scheduleDelivery(Job job, String eventId, ProviderOutcome outcome, String reason, long delayMs,
                                  int retry) {
        scheduler.schedule(() -> {
            int status = post(job.externalRef, eventId, outcome, reason);
            job.deliveriesAttempted++;
            if (status >= 200 && status < 300) {
                job.state = JobState.DELIVERED;
            } else if ((status == -1 || status >= 500) && retry < MAX_DELIVERY_RETRIES) {
                // Like a real webhook sender: retry server errors with exponential backoff.
                scheduleDelivery(job, eventId, outcome, reason, 1000L << retry, retry + 1);
            } else {
                LOG.warnf("Simulator gave up delivering %s (last status %d)", job.externalRef, status);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private int post(String externalRef, String eventId, ProviderOutcome outcome, String reason) {
        try {
            byte[] body = mapper.writeValueAsBytes(
                    new ProviderCallback(eventId, externalRef, outcome, reason, Instant.now()));
            long ts = Instant.now().getEpochSecond();
            HttpRequest req = HttpRequest.newBuilder(URI.create(config.simulator().callbackUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header(CallbackSigner.TIMESTAMP_HEADER, Long.toString(ts))
                    .header(CallbackSigner.SIGNATURE_HEADER, signer.sign(ts, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            LOG.debugf("Simulator delivery to callback endpoint failed: %s", e.toString());
            return -1;
        }
    }

    private void evictIfNeeded() {
        if (jobs.size() <= MAX_TRACKED_JOBS) {
            return;
        }
        jobs.values().stream()
                .filter(j -> j.state == JobState.DELIVERED || j.state == JobState.DROPPED)
                .sorted(Comparator.comparing(j -> j.lastSubmittedAt))
                .limit(jobs.size() - MAX_TRACKED_JOBS / 2)
                .map(j -> j.externalRef)
                .toList()
                .forEach(jobs::remove);
    }

    private static long randomDelay(Settings s) {
        return s.minDelayMs() == s.maxDelayMs() ? s.minDelayMs()
                : ThreadLocalRandom.current().nextLong(s.minDelayMs(), s.maxDelayMs() + 1);
    }

    private static boolean chance(double p) {
        return p > 0 && ThreadLocalRandom.current().nextDouble() < p;
    }

    private static String newEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }
}
