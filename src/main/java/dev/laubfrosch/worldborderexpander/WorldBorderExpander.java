package dev.laubfrosch.worldborderexpander;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

	private static MinecraftServer server;
	private static Timer dailyTimer;
	private static double currentBorderSize = 0;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("worldborderexpander.json").toFile();

	private static boolean autoExpansionEnabled = false; // Standard: deaktiviert
	private static int expansionAmount = 100; // Standard: 100 Blöcke
	private static int targetHour = 0; // Standard: 00:00 Uhr
	private static int targetMinute = 0;

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

	@Override
	public void onInitialize() {
		loadConfig();

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				CommandManager.literal("expandborder")
					.requires(source -> {
						ServerPlayerEntity player = source.getPlayer();
						if (player != null) {
							return source.getServer().getPlayerManager().isOperator(player.getPlayerConfigEntry());
						}
						return true;
					})

					// Subcommand: /expandborder configure
					.then(CommandManager.literal("configure")
						// Subcommand: /expandborder configure expansion <amount>
						.then(CommandManager.literal("expansion")
							.then(CommandManager.argument("amount", IntegerArgumentType.integer())
								.executes(context -> {
									int amount = IntegerArgumentType.getInteger(context, "amount");
									expansionAmount = amount;
									saveConfig();

									context.getSource().sendFeedback(
										() -> Text.literal("§c[Server] §aErweiterungsgröße auf " + amount + " Blöcke gesetzt."),
										true
									);

									ServerPlayerEntity player = context.getSource().getPlayer();
									playClickSound(player);

									LOGGER.info("Erweiterungsgröße geändert auf: {}", amount);
									return 1;
								})
							)
						)
						// Subcommand: /expandborder configure time <hour> [minutes]
						.then(CommandManager.literal("time")
							.then(CommandManager.argument("hour", IntegerArgumentType.integer(0, 23))
								// mit Minuten
								.then(CommandManager.argument("minute", IntegerArgumentType.integer(0, 59))
									.executes(context -> {
										int hour = IntegerArgumentType.getInteger(context, "hour");
										int minute = IntegerArgumentType.getInteger(context, "minute");

										ServerPlayerEntity player = context.getSource().getPlayer();
										playClickSound(player);
										return setExpansionTime(context, hour, minute);
									})
								)
								// ohne Minuten
								.executes(context -> {
									int hour = IntegerArgumentType.getInteger(context, "hour");

									ServerPlayerEntity player = context.getSource().getPlayer();
									playClickSound(player);
									return setExpansionTime(context, hour, 0);
								})
							)
						)
					)

					// Subcommand: /expandborder now
					.then(CommandManager.literal("now")
						.executes(context -> {
							expandWorldBorder();
							context.getSource().sendFeedback(
								() -> Text.literal("§c[Server] §aWeltbarriere-Erweiterung manuell ausgelöst!"),
								true
							);
							return 1;
						})
					)
					.then(CommandManager.literal("toggle")
							.executes(context -> {
								autoExpansionEnabled = !autoExpansionEnabled;
								saveConfig();

								if (autoExpansionEnabled) {
									// Timer neu starten
									scheduleDailyClock();
									context.getSource().sendFeedback(
											() -> Text.literal("§c[Server] §aAutomatische Weltbarriere-Erweiterung aktiviert."),
											true
									);
								} else {
									// Timer stoppen
									if (dailyTimer != null) {
										dailyTimer.cancel();
										dailyTimer = null;
									}
									context.getSource().sendFeedback(
											() -> Text.literal("§c[Server] §cAutomatische Weltbarriere-Erweiterung deaktiviert."),
											true
									);
								}

								ServerPlayerEntity player = context.getSource().getPlayer();
								playClickSound(player);

								LOGGER.info("Automatische Erweiterung: {}", autoExpansionEnabled ? "aktiviert" : "deaktiviert");
								return 1;
							})
					)
			);

			// Subcommand: /expandborder info
			dispatcher.register(
				CommandManager.literal("expandborder")
					.then(CommandManager.literal("info")
						.executes(context -> {
							String time = autoExpansionEnabled ?
									"§7> Uhrzeit: §a" + String.format("%02d:%02d", targetHour, targetMinute) + " Uhr\n" :
									"§7> Uhrzeit: §8§m" + String.format("%02d:%02d", targetHour, targetMinute) + " Uhr§r §c(deaktiviert)\n";

							context.getSource().sendFeedback(
								() -> Text.literal("\n§c[Server] §aAktuelle Weltbarriere-Konfiguration:\n\n" +
										time +
										"§7> Erweiterung: §a+" + expansionAmount + " Blöcke\n" +
										"§7> Aktuelle Größe: §a" + (int)getCurrentBorderSize() + " Blöcke\n "),
								false
							);
							ServerPlayerEntity player = context.getSource().getPlayer();
							playClickSound(player);
							return 1;
						})
					)
			);
		});

		LOGGER.info("WorldBorderExpander erfolgreich initialisiert.");
	}

	public static void playClickSound(ServerPlayerEntity player) {
		if (player != null) {
			player.networkHandler.sendPacket(
				new PlaySoundS2CPacket(
					SoundEvents.UI_BUTTON_CLICK,
					SoundCategory.PLAYERS,
					player.getX(),
					player.getY(),
					player.getZ(),
					1.0f,
					1.0f,
					42
				)
			);
		}
	}

	private int setExpansionTime(CommandContext<ServerCommandSource> context, int hour, int minute) {
		targetHour = hour;
		targetMinute = minute;
		saveConfig();

		// Berechne nächste Erweiterungszeit
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime next = now.withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0);

		if (now.isAfter(next)) {
			next = next.plusDays(1);
		}

		long minutesUntil = ChronoUnit.MINUTES.between(now, next);
		long hoursUntil = minutesUntil / 60;
		long remainingMinutes = minutesUntil % 60;

		// Timer neu planen
		if (dailyTimer != null) {
			dailyTimer.cancel();
		}
		scheduleDailyClock();

		String nextExpansion = autoExpansionEnabled ?
				"§7Nächste Erweiterung in §a" + hoursUntil + "h " + remainingMinutes + "min§7." :
				"§7Die automatische Erweiterung ist derzeit deaktiviert.";

		// Feedback senden
		context.getSource().sendFeedback(
			() -> Text.literal("§c[Server] §aErweiterungsuhrzeit auf " +
					String.format("%02d:%02d", hour, minute) + " Uhr gesetzt.\n" +
					nextExpansion),
			true
		);
		LOGGER.info("Erweiterungsuhrzeit geändert auf: {} (in {} Minuten)",
				String.format("%02d:%02d", hour, minute),
				minutesUntil);
		return 1;
	}

	private void onServerStarted(MinecraftServer minecraftServer) {
		server = minecraftServer;

		ServerWorld overworld = server.getOverworld();
		if (overworld != null) {
			WorldBorder border = overworld.getWorldBorder();
			currentBorderSize = border.getSize();
		}

		scheduleDailyClock();
		LOGGER.info("WorldBorderExpander erfolgreich gestartet.");
	}

	private void onServerStopping(MinecraftServer minecraftServer) {
		if (dailyTimer != null) {
			dailyTimer.cancel();
			dailyTimer = null;
		}
		LOGGER.info("WorldBorderExpander gestoppt.");
	}

	private void scheduleDailyClock() {
		if (!autoExpansionEnabled) {
			LOGGER.info("Automatische Erweiterung ist deaktiviert.");
			return;
		}

		LocalDateTime now = LocalDateTime.now();
		LocalDateTime next = now.withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0);

		if (now.isAfter(next)) {
			next = next.plusDays(1);
		}

		long delayMillis = ChronoUnit.MILLIS.between(now, next);
		LOGGER.info("Nächste Weltbarriere-Erweiterung um {} Uhr in {} Minuten",
				String.format("%02d:%02d", targetHour, targetMinute), delayMillis / 1000 / 60);

		dailyTimer = new Timer("WorldBorderExpander-Timer", true);

		dailyTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				expandWorldBorder();
				scheduleDailyClock();
			}
		}, delayMillis);
	}

	private void expandWorldBorder() {
		if (server == null) return;

		server.execute(() -> {
			ServerWorld overworld = server.getOverworld();
			if (overworld != null) {
				WorldBorder border = overworld.getWorldBorder();
				double oldSize = border.getSize();
				double newSize = oldSize + expansionAmount;

				border.interpolateSize(oldSize, newSize, 0, System.currentTimeMillis());
				currentBorderSize = newSize;

				Text message = Text.literal("§c[Server] §aDie Weltbarriere wurde soeben um " + expansionAmount + " Blöcke erweitert.");

				server.getPlayerManager().broadcast(message, false);
				LOGGER.info("Weltbarriere erweitert: {} -> {} (+{})", oldSize, newSize, expansionAmount);

				for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
					player.getEntityWorld().playSound(
						null,
						player.getX(),
						player.getY(),
						player.getZ(),
						SoundEvents.ENTITY_PLAYER_LEVELUP,
						SoundCategory.MASTER,
						1.0f,
						1.25f
					);
				}
			}
		});
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

	private void loadConfig() {
		if (!CONFIG_FILE.exists()) {
			saveConfig(); // Erstelle Standard-Datei, falls nicht vorhanden
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

	// Hilfsklasse für die JSON-Struktur
	private static class ConfigData {
		boolean autoExpansionEnabled;
		int expansionAmount;
		int targetHour;
		int targetMinute;
	}
}