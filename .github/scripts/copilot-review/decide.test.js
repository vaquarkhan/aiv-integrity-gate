// Copyright 2026 Vaquar Khan
// SPDX-License-Identifier: Apache-2.0
// Run: node --test .github/scripts/copilot-review/
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const d = require('./decide.js');

const HEAD = 'a'.repeat(40);
const OLD = 'b'.repeat(40);

function facts(over = {}) {
  const base = {
    trigger: 'aiv-completed',
    pr: { number: 7, state: 'open', draft: false, labels: ['ai-review-requested'], headSha: HEAD },
    aivStatus: 'success',
    changedFiles: ['src/Main.java'],
    changedFilesTruncated: false,
    copilotReviewedShas: [],
    copilotRequested: false,
    humanFeedbackAfterHead: false,
  };
  const out = { ...base, ...over };
  out.pr = { ...base.pr, ...(over.pr || {}) };
  return out;
}
const cfg = (env = {}) => d.defaultConfig(env);

test('requests the first review when AIV passed and the label is set', () => {
  assert.equal(d.decide(facts(), cfg()).action, 'request');
});

test('re-requests only after new commits', () => {
  assert.equal(d.decide(facts({ copilotReviewedShas: [OLD] }), cfg()).action, 'rerequest');
  const same = d.decide(facts({ copilotReviewedShas: [OLD, HEAD] }), cfg());
  assert.equal(same.action, 'skip');
  assert.match(same.reason, /already reviewed/);
});

test('never reviews before the deterministic gate passes on the head commit', () => {
  for (const s of ['failure', 'pending', 'missing']) {
    const r = d.decide(facts({ aivStatus: s }), cfg());
    assert.equal(r.action, 'skip', s);
    assert.match(r.reason, /AIV Gate/);
  }
});

test('refuses when the PR edits files the reviewer reads instructions from', () => {
  for (const f of ['.github/copilot-instructions.md', '.github/instructions/x.instructions.md',
    '.github/skills/code-review/SKILL.md', 'AGENTS.md', 'airflow-core/CLAUDE.md', 'REVIEW.md',
    '.github/workflows/copilot-code-review.yml', '.github/scripts/copilot-review/decide.js']) {
    const r = d.decide(facts({ changedFiles: ['src/a.py', f] }), cfg());
    assert.equal(r.action, 'skip', f);
    assert.match(r.reason, /AI review instructions/, f);
  }
});

test('fails safe when the changed file list is truncated', () => {
  assert.equal(d.decide(facts({ changedFilesTruncated: true }), cfg()).action, 'skip');
});

test('label mode needs the label; auto mode does not', () => {
  const unlabeled = facts({ pr: { labels: [] } });
  assert.equal(d.decide(unlabeled, cfg()).action, 'skip');
  assert.equal(d.decide(unlabeled, cfg({ AIV_COPILOT_MODE: 'auto' })).action, 'request');
  assert.equal(d.decide(unlabeled, cfg({ AIV_COPILOT_MODE: 'bogus' })).action, 'skip');
});

test('opt-out label, drafts and closed PRs are skipped', () => {
  assert.equal(d.decide(facts({ pr: { labels: ['ai-review-requested', 'No-AI-Review'] } }), cfg()).action, 'skip');
  assert.equal(d.decide(facts({ pr: { draft: true } }), cfg()).action, 'skip');
  assert.equal(d.decide(facts({ pr: { state: 'closed' } }), cfg()).action, 'skip');
  assert.equal(d.decide({ trigger: 'labeled', pr: null }, cfg()).action, 'skip');
});

test('labeled trigger requires the request label from a user with write access', () => {
  const ok = facts({ trigger: 'labeled', labelName: 'ai-review-requested', labelSenderPermission: 'write' });
  assert.equal(d.decide(ok, cfg()).action, 'request');
  assert.equal(d.decide({ ...ok, labelSenderPermission: 'triage' }, cfg()).action, 'skip');
  assert.equal(d.decide({ ...ok, labelName: 'bug' }, cfg()).action, 'skip');
  assert.equal(d.decide({ ...ok, labelSenderPermission: 'triage' }, cfg({ AIV_COPILOT_MIN_PERMISSION: 'triage' })).action, 'request');
});

test('waits while reviewers are mid-discussion, unless a maintainer explicitly asks', () => {
  assert.equal(d.decide(facts({ humanFeedbackAfterHead: true }), cfg()).action, 'skip');
  const explicit = facts({ trigger: 'labeled', labelName: 'ai-review-requested', labelSenderPermission: 'admin', humanFeedbackAfterHead: true });
  assert.equal(d.decide(explicit, cfg()).action, 'request');
});

