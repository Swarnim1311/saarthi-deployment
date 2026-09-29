package com.saarthi.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign-in behaviour. These tests never hardcode a real password: each one
 * generates its own hash at runtime, which is also how a deployment is meant
 * to be configured.
 */
class AuthServiceTest {

    private static AuthService service(boolean enabled, String farmerUser, String farmerHash,
            String officialUser, String officialHash) {
        AuthService s = new AuthService();
        s.setEnabled(enabled);
        s.setFarmerCredentials(farmerUser, farmerHash);
        s.setOfficialCredentials(officialUser, officialHash);
        return s;
    }

    @Test
    void disabledByDefaultAndSaysSoInsteadOfFakingASession() {
        AuthService s = service(false, null, null, null, null);
        assertFalse(s.isEnabled());
        Map<String, Object> r = s.signIn("anyone", "anything", "farmer");
        assertEquals("not_configured", r.get("status"));
        assertNull(r.get("token"), "no session token may be issued when unconfigured");
    }

    @Test
    void enabledButUnsetCredentialsStaysNotConfigured() {
        AuthService s = service(true, "", "", "", "");
        assertFalse(s.isEnabled(), "one hash is not enough: both roles must be configured");
    }

    @Test
    void correctCredentialsReturnAnOpaqueToken() {
        String farmerHash = AuthService.hash("correct horse battery staple");
        String officialHash = AuthService.hash("a different official passphrase");
        AuthService s = service(true, "kisan@example.com", farmerHash,
                "officer@example.com", officialHash);
        assertTrue(s.isEnabled());

        Map<String, Object> ok = s.signIn("kisan@example.com", "correct horse battery staple", "farmer");
        assertEquals("ok", ok.get("status"));
        assertEquals("farmer", ok.get("role"));
        assertNotNull(ok.get("token"));
        assertNull(ok.get("password"), "the response must never echo a password");

        Map<String, Object> off = s.signIn("officer@example.com", "a different official passphrase", "official");
        assertEquals("ok", off.get("status"));
        assertEquals("official", off.get("role"));
        assertNotEquals(ok.get("token"), off.get("token"), "each session gets its own token");
    }

    @Test
    void wrongPasswordAndUnknownRoleAreBothRefused() {
        String farmerHash = AuthService.hash("farmer-pass-1");
        String officialHash = AuthService.hash("officer-pass-1");
        AuthService s = service(true, "kisan", farmerHash, "officer", officialHash);

        assertEquals("invalid_credentials", s.signIn("kisan", "wrong", "farmer").get("status"));
        assertEquals("invalid_credentials", s.signIn("someone-else", "farmer-pass-1", "farmer").get("status"));
        assertEquals("bad_role", s.signIn("kisan", "farmer-pass-1", "admin").get("status"));
        assertEquals("bad_role", s.signIn("kisan", "farmer-pass-1", null).get("status"));
    }

    @Test
    void aFarmerPasswordCannotSignInAsOfficial() {
        String farmerHash = AuthService.hash("shared-secret");
        String officialHash = AuthService.hash("official-only");
        AuthService s = service(true, "kisan", farmerHash, "officer", officialHash);
        assertEquals("invalid_credentials",
                s.signIn("officer", "shared-secret", "official").get("status"));
    }

    @Test
    void hashingIsSaltedSoTheSamePasswordHashesDifferently() {
        String a = AuthService.hash("same-password");
        String b = AuthService.hash("same-password");
        assertNotEquals(a, b, "a per-credential salt must make the hashes differ");
        assertTrue(AuthService.verify("same-password", a));
        assertTrue(AuthService.verify("same-password", b));
        assertFalse(AuthService.verify("other-password", a));
    }

    @Test
    void malformedOrMissingHashNeverVerifies() {
        assertFalse(AuthService.verify("anything", null));
        assertFalse(AuthService.verify("anything", ""));
        assertFalse(AuthService.verify("anything", "not-a-hash"));
        assertFalse(AuthService.verify("anything", "pbkdf2-sha256$notanumber$c2FsdA==$aGFzaA=="));
    }

    @Test
    void sessionCanBeInspectedAndForgotten() {
        String farmerHash = AuthService.hash("p1");
        String officialHash = AuthService.hash("p2");
        AuthService s = service(true, "kisan", farmerHash, "officer", officialHash);
        Map<String, Object> ok = s.signIn("kisan", "p1", "farmer");
        String token = (String) ok.get("token");
        assertEquals("ok", s.inspect(token).get("status"));
        s.signOut(token);
        assertEquals("no_session", s.inspect(token).get("status"));
    }

    @Test
    void onlyTheTwoSupportedRolesAreOffered() {
        AuthService s = service(false, null, null, null, null);
        Map<String, Object> roles = s.roles();
        assertEquals(2, roles.size());
        assertEquals("Farmer", roles.get("farmer"));
        assertEquals("Panchayat Official", roles.get("official"));
    }
}
