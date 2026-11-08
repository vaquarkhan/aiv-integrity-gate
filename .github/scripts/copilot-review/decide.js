// Copyright 2026 Vaquar Khan
// SPDX-License-Identifier: Apache-2.0
//
// Decides whether to ask GitHub Copilot code review to look at a pull request, and does it.
//
// The rules follow the AIV pipeline (docs/pipeline-aiv-copilot.md) plus the safeguards the
// Apache Spark community asked for when it trialled an automated AI reviewer (dev@spark,
// "[DISCUSS] Automated AI code review on new Spark PRs (trial)", October 2026):
//
//   * on request only: a maintainer applies a label, so nobody gets an unwanted review
//   * a per-PR opt-out label
//   * drafts are skipped
//   * review again only after the author pushes new commits, never twice for one commit
//   * wait while humans are mid-discussion on the latest commit
//   * never approve, never comment when there is nothing to say
//
// AIV adds two rules of its own:
//
//   * the deterministic AIV Gate must have passed on the exact head commit, so model
//     credits are never spent on a PR with a merge conflict block or a broken parse
//   * no AI review when the PR edits the files AI reviewers read their instructions from,
//     because Copilot loads instructions and skills from the PR head branch
//   * optional review tier from AIV's review-routing gate (label aiv:review-<tier>): tier
//     'none' gets no AI review; other tiers can use their own token (COPILOT_REVIEW_TOKEN_<TIER>),
//     which is how a Lite or Balanced effort account is chosen
//
// Everything here is advisory. A failure to request a review is a warning, never a red X.
//
// decide() is a pure function so it can be unit tested with `node --test`.

'use strict';

const COPILOT_REVIEWER = 'copilot-pull-request-reviewer[bot]';
const COPILOT_LOGINS = new Set(['copilot-pull-request-reviewer[bot]', 'copilot-pull-request-reviewer', 'copilot']);

const DEFAULT_INSTRUCTION_PATHS = [
  '.github/copilot-instructions.md',
  '.github/instructions/**',
  '.github/skills/**',
  '.github/prompts/**',
  '.github/agents/**',
  '.github/workflows/copilot-code-review.yml',
  '.github/workflows/copilot-setup-steps.yml',
  '.github/workflows/copilot-review.yml',
  '.github/scripts/copilot-review/**',
  '**/AGENTS.md',
  '**/CLAUDE.md',
  '**/GEMINI.md',
  '**/REVIEW.md',
];

function defaultConfig(env = {}) {
  const list = (v, fallback) =>
    v && v.trim() ? v.split(/[\n,]/).map((s) => s.trim()).filter(Boolean) : fallback;
  return {
    mode: (env.AIV_COPILOT_MODE || 'label').trim().toLowerCase(),
    requestLabel: (env.AIV_COPILOT_REQUEST_LABEL || 'ai-review-requested').trim(),
    optOutLabel: (env.AIV_COPILOT_OPT_OUT_LABEL || 'no-ai-review').trim(),
    aivCheckName: (env.AIV_CHECK_NAME || 'aiv').trim(),
    instructionPaths: list(env.AIV_REVIEW_INSTRUCTION_PATHS, DEFAULT_INSTRUCTION_PATHS),
    minLabelPermission: (env.AIV_COPILOT_MIN_PERMISSION || 'write').trim().toLowerCase(),
    // Review tier published by AIV's opt-in review-routing gate (--label-review-tier).
    tierPrefix: (env.AIV_REVIEW_TIER_PREFIX || 'aiv:review-').trim().toLowerCase(),
    noReviewTiers: list(env.AIV_NO_REVIEW_TIERS, ['none']).map((t) => t.toLowerCase()),
    requireTier: ['true', '1'].includes(String(env.AIV_COPILOT_REQUIRE_TIER || '').toLowerCase()),
  };
}

/** The review tier from labels such as `aiv:review-balanced`; '' when none. Several labels: first in sort order. */
function tierFromLabels(labels, prefix) {
  const tiers = (labels || [])
    .map((l) => String(l).toLowerCase())
    .filter((l) => l.startsWith(prefix) && l.length > prefix.length)
    .map((l) => l.slice(prefix.length))
    .sort();
  return tiers.length ? tiers[0] : '';
}

