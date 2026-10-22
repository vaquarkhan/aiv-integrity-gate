/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.governance;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Ties the orchestration output to the tamper-evident record: it commits the canonical governance
 * result to the hash-chained audit so the verdict trail is replayable and any later edit is caught.
 * This is the "end to end" seam - one call turns a set of per-tool outcomes into a permanent,
 * verifiable governance record.
 *
 * @author Vaquar Khan
 */
public final class GovernanceAudit {

    private GovernanceAudit() {
    }

    /**
     * Append the canonical governance result to the hash-chained audit at {@code logFile}.
     *
     * @return the new head hash of the audit chain
     */
    public static String record(Path logFile, List<ConcernOutcome> outcomes) throws IOException {
        return HashChainAudit.append(logFile, GovernanceReport.canonical(outcomes));
    }
}
