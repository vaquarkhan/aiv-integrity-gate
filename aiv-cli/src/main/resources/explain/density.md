# Gate: `density`

## Why it exists

Catches **low-signal changes**: empty scaffolding, repetitive boilerplate, and (for Java) classes with almost no real control flow. This is the main statistical signal that a change is “slop” rather than intentional logic.

## When a finding is legitimate

Large DTO layers, generated stubs, or data holders that truly have almost no branches may trip LDR. Use **refactor** thresholds, **exclude_paths**, or **trusted_authors** in config.

## How to fix

- Add real control flow, validation, or domain logic instead of pure accessors.
- For non-Java files, raise entropy by varying structure meaningfully (not by adding noise).

## Related config

- `ldr_threshold`, `entropy_threshold`, `refactor_net_loc_threshold`, `trusted_authors`
