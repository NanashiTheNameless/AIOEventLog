package dev.namelessnanashi.aioeventlog;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AIOEventLogFileWriter implements Closeable
{
	private static final Logger log = LoggerFactory.getLogger(AIOEventLogFileWriter.class);
	private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
		DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
	private static final String ARCHIVE_PREFIX = "AIOEventLog-";

	private final Path logPath = RuneLite.RUNELITE_DIR.toPath().resolve("AIOEventLog.log");
	private final Path archiveDirectory = RuneLite.RUNELITE_DIR.toPath().resolve("AIOEventLog");

	private BufferedWriter writer;

	Path getLogPath()
	{
		return logPath;
	}

	Path getArchiveDirectory()
	{
		return archiveDirectory;
	}

	synchronized void open(AIOEventLogConfig config) throws IOException
	{
		if (writer != null)
		{
			return;
		}

		Files.createDirectories(logPath.getParent());
		rolloverStartupLog(config.archivePreviousLogOnStartup());
		reopenWriter();
	}

	synchronized void write(String eventType, Map<String, ?> fields)
	{
		if (writer == null)
		{
			return;
		}

		String line = formatLine(eventType, fields);

		try
		{
			writer.write(line);
			writer.newLine();
			writer.flush();
		}
		catch (IOException ex)
		{
			log.warn("Unable to write to event log {}", logPath, ex);
		}
	}

	@Override
	public synchronized void close() throws IOException
	{
		if (writer == null)
		{
			return;
		}

		writer.close();
		writer = null;
	}

	private void reopenWriter() throws IOException
	{
		writer = Files.newBufferedWriter(
			logPath,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.WRITE,
			StandardOpenOption.APPEND
		);
	}

	private void rolloverStartupLog(boolean archivePreviousLog) throws IOException
	{
		if (!Files.exists(logPath) || Files.size(logPath) <= 0)
		{
			return;
		}

		if (archivePreviousLog)
		{
			archiveWholeLog("startup-archive");
		}

		Files.write(logPath, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	private void archiveWholeLog(String reason) throws IOException
	{
		Path archivePath = nextArchivePath(reason);
		try (var in = Files.newInputStream(logPath);
		     OutputStream out = new GZIPOutputStream(Files.newOutputStream(
			 archivePath,
			 StandardOpenOption.CREATE_NEW,
			 StandardOpenOption.WRITE)))
		{
			in.transferTo(out);
		}
	}

	private Path nextArchivePath(String reason) throws IOException
	{
		Files.createDirectories(archiveDirectory);

		String timestamp = ARCHIVE_TIMESTAMP.format(Instant.now());
		Path archivePath = archiveDirectory.resolve(ARCHIVE_PREFIX + timestamp + "-" + reason + ".log.gz");
		int collision = 1;
		while (Files.exists(archivePath))
		{
			archivePath = archiveDirectory.resolve(ARCHIVE_PREFIX + timestamp + "-" + reason + "-" + collision + ".log.gz");
			collision++;
		}

		return archivePath;
	}

	private static String formatLine(String eventType, Map<String, ?> fields)
	{
		StringBuilder line = new StringBuilder(256);
		appendField(line, "ts", Instant.now().toString());
		appendField(line, "event", eventType);
		for (Map.Entry<String, ?> entry : fields.entrySet())
		{
			appendField(line, entry.getKey(), entry.getValue());
		}
		return line.toString();
	}

	private static void appendField(StringBuilder line, String key, Object value)
	{
		if (line.length() > 0)
		{
			line.append('\t');
		}

		line.append(key).append('=');
		appendValue(line, value);
	}

	private static void appendValue(StringBuilder line, Object value)
	{
		if (value == null)
		{
			line.append("null");
			return;
		}

		if (value instanceof Number || value instanceof Boolean)
		{
			line.append(value);
			return;
		}

		line.append('"')
			.append(escapeValue(value.toString()))
			.append('"');
	}

	private static String escapeValue(String value)
	{
		return value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\r", "\\r")
			.replace("\n", "\\n")
			.replace("\t", "\\t");
	}
}
