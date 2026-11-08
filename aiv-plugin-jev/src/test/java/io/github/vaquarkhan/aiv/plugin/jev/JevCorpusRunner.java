/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.jev;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.util.JsonLite;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the real {@link JevGate} (production HTTP transport) over corpus PRs. Not a unit test: invoked by
 * {@code benchmarks/airflow/scripts/run-jev-bedrock-eval.py}, which prepares the input and starts the
 * local endpoint.
 *
 * <p>Args: input TSV (id, base64 title, base64 body, base64 diff, base64 newline-separated paths), output
 * TSV, base URL, threads, checks JSON file ({@code {"id": {"question": "...", "yes": "...", "no": "..."}}}).
 * Output TSV: id, outcome (pass, advisory, skipped), probabilities as {@code id=p} pairs, gate summary.
 */
public final class JevCorpusRunner {

    private static final Pattern PROB = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9_-]*): P\\(yes\\)=([0-9.]+)$", Pattern.MULTILINE);

    private JevCorpusRunner() {
    }

    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        String baseUrl = args[2];
        int threads = Integer.parseInt(args[3]);
        Object checks = JsonLite.parse(Files.readString(Path.of(args[4]), StandardCharsets.UTF_8));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("base_url", baseUrl);
        cfg.put("api_key_env", "");
        cfg.put("model", "bedrock");
        cfg.put("report_at", 0.0);
        cfg.put("max_diff_chars", 40000);
        cfg.put("timeout_seconds", 240);
        cfg.put("checks", checks);
        AIVConfig config = new AIVConfig(List.of(new AIVConfig.GateConfig(JevGate.ID, true, cfg)), Map.of());
        Base64.Decoder b64 = Base64.getDecoder();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> results = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split("\t", -1);
            results.add(pool.submit(() -> {
                String title = new String(b64.decode(c[1]), StandardCharsets.UTF_8);
                String body = new String(b64.decode(c[2]), StandardCharsets.UTF_8);
                String diff = new String(b64.decode(c[3]), StandardCharsets.UTF_8);
                List<ChangedFile> files = new ArrayList<>();
                for (String p : new String(b64.decode(c[4]), StandardCharsets.UTF_8).split("\n")) {
                    if (!p.isBlank()) {
                        files.add(new ChangedFile(p, ChangedFile.ChangeType.MODIFIED, ""));
                    }
                }
                AIVContext ctx = new AIVContext(Path.of("."), new Diff("base", "head", files, diff), config,
                        Map.of("pr_title", title, "pr_body", body.isBlank() ? "(empty)" : body));
                GateResult r = new JevGate().evaluate(ctx);
                String outcome = r.getMessage().startsWith("SKIPPED") ? "skipped" : (r.isPassed() ? "pass" : "advisory");
                List<String> probs = new ArrayList<>();
                Matcher m = PROB.matcher(r.getMessage());
                while (m.find()) {
                    probs.add(m.group(1) + "=" + m.group(2));
                }
                return c[0] + "\t" + outcome + "\t" + String.join(",", probs) + "\t"
                        + r.getMessage().replace('\t', ' ').replace("\n", " | ");
            }));
        }
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            int done = 0;
            for (Future<String> f : results) {
                out.write(f.get() + "\n");
                out.flush();
                if (++done % 20 == 0) {
                    System.out.println("jev runner: " + done + "/" + results.size());
                }
            }
        }
        pool.shutdown();
    }
}
