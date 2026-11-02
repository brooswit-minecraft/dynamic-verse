# Retained M470/M471 integration continuation

This draft preserves the existing integration source and test runners previously retained outside
its repository. It continues the existing candidate; held authors and their worktrees are unchanged.
The travel command executor is reconciled to published Verse1 (`ab754a0`) rather than reimplemented:
the retained integration had lost its synchronous-result buffering. New unit regressions cover
inline failure/success for both send and teleport, deferred completion, duplicate completion and
lease release.

## Validation

The first focused command/service run passed 11 unit tests. After reconciling to the exact
published executor, the complete non-server suite passed **64 unit tests in 14 suites**, with
zero failures/errors/skips and process exit 0 on 2026-11-02. This ordinary unit build needs the
reviewed provider's compiled classes exactly like the native profiles do (see Portability below);
it is not README's `local-artifacts` jar alone. Reproduce with:

```sh
./gradlew compileJava compileTestJava test --offline --no-daemon \
  -PreviewedDynamicDimensionsClasses=/path/to/reviewed/classes
```

The ordinary-restart O1/O2 acceptance receipts remain retained from the earlier successful run;
this continuation does not repeat them or claim a new provider GameTest run.

## Portability

See README's "Provider setup" section for the exact pinned provider repository/ref, why its
reviewed classes are a pinned-artifact checksum rather than a cleanly rebuildable source ref today,
and the ordinary `compileJava`/`compileTestJava`/`test` command with the required
`reviewedDynamicDimensionsClasses` property. Run once with network access before adding `--offline`:
a cold Gradle dependency/toolchain cache cannot resolve anything while offline.

Native provider profiles additionally require the reviewed provider candidate jar. Both existing
local defaults can be overridden on another computer without editing source:

```sh
./gradlew <provider-task> \
  -PacceptedDynamicDimensionsJar=/path/to/reviewed-provider.jar \
  -PreviewedDynamicDimensionsClasses=/path/to/reviewed/classes
```

Do not substitute an arbitrary released provider: the profiles exercise the reviewed explicit-seed
and confirmed-close API. Runtime worlds, raw private logs, credentials and compiled outputs are not
part of this source snapshot. Review the receipt wrappers before invoking provider tasks: restart
phases deliberately share their retained world and must not be repeated as a generic build check.

## Remaining acceptance

M470 is unfinished. Safe delete still fails closed with `ERR_SAFE_DELETE_UNAVAILABLE`; its full
owned-path confinement, player handling, confirmed-close, destructive phase and crash-recovery
protocol has not been implemented. No deletion was attempted. Explicit-seed SUPERFLAT runtime
acceptance also remains. M472 retains its M470/M471 dependencies. This draft neither approves nor
merges Verse1 and does not authorize deployment or starting held workers.
