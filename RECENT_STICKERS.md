# Recent sticker count

Settings → Chats → Keyboard → Recent sticker limit opens a numeric input prefilled with the current value. Any integer from 1 to 1,000 is accepted; existing installations default to 24. Empty, non-integer, overflowing and out-of-range inputs cannot be confirmed, and cancel leaves the setting unchanged. The selected maximum applies to both the keyboard's recent pack and the search screen with an empty query. Emoji and text searches keep their existing behavior.

The preference uses the existing encrypted settings store and is included in local settings backups. Unsupported restored integer values fall back to 24; unsupported selections are rejected. No sticker table migration or usage-history deletion is required. Reducing the limit only changes the number of results retrieved in descending last-used order; increasing it can show older available stickers again.

A retained conversation's sticker keyboard reloads when resumed after the limit changes. A keyboard refresh and each empty search read the current preference rather than caching a repository-construction value.

Regression tests cover default/unsupported values, persistence across store reopening (including a custom value of 37 and the bounds), backup inclusion, rejected inputs preserving the current value, and both repositories reading a changed limit on their next query. Device checks: enter a custom count, try empty/zero/negative/decimal/oversized input and cancel, return to an already open conversation, check the recent pack and empty search with enough previously used stickers, then restart the app and confirm the entered setting remains.