/** Token for a tier: COPILOT_REVIEW_TOKEN_<TIER> when present, otherwise null (use the default token). */
function tokenForTier(tier, env) {
  if (!tier) return null;
  const v = env[`COPILOT_REVIEW_TOKEN_${tier.toUpperCase().replace(/-/g, '_')}`];
  return v && String(v).trim() ? String(v).trim() : null;
}

/** Same matching rules as InvariantGate.matchesReviewInstructionPath on the Java side. */
function matchesInstructionPath(path, patterns) {
  if (!path || !path.trim()) return false;
  const p = path.replace(/\\/g, '/').toLowerCase();
  return patterns.some((raw) => {
    const pat = raw.replace(/\\/g, '/').toLowerCase();
    if (pat.startsWith('**/')) {
      const name = pat.slice(3);
      return p === name || p.endsWith('/' + name);
    }
    if (pat.endsWith('/**')) return p.startsWith(pat.slice(0, -2));
    return p === pat;
  });
}

/**
 * Reduces the check runs reported for one commit to a single AIV status.
 * Any failing run wins; otherwise any unfinished run means pending.
 */
function aivStatusFromCheckRuns(checkRuns, name) {
  const runs = (checkRuns || []).filter((r) => r && r.name === name);
  if (runs.length === 0) return 'missing';
  const bad = new Set(['failure', 'cancelled', 'timed_out', 'action_required', 'stale', 'startup_failure']);
  if (runs.some((r) => r.status === 'completed' && bad.has(r.conclusion))) return 'failure';
  if (runs.some((r) => r.status !== 'completed')) return 'pending';
  return runs.every((r) => r.conclusion === 'success') ? 'success' : 'failure';
}

function isCopilot(user) {
  return Boolean(user && user.login && COPILOT_LOGINS.has(user.login.toLowerCase()));
}

const PERMISSION_RANK = { none: 0, read: 1, triage: 2, write: 3, maintain: 4, admin: 5 };

function permissionAtLeast(actual, required) {
  return (PERMISSION_RANK[(actual || 'none').toLowerCase()] || 0) >= (PERMISSION_RANK[required] || 3);
}

/**
 * facts = {
 *   trigger: 'labeled' | 'aiv-completed' | 'ready-for-review',
 *   labelName, labelSenderPermission,          // labeled trigger only
 *   pr: { number, state, draft, labels: [names], headSha, headCommittedAt },
 *   aivStatus: 'success' | 'failure' | 'pending' | 'missing',
 *   changedFiles: [paths], changedFilesTruncated: bool,
 *   copilotReviewedShas: [sha], copilotRequested: bool,
 *   humanFeedbackAfterHead: bool,
 * }
 * returns { action: 'request' | 'rerequest' | 'skip', reason }
 */
