package dev.laubfrosch.worldborderexpander;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Timer;
import java.util.TimerTask;

public class WorldBorderExpander implements ModInitializer {
	public static final String MOD_ID = "worldborderexpander";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Minecraft WorldBorder Limits
	private static final double MIN_BORDER_SIZE = 1.0;
	private static final double MAX_BORDER_SIZE = 59999968.0;

	// Server & Timer
	private static MinecraftServer server;
	private static Timer dailyTimer;
	private static double currentBorderSize = 0;

	// Config
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("worldborderexpander.json").toFile();

	private static boolean autoExpansionEnabled = false; // Standard: deaktiviert
	private static int expansionAmount = 100; // Standard: 100 Blöcke
	private static int targetHour = 0; // Standard: 00:00 Uhr
	private static int targetMinute = 0;

	/// Public Getter for Motd

	public static boolean isAutoExpansionEnabled() {
		return autoExpansionEnabled;
	}

	public static int getTargetHour() {
		return targetHour;
	}

	public static int getTargetMinute() {
		return targetMinute;
	}

	public static int getExpansionAmount() {
		return expansionAmount;
	}

	public static double getCurrentBorderSize() {
		if (server != null) {
			ServerWorld overworld = server.getOverworld();
			if (overworld != null) {
				return overworld.getWorldBorder().getSize();
			}
		}
		return currentBorderSize;
	}

	/// Init

	@Override
	public void onInitialize() {
		loadConfig();
		registerServerEvents();
		registerCommands();
		LOGGER.info("WorldBorderExpander erfolgreich initialisiert.");
	}

