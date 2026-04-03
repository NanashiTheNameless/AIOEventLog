package dev.namelessnanashi.aioeventlog;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AIOEventLogPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AIOEventLogPlugin.class);
		RuneLite.main(args);
	}
}
