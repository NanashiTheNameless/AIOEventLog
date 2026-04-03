# AIOEventLog
All-in-one event logging for damage, AFK, level-ups, and chat keywords.

The plugin writes one Event record per line to `~/.runelite/AIOEventLog.log` (or `%USERPROFILE%\.runelite\AIOEventLog.log` on Windows) using a tab-delimited `key=value` format that is easy to read and understand.

String values are always quoted and escaped, while numbers and booleans stay unquoted. A typical line looks like: `ts="2026-04-02T22:10:00Z"\tevent="incoming_damage"\tamount=12\tworld=301`.

Each startup begins with a fresh `AIOEventLog.log`. If `Archive previous log on startup` is enabled, the prior log is gzipped into `~/.runelite/AIOEventLog/` as a `.log.gz` archive first; otherwise it is cleared instead.

Feature settings let you turn incoming damage, outgoing damage, AFK events, level-ups, and user-chat/system-chat keyword logging on or off independently.

It records:

- Session start and end events
- Incoming damage on the local player
- Outgoing damage dealt by the local player
- AFK start and end events after a configurable inactivity timeout
- Local skill level-ups
- `keyword_detected` events for comma-separated keywords configured in the plugin panel, with user chat and system chat configurable separately
- `config_updated` events when AIOEventLog's own settings change

Combat events intentionally do not include player names or actor IDs. When another player is involved, the log uses generic values like `player` instead.

This plugin uses features also found in:

- [Combat-Logger](https://runelite.net/plugin-hub/show/combat-logger): combat event capture and local-player damage attribution for incoming and outgoing hits.
- [Damage-Taken-Logger](https://runelite.net/plugin-hub/show/damage-taken-logger): focused logging of damage taken and damage amounts.
- [Chat-Logger](https://runelite.net/plugin-hub/show/chat-logger): chat message logging to a local file.
- [Data-Logger](https://runelite.net/plugin-hub/show/data-logger): structured local event logging.
- [RuneLogger](https://runelite.net/plugin-hub/show/runelogger): general-purpose RuneLite event logging and event trail ideas.
- [Chat-Notifications](https://github.com/runelite/runelite/wiki/Chat-Notifications): matching configured keywords or phrases in chat messages.
- [Virtual-Levels](https://github.com/runelite/runelite/wiki/Virtual-Levels): monitoring skill progression and level-related events.
- [Virtual-Level-Ups](https://runelite.net/plugin-hub/show/virtual-level-ups): extra level-up-focused tracking in the same general area as local level-up logging.
- [AfkTimer](https://runelite.net/plugin-hub/show/afk-timer): AFK timeout tracking based on player inactivity.
