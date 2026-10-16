# Memoly branding

App-name references in shipped string resources use Memoly across all locales and modules. Korean text also replaces the old transliteration and adjusts particles for the new name. The project website and install links point to this fork and its releases. Translation maintenance generates Memoly app-name text so updates do not restore the previous branding.

Run `python3 .github/scripts/memoly_branding.py` after importing upstream translations. The pipeline's `test-memoly-branding.py` verifies current branding, XML validity, placeholder/markup preservation and handling of external services and notices.

Original copyright and upstream donation/support notices retain their actual attribution. MollySocket is a separate service and keeps its product name, protocol and setup URL. Support email addresses, F-Droid/dependency endpoints, repository URLs, source packages/resource IDs, saved preference keys, legacy backup identifiers, Keystore aliases and PIN verification domains keep their existing values. Those strings are compatibility data or upstream identity, not the Memoly display name. Renaming them would require separate migrations or replacement services.

The original upstream README is retained in `UPSTREAM.md`. Its distribution, signing, sponsorship and support claims describe upstream; the top-level README describes this fork. This branding change does not alter signing keys, application ID, lock policy or saved data.
