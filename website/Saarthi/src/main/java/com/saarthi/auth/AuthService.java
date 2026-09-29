package com.saarthi.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in for the two SAARTHI roles (farmer, panchayat official).
 *
 * <p><b>Disabled by default and credential-free by design.</b> Nothing is
 * hardcoded here and no account is seeded in the source tree: both roles'
 * credentials must be supplied through configuration
 * ({@code saarthi.auth.*} properties or the matching {@code SAARTHI_AUTH_*}
 * environment variables), carrying a PBKDF2 password hash rather than a
 * password. Until that configuration exists, {@link #isEnabled()} is
 * {@code false} and every sign-in attempt is answered honestly with
 * {@code not_configured} — the page then says so instead of pretending anyone
 * is logged in. A prototype that fakes a session would be worse than one that
 * admits it has none.
 *
 * <p><b>Password handling.</b> Hashes use PBKDF2WithHmacSHA256 (JDK built-in,
 * no extra dependency) with a per-credential random salt and 210 000
 * iterations. Verification is constant-time. Plaintext passwords are never
 * stored, logged, or returned, and the hash format is
 * {@code pbkdf2-sha256$<iterations>$<saltBase64>$<hashBase64>} — generate one
 * with {@link PasswordHashTool}.
 *
 * <p><b>Sessions.</b> Successful sign-in returns an opaque 256-bit random
 * token held in memory with a fixed TTL. Tokens are never derived from the
 * password and are lost on restart, so this is a prototype session store, not
 * a distributed session system.
 */
@Service
public class AuthService {

    static final String ROLE_FARMER = "farmer";
    static final String ROLE_OFFICIAL = "official";

    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;

    @Value("${saarthi.auth.enabled:false}")
    private boolean enabled;

    @Value("${saarthi.auth.farmer.username:}")
    private String farmerUsername;

    @Value("${saarthi.auth.farmer.password-hash:}")
    private String farmerHash;

    @Value("${saarthi.auth.official.username:}")
    private String officialUsername;

    @Value("${saarthi.auth.official.password-hash:}")
    private String officialHash;

    @Value("${saarthi.auth.session-ttl-minutes:480}")
    private long sessionTtlMinutes = 480;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String role, Instant expiresAt) {}

    // ---- test seams (package-private: no Spring, no real credentials) ----
    void setEnabled(boolean v) { this.enabled = v; }
    void setFarmerCredentials(String username, String passwordHash) {
        this.farmerUsername = username;
        this.farmerHash = passwordHash;
    }
    void setOfficialCredentials(String username, String passwordHash) {
        this.officialUsername = username;
        this.officialHash = passwordHash;
    }

    /** True only when enabled AND both roles carry a usable configured hash. */
    public boolean isEnabled() {
        if (!enabled) return false;
        return configured(ROLE_FARMER) && configured(ROLE_OFFICIAL);
    }

    private boolean configured(String role) {
        String u = usernameFor(role);
        String h = hashFor(role);
        return u != null && !u.isBlank() && h != null && h.startsWith("pbkdf2-sha256$");
    }

    private String usernameFor(String role) {
        if (ROLE_FARMER.equals(role)) return firstNonBlank(farmerUsername, "SAARTHI_AUTH_FARMER_USERNAME");
        return firstNonBlank(officialUsername, "SAARTHI_AUTH_OFFICIAL_USERNAME");
    }

    private String hashFor(String role) {
        if (ROLE_FARMER.equals(role)) return firstNonBlank(farmerHash, "SAARTHI_AUTH_FARMER_PASSWORD_HASH");
        return firstNonBlank(officialHash, "SAARTHI_AUTH_OFFICIAL_PASSWORD_HASH");
    }

    private static String firstNonBlank(String configured, String envName) {
        if (configured != null && !configured.isBlank()) return configured;
        String env = System.getenv(envName);
        return env == null || env.isBlank() ? null : env;
    }

    /** Role labels as the two options the sign-in page offers. */
    public Map<String, Object> roles() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ROLE_FARMER, "Farmer");
        out.put(ROLE_OFFICIAL, "Panchayat Official");
        return out;
    }

    // __PART_SIGNIN__

    /**
     * Attempt a sign-in.
     *
     * @return one of: {@code ok} (200, with a token), {@code not_configured}
     *         (503), {@code bad_role} (400), {@code invalid_credentials} (401).
     */
    public Map<String, Object> signIn(String username, String password, String role) {
        if (!isEnabled()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "not_configured");
            out.put("message", "Sign-in is not configured in this build. No SAARTHI_AUTH_* "
                    + "credentials are present, so no session was created.");
            return out;
        }
        if (role == null || !(ROLE_FARMER.equals(role) || ROLE_OFFICIAL.equals(role))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "bad_role");
            out.put("message", "Choose either the Farmer or the Panchayat Official role.");
            return out;
        }
        String expectedUser = usernameFor(role);
        String expectedHash = hashFor(role);
        boolean userOk = username != null && constantTimeEquals(username.trim(), expectedUser);
        boolean passOk = password != null && verify(password, expectedHash);
        if (!userOk || !passOk) {
            // One message for both failures: never reveal which part was wrong.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "invalid_credentials");
            out.put("message", "That username and password combination was not recognised.");
            return out;
        }
        String token = newToken();
        Instant expires = Instant.now().plusSeconds(Math.max(60, sessionTtlMinutes) * 60);
        sessions.put(token, new Session(role, expires));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("role", role);
        out.put("role_label", role.equals(ROLE_FARMER) ? "Farmer" : "Panchayat Official");
        out.put("token", token);
        out.put("expires_at", expires.toString());
        out.put("username", expectedUser);
        return out;
    }

    /** Session lookup for future route guards; never returns the token. */
    public Map<String, Object> inspect(String token) {
        if (token == null || token.isBlank()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "no_session");
            return out;
        }
        Session s = sessions.get(token);
        if (s == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "no_session");
            return out;
        }
        if (Instant.now().isAfter(s.expiresAt())) {
            sessions.remove(token);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "expired");
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("role", s.role());
        out.put("expires_at", s.expiresAt().toString());
        return out;
    }

    /** Forget one session (sign-out). */
    public void signOut(String token) {
        if (token != null) sessions.remove(token);
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // __PART_HASH__

    /** Build a storable hash for a new credential. */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] dk = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(dk);
    }

    /** Verify a password against a stored hash, constant-time. */
    static boolean verify(String password, String stored) {
        if (stored == null || !stored.startsWith("pbkdf2-sha256$")) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable on this JVM", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
