package com.amaguri.chunklink;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 全員が「3チャンク分の面積の正方形」に入っていないと全員死ぬ。
 *
 * 正方形の一辺 = √(16×16×3) = 16√3 ≈ 27.71 ブロック。
 * 中心 = 一番遠いメンバーまでの距離が最小になる点（全員を囲む箱の真ん中）。
 * よって「全員の X の幅と Z の幅がどちらも一辺以内」なら安全。高さ(Y)は見ない。
 */
public class ChunkLink implements ModInitializer {
	public static final double SIDE = Math.sqrt(16.0 * 16.0 * 3.0); // 27.7128...
	public static final double HALF = SIDE / 2.0;                   // 13.8564...

	/** 死亡・参加の直後に判定を止める時間（復活して集まる猶予） */
	static final int GRACE_TICKS = 200;          // 10秒
	/** 別ディメンションにいるとき、全員がそろうまでの猶予 */
	static final int DIMENSION_LIMIT_TICKS = 300; // 15秒
	/** この割合を超えたら警告色 */
	static final double WARN_RATIO = 0.8;

	static boolean enabled = true;
	static int graceTicks = GRACE_TICKS;
	static int dimensionTicks = 0;
	static int lastMemberCount = 0;

	@Override
	public void onInitialize() {
		ServerTickEvents.END_SERVER_TICK.register(ChunkLink::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> registerCommand(dispatcher));
	}

	/** 判定の対象: サバイバルかアドベンチャーで、生きているプレイヤー */
	static boolean isMemberMode(ServerPlayer p) {
		GameType mode = p.gameMode();
		return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
	}

	static void tick(MinecraftServer server) {
		if (!enabled) return;

		List<ServerPlayer> members = new ArrayList<>();
		boolean someoneDead = false;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!isMemberMode(p)) continue;
			if (!p.isAlive()) { someoneDead = true; continue; }
			members.add(p);
		}

		// 誰かが死んでいる間・人が増えた直後は猶予をリセット
		if (someoneDead || members.size() > lastMemberCount) {
			graceTicks = GRACE_TICKS;
			dimensionTicks = 0;
		}
		lastMemberCount = members.size();

		if (members.size() < 2) {
			dimensionTicks = 0;
			return;
		}

		boolean showHud = server.getTickCount() % 10 == 0;

		// ディメンションがそろっているか
		ResourceKey<Level> dim = members.get(0).level().dimension();
		boolean sameDimension = true;
		for (ServerPlayer p : members) {
			if (p.level().dimension() != dim) { sameDimension = false; break; }
		}

		if (graceTicks > 0) {
			graceTicks--;
			if (showHud) {
				int sec = (graceTicks + 19) / 20;
				Component msg = Component.literal("集合猶予 あと " + sec + " 秒").withStyle(ChatFormatting.AQUA);
				for (ServerPlayer p : members) p.sendSystemMessage(msg, true);
			}
			return;
		}

		if (!sameDimension) {
			dimensionTicks++;
			int left = DIMENSION_LIMIT_TICKS - dimensionTicks;
			if (left <= 0) {
				killAll(server, members, Component.literal("全員が同じディメンションにそろわなかった！"));
				return;
			}
			if (showHud) {
				int sec = (left + 19) / 20;
				Component msg = Component.literal("別ディメンションにメンバーがいる！ あと " + sec + " 秒でそろえ")
						.withStyle(ChatFormatting.RED);
				for (ServerPlayer p : members) p.sendSystemMessage(msg, true);
			}
			return;
		}
		dimensionTicks = 0;

		// 全員を囲む箱
		double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (ServerPlayer p : members) {
			minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
		}
		double spanX = maxX - minX;
		double spanZ = maxZ - minZ;

		if (spanX > SIDE || spanZ > SIDE) {
			double cx = (minX + maxX) / 2.0;
			double cz = (minZ + maxZ) / 2.0;
			ServerPlayer farthest = members.get(0);
			double far = -1;
			for (ServerPlayer p : members) {
				double d = Math.max(Math.abs(p.getX() - cx), Math.abs(p.getZ() - cz));
				if (d > far) { far = d; farthest = p; }
			}
			killAll(server, members, Component.literal(farthest.getName().getString()
					+ " が3チャンクの範囲から出た！"));
			return;
		}

		if (showHud) {
			double ratio = Math.max(spanX, spanZ) / SIDE;
			ChatFormatting color = ratio >= WARN_RATIO ? ChatFormatting.RED
					: ratio >= 0.5 ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
			Component msg = Component.literal(String.format("幅 X %.1f / Z %.1f （限界 %.1f）", spanX, spanZ, SIDE))
					.withStyle(color);
			for (ServerPlayer p : members) p.sendSystemMessage(msg, true);
		}
	}

	static void killAll(MinecraftServer server, List<ServerPlayer> members, Component reason) {
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("[Chunk Link] ").withStyle(ChatFormatting.DARK_RED)
						.append(reason.copy().withStyle(ChatFormatting.RED))
						.append(Component.literal(" 全員道連れだ…").withStyle(ChatFormatting.GRAY)),
				false);
		for (ServerPlayer p : members) {
			p.kill(p.level());
		}
		graceTicks = GRACE_TICKS;
		dimensionTicks = 0;
	}

	static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("chunklink")
				.then(Commands.literal("on").executes(c -> {
					enabled = true;
					graceTicks = GRACE_TICKS;
					c.getSource().sendSuccess(() -> Component.literal("Chunk Link: ON（10秒後に判定開始）"), true);
					return 1;
				}))
				.then(Commands.literal("off").executes(c -> {
					enabled = false;
					c.getSource().sendSuccess(() -> Component.literal("Chunk Link: OFF"), true);
					return 1;
				}))
				.then(Commands.literal("status").executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal(String.format(
							"Chunk Link: %s / 正方形の一辺 %.2f ブロック（中心から ±%.2f）",
							enabled ? "ON" : "OFF", SIDE, HALF)), false);
					return 1;
				})));
	}
}
