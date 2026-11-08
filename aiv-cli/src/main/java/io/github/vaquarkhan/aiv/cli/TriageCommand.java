/*
 * Copyright 2026 Viquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.util.JsonLite;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline, side-effect-free replay of a deterministic pull-request triage policy.
 *
 * <p>The command deliberately has no GitHub client, environment lookup, HTTP client, current-clock lookup,
 * publishing flag, or normal-gate option. It consumes a saved {@code aiv-triage-snapshot/1} JSON document and
 * a {@code aiv-triage-policy/1} YAML policy, then writes only the caller-selected local JSON and Markdown
 * result files. See {@code docs/TRIAGE-PRODUCT.md} for the contract.
 *
 * @author Viquar Khan
 */
public final class TriageCommand {

    static final String SNAPSHOT_SCHEMA = "aiv-triage-snapshot/1";
    static final String POLICY_SCHEMA = "aiv-triage-policy/1";
    static final String RESULT_SCHEMA = "aiv-triage-result/1";

    private static final Set<String> ROOT_KEYS = Set.of("schema_version", "repository", "captured_at", "pull_requests");
    private static final Set<String> PR_KEYS = Set.of("number", "title", "body", "created_at", "updated_at", "draft", "labels",
            "author", "checks", "review", "branch", "issue", "ci", "files", "outcome");
    private static final Set<String> AUTHOR_KEYS = Set.of("login", "association", "prior_merged_prs", "open_prs",
            "failing_open_prs", "last_activity_at");
    private static final Set<String> CHECK_KEYS = Set.of("required_failed", "required_missing", "conflicts");
    private static final Set<String> REVIEW_KEYS = Set.of("unresolved_threads", "maintainer_engaged");
    private static final Set<String> BRANCH_KEYS = Set.of("commits_behind_base");
    private static final Set<String> ISSUE_KEYS = Set.of("linked", "accepted");
    private static final Set<String> CI_KEYS = Set.of("definition_touched");
    private static final Set<String> FILE_KEYS = Set.of("changed", "lines_changed");
    private static final Set<String> POLICY_KEYS = Set.of("schema_version", "name", "labels", "thresholds", "rules");
    private static final Set<String> LABEL_KEYS = Set.of("ready", "needs_permission", "ci_safety");
    private static final Set<String> THRESHOLD_KEYS = Set.of("external_associations", "newcomer_prior_merged_prs",
            "newcomer_large_change_lines", "max_open_prs", "max_failing_open_prs", "stale_draft_days",
            "behind_base_commits");
    private static final Set<String> RULE_KEYS = Set.of("id", "enabled");
    private static final Set<String> RULE_IDS = Set.of("close-author-quality-violations", "hold-open-pr-limit", "close-stale-draft",
            "draft-required-ci-failure", "rebase-conflicted-or-stale-branch", "hold-unresolved-review",
            "hold-external-ci-definition-change", "hold-large-newcomer-without-accepted-issue",
            "ready-for-maintainer-review");

    private TriageCommand() {
    }

