package com.loanverify.provider;

import com.loanverify.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signing of provider callbacks.
 * <pre>
 *   X-Provider-Timestamp: 1767225600                (unix seconds)
 *   X-Provider-Signature: sha256=hex(HMAC(secret, timestamp + "." + rawBody))
 * </pre>
 * The timestamp bounds the replay window. The event id makes replays inside the window harmless.
 */
@ApplicationScoped
public class CallbackSigner {

    public static final String TIMESTAMP_HEADER = "X-Provider-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Provider-Signature";
    private static final String PREFIX = "sha256=";

    @Inject
    AppConfig config;

    private byte[] secret;
    private Duration tolerance;

    @PostConstruct
    void init() {
        String s = config.provider().callbackSecret();
        if (s == null || s.length() < 32) {
            throw new IllegalStateException("app.provider.callback-secret must be at least 32 characters");
        }
        this.secret = s.getBytes(StandardCharsets.UTF_8);
        this.tolerance = config.provider().signatureTolerance();
    }

    public String sign(long timestampSeconds, byte[] body) {
        return PREFIX + HexFormat.of().formatHex(hmac(timestampSeconds, body));
    }

    public boolean verify(String timestampHeader, String signatureHeader, byte[] body, Instant now) {
        if (timestampHeader == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - ts) > tolerance.toSeconds()) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(ts, body), provided);
    }

    private byte[] hmac(long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