function decide(facts, cfg) {
  const skip = (reason) => ({ action: 'skip', reason });
  const pr = facts.pr;
  if (!pr) return skip('no open pull request for this event');
  if (pr.state !== 'open') return skip(`PR #${pr.number} is ${pr.state}`);
  if (!['label', 'auto'].includes(cfg.mode)) return skip(`unknown AIV_COPILOT_MODE '${cfg.mode}'`);
  const labels = new Set((pr.labels || []).map((l) => l.toLowerCase()));
  if (labels.has(cfg.optOutLabel.toLowerCase())) return skip(`opt-out label '${cfg.optOutLabel}' is set`);
  if (pr.draft) return skip('draft PR; reviewed once it is marked ready and AIV passes');

  if (facts.trigger === 'labeled') {
    if ((facts.labelName || '').toLowerCase() !== cfg.requestLabel.toLowerCase()) {
      return skip(`label '${facts.labelName}' is not the request label`);
    }
    if (!permissionAtLeast(facts.labelSenderPermission, cfg.minLabelPermission)) {
      return skip(`label applied by a user with '${facts.labelSenderPermission}' permission; '${cfg.minLabelPermission}' required`);
    }
  } else if (cfg.mode === 'label' && !labels.has(cfg.requestLabel.toLowerCase())) {
    return skip(`label mode: '${cfg.requestLabel}' is not set`);
  }

  const tier = tierFromLabels(pr.labels, cfg.tierPrefix);
  if (tier && cfg.noReviewTiers.includes(tier)) {
    return skip(`AIV routed this PR to review tier '${tier}', which gets no AI review`);
  }
  if (!tier && cfg.requireTier) {
    return skip(`no '${cfg.tierPrefix}<tier>' label yet; AIV review routing must run first`);
  }

  if (facts.aivStatus !== 'success') {
    return skip(`AIV Gate is '${facts.aivStatus}' on ${short(pr.headSha)}; deterministic defects are fixed before any AI review`);
  }
  if (facts.changedFilesTruncated) {
    return skip('PR file list is too large to verify that no review instruction file changed');
  }
  const touched = (facts.changedFiles || []).filter((f) => matchesInstructionPath(f, cfg.instructionPaths));
  if (touched.length > 0) {
    return skip(`PR edits AI review instructions (${touched.slice(0, 3).join(', ')}); a maintainer reviews those by hand`);
  }
  const reviewed = facts.copilotReviewedShas || [];
  if (reviewed.includes(pr.headSha)) return skip(`Copilot already reviewed ${short(pr.headSha)}; waiting for new commits`);
  if (facts.copilotRequested) return skip('a Copilot review is already requested and pending');
  // An explicit label from a maintainer overrides the wait; automatic re-reviews do not pile on.
  if (facts.trigger !== 'labeled' && facts.humanFeedbackAfterHead) {
    return skip('reviewers commented after the latest commit; waiting for the author to push');
  }
  const at = tier ? ` at tier '${tier}'` : '';
  if (reviewed.length > 0) {
    return { action: 'rerequest', tier, reason: `new commits since the last Copilot review; reviewing ${short(pr.headSha)}${at}` };
  }
  return { action: 'request', tier, reason: `AIV passed on ${short(pr.headSha)}; requesting the first Copilot review${at}` };
}

function short(sha) {
  return (sha || '').slice(0, 8) || 'unknown';
}

// ---------------------------------------------------------------- GitHub I/O

async function findPullRequest({ github, context }) {
  const { owner, repo } = context.repo;
  const p = context.payload;
  if (p.pull_request) return p.pull_request.number;
  const run = p.workflow_run;
  if (!run) return null;
  if (run.pull_requests && run.pull_requests.length > 0) return run.pull_requests[0].number;
  const prs = await github.rest.repos.listPullRequestsAssociatedWithCommit({ owner, repo, commit_sha: run.head_sha });
  const open = prs.data.find((x) => x.state === 'open' && x.head.sha === run.head_sha);
  return open ? open.number : null;
}

async function gatherFacts({ github, context, cfg }) {
  const { owner, repo } = context.repo;
  const number = await findPullRequest({ github, context });
  const trigger = context.payload.workflow_run ? 'aiv-completed'
    : context.payload.action === 'ready_for_review' ? 'ready-for-review' : 'labeled';
  if (!number) return { trigger, pr: null };

  const { data: pr } = await github.rest.pulls.get({ owner, repo, pull_number: number });
  const headSha = pr.head.sha;
  const facts = {
    trigger,
    pr: {
      number,
      state: pr.state,
      draft: pr.draft,
      labels: (pr.labels || []).map((l) => l.name),
      headSha,
    },
  };

  if (trigger === 'labeled') {
    facts.labelName = context.payload.label ? context.payload.label.name : '';
    const sender = context.payload.sender ? context.payload.sender.login : '';
    try {
      const { data } = await github.rest.repos.getCollaboratorPermissionLevel({ owner, repo, username: sender });
      facts.labelSenderPermission = data.permission;
    } catch (e) {
      facts.labelSenderPermission = 'none';
    }
  }

  // A workflow_run event for an older commit must not stand in for the current head.
  const run = context.payload.workflow_run;
  if (run && run.head_sha === headSha) {
    facts.aivStatus = run.conclusion === 'success' ? 'success' : 'failure';
  } else {
    const checks = await github.paginate(github.rest.checks.listForRef, {
      owner, repo, ref: headSha, check_name: cfg.aivCheckName, per_page: 100,
    });
    facts.aivStatus = aivStatusFromCheckRuns(checks, cfg.aivCheckName);
  }

  const files = await github.paginate(github.rest.pulls.listFiles, { owner, repo, pull_number: number, per_page: 100 });
  facts.changedFiles = [];
  for (const f of files) {
    facts.changedFiles.push(f.filename);
    if (f.previous_filename) facts.changedFiles.push(f.previous_filename);
  }
  // The files endpoint stops at 3000 entries; fail safe instead of trusting a partial list.
  facts.changedFilesTruncated = files.length >= 3000 || (pr.changed_files || 0) > files.length;

  const reviews = await github.paginate(github.rest.pulls.listReviews, { owner, repo, pull_number: number, per_page: 100 });
  facts.copilotReviewedShas = reviews.filter((r) => isCopilot(r.user)).map((r) => r.commit_id);
  facts.copilotRequested = (pr.requested_reviewers || []).some(isCopilot);

  const { data: commit } = await github.rest.repos.getCommit({ owner, repo, ref: headSha });
  const headAt = Date.parse(commit.commit.committer.date);
  const comments = await github.paginate(github.rest.pulls.listReviewComments, { owner, repo, pull_number: number, per_page: 100 });
  const humanAfter = (ts, user) => Boolean(user) && !isCopilot(user) && user.type !== 'Bot' && Date.parse(ts) > headAt;
  facts.humanFeedbackAfterHead =
    comments.some((c) => humanAfter(c.created_at, c.user)) ||
    reviews.some((r) => humanAfter(r.submitted_at, r.user));
  return facts;
}