    /** Executes {@code aiv triage replay}; exit code 0 means replay completed, never that a PR is ready. */
    public static int run(String[] args) {
        try {
            Args parsed = Args.parse(args);
            Snapshot snapshot = Snapshot.parse(readJson(parsed.input()));
            Policy policy = Policy.parse(readYaml(parsed.policy()));
            Instant asOf = parsed.asOf() == null ? snapshot.capturedAt() : parseInstant(parsed.asOf(), "--as-of");
            Result result = replay(snapshot, policy, asOf);
            write(parsed.jsonOutput(), JsonLite.write(result.toMap()) + "\n");
            write(parsed.markdownOutput(), result.markdown());
            System.out.println("AIV triage replay: " + result.proposalCount() + " proposal(s) for "
                    + snapshot.pullRequests().size() + " PR(s); mode=dry-run; side_effects=0");
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("AIV triage error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("AIV triage error: " + e.getMessage());
            return 2;
        }
    }

    static Result replay(Snapshot snapshot, Policy policy, Instant asOf) {
        List<Decision> decisions = new ArrayList<>();
        for (PullRequest pr : snapshot.pullRequests()) {
            decisions.add(evaluate(pr, policy, asOf));
        }
        return new Result(snapshot, policy, asOf, decisions);
    }

    private static Decision evaluate(PullRequest pr, Policy policy, Instant asOf) {
        List<String> skipped = new ArrayList<>();
        List<Proposal> proposals = new ArrayList<>();
        boolean external = pr.author().association() != null && policy.externalAssociations().contains(pr.author().association());
        boolean newcomer = pr.author().priorMergedPrs() != null
                && pr.author().priorMergedPrs() <= policy.newcomerPriorMergedPrs();
        Map<String, Object> facts = facts(pr, external, newcomer, asOf);

        if (!external) {
            return new Decision(pr.number(), facts, List.of(), List.of("external-contributor scope (author.association)"));
        }

        if (enabled(policy, "close-author-quality-violations")) {
            if (missing(pr.author().failingOpenPrs(), "author.failing_open_prs", skipped, "close-author-quality-violations")) {
                // skipped recorded
            } else if (pr.author().failingOpenPrs() > policy.maxFailingOpenPrs()) {
                proposals.add(proposal("close-author-quality-violations", "close-pr", 1,
                        "author.failing_open_prs", pr.author().failingOpenPrs(), "> " + policy.maxFailingOpenPrs(),
                        "Close this PR because the author exceeds the configured failing-PR limit."));
                return new Decision(pr.number(), facts, proposals, skipped);
            }
        }

        if (enabled(policy, "hold-open-pr-limit")) {
            if (missing(pr.author().openPrs(), "author.open_prs", skipped, "hold-open-pr-limit")) {
                // skipped recorded
            } else if (pr.author().openPrs() > policy.maxOpenPrs()) {
                proposals.add(proposal("hold-open-pr-limit", "hold-open-pr-limit", 2, "author.open_prs",
                        pr.author().openPrs(), "<= " + policy.maxOpenPrs(),
                        "Hold this PR because the author exceeds the configured open-PR limit."));
                return new Decision(pr.number(), facts, proposals, skipped);
            }
        }

        if (enabled(policy, "close-stale-draft")) {
            if (missing(pr.draft(), "pr.draft", skipped, "close-stale-draft")
                    || missing(pr.review().maintainerEngaged(), "review.maintainer_engaged", skipped, "close-stale-draft")
                    || missing(pr.author().lastActivityAt(), "author.last_activity_at", skipped, "close-stale-draft")) {
                // skipped recorded
            } else if (pr.draft() && !pr.review().maintainerEngaged()
                    && Duration.between(pr.author().lastActivityAt(), asOf).toDays() >= policy.staleDraftDays()) {
                proposals.add(proposal("close-stale-draft", "close-pr", 2, "author.days_since_activity",
                        Duration.between(pr.author().lastActivityAt(), asOf).toDays(), ">= " + policy.staleDraftDays(),
                        "Close the stale draft because the author has not acted and no maintainer is engaged."));
                return new Decision(pr.number(), facts, proposals, skipped);
            }
        }

        if (enabled(policy, "draft-required-ci-failure")) {
            if (missing(pr.checks().requiredFailed(), "checks.required_failed", skipped, "draft-required-ci-failure")
                    || missing(pr.checks().requiredMissing(), "checks.required_missing", skipped, "draft-required-ci-failure")) {
                // skipped recorded
            } else if (pr.checks().requiredFailed() > 0 || pr.checks().requiredMissing() > 0) {
                proposals.add(proposal("draft-required-ci-failure", "draft-pr", 3, "checks.required_failed",
                        pr.checks().requiredFailed(), "> 0", "Draft until required checks are present and passing."));
                proposals.add(labelProposal("draft-required-ci-failure", "remove-label", 3, policy.readyLabel(),
                        "Remove the ready label while required CI is not clear."));
                proposals.add(proposal("draft-required-ci-failure", "update-status", 3, "status", "ci-failure", "set",
                        "Update one triage status block instead of adding repeated comments."));
                return new Decision(pr.number(), facts, proposals, skipped);
            }
        }

        if (enabled(policy, "rebase-conflicted-or-stale-branch")) {
            if (missing(pr.checks().conflicts(), "checks.conflicts", skipped, "rebase-conflicted-or-stale-branch")
                    || missing(pr.branch().commitsBehindBase(), "branch.commits_behind_base", skipped,
                    "rebase-conflicted-or-stale-branch")) {
                // skipped recorded
            } else if (pr.checks().conflicts() || pr.branch().commitsBehindBase() > policy.behindBaseCommits()) {
                String fact = pr.checks().conflicts() ? "checks.conflicts" : "branch.commits_behind_base";
                Object value = pr.checks().conflicts() ? true : pr.branch().commitsBehindBase();
                String expected = pr.checks().conflicts() ? "false" : "<= " + policy.behindBaseCommits();
                proposals.add(proposal("rebase-conflicted-or-stale-branch", "request-rebase", 4, fact, value, expected,
                        "Ask the author to rebase before review."));
                proposals.add(labelProposal("rebase-conflicted-or-stale-branch", "remove-label", 4, policy.readyLabel(),
                        "Remove the ready label until the branch is current and conflict-free."));
                return new Decision(pr.number(), facts, proposals, skipped);
            }
        }

        if (enabled(policy, "hold-unresolved-review")) {
            if (missing(pr.review().unresolvedThreads(), "review.unresolved_threads", skipped, "hold-unresolved-review")) {
                // skipped recorded
            } else if (pr.review().unresolvedThreads() > 0) {
                proposals.add(proposal("hold-unresolved-review", "hold-review", 5, "review.unresolved_threads",
                        pr.review().unresolvedThreads(), "0", "Hold readiness until review threads are resolved."));
                proposals.add(labelProposal("hold-unresolved-review", "remove-label", 5, policy.readyLabel(),
                        "Remove the ready label while review threads remain unresolved."));
                return new Decision(pr.number(), facts, proposals, skipped);
            }
        }

        if (enabled(policy, "hold-external-ci-definition-change")) {
            if (missing(pr.ci().definitionTouched(), "ci.definition_touched", skipped,
                    "hold-external-ci-definition-change")) {
                // skipped recorded
            } else if (pr.ci().definitionTouched()) {
                proposals.add(proposal("hold-external-ci-definition-change", "require-maintainer-ci-approval", 6,
                        "ci.definition_touched", true, "false", "Require maintainer approval before trusting CI changed by an external author."));
                proposals.add(labelProposal("hold-external-ci-definition-change", "add-label", 6, policy.ciSafetyLabel(),
                        "Apply the configured CI-safety label."));
                return new Decision(pr.number(), facts, proposals, skipped);
            }
        }

        if (enabled(policy, "hold-large-newcomer-without-accepted-issue")) {
            if (missing(pr.files().linesChanged(), "files.lines_changed", skipped,
                    "hold-large-newcomer-without-accepted-issue")
                    || missing(pr.issue().accepted(), "issue.accepted", skipped,
                    "hold-large-newcomer-without-accepted-issue")
                    || missing(pr.author().priorMergedPrs(), "author.prior_merged_prs", skipped,
                    "hold-large-newcomer-without-accepted-issue")) {
                // skipped recorded
            } else if (newcomer && pr.files().linesChanged() >= policy.newcomerLargeChangeLines() && !pr.issue().accepted()) {
                proposals.add(proposal("hold-large-newcomer-without-accepted-issue", "require-maintainer-permission", 7,
                        "files.lines_changed", pr.files().linesChanged(), ">= " + policy.newcomerLargeChangeLines(),
                        "Hold a large newcomer change until a maintainer accepts the linked issue or grants permission."));
                proposals.add(labelProposal("hold-large-newcomer-without-accepted-issue", "add-label", 7,
                        policy.needsPermissionLabel(), "Apply the configured permission-needed label."));
                return new Decision(pr.number(), facts, proposals, skipped);
            }
        }

        if (enabled(policy, "ready-for-maintainer-review")) {
            List<String> readinessMissing = readinessMissing(pr);
            if (!readinessMissing.isEmpty()) {
                skipped.add("ready-for-maintainer-review (" + String.join(", ", readinessMissing) + ")");
            } else if (!pr.draft() && pr.checks().requiredFailed() == 0 && pr.checks().requiredMissing() == 0
                    && !pr.checks().conflicts() && pr.branch().commitsBehindBase() <= policy.behindBaseCommits()
                    && pr.review().unresolvedThreads() == 0 && !pr.ci().definitionTouched()) {
                proposals.add(labelProposal("ready-for-maintainer-review", "mark-ready", 8, policy.readyLabel(),
                        "Mark this externally contributed PR ready for maintainer review."));
            }
        }
        return new Decision(pr.number(), facts, proposals, skipped);
    }

    private static List<String> readinessMissing(PullRequest pr) {
        List<String> missing = new ArrayList<>();
        if (pr.draft() == null) missing.add("pr.draft");
        if (pr.checks().requiredFailed() == null) missing.add("checks.required_failed");
        if (pr.checks().requiredMissing() == null) missing.add("checks.required_missing");
        if (pr.checks().conflicts() == null) missing.add("checks.conflicts");
        if (pr.branch().commitsBehindBase() == null) missing.add("branch.commits_behind_base");
        if (pr.review().unresolvedThreads() == null) missing.add("review.unresolved_threads");
        if (pr.ci().definitionTouched() == null) missing.add("ci.definition_touched");
        return missing;
    }

    private static boolean enabled(Policy policy, String rule) {
        return policy.enabledRules().contains(rule);
    }

    private static boolean missing(Object value, String fact, List<String> skipped, String rule) {
        if (value == null) {
            skipped.add(rule + " (" + fact + ")");
            return true;
        }
        return false;
    }

    private static Proposal proposal(String rule, String action, int priority, String fact, Object actual,
                                     String expected, String message) {
        return new Proposal(rule, action, priority, Map.of("fact", fact, "actual", actual, "expected", expected), message);
    }

    private static Proposal labelProposal(String rule, String action, int priority, String label, String message) {
        return new Proposal(rule, action, priority, Map.of("label", label), message);
    }

    private static Map<String, Object> facts(PullRequest pr, boolean external, boolean newcomer, Instant asOf) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("pr.number", pr.number());
        facts.put("pr.draft", pr.draft());
        facts.put("author.association", pr.author().association());
        facts.put("author.external", external);
        facts.put("author.newcomer", newcomer);
        facts.put("author.prior_merged_prs", pr.author().priorMergedPrs());
        facts.put("author.open_prs", pr.author().openPrs());
        facts.put("author.failing_open_prs", pr.author().failingOpenPrs());
        if (pr.author().lastActivityAt() != null) {
            facts.put("author.days_since_activity", Duration.between(pr.author().lastActivityAt(), asOf).toDays());
        }
        facts.put("checks.required_failed", pr.checks().requiredFailed());
        facts.put("checks.required_missing", pr.checks().requiredMissing());
        facts.put("checks.conflicts", pr.checks().conflicts());
        facts.put("review.unresolved_threads", pr.review().unresolvedThreads());
        facts.put("review.maintainer_engaged", pr.review().maintainerEngaged());
        facts.put("branch.commits_behind_base", pr.branch().commitsBehindBase());
        facts.put("issue.linked", pr.issue().linked());
        facts.put("issue.accepted", pr.issue().accepted());
        facts.put("ci.definition_touched", pr.ci().definitionTouched());
        facts.put("files.changed", pr.files().changed());
        facts.put("files.lines_changed", pr.files().linesChanged());
        return facts;
    }