	private void registerServerEvents() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
	}

	private void onServerStarted(MinecraftServer minecraftServer) {
		server = minecraftServer;
		updateCurrentBorderSize();
		scheduleDailyClock();
		LOGGER.info("WorldBorderExpander erfolgreich gestartet.");
	}

	private void onServerStopping(MinecraftServer minecraftServer) {
		stopTimer();
		LOGGER.info("WorldBorderExpander gestoppt.");
	}

	/// Commands

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// Admin Commands
			dispatcher.register(
					CommandManager.literal("expandborder")
							.requires(this::hasOperatorPermission)
							.then(buildConfigureCommand())
							.then(buildNowCommand())
							.then(buildToggleCommand())
			);

			// Public Info Command
			dispatcher.register(
					CommandManager.literal("expandborder")
							.then(buildInfoCommand())
			);
		});
	}

	private boolean hasOperatorPermission(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player != null) {
			return source.getServer().getPlayerManager().isOperator(player.getPlayerConfigEntry());
		}
		return true;
	}

	/// Command-Builders

	private LiteralArgumentBuilder<ServerCommandSource> buildConfigureCommand() {
		return CommandManager.literal("configure")
				.then(CommandManager.literal("expansion")
						.then(CommandManager.argument("amount", IntegerArgumentType.integer())
								.executes(this::executeSetExpansion)
						)
				)
				.then(CommandManager.literal("time")
						.then(CommandManager.argument("hour", IntegerArgumentType.integer(0, 23))
								.then(CommandManager.argument("minute", IntegerArgumentType.integer(0, 59))
										.executes(ctx -> executeSetTime(ctx,
												IntegerArgumentType.getInteger(ctx, "hour"),
												IntegerArgumentType.getInteger(ctx, "minute")))
								)
								.executes(ctx -> executeSetTime(ctx,
										IntegerArgumentType.getInteger(ctx, "hour"), 0))
						)
				);
	}

	private LiteralArgumentBuilder<ServerCommandSource> buildNowCommand() {
		return CommandManager.literal("now")
				.executes(context -> {
					if (tryExpandWorldBorder()) {
						sendFeedback(context.getSource(), "§aWeltbarriere-Erweiterung manuell ausgelöst!", true);
					}
					return 1;
				});
	}

	private LiteralArgumentBuilder<ServerCommandSource> buildToggleCommand() {
		return CommandManager.literal("toggle")
				.executes(this::executeToggle);
	}

	private LiteralArgumentBuilder<ServerCommandSource> buildInfoCommand() {
		return CommandManager.literal("info")
				.executes(this::executeInfo);
	}

	/// Command-Executors

	private int executeSetExpansion(CommandContext<ServerCommandSource> context) {
		int amount = IntegerArgumentType.getInteger(context, "amount");
		expansionAmount = amount;
		saveConfig();

		sendFeedback(context.getSource(),
				"§aErweiterungsgröße auf " + amount + " Blöcke gesetzt.", true);
		playClickSound(context.getSource().getPlayer());

		LOGGER.info("Erweiterungsgröße geändert auf: {}", amount);
		return 1;
	}

	private int executeSetTime(CommandContext<ServerCommandSource> context, int hour, int minute) {
		targetHour = hour;
		targetMinute = minute;
		saveConfig();

		LocalDateTime now = LocalDateTime.now();
		LocalDateTime next = calculateNextExpansion(now);
		long minutesUntil = ChronoUnit.MINUTES.between(now, next);
		long hoursUntil = minutesUntil / 60;
		long remainingMinutes = minutesUntil % 60;

		stopTimer();
		scheduleDailyClock();

		String nextExpansion = autoExpansionEnabled ?
				"§7Nächste Erweiterung in §a" + hoursUntil + "h " + remainingMinutes + "min§7." :
				"§7Die automatische Erweiterung ist derzeit deaktiviert.";

		sendFeedback(context.getSource(),
				"§aErweiterungsuhrzeit auf " + String.format("%02d:%02d", hour, minute) + " Uhr gesetzt.\n" + nextExpansion,
				true);
		playClickSound(context.getSource().getPlayer());

		LOGGER.info("Erweiterungsuhrzeit geändert auf: {} Uhr (in {} Minuten)",
				String.format("%02d:%02d", hour, minute), minutesUntil);
		return 1;
	}

	private int executeToggle(CommandContext<ServerCommandSource> context) {
		autoExpansionEnabled = !autoExpansionEnabled;
		saveConfig();

		if (autoExpansionEnabled) {
			scheduleDailyClock();
			sendFeedback(context.getSource(),
					"§aAutomatische Weltbarriere-Erweiterung aktiviert.", true);
		} else {
			stopTimer();
			sendFeedback(context.getSource(),
					"§cAutomatische Weltbarriere-Erweiterung deaktiviert.", true);
		}

		playClickSound(context.getSource().getPlayer());
		LOGGER.info("Automatische Erweiterung: {}", autoExpansionEnabled ? "aktiviert" : "deaktiviert");
		return 1;
	}

	private int executeInfo(CommandContext<ServerCommandSource> context) {
		String time = autoExpansionEnabled ?
				"§7> Uhrzeit: §a" + String.format("%02d:%02d", targetHour, targetMinute) + " Uhr\n" :
				"§7> Uhrzeit: §8§m" + String.format("%02d:%02d", targetHour, targetMinute) + " Uhr§r §c(deaktiviert)\n";

		sendFeedback(context.getSource(),
				"§aAktuelle Weltbarriere-Konfiguration:\n\n" +
						time +
						"§7> Erweiterung: §a" + (expansionAmount >= 0 ? "+" : "") + expansionAmount + " Blöcke\n" +
						"§7> Aktuelle Größe: §a" + (int)getCurrentBorderSize() + " Blöcke\n ",
				false);
		playClickSound(context.getSource().getPlayer());
		return 1;
	}

	/// Worldborder

	private boolean tryExpandWorldBorder() {
		if (server == null) return false;

		double currentSize = getCurrentBorderSize();
		double newSize = currentSize + expansionAmount;

		// Validierung
		if (newSize > MAX_BORDER_SIZE) {
			setBorderSize(MAX_BORDER_SIZE);
			broadcastError("§cMaximale Weltbarriere-Größe erreicht! Automatische Erweiterung wurde deaktiviert.");
			disableAutoExpansion();
			LOGGER.warn("Weltbarriere-Maximum erreicht ({}). Automatische Erweiterung deaktiviert.", MAX_BORDER_SIZE);
			return false;
		}

		if (newSize < MIN_BORDER_SIZE) {
			setBorderSize(MIN_BORDER_SIZE);
			broadcastError("§cMinimale Weltbarriere-Größe erreicht! Automatische Erweiterung wurde deaktiviert.");
			disableAutoExpansion();
			LOGGER.warn("Weltbarriere-Minimum erreicht ({}). Automatische Erweiterung deaktiviert.", MIN_BORDER_SIZE);
			return false;
		}

		// Expansion durchführen
		setBorderSize(newSize);
		broadcastSuccess("§aDie Weltbarriere wurde soeben um " + expansionAmount + " Blöcke erweitert.");
		playExpansionSound();
		LOGGER.info("Weltbarriere erweitert: {} -> {} (+{})", currentSize, newSize, expansionAmount);

		return true;
	}

	/// Timer

	private void scheduleDailyClock() {
		if (!autoExpansionEnabled) {
			LOGGER.info("Automatische Erweiterung ist deaktiviert.");
			return;
		}

		LocalDateTime now = LocalDateTime.now();
		LocalDateTime next = calculateNextExpansion(now);
		long delayMillis = ChronoUnit.MILLIS.between(now, next);

		LOGGER.info("Nächste Weltbarriere-Erweiterung um {} Uhr in {} Minuten",
				String.format("%02d:%02d", targetHour, targetMinute), delayMillis / 1000 / 60);

		dailyTimer = new Timer("WorldBorderExpander-Timer", true);
		dailyTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (tryExpandWorldBorder()) {
					scheduleDailyClock(); // Reschedule nur wenn erfolgreich
				}
			}
		}, delayMillis);
	}

	private LocalDateTime calculateNextExpansion(LocalDateTime now) {
		LocalDateTime next = now.withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0);
		if (now.isAfter(next)) {
			next = next.plusDays(1);
		}
		return next;
	}

	private void stopTimer() {
		if (dailyTimer != null) {
			dailyTimer.cancel();
			dailyTimer = null;
		}
	}

	/// Utilities

	private void updateCurrentBorderSize() {
		ServerWorld overworld = server.getOverworld();
		if (overworld != null) {
			WorldBorder border = overworld.getWorldBorder();
			currentBorderSize = border.getSize();
		}
	}

	private void setBorderSize(double size) {
		if (server == null) return;

		double currentSize = getCurrentBorderSize();
		server.execute(() -> {
			ServerWorld overworld = server.getOverworld();
			if (overworld != null) {
				WorldBorder border = overworld.getWorldBorder();
				border.interpolateSize(currentSize, size, 0, System.currentTimeMillis());
				currentBorderSize = size;
				LOGGER.info("Weltbarriere gesetzt: {} -> {}", currentSize, size);
			}
		});
	}

	private void disableAutoExpansion() {
		autoExpansionEnabled = false;
		saveConfig();
		stopTimer();
	}

	private void sendFeedback(ServerCommandSource source, String message, boolean broadcast) {
		source.sendFeedback(() -> Text.literal("§c[Server] " + message), broadcast);
	}

	private void broadcastSuccess(String message) {
		if (server != null) {
			server.getPlayerManager().broadcast(Text.literal("§c[Server] " + message), false);
		}
	}

	private void broadcastError(String message) {
		if (server != null) {
			server.getPlayerManager().broadcast(Text.literal("§c[Server] " + message), false);
		}
	}

	private void playClickSound(ServerPlayerEntity player) {
		if (player != null) {
			player.networkHandler.sendPacket(
					new PlaySoundS2CPacket(
							SoundEvents.UI_BUTTON_CLICK,
							SoundCategory.PLAYERS,
							player.getX(), player.getY(), player.getZ(),
							1.0f, 1.0f, 42
					)
			);
		}
	}

	private void playExpansionSound() {
		if (server == null) return;
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			player.getEntityWorld().playSound(
					null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.ENTITY_PLAYER_LEVELUP,
					SoundCategory.MASTER,
					1.0f, 1.25f
			);
		}
	}

	/// Config-Management

	private void loadConfig() {
		if (!CONFIG_FILE.exists()) {
			saveConfig();
			return;
		}
		try (FileReader reader = new FileReader(CONFIG_FILE)) {
			ConfigData data = GSON.fromJson(reader, ConfigData.class);
			if (data != null) {
				autoExpansionEnabled = data.autoExpansionEnabled;
				expansionAmount = data.expansionAmount;
				targetHour = data.targetHour;
				targetMinute = data.targetMinute;
			}
		} catch (IOException e) {
			LOGGER.error("Fehler beim Laden der Config!", e);
		}
	}

	private static void saveConfig() {
		try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
			ConfigData data = new ConfigData();
			data.autoExpansionEnabled = autoExpansionEnabled;
			data.expansionAmount = expansionAmount;
			data.targetHour = targetHour;
			data.targetMinute = targetMinute;
			GSON.toJson(data, writer);
		} catch (IOException e) {
			LOGGER.error("Fehler beim Speichern der Config!", e);
		}
	}

	private static class ConfigData {
		boolean autoExpansionEnabled;
		int expansionAmount;
		int targetHour;
		int targetMinute;
	}
}