test('does not stack a second request while one is pending', () => {
  assert.equal(d.decide(facts({ copilotRequested: true }), cfg()).action, 'skip');
});

test('path matching mirrors the Java InvariantGate rule', () => {
  const p = ['.github/instructions/**', '**/AGENTS.md', '.github/copilot-instructions.md'];
  assert.ok(d.matchesInstructionPath('docs\\agents.md', p));
  assert.ok(!d.matchesInstructionPath('NOTAGENTS.md', p));
  assert.ok(!d.matchesInstructionPath('.github/instructionsX/a.md', p));
  assert.ok(!d.matchesInstructionPath('.github/copilot-instructions.md.bak', p));
  assert.ok(!d.matchesInstructionPath('', p));
  assert.ok(!d.matchesInstructionPath(undefined, p));
});

test('custom instruction paths from env replace the defaults', () => {
  const c = cfg({ AIV_REVIEW_INSTRUCTION_PATHS: 'docs/review/**, SECURITY.md' });
  assert.deepEqual(c.instructionPaths, ['docs/review/**', 'SECURITY.md']);
  assert.equal(cfg().instructionPaths.length, d.DEFAULT_INSTRUCTION_PATHS.length);
});

test('AIV status reduction from check runs', () => {
  const run = (status, conclusion, name = 'aiv') => ({ name, status, conclusion });
  assert.equal(d.aivStatusFromCheckRuns([], 'aiv'), 'missing');
  assert.equal(d.aivStatusFromCheckRuns([run('completed', 'success', 'other')], 'aiv'), 'missing');
  assert.equal(d.aivStatusFromCheckRuns([run('completed', 'success')], 'aiv'), 'success');
  assert.equal(d.aivStatusFromCheckRuns([run('in_progress', null)], 'aiv'), 'pending');
  assert.equal(d.aivStatusFromCheckRuns([run('completed', 'success'), run('completed', 'failure')], 'aiv'), 'failure');
  assert.equal(d.aivStatusFromCheckRuns([run('completed', 'neutral')], 'aiv'), 'failure');
  assert.equal(d.aivStatusFromCheckRuns(null, 'aiv'), 'missing');
});

test('Copilot identity and permission ranking helpers', () => {
  assert.ok(d.isCopilot({ login: 'copilot-pull-request-reviewer[bot]' }));
  assert.ok(d.isCopilot({ login: 'Copilot' }));
  assert.ok(!d.isCopilot({ login: 'octocat' }));
  assert.ok(!d.isCopilot(null));
  assert.ok(d.permissionAtLeast('maintain', 'write'));
  assert.ok(!d.permissionAtLeast(undefined, 'write'));
});

// ------------------------------------------------------------- I/O with a fake client

function fakeGithub(state) {
  if (state.pr.changed_files === undefined) state.pr.changed_files = state.files.length;
  const calls = [];
  const ok = (data) => Promise.resolve({ data });
  const github = {
    calls,
    paginate: (fn, args) => fn(args).then((r) => r.data),
    request: (route, body) => {
      const re = route.endsWith('/rerequest');
      calls.push([re ? 'rerequest' : 'request', route, body]);
      if (re && state.rerequestFails) return Promise.reject(Object.assign(new Error('Not Found'), { status: 404 }));
      if (!re && state.requestFails) return Promise.reject(Object.assign(new Error('Forbidden'), { status: 403 }));
      return ok({});
    },
    rest: {
      pulls: {
        get: () => ok(state.pr),
        listFiles: () => ok(state.files),
        listReviews: () => ok(state.reviews || []),
        listReviewComments: () => ok(state.comments || []),

      },
      repos: {
        listPullRequestsAssociatedWithCommit: () => ok([{ number: 7, state: 'open', head: { sha: HEAD } }]),
        getCollaboratorPermissionLevel: () => (state.permissionError ? Promise.reject(new Error('x')) : ok({ permission: state.permission || 'write' })),
        getCommit: () => ok({ commit: { committer: { date: '2026-10-20T00:00:00Z' } } }),
      },
      checks: { listForRef: () => ok(state.checks || []) },
    },
  };
  return github;
}

