# Gate: `dependency`

## Why it exists

Ensures **imports match declared dependencies** (e.g. Maven `pom.xml`, Python requirements) so typos and unexpected packages surface on the diff.

## When a finding is wrong

JDK modules, generated code, or known libraries whose Maven coordinates do not match Java package roots may need your **whitelist** (see configuration reference).

## How to fix

- Add the dependency to the project build file, or add an explicit whitelist entry if your policy allows it.
