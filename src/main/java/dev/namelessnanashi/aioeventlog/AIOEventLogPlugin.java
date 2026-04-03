package dev.namelessnanashi.aioeventlog;

import com.google.inject.Provides;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "AIOEventLog",
	description = "All-in-one local event logging for damage, AFK, level-ups, and chat keywords",
	tags = {"logging", "damage", "chat", "afk", "levels"}
)
public class AIOEventLogPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "aioeventlog";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private AIOEventLogConfig config;

	private AIOEventLogFileWriter logWriter;
	private final Map<Skill, Integer> skillLevels = new EnumMap<>(Skill.class);
	private List<String> trackedKeywords = Collections.emptyList();
	private boolean sessionActive;
	private Instant lastActivityAt;
	private Instant afkStartedAt;
	private WorldPoint lastPosition;
	private boolean afkActive;

	@Override
	protected void startUp() throws Exception
	{
		logWriter = new AIOEventLogFileWriter();
		logWriter.open(config);
		reloadTrackedKeywords();
		scheduleSessionStart();
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (logWriter != null)
		{
			closeWriter();
		}

		trackedKeywords = Collections.emptyList();
		skillLevels.clear();
		sessionActive = false;
		clearAfkTracking();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		reloadTrackedKeywords();
		writeEvent(
			"config_updated",
			"tracked_keywords", trackedKeywords.size(),
			"keywords", String.join(",", trackedKeywords),
			"archive_previous_log_on_startup", config.archivePreviousLogOnStartup(),
			"log_incoming_damage", config.logIncomingDamage(),
			"log_outgoing_damage", config.logOutgoingDamage(),
			"log_afk", config.logAfk(),
			"afk_timeout_seconds", config.afkTimeoutSeconds(),
			"log_level_ups", config.logLevelUps(),
			"log_user_chat_keywords", config.logUserChatKeywords(),
			"log_system_chat_keywords", config.logSystemChatKeywords()
		);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState gameState = event.getGameState();
		if (gameState == GameState.LOGGED_IN)
		{
			scheduleSessionStart();
			return;
		}

		if (gameState == GameState.LOGIN_SCREEN || gameState == GameState.HOPPING)
		{
			if (sessionActive)
			{
				writeEvent("session_end", "world", client.getWorld());
			}

			sessionActive = false;
			skillLevels.clear();
			clearAfkTracking();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (!sessionActive)
		{
			return;
		}

		Skill skill = event.getSkill();
		int currentLevel = event.getLevel();
		Integer previousLevel = skillLevels.put(skill, currentLevel);
		if (previousLevel == null || currentLevel <= previousLevel)
		{
			return;
		}

		if (!config.logLevelUps())
		{
			return;
		}

		writeEvent(
			"level_up",
			"account", sanitizePlayerName(client.getLocalPlayer()),
			"world", client.getWorld(),
			"tick", client.getTickCount(),
			"skill", skill.getName(),
			"previous_level", previousLevel,
			"new_level", currentLevel,
			"levels_gained", currentLevel - previousLevel,
			"xp", event.getXp(),
			"boosted_level", event.getBoostedLevel()
		);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		Hitsplat hitsplat = event.getHitsplat();
		Actor actor = event.getActor();
		if (actor == null || !isDamageHitsplat(hitsplat))
		{
			return;
		}

		if (actor == localPlayer)
		{
			if (!config.logIncomingDamage())
			{
				return;
			}

			writeEvent(
				"incoming_damage",
				"world", client.getWorld(),
				"tick", client.getTickCount(),
				"amount", hitsplat.getAmount(),
				"hitsplat_type", hitsplat.getHitsplatType(),
				"hitsplat_name", hitsplatTypeName(hitsplat.getHitsplatType()),
				"interacting_with", combatActorLabel(localPlayer.getInteracting())
			);
			return;
		}

		if (hitsplat.isMine() && config.logOutgoingDamage())
		{
			writeEvent(
				"outgoing_damage",
				"world", client.getWorld(),
				"tick", client.getTickCount(),
				"amount", hitsplat.getAmount(),
				"hitsplat_type", hitsplat.getHitsplatType(),
				"hitsplat_name", hitsplatTypeName(hitsplat.getHitsplatType()),
				"target", combatActorLabel(actor),
				"target_kind", combatActorKind(actor)
			);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String message = sanitizeText(event.getMessage());
		if (message.isEmpty())
		{
			return;
		}

		if (trackedKeywords.isEmpty() || !shouldLogChatType(event.getType()))
		{
			return;
		}

		String normalizedMessage = normalizeText(message);
		for (String keyword : trackedKeywords)
		{
			if (!normalizedMessage.contains(keyword))
			{
				continue;
			}

			writeEvent(
				"keyword_detected",
				"keyword", keyword
			);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		updateAfkState();
	}

	@Provides
	AIOEventLogConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AIOEventLogConfig.class);
	}

	private void scheduleSessionStart()
	{
		if (logWriter == null)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			if (sessionActive)
			{
				return true;
			}

			Player localPlayer = client.getLocalPlayer();
			if (client.getGameState() != GameState.LOGGED_IN || localPlayer == null)
			{
				return false;
			}

			sessionActive = true;
			writeEvent(
				"session_start",
				"account", sanitizePlayerName(localPlayer),
				"world", client.getWorld(),
				"log_path", logWriter.getLogPath()
			);
			snapshotSkillLevels();
			resetAfkTracking(localPlayer);
			return true;
		});
	}

	private void reloadTrackedKeywords()
	{
		List<String> keywords = new ArrayList<>();
		for (String keyword : Text.fromCSV(config.keywords()))
		{
			String normalizedKeyword = normalizeText(keyword);
			if (!normalizedKeyword.isEmpty() && !keywords.contains(normalizedKeyword))
			{
				keywords.add(normalizedKeyword);
			}
		}

		trackedKeywords = Collections.unmodifiableList(keywords);
	}

	private void snapshotSkillLevels()
	{
		skillLevels.clear();
		for (Skill skill : Skill.values())
		{
			skillLevels.put(skill, client.getRealSkillLevel(skill));
		}
	}

	private void updateAfkState()
	{
		int afkTimeoutSeconds = config.afkTimeoutSeconds();
		Player localPlayer = client.getLocalPlayer();
		if (!config.logAfk() || afkTimeoutSeconds <= 0 || !sessionActive || client.getGameState() != GameState.LOGGED_IN || localPlayer == null)
		{
			clearAfkTracking();
			return;
		}

		Instant now = Instant.now();
		if (lastActivityAt == null)
		{
			resetAfkTracking(localPlayer);
		}

		WorldPoint currentPosition = localPlayer.getWorldLocation();
		if (currentPosition != null)
		{
			if (lastPosition == null)
			{
				lastPosition = currentPosition;
			}
			else if (!lastPosition.equals(currentPosition))
			{
				lastPosition = currentPosition;
				observeActivity("movement", now);
			}
		}

		if (System.currentTimeMillis() - client.getMouseLastPressedMillis() < 1000L || client.getKeyboardIdleTicks() < 10)
		{
			observeActivity("input", now);
		}

		if (localPlayer.getAnimation() != -1)
		{
			observeActivity("animation", now);
		}

		Actor interacting = localPlayer.getInteracting();
		if (interacting != null)
		{
			observeActivity(activitySourceFor(interacting), now);
		}

		if (!afkActive && lastActivityAt != null
			&& Duration.between(lastActivityAt, now).compareTo(Duration.ofSeconds(afkTimeoutSeconds)) >= 0)
		{
			afkActive = true;
			afkStartedAt = lastActivityAt.plusSeconds(afkTimeoutSeconds);
			writeEvent(
				"afk_start",
				"account", sanitizePlayerName(localPlayer),
				"world", client.getWorld(),
				"tick", client.getTickCount(),
				"afk_timeout_seconds", afkTimeoutSeconds
			);
		}
	}

	private void observeActivity(String source, Instant observedAt)
	{
		if (afkActive)
		{
			writeEvent(
				"afk_end",
				"account", sanitizePlayerName(client.getLocalPlayer()),
				"world", client.getWorld(),
				"tick", client.getTickCount(),
				"resume_source", source,
				"afk_timeout_seconds", config.afkTimeoutSeconds(),
				"afk_duration_seconds", afkDurationSeconds(observedAt)
			);
			afkActive = false;
			afkStartedAt = null;
		}

		lastActivityAt = observedAt;
	}

	private long afkDurationSeconds(Instant observedAt)
	{
		if (afkStartedAt == null)
		{
			return 0;
		}

		return Math.max(0L, Duration.between(afkStartedAt, observedAt).getSeconds());
	}

	private void resetAfkTracking(Player localPlayer)
	{
		lastActivityAt = Instant.now();
		afkStartedAt = null;
		afkActive = false;
		lastPosition = localPlayer != null ? localPlayer.getWorldLocation() : null;
	}

	private void clearAfkTracking()
	{
		lastActivityAt = null;
		afkStartedAt = null;
		lastPosition = null;
		afkActive = false;
	}

	private static String activitySourceFor(Actor actor)
	{
		if (actor instanceof NPC)
		{
			return "interaction_npc";
		}

		if (actor instanceof Player)
		{
			return "interaction_player";
		}

		return "interaction";
	}

	private void closeWriter() throws IOException
	{
		logWriter.close();
		logWriter = null;
	}

	private void writeEvent(String eventType, Object... keyValues)
	{
		if (logWriter == null)
		{
			return;
		}

		java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2)
		{
			fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
		}

		logWriter.write(eventType, fields);
	}

	private boolean shouldLogChatType(ChatMessageType type)
	{
		if (type == null || type == ChatMessageType.UNKNOWN)
		{
			return false;
		}

		if (isUserChatType(type))
		{
			return config.logUserChatKeywords();
		}

		return config.logSystemChatKeywords();
	}

	private static boolean isUserChatType(ChatMessageType type)
	{
		switch (type)
		{
			case MODCHAT:
			case PUBLICCHAT:
			case PRIVATECHAT:
			case PRIVATECHATOUT:
			case MODPRIVATECHAT:
			case FRIENDSCHAT:
			case CLAN_CHAT:
			case CLAN_GUEST_CHAT:
			case AUTOTYPER:
			case MODAUTOTYPER:
			case CLAN_GIM_CHAT:
				return true;
			default:
				return false;
		}
	}

	private static boolean isDamageHitsplat(Hitsplat hitsplat)
	{
		if (hitsplat == null || hitsplat.getAmount() <= 0)
		{
			return false;
		}

		switch (hitsplat.getHitsplatType())
		{
			case HitsplatID.BLOCK_ME:
			case HitsplatID.BLOCK_OTHER:
			case HitsplatID.HEAL:
			case HitsplatID.CYAN_UP:
			case HitsplatID.CYAN_DOWN:
			case HitsplatID.SANITY_RESTORE:
				return false;
			default:
				return true;
		}
	}

	private static String hitsplatTypeName(int hitsplatType)
	{
		switch (hitsplatType)
		{
			case HitsplatID.BLOCK_ME:
				return "BLOCK_ME";
			case HitsplatID.BLOCK_OTHER:
				return "BLOCK_OTHER";
			case HitsplatID.DAMAGE_ME:
				return "DAMAGE_ME";
			case HitsplatID.DAMAGE_OTHER:
				return "DAMAGE_OTHER";
			case HitsplatID.POISON:
				return "POISON";
			case HitsplatID.DISEASE:
				return "DISEASE";
			case HitsplatID.VENOM:
				return "VENOM";
			case HitsplatID.HEAL:
				return "HEAL";
			case HitsplatID.DAMAGE_ME_CYAN:
				return "DAMAGE_ME_CYAN";
			case HitsplatID.DAMAGE_OTHER_CYAN:
				return "DAMAGE_OTHER_CYAN";
			case HitsplatID.DAMAGE_ME_ORANGE:
				return "DAMAGE_ME_ORANGE";
			case HitsplatID.DAMAGE_OTHER_ORANGE:
				return "DAMAGE_OTHER_ORANGE";
			case HitsplatID.DAMAGE_ME_YELLOW:
				return "DAMAGE_ME_YELLOW";
			case HitsplatID.DAMAGE_OTHER_YELLOW:
				return "DAMAGE_OTHER_YELLOW";
			case HitsplatID.DAMAGE_ME_WHITE:
				return "DAMAGE_ME_WHITE";
			case HitsplatID.DAMAGE_OTHER_WHITE:
				return "DAMAGE_OTHER_WHITE";
			case HitsplatID.DAMAGE_MAX_ME:
				return "DAMAGE_MAX_ME";
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
				return "DAMAGE_MAX_ME_CYAN";
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
				return "DAMAGE_MAX_ME_ORANGE";
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
				return "DAMAGE_MAX_ME_YELLOW";
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
				return "DAMAGE_MAX_ME_WHITE";
			case HitsplatID.DAMAGE_ME_POISE:
				return "DAMAGE_ME_POISE";
			case HitsplatID.DAMAGE_OTHER_POISE:
				return "DAMAGE_OTHER_POISE";
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return "DAMAGE_MAX_ME_POISE";
			case HitsplatID.PRAYER_DRAIN:
				return "PRAYER_DRAIN";
			case HitsplatID.BLEED:
				return "BLEED";
			case HitsplatID.SANITY_DRAIN:
				return "SANITY_DRAIN";
			case HitsplatID.SANITY_RESTORE:
				return "SANITY_RESTORE";
			case HitsplatID.DOOM:
				return "DOOM";
			case HitsplatID.BURN:
				return "BURN";
			default:
				return "TYPE_" + hitsplatType;
		}
	}

	private static String combatActorLabel(Actor actor)
	{
		if (actor == null)
		{
			return null;
		}

		if (actor instanceof Player)
		{
			return "player";
		}

		if (actor instanceof NPC)
		{
			String name = sanitizeText(actor.getName());
			return name.isEmpty() ? "npc" : name;
		}

		return actor.getClass().getSimpleName();
	}

	private static String combatActorKind(Actor actor)
	{
		if (actor instanceof NPC)
		{
			return "npc";
		}

		if (actor instanceof Player)
		{
			return "player";
		}

		return actor == null ? null : "actor";
	}

	private static String sanitizePlayerName(Player player)
	{
		return player == null ? null : sanitizePlayerName(player.getName());
	}

	private static String sanitizePlayerName(String name)
	{
		String sanitized = sanitizeText(Text.sanitize(name == null ? "" : name));
		return sanitized.isEmpty() ? null : sanitized;
	}

	private static String sanitizeText(String text)
	{
		if (text == null)
		{
			return "";
		}

		return Text.removeTags(text)
			.replace('\u00A0', ' ')
			.trim();
	}

	private static String normalizeText(String text)
	{
		return sanitizeText(text).toLowerCase(Locale.ENGLISH);
	}
}
