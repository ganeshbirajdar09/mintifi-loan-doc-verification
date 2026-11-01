package com.loanverify.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "app")
public interface AppConfig {

    DevAuth devAuth();

    Provider provider();

    Verification verification();

    Outbox outbox();

    Simulator simulator();

    interface DevAuth {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("1h")
        Duration tokenTtl();
    }

    interface Provider {
        /** Shared HMAC secret for provider callbacks. Must be at least 32 characters. */
        String callbackSecret();

        @WithDefault("5m")
        Duration signatureTolerance();
    }

    interface Verification {
        /** How long a submitted attempt may wait for a callback before it is re-submitted or timed out. */
        @WithDefault("2m")
        Duration timeout();

        /** Total submissions per attempt (initial + retries after submit failures or timeouts). */
        @WithDefault("3")
        int maxSubmissions();

        @WithDefault("2s")
        Duration backoffBase();

        @WithDefault("1m")
        Duration backoffMax();
    }

    interface Outbox {
        /** Consumed by the @Scheduled expression; declared here so the whole app.* namespace is mapped. */
        @WithDefault("1s")
        String pollInterval();

        @WithDefault("50")
        int batchSize();
    }

    interface Simulator {
        @WithDefault("true")
        boolean enabled();

        String callbackUrl();

        @WithDefault("AUTO")
        String mode();

        @WithDefault("500ms")
        Duration minDelay();

        @WithDefault("5s")
        Duration maxDelay();

        @WithDefault("0.1")
        double submitFailureRate();

        @WithDefault("0.05")
        double dropRate();

        @WithDefault("0.3")
        double duplicateRate();

        @WithDefault("0.0")
        double rejectRate();
    }
}
