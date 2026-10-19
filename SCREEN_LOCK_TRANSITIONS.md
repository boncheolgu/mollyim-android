# Screen-lock transition races

Reported on Memoly .10: a correct normal PIN sometimes leads to another PIN prompt; either incoming or outgoing calls can leave a black screen after ending, requiring an app restart.

## Changes

- Commit app-unlock authentication synchronously after successful verification and before dismissing the PIN window. Keep settings/setup completion deferred until removal because those callbacks may open another dialog using the same tag.
- Treat the verification request, generation and listener as one request. Cancel stale dialogs on resume instead of updating only their argument token, and replace stale requests with newly bound listeners. A queued app-unlock replacement must not reopen after another activity has authenticated.
- Track the top-resumed activity and schedule a single prompt after resume, lock-state changes and protection refresh. Recheck lifecycle, top state, access and fragment state when executing. Do not depend on focus arriving at a protected window marked not focusable. Cancel queued work on pause and coalesce duplicate PIN prompts.

No PIN verification algorithm, retry policy, decoy policy or protected-call action exception changes. Old/background verification results remain invalid. These fixes address concrete ordering defects found in code; physical-device confirmation is still needed to establish that both reported symptoms are resolved.

## .10 device reproduction attempts

Set normal PIN mode and immediate locking. Open its PIN screen, go Home for 2–3 seconds, return and enter the correct PIN once. Separately try switching the display off/on while the PIN screen is open, unlocking the system, then entering Memoly's PIN. Repeat each path 5–10 times and record whether an additional prompt appears after the valid entry.

For both incoming and outgoing calls, try (A) ending a connected call while Memoly stays foreground and (B) going Home for 2–3 seconds, returning to the call, authenticating if required, then ending it. Speakerphone helps separate proximity-sensor blanking from a persistent app window. A chat or necessary authentication screen should appear; a persistent unresponsive black screen is a failure. Note whether Back recovers it.

## Automated coverage

Eight regressions cover authentication-before-dismissal, settings completion after dismissal, stale resumed requests, queued replacement after another unlock, return from a call when top-resume precedes resume without a focus event, lock-state changes on an already top screen, queued work after pause, and unlock before queued prompt execution. PIN verification is mocked at its existing store boundary; persistence/cryptographic/retry tests remain separate. Android tests run in CI because no local JDK/Android SDK is available.

This branch is based on the .10 source, including the unmerged branding PR #3. Its review targets that branding branch so the fix diff remains isolated.
