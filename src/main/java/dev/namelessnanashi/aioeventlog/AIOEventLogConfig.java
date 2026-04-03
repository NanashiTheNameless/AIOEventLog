package dev.namelessnanashi.aioeventlog;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("aioeventlog")
public interface AIOEventLogConfig extends Config
{
	@ConfigItem(
		keyName = "archivePreviousLogOnStartup",
		name = "Archive previous log on startup",
		description = "When enabled, an existing AIOEventLog.log is gzipped into ~/.runelite/AIOEventLog on startup. When disabled, the active AIOEventLog.log is cleared on startup instead.",
		position = 1
	)
	default boolean archivePreviousLogOnStartup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logIncomingDamage",
		name = "Log incoming damage",
		description = "Log damage dealt to the local player.",
		position = 2
	)
	default boolean logIncomingDamage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logOutgoingDamage",
		name = "Log outgoing damage",
		description = "Log damage dealt by the local player.",
		position = 3
	)
	default boolean logOutgoingDamage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logAfk",
		name = "Log AFK events",
		description = "Log AFK start and AFK end events for the local player.",
		position = 4
	)
	default boolean logAfk()
	{
		return true;
	}

	@Range(
		min = 0,
		max = 86400
	)
	@ConfigItem(
		keyName = "afkTimeoutSeconds",
		name = "AFK timeout (seconds)",
		description = "Logs AFK start and end events after this many seconds without local input, movement, animation, or interaction. Set to 0 to disable AFK logging.",
		position = 5
	)
	default int afkTimeoutSeconds()
	{
		return 300;
	}

	@ConfigItem(
		keyName = "logLevelUps",
		name = "Log level-ups",
		description = "Log local skill level-up events.",
		position = 6
	)
	default boolean logLevelUps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logDeaths",
		name = "Log deaths",
		description = "Log local player death events.",
		position = 7
	)
	default boolean logDeaths()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logUserChatKeywords",
		name = "Log user chat keywords",
		description = "Log a keyword-detected event when a tracked keyword appears in user chat.",
		position = 8
	)
	default boolean logUserChatKeywords()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logSystemChatKeywords",
		name = "Log system chat keywords",
		description = "Log a keyword-detected event when a tracked keyword appears in system, game, or filtered game messages.",
		position = 9
	)
	default boolean logSystemChatKeywords()
	{
		return true;
	}

	@ConfigItem(
		keyName = "keywords",
		name = "Tracked keywords",
		description = "Comma-separated keywords to detect in chat. Matching is case-insensitive.",
		position = 10
	)
	default String keywords()
	{
		return "";
	}
}