function fakeCore() {
  const log = { info: [], warning: [], notice: [] };
  const summary = { addHeading() { return summary; }, addTable() { return summary; }, write: () => Promise.resolve() };
  return { log, summary, info: (m) => log.info.push(m), warning: (m) => log.warning.push(m), notice: (m) => log.notice.push(m) };
}

const prState = (over = {}) => ({
  state: 'open', draft: false, labels: [{ name: 'ai-review-requested' }],
  head: { sha: HEAD }, user: { login: 'author' }, requested_reviewers: [], ...over,
});

test('run: PR reporting more files than the API returned is treated as truncated', async () => {
  const github = fakeGithub({ pr: prState({ changed_files: 3500 }), files: [{ filename: 'src/a.py' }] });
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { workflow_run: { head_sha: HEAD, conclusion: 'success', pull_requests: [{ number: 7 }] } } };
  const r = await d.run({ github, context, core: fakeCore(), env: {} });
  assert.match(r.reason, /too large/);
});

test('run: workflow_run success requests Copilot by its bot login', async () => {
  const github = fakeGithub({ pr: prState(), files: [{ filename: 'src/a.py' }] });
  const core = fakeCore();
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { workflow_run: { head_sha: HEAD, conclusion: 'success', pull_requests: [] } } };
  const r = await d.run({ github, context, core, env: { COPILOT_REVIEW_TOKEN_SET: '1' } });
  assert.equal(r.action, 'request');
  assert.equal(github.calls[0][1], 'POST /repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers');
  assert.deepEqual(github.calls[0][2].reviewers, [d.COPILOT_REVIEWER]);
  assert.equal(github.calls[0][2].headers, undefined, 'no tier token: default workflow token');
  assert.equal(core.log.warning.length, 0);
});

test('run: stale workflow_run falls back to the check runs on the current head', async () => {
  const github = fakeGithub({ pr: prState(), files: [], checks: [{ name: 'aiv', status: 'completed', conclusion: 'failure' }] });
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { workflow_run: { head_sha: OLD, conclusion: 'success', pull_requests: [{ number: 7 }] } } };
  const r = await d.run({ github, context, core: fakeCore(), env: {} });
  assert.equal(r.action, 'skip');
  assert.equal(github.calls.length, 0);
});

test('run: labeled trigger from a reader is refused; renamed instruction file is caught', async () => {
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { pull_request: { number: 7 }, label: { name: 'ai-review-requested' }, sender: { login: 'x' } } };
  const checks = [{ name: 'aiv', status: 'completed', conclusion: 'success' }];
  let r = await d.run({ github: fakeGithub({ pr: prState(), files: [], checks, permissionError: true }), context, core: fakeCore(), env: {} });
  assert.match(r.reason, /permission/);
  r = await d.run({ github: fakeGithub({ pr: prState(), files: [{ filename: 'docs/x.md', previous_filename: 'AGENTS.md' }], checks }), context, core: fakeCore(), env: {} });
  assert.match(r.reason, /AI review instructions/);
});

test('run: re-request falls back to a plain request and warns without a licensed token', async () => {
  const reviews = [{ user: { login: 'copilot-pull-request-reviewer[bot]', type: 'Bot' }, commit_id: OLD, submitted_at: '2026-10-19T00:00:00Z' }];
  const github = fakeGithub({ pr: prState(), files: [], reviews, rerequestFails: true });
  const core = fakeCore();
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { workflow_run: { head_sha: HEAD, conclusion: 'success', pull_requests: [{ number: 7 }] } } };
  const r = await d.run({ github, context, core, env: {} });
  assert.equal(r.action, 'rerequest');
  assert.deepEqual(github.calls.map((c) => c[0]), ['rerequest', 'request']);
  assert.equal(core.log.warning.length, 1);
});

test('run: human review comment after the head commit defers the automatic re-review', async () => {
  const comments = [{ user: { login: 'committer', type: 'User' }, created_at: '2026-10-21T00:00:00Z' }];
  const github = fakeGithub({ pr: prState(), files: [], comments });
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { workflow_run: { head_sha: HEAD, conclusion: 'success', pull_requests: [{ number: 7 }] } } };
  const r = await d.run({ github, context, core: fakeCore(), env: {} });
  assert.match(r.reason, /waiting for the author/);
});

