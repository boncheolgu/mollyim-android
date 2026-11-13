# PIN re-entry investigation

The user's .10 device has not reproduced the reported intermittent PIN issue with the Home/screen-off attempts. Those attempts are candidate scenarios, not a confirmed manual reproduction. Device occurrence and a code-level failing path must be distinguished.

## Concrete generation mismatch in .10

Source: `52d530cebbd8e5ad178a4ededbe7fc3ac458c61b`, corresponding to .10.

1. `ScreenLockAuthentication.authenticateForUnlock()` captures the session generation in its guarded success listener. `BaseActivity.showScreenLockPrompt()` also captures that generation for `completeAuthentication()`.
2. A PIN fragment stores that generation in its arguments when created. The verification job later captures it as `requestGeneration`.
3. `ScreenLockSession.hide()`/`end()` invalidates the session. A later unlock request captures the new generation.
4. In .10, the existing-fragment generation mismatch is checked only for setup dialogs. An ordinary app-unlock request can therefore reuse the old fragment and replace its listener without replacing its stored generation.
5. The correct PIN verifier returns Success, but the fragment checks the old request generation, finds it stale, and returns without calling the fresh success listener. Input was already cleared and the field is re-enabled in finally. The user is still locked with the PIN window present.

There is also an inverse mismatch: .10 `onResume()` can update only the stored generation, leaving its old guarded listener. In that case verification passes the fragment check but the old listener rejects success. Both request components must be rebound together; accepting an old generation would bypass cancellation and is not a fix.

## Differential execution

`freshUnlockRequestAfterSessionChangeAcceptsOneCorrectPin` keeps a resumed PIN fragment, invalidates its session, binds a fresh app-unlock request and submits one successful verification. It checks the observable result: one success callback, authenticated access and no remaining PIN window. The revised fixture uses the real PIN store, HMAC verifier and retry-state updates with an in-memory key supplied at the existing Java KeyStoreHelper boundary. It does not mock the PIN result.

The `PIN request race proof` workflow (manual or `ci-pin-race-proof-*` tag) runs that same test against the .10 PIN fragment and then the fixed fragment. The legacy source has one scheduling-only test seam: Dispatchers.IO is routed through the existing SignalDispatchers wrapper. Legacy generation, verification-result and callback logic is retained. Both runs use the same resources and fixture; the host is a plain FragmentActivity, so the changed BaseActivity is not involved in this particular proof.

The proof accepts only the named assertion showing zero success callbacks after one valid entry as a legacy reproduction. Compilation failure, fixture errors, missing reports, wrong test selection, skipped tests or an unexpected legacy pass fail the workflow. An ordinary pause/stop/start/resume with a fresh request is a separate control that must pass even against .10. The script then requires all ten current PIN/transition regressions to pass. Logs, JUnit XML and verification.json are archived together. Execution results must be checked before claiming that comparison passed.

## Scope of the conclusion

This demonstrates whether .10 contains a path that discards a correct PIN and leaves the prompt active. It does not establish which physical-device event caused the user's earlier occurrence. Exact device-path attribution needs an event trace or a confirmed manual reproduction.

The delayed-success/dismissal ordering is a separate risk. It should not, by itself, be claimed as the proven cause of a persistent second PIN request: the authentication-change observer may subsequently remove a transient extra prompt. The generation mismatch above has a directly testable functional failure.

## Further reachability review (2026-10-20, Asia/Seoul)

All .10 line references below refer to commit `52d530cebbd8e5ad178a4ededbe7fc3ac458c61b`, not the modified working tree.

### Ordinary Home return does not establish the mismatch

A normal retained-fragment return can repair both request components: fragment `onResume()` updates the argument generation (PIN dialog lines 100–114), and the top-resumed handler requests authentication again (BaseActivity lines 89–93), rebinding the listener (dialog line 288). Depending on ordering, both can refer to the same new generation before input. Therefore a test that calls task-removal and resume controller hooks on an otherwise resumed bare host is a forced inconsistent-state test, not evidence that ordinary Home return on a real device follows that path. The new ordinary-background control is intended to prevent making that inference.

### Three distinct ways an apparently correct entry can be lost

1. **Verification interrupted before completion.** `submitPin()` clears the field at line 147, before starting worker verification. Fragment `onPause()` (125–131) cancels the coroutine and clears input. A valid PIN typed just before an activity interruption may never become an authenticated session. This is not proof of an extra prompt after completed authentication. Cancellation of background authentication should remain enforced.
2. **Stale fragment or stale listener.** The fragment check at line 193 can discard a successful verifier result while retaining the empty dialog, without displaying an incorrect-PIN error. Alternatively, after `onResume()` changes only the argument, the old listener's guard (ScreenLockAuthentication lines 22–26) rejects success and reports non-user cancellation. By then the dialog has already been dismissed (PIN lines 199–203). BaseActivity's non-user cancellation handler (256–267) does not request another prompt. These are different observable states: retained PIN window versus dismissed PIN with still-protected content.
3. **Authentication invalidated after completion.** `ScreenLockSession.authenticate()` increments generation and unlocks; a later `hide()` or `end()` can invalidate/lock that session again. This must be distinguished from the first two by tracing acceptance and later invalidation separately. PIN verification success alone is not session authentication success.

