package comedymonkey.dailiescompanion;

import java.util.Collection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <pre>
 * /moment send &lt;players&gt; &lt;type&gt; &lt;label...&gt;
 * /moment data &lt;players&gt; &lt;type&gt; &lt;{nbt}&gt; &lt;label...&gt;
 * </pre>
 *
 * For ops, command blocks and functions (permission level 2). type is one word; the label is the rest of the line.
 */
final class MomentCommand {
	private MomentCommand() {}

	static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("moment").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("send").then(Commands.argument("players", EntityArgument.players()).then(Commands.argument("type", StringArgumentType.word())
				.then(Commands.argument("label", StringArgumentType.greedyString()).executes(c -> run(c, null))))))
			.then(Commands.literal("data").then(Commands.argument("players", EntityArgument.players()).then(Commands.argument("type", StringArgumentType.word())
				.then(Commands.argument("data", CompoundTagArgument.compoundTag())
					.then(Commands.argument("label", StringArgumentType.greedyString()).executes(c -> run(c, CompoundTagArgument.getCompoundTag(c, "data")))))))));
	}

	private static int run(CommandContext<CommandSourceStack> c, CompoundTag tag) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
		String type = StringArgumentType.getString(c, "type"), label = StringArgumentType.getString(c, "label");
		JsonObject data = null;
		if (tag != null) {
			JsonElement j = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
			if (j.isJsonObject())
				data = j.getAsJsonObject();
		}
		if (!DailiesCompanion.fits(data)) {
			c.getSource().sendFailure(Component.literal("The data of moment '" + type + "' is too long: a moment carries at most "
				+ MomentPayload.MAX_JSON + " characters of JSON. Nothing was sent"));
			return 0;
		}
		int sent = 0;
		for (ServerPlayer p : players)
			if (DailiesCompanion.send(p, type, label, data))
				sent++;
		int n = sent, deaf = players.size() - sent;
		c.getSource().sendSuccess(() -> Component.literal("Moment '" + type + "' sent to " + n + " player" + (n == 1 ? "" : "s")
			+ (deaf > 0 ? " (" + deaf + " not recording moments)" : "")), false);
		return sent;
	}
}
