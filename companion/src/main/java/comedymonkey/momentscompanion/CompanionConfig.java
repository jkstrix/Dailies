package comedymonkey.momentscompanion;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/moments_companion-common.toml: which built-in events the server raises, and their thresholds. */
final class CompanionConfig {
	private CompanionConfig() {}

	static final ModConfigSpec SPEC;
	private static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED;
	private static final ModConfigSpec.DoubleValue CLOSE_CALL_HEALTH, BIG_FALL_DISTANCE, BOSS_HEALTH, NEARBY_RADIUS;
	private static final ModConfigSpec.IntValue EXPLOSION_MIN_BLOCKS;

	static {
		ModConfigSpec.Builder b = new ModConfigSpec.Builder();
		DISABLED = b.comment("Built-in events the server should not raise. Names: " + String.join(", ", Detectors.EVENTS),
			"Players can also ignore events on their own side; this turns them off for everyone.")
			.defineListAllowEmpty("disabledEvents", List.of(), () -> "", o -> o instanceof String);
		CLOSE_CALL_HEALTH = b.comment("close_call: a player survives a hit with this much health or less (2 = one heart)")
			.defineInRange("closeCallHealth", 4.0, 0.5, 20.0);
		BIG_FALL_DISTANCE = b.comment("big_fall: a player survives a fall of at least this many blocks")
			.defineInRange("bigFallDistance", 15.0, 4.0, 1000.0);
		BOSS_HEALTH = b.comment("boss_fight: a mob counts as a boss with this much maximum health, or when it is in the c:bosses tag")
			.defineInRange("bossHealth", 150.0, 20.0, 1_000_000.0);
		EXPLOSION_MIN_BLOCKS = b.comment("explosion: only explosions that destroy at least this many blocks")
			.defineInRange("explosionMinBlocks", 10, 1, 10_000);
		NEARBY_RADIUS = b.comment("How close, in blocks, other players have to be to hear about something that happened to someone else")
			.defineInRange("nearbyRadius", 48.0, 4.0, 512.0);
		SPEC = b.build();
	}

	static boolean enabled(String event) {
		return !SPEC.isLoaded() || !DISABLED.get().contains(event);
	}

	static double closeCallHealth() {
		return SPEC.isLoaded() ? CLOSE_CALL_HEALTH.get() : 4.0;
	}

	static double bigFallDistance() {
		return SPEC.isLoaded() ? BIG_FALL_DISTANCE.get() : 15.0;
	}

	static double bossHealth() {
		return SPEC.isLoaded() ? BOSS_HEALTH.get() : 150.0;
	}

	static int explosionMinBlocks() {
		return SPEC.isLoaded() ? EXPLOSION_MIN_BLOCKS.get() : 10;
	}

	static double nearbyRadius() {
		return SPEC.isLoaded() ? NEARBY_RADIUS.get() : 48.0;
	}
}
