# Shadow-pilot measurement plan

This plan prepares an advisory-only AIV evaluation. It does not claim that AIV reduces review time, queue size, cost, or false positives. Those are hypotheses to test with an agreed baseline.

## Purpose

Test whether deterministic pre-review policy evidence is useful to maintainers, platform teams, security teams, and compliance reviewers before any blocking policy or commercial packaging decision.

## Entry conditions

- A participant owns a repository with sustained pull-request volume.
- The participant can run AIV in advisory mode for four weeks.
- The participant agrees which rules are in scope and which existing controls remain authoritative.
- The participant agrees not to treat JEV output as an admission decision.
- A baseline period and collection method are written down before the pilot starts.

Do not describe participants as customers, design partners, or buyers until they explicitly agree to that role.

## Operating model

1. Keep blocking syntax and invariant rules unchanged if they already protect the repository.
2. Configure new policy, review-routing, external-tool, documentation, or metadata findings as advisory.
3. Keep JEV optional and advisory. Record skip reasons and latency separately.
4. Publish stable finding IDs, policy digest, base commit, head commit, and any suppression or override.
5. Review finding samples with maintainers weekly. Do not tune rules silently.
6. Promote a rule only after the participant agrees on measured precision, false-positive cost, and exception handling.

## Measures

| Measure | Definition | Baseline | Pilot target | Evidence source |
|---|---|---|---|---|
| Setup time | Minutes from approved start to first valid AIV result | Measure before rollout | Record, do not pre-claim improvement | Workflow logs and setup checklist |
| Finding precision by rule | Findings judged useful divided by findings reviewed | Sample and label before change | Participant-defined threshold | Review disposition export |
| False-positive rate | Findings judged incorrect or not actionable divided by findings reviewed | Sample and label before change | Participant-defined threshold | Review disposition export |
| Finding acceptance | Findings that result in a code, policy, or review action | None | Record trend only | Pull-request links and findings receipt |
| First useful reviewer action | Time from PR opening to first accepted review action | Existing PR analytics | Compare only after baseline is stable | SCM timestamps |
| Review routing | Counts per no-review, lite, balanced, specialist tier | None | Distribution only, no throughput claim | AIV receipt and labels |
| Override rate | Approved exceptions divided by applicable findings | None | Record reason and expiration | Policy/audit record |
| Repeated author friction | Repeat findings for the same rule after a documented fix brief | None | Record without author scoring | De-identified rule sequence |
| Human review minutes | Actual reviewer minutes, sampled through participant-approved method | Existing process | Compare only with matched work categories | Participant time study |
| JEV availability | Successful response, skip reason, adapter latency, tokens | None | Operational observation only | Adapter stats and AIV receipt |
| Evidence usefulness | Maintainer or compliance reviewer rating of receipt completeness | None | Qualitative decision record | Structured interview notes |

Do not make PR-block count the primary success measure. A large number can indicate noise or over-broad policy rather than value.

## Weekly review questions

- Which deterministic findings did reviewers act on?
- Which findings were incorrect, unclear, or too late to help?
- Did the protected-base policy make the outcome easier to explain?
- Did existing GitHub, Sonar, Semgrep, or other controls already cover the finding?
- Did review-tier labels change reviewer behavior in a useful way?
- Did any exception need an owner, reason, expiration, or follow-up rule change?
- Did JEV add review context that a maintainer found useful? Keep this separate from deterministic precision.

## End-of-pilot decision

Choose one outcome per rule or profile:

- Keep advisory.
- Tune and repeat measurement.
- Promote to blocking with documented evidence.
- Remove because an existing control already solves it.
- Stop the pilot because the participant does not find the evidence useful.

A compliance evidence pilot starts only after a regulated buyer or internal compliance owner confirms that the receipt, retention, and approval workflow address a real audit need. AIV supplies control evidence. It does not certify compliance.