### A concrete producer to investigate: duplicated task-removal detection

There are two independently delivered calls to `ScreenLockController.onTaskRemoved()`:

- Activity creation notices a new task whose previous task has disappeared (ScreenLockVisibilityObserver line 63; ScreenLockTaskTracker entering).
- KeyCachingService's system callback unconditionally ends the global session (lines 149–153), without a task ID, generation or deduplication check. `rootIntent` is not used for correlation.

If the service callback arrives after the newly launched activity has resumed, the new request is invalidated and `session.end()` sets `background=true` even though that activity is resumed. `isCurrent()` rejects *any* token until a subsequent `session.resume()` (Session lines 52–59). Even a newly captured generation cannot authenticate in that interval. If the callback arrives after successful authentication, it can instead cause a second lock.

This source-level delivery dependency is real, but delayed delivery after the new activity's resume has not been observed on the user's device. The service also launches DummyActivity, which can affect subsequent lifecycle ordering. Do not claim a confirmed swipe-away/reopen repro or ignore the DummyActivity transition in an integration test. A task-specific invalidation design should be reviewed before deduplicating events; simply ignoring removal while an activity is visible can weaken the requested immediate-lock policy.

### Black screen has an explicit recovery gap in .10

The locked branch of BaseActivity.onScreenLockStateChanged (177–181) hides content and marks its window non-focusable/non-touchable. It does not open an authentication prompt. If this happens after the activity is already top-resumed, another top-resumed event is not necessarily caused by that state update. Window focus is not a reliable recovery trigger because the window is deliberately non-focusable. Separately, the non-user cancellation branch also does not retry. A hidden, locked activity can thus lack a path to a visible prompt. This is a concrete missing recovery transition; it does not prove that the reported call-ending event was its trigger.

The call activity's ordinary disconnect path sets hangup status, removes its sticky service event and schedules finish on its root view. It does not explicitly lock the screen in that path. No call-end-specific PIN invalidation was found. Return-to-activity, screen-off and invalidation ordering remain relevant; do not label proximity-sensor behavior as an ACTION_SCREEN_OFF event without evidence.

### Additional risk in the current f9b5ba7 fix proposal

The new scheduler retries on a non-user cancellation while checking activity RESUMED/top state. A new PIN dialog cancels itself if `isCurrentAuthentication()` is false. With `session.background=true` while the activity still appears resumed, the scheduler can repeatedly create a dialog that immediately cancels and schedules another. The forced mismatch test currently calls `onActivityResuming()` before re-requesting, so it cannot detect this case. Guard prompt retry on session readiness and ensure real resume can rearm it; separately resolve late invalidation against the appropriate task. The current proposal must not be treated as fully verified or ready for release.

### Verification status

CI run `39266420312`, commit `f9b5ba7bbba709751da06fa2720547eeacd5c820`, completed with failure. All four PIN completion tests failed in setup at the mocked ScreenLockPinStore.verify stub with AbstractMethodError, before behavioral assertions. StoryContextMenuTest also failed with an initialization/dependency error; its cause/relationship has not been established. The revised fixture replaces the failing object stub with the existing KeyStoreHelper fake pattern used by PIN store tests. The transition test now places top-resume after onResume and onPostResume. AOSP performs onPostResume inside performResume and reports top-resume afterward; earlier synthetic pre-postResume ordering is not evidence of a device race. This test is a normal-return control. These revised Android tests and the baseline comparison have not been executed. Local Python proof-classifier tests pass; they validate evidence classification only, not Android authentication behavior.

### Required evidence for a physical-device conclusion

Trace a non-secret activity/request identifier, event reason, authentication generation, session background flag, host lifecycle/top state, verifier classification, callback acceptance and task identifier. Never trace PIN digits, derived verifier/key material, contacts or message contents. Distinguish request cancellation, accepted session authentication and later relocking. A real integration trace or lifecycle-driven failing test is required before naming one of the above as the user's exact cause.

## Consolidated review

See SCREEN_LOCK_CAUSE_REPRO_FIX_KO.md for the Korean report, evidence ranking, excluded lifecycle-order hypothesis, reproduction matrix and repair acceptance criteria. No revised Android execution or device reproduction was completed in this additional review.


## Implementation update after fingerprint comparison

The user reports that fingerprint authentication did not exhibit the symptom. PIN-specific request and cancellation differences are now the first validation target; shared task-removal hypotheses remain unconfirmed and unchanged. The working fix carries one captured generation through authentication and queued PIN creation, cancels/notifies on pause exactly once, captures the verification listener, tracks host request IDs, replaces invalidated open prompts, and guards scheduler retries on session readiness. The background retry-loop risk documented for f9b5ba7 has been addressed in code but awaits Android verification. The proposed focused suite now has fifteen cases. The comparison replaces both the PIN dialog and authentication dispatcher with their .10 versions, with the documented IO-dispatch seam only, before restoring both fixed files. No physical-device resolution is claimed. See SCREEN_LOCK_TRANSITIONS.md for current behavior and verification state.
