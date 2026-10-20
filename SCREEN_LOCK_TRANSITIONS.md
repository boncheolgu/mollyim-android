# Screen-lock request and foreground recovery changes

Reported on .10: a normal PIN sometimes needs to be entered again, and ending incoming/outgoing calls sometimes leaves a persistent black screen. The user reports not observing this with fingerprint authentication. Device trigger attribution remains unconfirmed.

## Implementation

- Complete app-unlock authentication before dismissing the PIN window, like the biometric callback path. Keep settings/setup callbacks deferred because they may open a different dialog under the same tag.
- Carry the generation captured by ScreenLockAuthentication through initial PIN creation and every queued replacement. Never retarget an old guarded callback to the newest generation. Replace stale dialogs rather than mutating their saved generation, and capture the listener used by each verification job.
- On host pause or unhandled dismissal, cancel PIN verification, dismiss its request and notify the host exactly once. A completed authentication does not subsequently report cancellation. Superseded or programmatically closed requests do not cancel a newer request.
- Track the host prompt's generation and request ID. Coalesce duplicates of the current request while allowing an invalidated open prompt to be replaced before another PIN entry. Ignore callbacks belonging to superseded host requests.
- Schedule prompts on real foreground/protection transitions without depending on focus at a protected non-focusable window. Check activity lifecycle, top state, FragmentManager saved state, access and session readiness before scheduling and again before execution. A background session cannot enter an immediate create/cancel/retry loop; real resume rearms prompting.

PIN verification algorithm, retry limits, decoy handling, notification privacy and permitted unauthenticated call reject/end actions are unchanged. Task-removal correlation is a separate unconfirmed hypothesis and is not modified here.

## Verification

Fifteen proposed Android regressions cover completion/dismissal ordering, settings confirmation, pause cancellation and recovery, stale requests, queued replacements invalidated before execution, ordinary background return, prompt invalidation while already open, and foreground/window recovery including session-background readiness. The bare BaseActivity tests simulate a return from a call; they do not place real WebRTC calls or prove the device report resolved.

The comparison script runs the .10 PIN dialog and authentication dispatcher against the same real PIN/HMAC fixture (only Java KeyStoreHelper is faked). It requires the named forced generation-mismatch assertion to fail while the ordinary-return control passes, restores current sources, then requires all fifteen current cases to pass. The only .10 scheduling seam is routing Dispatchers.IO through the existing SignalDispatchers provider. Infrastructure failures, missing tests and unexpected baseline results fail proof. Logs and JUnit evidence are archived by the diagnostic tag workflow.

Original CI 39266420312 failed in the old PIN mock fixture before assertions, plus an uninvestigated StoryContextMenuTest initialization error. The replacement fixture follows the existing KeyStoreHelper fake pattern. The current comparison and revised Android tests still await execution. No local JDK/Android SDK is installed.

See SCREEN_LOCK_CAUSE_REPRO_FIX_KO.md for evidence ranking and PIN_REENTRY_ANALYSIS.md for the historical investigation. The excluded synthetic top-resume-before-postResume ordering is not used as device proof. AOSP-style resume/postResume then top-resume is a normal-return control.

This branch remains stacked on draft branding PR #3. Do not merge or publish releases as part of validation.