    private static void write(Path output, String text) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static Object readJson(Path input) throws IOException {
        return JsonLite.parse(Files.readString(input, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Object readYaml(Path input) throws IOException {
        Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.newInputStream(input));
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("triage policy root must be a YAML map");
        }
        return value;
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be RFC-3339 UTC instant, got '" + value + "'");
        }
    }

    private static String digest(Object value) {
        return digestForAlgorithm("SHA-256", value);
    }

    /** Package-visible for a deterministic failure-path test; production always requests SHA-256. */
    static String digestForAlgorithm(String algorithm, Object value) {
        try {
            byte[] hash = MessageDigest.getInstance(algorithm).digest(JsonLite.write(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Strict arguments for a side-effect-free replay. */
    record Args(Path input, Path policy, Path jsonOutput, Path markdownOutput, String asOf) {
        static Args parse(String[] args) {
            if (args.length == 0 || !"replay".equals(args[0])) {
                throw new IllegalArgumentException("Usage: aiv triage replay --input <snapshot.json> --policy <policy.yaml> "
                        + "--output-json <result.json> --output-markdown <result.md> [--as-of <RFC-3339>]");
            }
            Path input = null, policy = null, json = null, markdown = null;
            String asOf = null;
            for (int i = 1; i < args.length; i++) {
                if (!Set.of("--input", "--policy", "--output-json", "--output-markdown", "--as-of").contains(args[i])
                        || i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Unknown triage option or missing value: " + args[i]);
                }
                String value = args[++i];
                switch (args[i - 1]) {
                    case "--input" -> input = once(input, Path.of(value), "--input");
                    case "--policy" -> policy = once(policy, Path.of(value), "--policy");
                    case "--output-json" -> json = once(json, Path.of(value), "--output-json");
                    case "--output-markdown" -> markdown = once(markdown, Path.of(value), "--output-markdown");
                    case "--as-of" -> {
                        if (asOf != null) throw new IllegalArgumentException("Duplicate --as-of");
                        if (value.isBlank()) throw new IllegalArgumentException("--as-of must not be blank");
                        asOf = value;
                    }
                }
            }
            if (input == null || policy == null || json == null || markdown == null) {
                throw new IllegalArgumentException("triage replay needs --input, --policy, --output-json and --output-markdown");
            }
            return new Args(input.toAbsolutePath(), policy.toAbsolutePath(), json.toAbsolutePath(), markdown.toAbsolutePath(), asOf);
        }

        private static Path once(Path previous, Path value, String option) {
            if (previous != null) throw new IllegalArgumentException("Duplicate " + option);
            return value;
        }
    }

    /** Parsed snapshot. Every absent optional nested field stays {@code null} and is reported as unavailable. */
    record Snapshot(String repository, Instant capturedAt, List<PullRequest> pullRequests, Object original) {
        static Snapshot parse(Object raw) {
            Map<String, Object> root = requiredMap(raw, "snapshot");
            rejectUnknown(root, ROOT_KEYS, "snapshot");
            requireEquals(string(root.get("schema_version"), "snapshot.schema_version"), SNAPSHOT_SCHEMA, "snapshot.schema_version");
            String repository = nonBlank(root.get("repository"), "snapshot.repository");
            Instant capturedAt = parseInstant(nonBlank(root.get("captured_at"), "snapshot.captured_at"), "snapshot.captured_at");
            List<Object> values = requiredList(root.get("pull_requests"), "snapshot.pull_requests");
            if (values.isEmpty()) throw new IllegalArgumentException("snapshot.pull_requests must not be empty");
            List<PullRequest> prs = new ArrayList<>();
            Set<Long> ids = new LinkedHashSet<>();
            for (int i = 0; i < values.size(); i++) {
                PullRequest pr = PullRequest.parse(values.get(i), "snapshot.pull_requests[" + i + "]");
                if (!ids.add(pr.number())) throw new IllegalArgumentException("snapshot has duplicate PR number " + pr.number());
                prs.add(pr);
            }
            return new Snapshot(repository, capturedAt, List.copyOf(prs), raw);
        }
    }

    record PullRequest(long number, String title, String body, Instant createdAt, Instant updatedAt, Boolean draft,
                       List<String> labels, Author author, Checks checks, Review review, Branch branch, Issue issue,
                       Ci ci, FilesFact files) {
        static PullRequest parse(Object raw, String path) {
            Map<String, Object> m = requiredMap(raw, path);
            rejectUnknown(m, PR_KEYS, path);
            long number = positiveLong(m.get("number"), path + ".number", false);
            String title = stringOrEmpty(m.get("title"), path + ".title");
            String body = stringOrEmpty(m.get("body"), path + ".body");
            Instant created = optionalInstant(m.get("created_at"), path + ".created_at");
            Instant updated = optionalInstant(m.get("updated_at"), path + ".updated_at");
            return new PullRequest(number, title, body, created, updated, optionalBoolean(m.get("draft"), path + ".draft"),
                    optionalStrings(m.get("labels"), path + ".labels"), Author.parse(m.get("author"), path + ".author"),
                    Checks.parse(m.get("checks"), path + ".checks"), Review.parse(m.get("review"), path + ".review"),
                    Branch.parse(m.get("branch"), path + ".branch"), Issue.parse(m.get("issue"), path + ".issue"),
                    Ci.parse(m.get("ci"), path + ".ci"), FilesFact.parse(m.get("files"), path + ".files"));
        }
    }

    record Author(String login, String association, Long priorMergedPrs, Long openPrs, Long failingOpenPrs, Instant lastActivityAt) {
        static Author parse(Object raw, String path) {
            Map<String, Object> m = optionalMap(raw, path);
            rejectUnknown(m, AUTHOR_KEYS, path);
            String association = optionalString(m.get("association"), path + ".association");
            return new Author(optionalString(m.get("login"), path + ".login"),
                    association == null ? null : association.toUpperCase(Locale.ROOT), optionalNonNegativeLong(m.get("prior_merged_prs"), path + ".prior_merged_prs"),
                    optionalNonNegativeLong(m.get("open_prs"), path + ".open_prs"), optionalNonNegativeLong(m.get("failing_open_prs"), path + ".failing_open_prs"),
                    optionalInstant(m.get("last_activity_at"), path + ".last_activity_at"));
        }
    }

    record Checks(Long requiredFailed, Long requiredMissing, Boolean conflicts) {
        static Checks parse(Object raw, String path) {
            Map<String, Object> m = optionalMap(raw, path);
            rejectUnknown(m, CHECK_KEYS, path);
            return new Checks(optionalNonNegativeLong(m.get("required_failed"), path + ".required_failed"),
                    optionalNonNegativeLong(m.get("required_missing"), path + ".required_missing"),
                    optionalBoolean(m.get("conflicts"), path + ".conflicts"));
        }
    }

    record Review(Long unresolvedThreads, Boolean maintainerEngaged) {
        static Review parse(Object raw, String path) {
            Map<String, Object> m = optionalMap(raw, path);
            rejectUnknown(m, REVIEW_KEYS, path);
            return new Review(optionalNonNegativeLong(m.get("unresolved_threads"), path + ".unresolved_threads"),
                    optionalBoolean(m.get("maintainer_engaged"), path + ".maintainer_engaged"));
        }
    }

    record Branch(Long commitsBehindBase) {
        static Branch parse(Object raw, String path) {
            Map<String, Object> m = optionalMap(raw, path);
            rejectUnknown(m, BRANCH_KEYS, path);
            return new Branch(optionalNonNegativeLong(m.get("commits_behind_base"), path + ".commits_behind_base"));
        }
    }

    record Issue(Boolean linked, Boolean accepted) {
        static Issue parse(Object raw, String path) {
            Map<String, Object> m = optionalMap(raw, path);
            rejectUnknown(m, ISSUE_KEYS, path);
            return new Issue(optionalBoolean(m.get("linked"), path + ".linked"), optionalBoolean(m.get("accepted"), path + ".accepted"));
        }
    }

    record Ci(Boolean definitionTouched) {
        static Ci parse(Object raw, String path) {
            Map<String, Object> m = optionalMap(raw, path);
            rejectUnknown(m, CI_KEYS, path);
            return new Ci(optionalBoolean(m.get("definition_touched"), path + ".definition_touched"));
        }
    }

    record FilesFact(Long changed, Long linesChanged) {
        static FilesFact parse(Object raw, String path) {
            Map<String, Object> m = optionalMap(raw, path);
            rejectUnknown(m, FILE_KEYS, path);
            return new FilesFact(optionalNonNegativeLong(m.get("changed"), path + ".changed"),
                    optionalNonNegativeLong(m.get("lines_changed"), path + ".lines_changed"));
        }
    }

    record Policy(String name, String readyLabel, String needsPermissionLabel, String ciSafetyLabel,
                  Set<String> externalAssociations, long newcomerPriorMergedPrs, long newcomerLargeChangeLines,
                  long maxOpenPrs, long maxFailingOpenPrs, long staleDraftDays, long behindBaseCommits,
                  Set<String> enabledRules, Object original) {
        static Policy parse(Object raw) {
            Map<String, Object> root = requiredMap(raw, "policy");
            rejectUnknown(root, POLICY_KEYS, "policy");
            requireEquals(string(root.get("schema_version"), "policy.schema_version"), POLICY_SCHEMA, "policy.schema_version");
            String name = nonBlank(root.get("name"), "policy.name");
            Map<String, Object> labels = optionalMap(root.get("labels"), "policy.labels");
            rejectUnknown(labels, LABEL_KEYS, "policy.labels");
            Map<String, Object> thresholds = requiredMap(root.get("thresholds"), "policy.thresholds");
            rejectUnknown(thresholds, THRESHOLD_KEYS, "policy.thresholds");
            Set<String> associations = new LinkedHashSet<>();
            for (String association : requiredStrings(thresholds.get("external_associations"), "policy.thresholds.external_associations")) {
                associations.add(association.toUpperCase(Locale.ROOT));
            }
            List<Object> ruleValues = requiredList(root.get("rules"), "policy.rules");
            Set<String> enabled = new LinkedHashSet<>();
            for (int i = 0; i < ruleValues.size(); i++) {
                Map<String, Object> rule = requiredMap(ruleValues.get(i), "policy.rules[" + i + "]");
                rejectUnknown(rule, RULE_KEYS, "policy.rules[" + i + "]");
                String id = nonBlank(rule.get("id"), "policy.rules[" + i + "].id");
                if (!RULE_IDS.contains(id)) throw new IllegalArgumentException("policy.rules[" + i + "]: unknown rule '" + id + "'");
                Boolean active = optionalBoolean(rule.get("enabled"), "policy.rules[" + i + "].enabled");
                if (active == null) throw new IllegalArgumentException("policy.rules[" + i + "].enabled is required");
                if (active && !enabled.add(id)) throw new IllegalArgumentException("policy enables rule more than once: " + id);
            }
            return new Policy(name, label(labels, "ready", "ready-for-maintainer-review"),
                    label(labels, "needs_permission", "maintainer-permission-needed"),
                    label(labels, "ci_safety", "maintainer-ci-approval-needed"), Set.copyOf(associations),
                    positiveLong(thresholds.get("newcomer_prior_merged_prs"), "policy.thresholds.newcomer_prior_merged_prs", true),
                    positiveLong(thresholds.get("newcomer_large_change_lines"), "policy.thresholds.newcomer_large_change_lines", false),
                    positiveLong(thresholds.get("max_open_prs"), "policy.thresholds.max_open_prs", false),
                    positiveLong(thresholds.get("max_failing_open_prs"), "policy.thresholds.max_failing_open_prs", false),
                    positiveLong(thresholds.get("stale_draft_days"), "policy.thresholds.stale_draft_days", false),
                    positiveLong(thresholds.get("behind_base_commits"), "policy.thresholds.behind_base_commits", false), Set.copyOf(enabled), raw);
        }

        private static String label(Map<String, Object> labels, String key, String defaultValue) {
            String value = optionalString(labels.get(key), "policy.labels." + key);
            return value == null ? defaultValue : value;
        }
    }

    record Proposal(String ruleId, String action, int priority, Map<String, Object> evidence, String message) {
        Map<String, Object> toMap() {
            return Map.of("rule_id", ruleId, "action", action, "priority", priority, "evidence", evidence, "message", message);
        }
    }

    record Decision(long prNumber, Map<String, Object> facts, List<Proposal> proposals, List<String> skippedRules) {
        Map<String, Object> toMap() {
            return Map.of("number", prNumber, "facts", facts, "proposals", proposals.stream().map(Proposal::toMap).toList(),
                    "skipped_rules", skippedRules);
        }
    }

    record Result(Snapshot snapshot, Policy policy, Instant asOf, List<Decision> decisions) {
        int proposalCount() {
            return decisions.stream().mapToInt(d -> d.proposals().size()).sum();
        }

        Map<String, Object> toMap() {
            Map<String, Long> actions = new LinkedHashMap<>();
            Map<String, Long> rules = new LinkedHashMap<>();
            for (Decision d : decisions) {
                for (Proposal p : d.proposals()) {
                    actions.merge(p.action(), 1L, Long::sum);
                    rules.merge(p.ruleId(), 1L, Long::sum);
                }
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("repository", snapshot.repository());
            source.put("captured_at", snapshot.capturedAt().toString());
            source.put("input_digest", digest(snapshot.original()));
            source.put("policy_name", policy.name());
            source.put("policy_digest", digest(policy.original()));
            source.put("as_of", asOf.toString());
            return Map.of("schema_version", RESULT_SCHEMA, "mode", "dry-run", "side_effects", List.of(), "source", source,
                    "summary", Map.of("pull_requests", decisions.size(), "proposals", proposalCount(),
                            "actions", actions, "rules", rules), "decisions", decisions.stream().map(Decision::toMap).toList());
        }

        String markdown() {
            StringBuilder md = new StringBuilder("# AIV Triage Replay\n\n");
            md.append("**Mode:** dry-run. No GitHub API call, comment, label, draft, close, assignment or CI rerun was attempted.\n\n");
            md.append("| Field | Value |\n|---|---|\n");
            md.append("| Repository | `").append(snapshot.repository()).append("` |\n");
            md.append("| Captured at | `").append(snapshot.capturedAt()).append("` |\n");
            md.append("| Evaluated as of | `").append(asOf).append("` |\n");
            md.append("| Policy | `").append(policy.name()).append("` |\n");
            md.append("| Pull requests | ").append(decisions.size()).append(" |\n");
            md.append("| Proposed actions | ").append(proposalCount()).append(" |\n\n");
            md.append("## Proposed actions\n\n");
            boolean any = false;
            for (Decision d : decisions) {
                if (d.proposals().isEmpty()) continue;
                any = true;
                md.append("### PR #").append(d.prNumber()).append("\n\n");
                for (Proposal p : d.proposals()) {
                    md.append("- **").append(p.action()).append("** from `").append(p.ruleId()).append("`: ")
                            .append(p.message()).append(" Evidence: `").append(JsonLite.write(p.evidence())).append("`.\n");
                }
                if (!d.skippedRules().isEmpty()) {
                    md.append("- Unavailable: `").append(String.join("; ", d.skippedRules())).append("`.\n");
                }
                md.append('\n');
            }
            if (!any) md.append("No action was proposed.\n\n");
            md.append("## Unavailable facts\n\n");
            boolean skipped = false;
            for (Decision d : decisions) {
                if (!d.skippedRules().isEmpty()) {
                    skipped = true;
                    md.append("- PR #").append(d.prNumber()).append(": `")
                            .append(String.join("; ", d.skippedRules())).append("`\n");
                }
            }
            if (!skipped) md.append("None.\n");
            return md.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException(path + " must be an object");
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static Map<String, Object> optionalMap(Object raw, String path) {
        return raw == null ? Map.of() : requiredMap(raw, path);
    }

    private static List<Object> requiredList(Object raw, String path) {
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException(path + " must be an array");
        return new ArrayList<>(list);
    }

    private static List<String> requiredStrings(Object raw, String path) {
        List<Object> values = requiredList(raw, path);
        List<String> out = new ArrayList<>();
        for (Object value : values) out.add(nonBlank(value, path + "[]"));
        if (out.isEmpty()) throw new IllegalArgumentException(path + " must not be empty");
        return out;
    }

    private static List<String> optionalStrings(Object raw, String path) {
        if (raw == null) return List.of();
        List<Object> values = requiredList(raw, path);
        List<String> out = new ArrayList<>();
        for (Object value : values) out.add(nonBlank(value, path + "[]"));
        return List.copyOf(out);
    }

    private static void rejectUnknown(Map<String, Object> map, Set<String> allowed, String path) {
        for (String key : map.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException(path + ": unknown key '" + key + "'");
    }

    private static void requireEquals(String actual, String expected, String path) {
        if (!expected.equals(actual)) throw new IllegalArgumentException(path + " must equal '" + expected + "'");
    }

    private static String string(Object raw, String path) {
        if (!(raw instanceof String value)) throw new IllegalArgumentException(path + " must be a string");
        return value;
    }

    private static String stringOrEmpty(Object raw, String path) {
        return raw == null ? "" : string(raw, path);
    }

    private static String nonBlank(Object raw, String path) {
        String value = string(raw, path).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(path + " must not be blank");
        return value;
    }

    private static String optionalString(Object raw, String path) {
        return raw == null ? null : nonBlank(raw, path);
    }

    private static Boolean optionalBoolean(Object raw, String path) {
        if (raw == null) return null;
        if (raw instanceof Boolean value) return value;
        throw new IllegalArgumentException(path + " must be a boolean");
    }

    private static Long optionalNonNegativeLong(Object raw, String path) {
        return raw == null ? null : positiveLong(raw, path, true);
    }

    private static long positiveLong(Object raw, String path, boolean zeroAllowed) {
        if (!(raw instanceof Number value) || value.longValue() != value.doubleValue()
                || value.longValue() < (zeroAllowed ? 0 : 1)) {
            throw new IllegalArgumentException(path + " must be a " + (zeroAllowed ? "non-negative" : "positive") + " integer");
        }
        return value.longValue();
    }

    private static Instant optionalInstant(Object raw, String path) {
        return raw == null ? null : parseInstant(string(raw, path), path);
    }
}
