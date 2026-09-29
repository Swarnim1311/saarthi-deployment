package com.saarthi.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sign-in endpoints for the cinematic authentication page.
 *
 * <ul>
 *   <li>{@code GET /api/auth/status} — whether sign-in is configured, and the
 *       two role labels. The page reads this first so it can state the truth
 *       before the user types anything.</li>
 *   <li>{@code POST /api/auth/sign-in} — username/email, password and one of
 *       the two roles. Answers 200 with a session token, 400 for a missing or
 *       unknown role, 401 for bad credentials, 503 when sign-in is not
 *       configured. It never returns a session it did not really establish.</li>
 *   <li>{@code POST /api/auth/sign-out} — forgets the presented token.</li>
 * </ul>
 *
 * <p>There is no social login, no OTP and no phone login here by design: the
 * page offers a username/email and password only.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    @Autowired
    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** Configuration state, so the page can be honest before submitting. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", auth.isEnabled());
        out.put("roles", auth.roles());
        out.put("methods", java.util.List.of("password"));
        out.put("note", auth.isEnabled()
                ? "Sign-in is configured. Choose your role, then sign in."
                : "Sign-in is not configured in this build. No credentials are bundled, "
                  + "so nothing will be signed in until SAARTHI_AUTH_* settings are provided.");
        return ResponseEntity.ok(out);
    }

    /** Sign in with a username/email, a password and one of the two roles. */
    @PostMapping("/sign-in")
    public ResponseEntity<Map<String, Object>> signIn(
            @RequestBody(required = false) Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        String role = body == null ? null : body.get("role");
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "missing_fields");
            err.put("message", "Enter both your username or email and your password.");
            return ResponseEntity.badRequest().body(err);
        }
        Map<String, Object> res = auth.signIn(username, password, role);
        String status = String.valueOf(res.get("status"));
        HttpStatus code = switch (status) {
            case "ok" -> HttpStatus.OK;
            case "bad_role" -> HttpStatus.BAD_REQUEST;
            case "not_configured" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNAUTHORIZED;
        };
        return ResponseEntity.status(code).body(res);
    }

    /** Forget the presented session token. Always safe to call. */
    @PostMapping("/sign-out")
    public ResponseEntity<Map<String, Object>> signOut(
            @RequestHeader(value = "X-SAARTHI-Session", required = false) String token) {
        auth.signOut(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("message", "Session cleared.");
        return ResponseEntity.ok(out);
    }
}
