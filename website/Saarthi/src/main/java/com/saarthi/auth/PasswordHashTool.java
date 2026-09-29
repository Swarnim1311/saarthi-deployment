package com.saarthi.auth;

import java.io.Console;
import java.util.Arrays;

/**
 * Local developer tool: prints a PBKDF2 hash for a password so it can be placed
 * in {@code saarthi.auth.*.password-hash} (or the matching
 * {@code SAARTHI_AUTH_*_PASSWORD_HASH} environment variable).
 *
 * <p>The password is read from the console with echo switched off, so it never
 * appears in shell history or in a process argument list. This is a local
 * tool, NOT an HTTP endpoint: exposing a hashing endpoint would be a security
 * hole.
 *
 * <p>Usage:
 * <pre>
 *   mvn -q -DskipTests exec:java \
 *       -Dexec.mainClass=com.saarthi.auth.PasswordHashTool
 * </pre>
 * or, from a compiled classpath: {@code java com.saarthi.auth.PasswordHashTool}
 */
public final class PasswordHashTool {

    private PasswordHashTool() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        char[] password;
        if (console != null) {
            System.out.print("Password: ");
            console.flush();
            password = console.readPassword();
            System.out.print("Confirm: ");
            console.flush();
            char[] confirm = console.readPassword();
            if (!Arrays.equals(password, confirm)) {
                System.err.println("Passwords do not match.");
                Arrays.fill(password, '\0');
                Arrays.fill(confirm, '\0');
                return;
            }
            Arrays.fill(confirm, '\0');
        } else if (args.length == 1) {
            // Non-interactive fallback. Prefer the console path above: an
            // argument can end up in shell history.
            password = args[0].toCharArray();
            System.err.println("No console available; reading the password from argv. "
                    + "Prefer running this from a terminal.");
        } else {
            System.err.println("Usage: PasswordHashTool   (reads the password from the console)");
            return;
        }
        String hash = AuthService.hash(new String(password));
        Arrays.fill(password, '\0');
        System.out.println();
        System.out.println("Hash (put this in saarthi.auth.<role>.password-hash):");
        System.out.println(hash);
    }
}
