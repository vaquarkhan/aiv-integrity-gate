/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.security;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGateTest {

    @Test
    void getId() {
        assertEquals("security", new SecurityGate().getId());
    }

    @Test
    void awsPatternMatchesKnownShape() {
        assertTrue(Pattern.compile("(AKIA|ASIA)[0-9A-Z]{16}").matcher("AWS_KEY=AKIA0000000000000000").find());
    }

    @Test
    void failsOnAwsKeyInAddedFile() {
        var gate = new SecurityGate();
        String content = "AWS_KEY=AKIA0000000000000000\n";
        String raw = """
                diff --git a/cfg.env b/cfg.env
                --- /dev/null
                +++ b/cfg.env
                @@ -0,0 +1 @@
                +AWS_KEY=AKIA0000000000000000
                """;
        var ctx = ctx(List.of(new ChangedFile("cfg.env", ChangedFile.ChangeType.ADDED, content)), raw);
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed(), () -> "unexpected pass: " + r.getMessage());
        assertTrue(r.getFindings().stream().anyMatch(f -> "security.aws-key".equals(f.getRuleId())));
    }

    @Test
    void ignoresPreExistingSecretWhenNotAdded() {
        var gate = new SecurityGate();
        String content = "OLD=AKIA0000000000000000\nnew=1\n";
        String raw = """
                diff --git a/cfg.env b/cfg.env
                --- a/cfg.env
                +++ b/cfg.env
                @@ -1,1 +1,2 @@
                 OLD=AKIA0000000000000000
                +new=1
                """;
        var ctx = ctx(List.of(new ChangedFile("cfg.env", ChangedFile.ChangeType.MODIFIED, content)), raw);
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsOnGithubPatAddedLine() {
        var gate = new SecurityGate();
        String content = "token=ghp_123456789012345678901234567890123456\n";
        String raw = """
                diff --git a/t.env b/t.env
                --- a/t.env
                +++ b/t.env
                @@ -0,0 +1 @@
                +token=ghp_123456789012345678901234567890123456
                """;
        var ctx = ctx(List.of(new ChangedFile("t.env", ChangedFile.ChangeType.MODIFIED, content)), raw);
        assertFalse(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsOnPrivateKeyHeader() {
        var gate = new SecurityGate();
        var ctx = ctx(List.of(new ChangedFile("id_rsa", ChangedFile.ChangeType.ADDED,
                "-----BEGIN RSA PRIVATE KEY-----\nMIIE\n")), "");
        assertFalse(gate.evaluate(ctx).isPassed());
    }

    @Test
    void skipsBlankContent() {
        var gate = new SecurityGate();
        var ctx = ctx(List.of(new ChangedFile("empty.env", ChangedFile.ChangeType.ADDED, "  \n")), "");
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsOnSlackAndAssignedSecret() {
        var gate = new SecurityGate();
        String content = "t=xoxb-1234567890-abcdefghij\napi_key = \"supersecretvalue99\"\n";
        String raw = """
                diff --git a/s.env b/s.env
                --- /dev/null
                +++ b/s.env
                @@ -0,0 +1,2 @@
                +t=xoxb-1234567890-abcdefghij
                +api_key = "supersecretvalue99"
                """;
        var ctx = ctx(List.of(new ChangedFile("s.env", ChangedFile.ChangeType.ADDED, content)), raw);
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getFindings().stream().anyMatch(f -> "security.slack-token".equals(f.getRuleId())));
        assertTrue(r.getFindings().stream().anyMatch(f -> "security.assigned-secret".equals(f.getRuleId())));
    }

    @Test
    void failsOnStripeNpmAndGoogleKeys() {
        var gate = new SecurityGate();
        // Build shapes at runtime so the source file never contains a contiguous secret-like token
        // (GitHub push protection flags even obvious fixtures).
        String stripe = "sk_" + "live_" + "0".repeat(24);
        String npm = "npm_" + "0".repeat(28);
        String google = "AIza" + "SyA" + "0".repeat(20);
        String content = stripe + "\n" + npm + "\n" + google + "\n";
        String raw = "diff --git a/keys.env b/keys.env\n"
                + "--- /dev/null\n"
                + "+++ b/keys.env\n"
                + "@@ -0,0 +1,3 @@\n"
                + "+" + stripe + "\n"
                + "+" + npm + "\n"
                + "+" + google + "\n";
        var ctx = ctx(List.of(new ChangedFile("keys.env", ChangedFile.ChangeType.ADDED, content)), raw);
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getFindings().stream().anyMatch(f -> "security.stripe-live".equals(f.getRuleId())));
        assertTrue(r.getFindings().stream().anyMatch(f -> "security.npm-token".equals(f.getRuleId())));
        assertTrue(r.getFindings().stream().anyMatch(f -> "security.google-api-key".equals(f.getRuleId())));
    }

    @Test
    void parseAddedLinesSkipsDevNull() {
        String raw = """
                diff --git a/gone.env b/gone.env
                --- a/gone.env
                +++ /dev/null
                @@ -1 +0,0 @@
                -x
                """;
        var map = SecurityGate.parseAddedLines(raw);
        assertTrue(map.isEmpty() || !map.containsKey("/dev/null"));
    }

    @Test
    void parseAddedLinesHandlesNull() {
        assertTrue(SecurityGate.parseAddedLines(null).isEmpty());
        assertTrue(SecurityGate.parseAddedLines("").isEmpty());
    }

    private static AIVContext ctx(List<ChangedFile> files, String raw) {
        return new AIVContext(Paths.get("."), new Diff("main", "HEAD", files, raw),
                new AIVConfig(List.of(), Map.of()));
    }
}
