/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.prmeta;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Vaquar Khan
 */
class PrMetadataGateTest {

    @Test
    void getIdAndDefaultConstructor() {
        assertEquals("pr-metadata", new PrMetadataGate().getId());
    }

    @Test
    void passesWhenNoMetadataAndNullEnvSeam() {
        // null env -> k -> null lambda; empty metadata -> title/body/template all null -> pass
        var gate = new PrMetadataGate(null);
        var r = gate.evaluate(ctx(Map.of(), List.of()));
        assertTrue(r.isPassed());
    }

    @Test
    void passesOnCleanTitleAndBody() {
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of(
                "pr_title", "Add retry with backoff to the S3 client",
                "pr_body", "This adds bounded retries so transient 5xx responses no longer fail the task."),
                List.of()));
        assertTrue(r.isPassed());
    }

    @Test
    void readsInputsFromEnvironmentWhenMetadataAbsent() {
        var gate = new PrMetadataGate(env(Map.of(
                "AIV_PR_TITLE", "Fix bug",
                "AIV_PR_BODY", "Fix bug")));
        var r = gate.evaluate(ctx(Map.of(), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.title-generic"));
    }

    @Test
    void failsEmptyTitle() {
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of("pr_title", "   "), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.empty-title"));
    }

    @Test
    void failsConventionalPrefix() {
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of("pr_title", "feat(core): add partitioning support"), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.title-prefix"));
    }

    @Test
    void failsIssueOnlyTitle() {
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of("pr_title", "Fixes #12345"), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.title-issue-only"));
    }

    @Test
    void failsGenericTitleWithTrailingPunctuation() {
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of("pr_title", "Update code."), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.title-generic"));
    }

    @Test
    void failsSingleWordTitleAsTooShort() {
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of("pr_title", "Tweak"), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.title-too-short"));
    }

    @Test
    void failsEmptyBody() {
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of(
                "pr_title", "Add asset partitioning to the scheduler",
                "pr_body", "   "), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.empty-body"));
    }

    @Test
    void failsBodyThatRepeatsTitle() {
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of(
                "pr_title", "Add asset partitioning to the scheduler",
                "pr_body", "add asset partitioning to the scheduler"), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.body-repeats-title"));
    }

    @Test
    void failsTemplateOnlyBody() {
        String template = "<!-- describe your change -->\n## Summary\n\n- [ ]\n---\n#\n";
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of(
                "pr_title", "Add asset partitioning to the scheduler",
                "pr_body", "## Summary\n- [ ]\n<!-- describe your change -->\n",
                "pr_template", template), List.of()));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.body-template-only"));
    }

    @Test
    void passesWhenBodyGoesBeyondTemplate() {
        String template = "## Summary\n\n## Testing\n";
        var gate = new PrMetadataGate(env(Map.of()));
        var r = gate.evaluate(ctx(Map.of(
                "pr_title", "Add asset partitioning to the scheduler",
                "pr_body", "## Summary\nAdds partitioning so assets update per key.\n## Testing\nUnit tests added.",
                "pr_template", template), List.of()));
        assertTrue(r.isPassed());
    }

    @Test
    void requireDisclosureFailsWhenBodyMissing() {
        var gate = new PrMetadataGate(env(Map.of()));
        var cfg = gateConfig(Map.of("require_disclosure", true));
        var r = gate.evaluate(ctx(Map.of("pr_title", "Add asset partitioning to the scheduler"), cfg));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.missing-disclosure"));
    }

    @Test
    void requireDisclosureFailsWhenBodyLacksMarker() {
        var gate = new PrMetadataGate(env(Map.of()));
        var cfg = gateConfig(Map.of("require_disclosure", true));
        var r = gate.evaluate(ctx(Map.of(
                "pr_title", "Add asset partitioning to the scheduler",
                "pr_body", "A plain description with no disclosure content."), cfg));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.missing-disclosure"));
    }

    @Test
    void requireDisclosurePassesWhenMarkerPresent() {
        var gate = new PrMetadataGate(env(Map.of()));
        var cfg = gateConfig(Map.of("require_disclosure", true));
        var r = gate.evaluate(ctx(Map.of(
                "pr_title", "Add asset partitioning to the scheduler",
                "pr_body", "Implements partitioning.\n\nAI Usage Disclosure: assisted-by: an agent for drafting."), cfg));
        assertTrue(r.isPassed());
    }

    @Test
    void honorsNumericMinTitleWordsFromConfig() {
        var gate = new PrMetadataGate(env(Map.of()));
        var cfg = gateConfig(Map.of("min_title_words", 4));
        var r = gate.evaluate(ctx(Map.of("pr_title", "Add scheduler partitioning"), cfg));
        assertFalse(r.isPassed());
        assertTrue(hasRule(r, "pr-metadata.title-too-short"));
    }

    @Test
    void wrongTypedConfigFallsBackToDefaults() {
        var gate = new PrMetadataGate(env(Map.of()));
        var cfg = gateConfig(Map.of("min_title_words", "not-a-number", "require_disclosure", "yes"));
        // default min words = 2, so a clean two-word title passes; disclosure default off.
        var r = gate.evaluate(ctx(Map.of("pr_title", "Add partitioning"), cfg));
        assertTrue(r.isPassed());
    }

    // ---- helpers ----

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    private static List<AIVConfig.GateConfig> gateConfig(Map<String, Object> config) {
        return List.of(new AIVConfig.GateConfig("pr-metadata", true, config));
    }

    private static AIVContext ctx(Map<String, Object> metadata, List<AIVConfig.GateConfig> gates) {
        var diff = new Diff("main", "HEAD", List.of(), "");
        var config = new AIVConfig(gates, Map.of());
        return new AIVContext(Paths.get("."), diff, config, new HashMap<>(metadata));
    }

    private static boolean hasRule(GateResult r, String ruleId) {
        return r.getFindings().stream().anyMatch(f -> ruleId.equals(f.getRuleId()));
    }
}