test('run: ready_for_review re-checks AIV on the head and needs the label in label mode', async () => {
  const checks = [{ name: 'aiv', status: 'completed', conclusion: 'success' }];
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { action: 'ready_for_review', pull_request: { number: 7 } } };
  const core = fakeCore();
  let r = await d.run({ github: fakeGithub({ pr: prState(), files: [], checks }), context, core, env: { COPILOT_REVIEW_TOKEN_SET: 'false' } });
  assert.equal(r.action, 'request');
  assert.equal(core.log.warning.length, 1, "'false' must not count as a configured token");
  r = await d.run({ github: fakeGithub({ pr: prState({ labels: [] }), files: [], checks }), context, core: fakeCore(), env: {} });
  assert.match(r.reason, /label mode/);
});

test('review tier: none skips, other tiers pass through, require-tier waits for routing', () => {
  const withLabels = (labels) => facts({ pr: { labels: ['ai-review-requested', ...labels] } });
  const none = d.decide(withLabels(['AIV:review-none']), cfg());
  assert.equal(none.action, 'skip');
  assert.match(none.reason, /tier 'none'/);
  const bal = d.decide(withLabels(['aiv:review-balanced']), cfg());
  assert.equal(bal.action, 'request');
  assert.equal(bal.tier, 'balanced');
  assert.match(bal.reason, /at tier 'balanced'/);
  assert.equal(d.decide(withLabels([]), cfg({ AIV_COPILOT_REQUIRE_TIER: 'true' })).action, 'skip');
  assert.equal(d.decide(withLabels([]), cfg()).tier, '');
  assert.equal(d.decide(withLabels(['aiv:review-docs']), cfg({ AIV_NO_REVIEW_TIERS: 'none, docs' })).action, 'skip');
  const custom = d.decide(withLabels(['ai-tier/lite']), cfg({ AIV_REVIEW_TIER_PREFIX: 'ai-tier/' }));
  assert.equal(custom.tier, 'lite');
  // The tier gate runs before the AIV status check, so a 'none' PR is never even evaluated for review.
  assert.match(d.decide({ ...withLabels(['aiv:review-none']), aivStatus: 'failure' }, cfg()).reason, /tier 'none'/);
});

test('tier helpers', () => {
  assert.equal(d.tierFromLabels(['bug', 'aiv:review-lite', 'aiv:review-balanced'], 'aiv:review-'), 'balanced');
  assert.equal(d.tierFromLabels(['aiv:review-'], 'aiv:review-'), '');
  assert.equal(d.tierFromLabels(undefined, 'aiv:review-'), '');
  assert.equal(d.tokenForTier('balanced', { COPILOT_REVIEW_TOKEN_BALANCED: ' t1 ' }), 't1');
  assert.equal(d.tokenForTier('deep-review', { COPILOT_REVIEW_TOKEN_DEEP_REVIEW: 't2' }), 't2');
  assert.equal(d.tokenForTier('lite', { COPILOT_REVIEW_TOKEN_LITE: '  ' }), null);
  assert.equal(d.tokenForTier('', {}), null);
});

test('run: tier token is sent only on the review request and silences the token warning', async () => {
  const github = fakeGithub({ pr: prState({ labels: [{ name: 'ai-review-requested' }, { name: 'aiv:review-lite' }] }), files: [] });
  const core = fakeCore();
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { workflow_run: { head_sha: HEAD, conclusion: 'success', pull_requests: [{ number: 7 }] } } };
  const r = await d.run({ github, context, core, env: { COPILOT_REVIEW_TOKEN_LITE: 'lite-secret' } });
  assert.equal(r.action, 'request');
  assert.deepEqual(github.calls[0][2].headers, { authorization: 'token lite-secret' });
  assert.equal(core.log.warning.length, 0);
});

test('run: API errors become warnings, never failures', async () => {
  const github = fakeGithub({ pr: prState(), files: [] });
  github.rest.pulls.get = () => Promise.reject(new Error('boom'));
  const core = fakeCore();
  const context = { repo: { owner: 'o', repo: 'r' }, payload: { pull_request: { number: 7 } } };
  const r = await d.run({ github, context, core, env: {} });
  assert.equal(r.reason, 'error');
  assert.equal(core.log.warning.length, 1);

  const g2 = fakeGithub({ pr: prState(), files: [], requestFails: true });
  const c2 = fakeCore();
  const ctx2 = { repo: { owner: 'o', repo: 'r' }, payload: { workflow_run: { head_sha: HEAD, conclusion: 'success', pull_requests: [{ number: 7 }] } } };
  await d.run({ github: g2, context: ctx2, core: c2, env: { COPILOT_REVIEW_TOKEN_SET: '1' } });
  assert.match(c2.log.warning[0], /not requested/);
});
