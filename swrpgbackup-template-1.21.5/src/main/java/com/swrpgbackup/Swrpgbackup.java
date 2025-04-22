package com.swrpgbackup;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

public class Swrpgbackup implements ModInitializer {
	public static final String MOD_ID = "swrpgbackup";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Backup intervals in ticks (20 ticks = 1 second)
	// Hourly backup: 1 hour = 3600s * 20 = 72,000 ticks.
	private static final long HOURLY_BACKUP_INTERVAL = 72000;
	// Daily backup: 24 hours = 24 * 72,000 = 1,728,000 ticks.
	private static final long DAILY_BACKUP_INTERVAL = 1728000;

	// Maximum backups to keep for each type.
	private static final int MAX_HOURLY_BACKUPS = 24; // e.g. last 24 hourly backups (24 hours)
	private static final int MAX_DAILY_BACKUPS = 7;   // e.g. last 7 daily backups (1 week)
	private static final int MAX_MANUAL_BACKUPS = 5;   // e.g. last 5 manual backups

	private long ticksSinceLastHourlyBackup = 0;
	private long ticksSinceLastDailyBackup = 0;
	// This flag prevents overlapping backup operations.
	private volatile boolean backupInProgress = false;

	@Override
	public void onInitialize() {
		LOGGER.info("Swrpgbackup mod initialized. Daily interval: {} ticks, Hourly interval: {} ticks", DAILY_BACKUP_INTERVAL, HOURLY_BACKUP_INTERVAL);

		// Automated backup scheduling via server ticks.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ticksSinceLastHourlyBackup++;
			ticksSinceLastDailyBackup++;

			// Obtain the world folder from the server.
			File worldFolder = server.getSavePath(WorldSavePath.ROOT).toFile();

			// Daily backups have priority.
			if (ticksSinceLastDailyBackup >= DAILY_BACKUP_INTERVAL && !backupInProgress) {
				backupInProgress = true;
				LOGGER.info("Starting daily backup...");
				startBackupAsync(server, worldFolder, true, "daily");
				ticksSinceLastDailyBackup = 0;
			} else if (ticksSinceLastHourlyBackup >= HOURLY_BACKUP_INTERVAL && !backupInProgress) {
				backupInProgress = true;
				LOGGER.info("Starting hourly backup...");
				startBackupAsync(server, worldFolder, false, "hourly");
				ticksSinceLastHourlyBackup = 0;
			}
		});

		// Manual backup command (/backup). Its backups are saved separately in a "manual" folder.
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
			dispatcher.register(CommandManager.literal("backup")
					.executes(context -> {
						MinecraftServer server = context.getSource().getServer();
						if (backupInProgress) {
							context.getSource().sendFeedback(() -> Text.literal("A backup is already in progress."), true);
							return 1;
						}
						// Inform players that a manual backup is starting.
						server.getPlayerManager().broadcast(Text.literal("Manual backup is starting... Please wait."), false);
						File worldFolder = server.getSavePath(WorldSavePath.ROOT).toFile();
						backupInProgress = true;
						startBackupAsync(server, worldFolder, true, "manual");
						context.getSource().sendFeedback(() -> Text.literal("Manual backup initiated."), true);
						return 1;
					})
			);
		});

		// Final synchronous backup on server shutdown.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Server stopping, creating final manual backup...");
			File worldFolder = server.getSavePath(WorldSavePath.ROOT).toFile();
			createBackup(worldFolder, "manual");
		});
	}

	/**
	 * Initiates the backup process on a separate thread.
	 *
	 * @param server        The MinecraftServer instance.
	 * @param worldFolder   Folder containing the world data.
	 * @param notifyPlayers If true, players are notified upon backup completion.
	 * @param backupType    A string identifying the backup type ("daily", "hourly", or "manual").
	 */
	private void startBackupAsync(MinecraftServer server, File worldFolder, boolean notifyPlayers, String backupType) {
		new Thread(() -> {
			LOGGER.info("{} backup initiated on thread {}", backupType, Thread.currentThread().getName());
			createBackup(worldFolder, backupType);
			backupInProgress = false;
			if (notifyPlayers) {
				server.execute(() ->
						server.getPlayerManager().broadcast(Text.literal(backupType + " backup has been successfully created."), false)
				);
			}
		}, backupType + "BackupThread").start();
	}

	/**
	 * Creates a backup using an incremental approach. Unchanged files from the latest backup are hard-linked.
	 * The backup is stored in a folder based on its type.
	 *
	 * @param worldFolder The world folder to back up.
	 * @param backupType  "daily", "hourly", or "manual" to determine the base folder and rotation.
	 */
	private void createBackup(File worldFolder, String backupType) {
		File worldParent = worldFolder.getParentFile();
		if (worldParent == null) {
			LOGGER.error("World folder has no parent directory. Cannot create backup.");
			return;
		}

		// Determine the base backup folder based on backup type.
		File baseBackupFolder = new File(worldParent, "backups" + File.separator + backupType);
		if (!baseBackupFolder.exists() && !baseBackupFolder.mkdirs()) {
			LOGGER.error("Failed to create backup directory at {}", baseBackupFolder.getAbsolutePath());
			return;
		}

		// Create a timestamped backup folder.
		String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		File backupDestination = new File(baseBackupFolder, "world_backup_" + timestamp);

		// For incremental backup, get the previous backup (if any) for this type.
		File previousBackupDir = getLatestBackupDir(baseBackupFolder);
		Path previousBackup = (previousBackupDir != null) ? previousBackupDir.toPath() : null;

		try {
			copyDirectory(worldFolder.toPath(), backupDestination.toPath(), previousBackup);
			LOGGER.info("Backup successfully created at {}", backupDestination.getAbsolutePath());
		} catch (IOException e) {
			LOGGER.error("Failed to create backup: {}", e.getMessage());
		}

		// Rotate (delete) older backups if they exceed the maximum allowed.
		if (backupType.equalsIgnoreCase("hourly")) {
			rotateBackups(baseBackupFolder, MAX_HOURLY_BACKUPS);
		} else if (backupType.equalsIgnoreCase("daily")) {
			rotateBackups(baseBackupFolder, MAX_DAILY_BACKUPS);
		} else if (backupType.equalsIgnoreCase("manual")) {
			rotateBackups(baseBackupFolder, MAX_MANUAL_BACKUPS);
		}
	}

	/**
	 * Recursively copies files and directories from source to target.
	 * If 'previousBackup' exists and a file is unchanged (using file size as a check),
	 * a hard link is created instead to save space.
	 * Any branch containing "backups" is skipped.
	 *
	 * @param source         The source directory.
	 * @param target         The target directory for the backup.
	 * @param previousBackup The previous backup's path (or null if none exists).
	 * @throws IOException if an I/O error occurs.
	 */
	private void copyDirectory(Path source, Path target, Path previousBackup) throws IOException {
		Files.walk(source).forEach(src -> {
			try {
				Path relative = source.relativize(src);
				if (containsBackupsFolder(relative))
					return; // Skip any branch that includes a "backups" folder.
				if (src.getFileName().toString().equalsIgnoreCase("session.lock")) {
					LOGGER.info("Skipping locked file: {}", src);
					return;
				}
				if (src.toAbsolutePath().toString().length() > 32000) {
					LOGGER.warn("Skipping file due to long path: {}", src);
					return;
				}
				Path dest = target.resolve(relative);
				if (Files.isDirectory(src)) {
					Files.createDirectories(dest);
				} else {
					if (previousBackup != null) {
						Path prevFile = previousBackup.resolve(relative);
						if (Files.exists(prevFile) && Files.size(src) == Files.size(prevFile)) {
							try {
								Files.createLink(dest, prevFile);
								return;
							} catch (UnsupportedOperationException | IOException e) {
								LOGGER.warn("Hard linking failed for {}: {}. Falling back to copy.", src, e.getMessage());
							}
						}
					}
					Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				LOGGER.error("Error copying file {}: {}", src, e.getMessage());
			}
		});
	}

	/**
	 * Checks if the given relative path includes any segment named "backups" (case-insensitive).
	 *
	 * @param relative The relative path.
	 * @return True if any segment equals "backups", false otherwise.
	 */
	private boolean containsBackupsFolder(Path relative) {
		for (Path part : relative) {
			if (part.toString().equalsIgnoreCase("backups"))
				return true;
		}
		return false;
	}

	/**
	 * Retrieves the most recent backup directory from the specified folder.
	 *
	 * @param backupsFolder The folder containing backup directories.
	 * @return The most recent backup directory, or null if none exists.
	 */
	private File getLatestBackupDir(File backupsFolder) {
		File[] backupDirs = backupsFolder.listFiles(file ->
				file.isDirectory() && file.getName().startsWith("world_backup_"));
		if (backupDirs == null || backupDirs.length == 0)
			return null;
		File latest = null;
		for (File dir : backupDirs) {
			if (latest == null || dir.getName().compareTo(latest.getName()) > 0) {
				latest = dir;
			}
		}
		return latest;
	}

	/**
	 * Rotates backup directories within the given folder so that only the latest 'maxBackups' remain.
	 *
	 * @param backupFolder The folder where backups are stored.
	 * @param maxBackups   Maximum backup folders to retain.
	 */
	private void rotateBackups(File backupFolder, int maxBackups) {
		File[] backupDirs = backupFolder.listFiles(file ->
				file.isDirectory() && file.getName().startsWith("world_backup_"));
		if (backupDirs == null || backupDirs.length <= maxBackups)
			return;
		// Sort backup directories by name (oldest first given the timestamp format).
		java.util.Arrays.sort(backupDirs, Comparator.comparing(File::getName));
		int numToDelete = backupDirs.length - maxBackups;
		for (int i = 0; i < numToDelete; i++) {
			deleteDirectory(backupDirs[i]);
			LOGGER.info("Rotated out backup: {}", backupDirs[i].getAbsolutePath());
		}
	}

	/**
	 * Recursively deletes a directory and its contents.
	 *
	 * @param directory The directory to delete.
	 */
	private void deleteDirectory(File directory) {
		try {
			Files.walk(directory.toPath())
					.sorted(Comparator.reverseOrder())
					.forEach(path -> {
						try {
							Files.delete(path);
						} catch (IOException e) {
							LOGGER.error("Failed to delete {}: {}", path, e.getMessage());
						}
					});
		} catch (IOException e) {
			LOGGER.error("Failed to delete directory {}: {}", directory.getAbsolutePath(), e.getMessage());
		}
	}
}
