# Module: `aiv-plugin-test-adequacy` (gate `test-adequacy`)

This gate provides two distinct pieces of review evidence: added test functions must contain recognizable assertions, and production changes should be accompanied by a changed or conventionally named test.

## Architecture role

Default-on mixed-severity gate. Assertion-free added tests are objective blockers. Missing associated tests are advisory because project test organization varies.

## Runtime flow

1. Classify changed files as production or test paths.
2. Parse raw diff into added-line ranges.
3. Discover added test functions/methods in Java/Kotlin, Python, JavaScript/TypeScript, and Go styles.
4. Scan each added test body for assertion/expectation/verification calls.
5. Emit hard `assertion-free-test` findings.
6. For changed production files, search changed files and a bounded workspace index for conventional test names.
7. Emit advisory `missing-tests` evidence when no candidate exists.

## Rule IDs

| Rule | Meaning | Severity |
|------|---------|----------|
| `test-adequacy.assertion-free-test` | added test function has no recognized assertion/expect/verify form | hard |
| `test-adequacy.missing-tests` | production change has no changed or conventional workspace test | advisory |

## Configuration

```yaml
gates:
  - id: test-adequacy
    enabled: true
    severity: fail
    config:
      check_assertions: true
      require_tests: true
      max_workspace_files: 20000
```

`check_assertions` controls the hard check. `require_tests` controls missing-test advice. `max_workspace_files` bounds discovery and avoids scanning generated/vendor/build directories.

## Examples

```java
@Test void formats() {
    new Formatter().format(" x ");
}
```

blocks because it has no assertion. This supplies assertion evidence:

```java
@Test void formats() {
    assertEquals("x", new Formatter().format(" x "));
}
```

The gate also recognizes common fluent assertions, expectations, mocks/verifications, exception assertions, and language-specific test APIs.

## Added-line behavior

Only newly introduced test functions are candidates when raw diff is present, so legacy assertion-free tests are not re-flagged. Multi-line Python signatures and decorators are handled. File-only requests without raw diff evaluate the supplied test content as the proposed artifact.

## Failure behavior

The gate can return a blocking assertion-free result and advisory missing-test evidence in the same run; blocking dominates. Workspace walk/read uncertainty remains precision-first and bounded.

## Interactions

- `invariant.placeholder-test` catches explicit tautologies such as `assertTrue(true)`.
- This gate checks assertion presence, not whether assertions are meaningful.
- Build/mutation/coverage tools belong in `external` and provide deeper behavioral evidence.
- AI-DLC full-request mode is required for authoritative production-plus-test context.

## Extension points

Extend `AssertionScanner` and test discovery with precise language/framework forms. Every new token needs positive and negative tests to avoid matching comments/helpers. Extend naming conventions carefully or expose configuration rather than broad guesses.

## Tests and build

`TestAdequacyGateTest` covers language styles, diff scoping, multiline Python, assertion tokens, conventional names, bounded workspace search, hard/advisory combination, and failure behavior. Helper tests cover added lines and path classification.

```bash
mvn -pl aiv-plugin-test-adequacy test
mvn -pl aiv-plugin-test-adequacy verify
```

## Boundary

Assertion presence is lexical evidence, not proof of test quality or execution. A trivial assertion can still pass this check. Pair it with real test execution, coverage, mutation testing, and maintainer-owned acceptance tests.
