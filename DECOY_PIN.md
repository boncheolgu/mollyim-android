# Decoy PIN

In Privacy → App security, select Memoly PIN mode, then Decoy PIN. Confirm the normal PIN, choose a launchable external app and enter a different 4–16 digit PIN twice. The same control changes or removes the alternate credential. Changing the normal PIN or switching out of PIN mode removes it and its Keystore key.

On the app unlock screen, a normal PIN authenticates as before. A decoy PIN ends the authentication session, invalidates pending authentication/action tokens, clears queued activity actions, hides protected windows and launches the selected app. Memoly-owned tasks are then removed from Recents and remaining live Memoly activities are finished. Launch failure still leaves Memoly locked and closes its screens. Tasks rooted in a foreign calling app are preserved. Call PiP auto-entry and manual entry are disabled during the exit; this does not request call termination.

Settings confirmation and guarded support-action prompts require the normal PIN. They cannot authorize a change or launch another app using the decoy PIN. Only the explicit app-unlock entry enables alternate routing; fullscreen presentation alone does not enable it.

Both PINs use the existing shared attempt delay. One bad input increments the failure count once; neither PIN bypasses an active delay. Only normal PIN success resets failed attempts; knowing the decoy PIN cannot reset the normal PIN guess counter. The alternate verifier has a separate random salt, non-exportable Android Keystore HMAC key and purpose domain. Neither raw PIN is saved or passed to another app. The optional record stays in the existing atomic, device-local `noBackupFilesDir` file. Existing normal PIN records remain compatible; malformed optional data cannot erase the normal verifier. Credential operations share one monitor.

The picker queries only MAIN/LAUNCHER entries and excludes Memoly, disabled/non-exported entries and activities requiring unavailable permissions. It does not add QUERY_ALL_PACKAGES. The stored explicit component is revalidated on each launch. A missing, disabled, replaced or inaccessible target cannot unlock Memoly.

## Validation

Automated regressions cover credential classification, purpose separation, duplicate PINs, common retry limits, missing keys, stale/cancelled setup, key cleanup, normal PIN/mode changes, settings versus app-unlock routing, launch failures, stale verification and own versus foreign tasks. Existing keyboard focus and session tests remain enabled. The PIN form scrolls when available space shrinks.

CI and device results will be recorded in the PR. Device checks should include setup/change/removal, an active retry delay, keyboard/landscape layout, target removal, process restart, Recents, a share opened from another app and ongoing-call PiP. This feature does not hide Memoly installation, notifications or all transition traces, and it does not create a fake database or delete conversations.