async function requestCopilot({ github, context, number, action, core, token }) {
  const { owner, repo } = context.repo;
  const body = { owner, repo, pull_number: number, reviewers: [COPILOT_REVIEWER] };
  // Copilot attributes the request (and picks the effort level) from the token owner, so a tier token
  // belonging to a Lite or Balanced machine account selects the effort. It is sent only on this call.
  if (token) body.headers = { authorization: `token ${token}` };
  if (action === 'rerequest') {
    try {
      await github.request('POST /repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers/rerequest', body);
      return 'rerequested';
    } catch (e) {
      core.info(`Re-request endpoint unavailable (${e.status || e.message}); falling back to a plain request.`);
    }
  }
  await github.request('POST /repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers', body);
  return 'requested';
}

async function run({ github, context, core, env = process.env }) {
  const cfg = defaultConfig(env);
  let facts;
  try {
    facts = await gatherFacts({ github, context, cfg });
  } catch (e) {
    core.warning(`Copilot review skipped: could not read PR state (${e.message}). Advisory step; PR not blocked.`);
    return { action: 'skip', reason: 'error' };
  }
  const decision = decide(facts, cfg);
  const where = facts.pr ? `PR #${facts.pr.number}` : 'event';
  await core.summary
    .addHeading('Copilot review request', 3)
    .addTable([
      [{ data: 'Field', header: true }, { data: 'Value', header: true }],
      ['Target', where],
      ['Mode', cfg.mode],
      ['AIV Gate on head', facts.aivStatus || 'n/a'],
      ['Review tier', (facts.pr && tierFromLabels(facts.pr.labels, cfg.tierPrefix)) || 'not set'],
      ['Decision', decision.action],
      ['Reason', decision.reason],
    ])
    .write();
  if (decision.action === 'skip') {
    core.info(`${where}: skip, ${decision.reason}`);
    return decision;
  }
  const tierToken = tokenForTier(decision.tier, env);
  if (!tierToken && !['true', '1'].includes(String(env.COPILOT_REVIEW_TOKEN_SET || '').toLowerCase())) {
    core.warning('COPILOT_REVIEW_TOKEN is not set. Requests made as github-actions[bot] may be accepted but never start a review; use a token from a user with Copilot code review.');
  }
  try {
    const done = await requestCopilot({
      github, context, number: facts.pr.number, action: decision.action, core, token: tierToken,
    });
    core.notice(`${where}: Copilot review ${done}. ${decision.reason}`);
  } catch (e) {
    core.warning(`${where}: Copilot review not requested (${e.status || ''} ${e.message}). Advisory step; PR not blocked.`);
  }
  return decision;
}

module.exports = {
  COPILOT_REVIEWER,
  DEFAULT_INSTRUCTION_PATHS,
  defaultConfig,
  matchesInstructionPath,
  aivStatusFromCheckRuns,
  isCopilot,
  permissionAtLeast,
  tierFromLabels,
  tokenForTier,
  decide,
  gatherFacts,
  requestCopilot,
  run,
};
