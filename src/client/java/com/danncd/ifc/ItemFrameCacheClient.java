package com.danncd.ifc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ItemFrameCacheClient implements ClientModInitializer {

	private static final Map<BlockPos, ItemFrame> TRACKED_FRAMES = new ConcurrentHashMap<>();

	private String getCurrentWorldKey(Minecraft mc) {
		if (mc.level == null) return "unknown";
		String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
		String dimension = mc.level.dimension().location().toString();
		return server + "_" + dimension;
	}

	@Override
	public void onInitializeClient() {
		System.err.println("[Item Frame Cache] Mod successfully loaded :)");

		ItemFrameCache.loadFromDisk();

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			ItemFrameCache.saveToDisk();
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ItemFrameCache.saveToDisk();
			ItemFrameCache.LOADED_REAL_FRAMES.clear();
			TRACKED_FRAMES.clear();
			ItemFrameCache.LIVE_CACHE.clear();
		});

		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return;

			String worldKey = getCurrentWorldKey(mc);
			var diskWorld = ItemFrameCache.DISK_CACHE.get(worldKey);

			if (diskWorld != null) {
				var chunkMap = diskWorld.get(chunk.getPos());
				if (chunkMap != null) {
					for (BlockPos pos : chunkMap.keySet()) {
						ItemFrameCache.getOrBuildFrame(worldKey, pos, mc);
					}
				}
			}
		});

		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity == Minecraft.getInstance().player) {
				ItemFrameCache.LOADED_REAL_FRAMES.clear();
				TRACKED_FRAMES.clear();
			}
		});

		ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ItemFrame frame) {
				ItemFrameCache.LOADED_REAL_FRAMES.put(frame.blockPosition(), frame);
				TRACKED_FRAMES.put(frame.blockPosition(), frame);

				if (!frame.getItem().isEmpty()) {
					String worldKey = getCurrentWorldKey(Minecraft.getInstance());
					ItemFrameCache.addFrame(worldKey, frame.blockPosition(), frame);
				}
			}
		});

		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof ItemFrame frame) {
				ItemFrameCache.LOADED_REAL_FRAMES.remove(frame.blockPosition());
				TRACKED_FRAMES.remove(frame.blockPosition());

				String worldKey = getCurrentWorldKey(Minecraft.getInstance());

				if (frame.getItem().isEmpty()) {
					ItemFrameCache.removeFrame(worldKey, frame.blockPosition());
				} else {
					ItemFrameCache.addFrame(worldKey, frame.blockPosition(), frame);
				}
			}
		});

		WorldRenderEvents.BEFORE_ENTITIES.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null) return;
			String worldKey = getCurrentWorldKey(mc);
			var diskWorld = ItemFrameCache.DISK_CACHE.get(worldKey);

			for (ItemFrame frame : TRACKED_FRAMES.values()) {
				boolean hasCache = false;
				if (diskWorld != null) {
					ChunkPos cPos = new ChunkPos(frame.blockPosition());
					var chunkMap = diskWorld.get(cPos);
					if (chunkMap != null && chunkMap.containsKey(frame.blockPosition())) {
						hasCache = true;
					}
				}

				if (hasCache && frame.tickCount < 40) {
					frame.setInvisible(true);
				} else {
					frame.setInvisible(false);
					if (frame.getItem().isEmpty() && frame.tickCount >= 40 && hasCache) {
						ItemFrameCache.removeFrame(worldKey, frame.blockPosition());
					}
				}
			}
		});

		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null) return;

			String worldKey = getCurrentWorldKey(mc);
			var diskWorld = ItemFrameCache.DISK_CACHE.get(worldKey);

			if (diskWorld == null || diskWorld.isEmpty()) return;

			PoseStack poseStack = context.matrixStack();
			Vec3 camera = context.camera().getPosition();
			MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
			var frustum = context.frustum();

			ChunkPos playerChunk = mc.player.chunkPosition();

			double entityScale = mc.options.entityDistanceScaling().get();
			double maxRenderDist = 64.0 * entityScale;
			double maxRenderDistSqr = maxRenderDist * maxRenderDist;

			int chunkRadius = (int) Math.ceil(maxRenderDist / 16.0);

			for (int cx = playerChunk.x - chunkRadius; cx <= playerChunk.x + chunkRadius; cx++) {
				for (int cz = playerChunk.z - chunkRadius; cz <= playerChunk.z + chunkRadius; cz++) {

					ChunkPos currentChunk = new ChunkPos(cx, cz);
					var chunkMap = diskWorld.get(currentChunk);

					if (chunkMap == null || chunkMap.isEmpty()) continue;

					for (BlockPos pos : chunkMap.keySet()) {

						if (pos.distToCenterSqr(camera) > maxRenderDistSqr) continue;

						if (frustum != null) {
							net.minecraft.world.phys.AABB boundingBox = new net.minecraft.world.phys.AABB(pos);
							if (!frustum.isVisible(boundingBox)) continue;
						}

						ItemFrame realFrame = TRACKED_FRAMES.get(pos);
						if (realFrame != null && realFrame.tickCount >= 40) continue;

						ItemFrame dummy = ItemFrameCache.getOrBuildFrame(worldKey, pos, mc);
						if (dummy == null) continue;

						if (dummy.getItem().isEmpty()) continue;

						poseStack.pushPose();
						poseStack.translate(dummy.getX() - camera.x, dummy.getY() - camera.y, dummy.getZ() - camera.z);

						@SuppressWarnings({"rawtypes", "unchecked"})
						var renderer = (net.minecraft.client.renderer.entity.EntityRenderer)
								mc.getEntityRenderDispatcher().getRenderer(dummy);

						int blockLight = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
						int skyLight = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
						int light = LightTexture.pack(blockLight, skyLight);

						var state = renderer.createRenderState();
						renderer.extractRenderState(dummy, state, 0.0F);

						Vec3 offset = renderer.getRenderOffset(state);
						poseStack.translate(offset.x(), offset.y(), offset.z());

						renderer.render(state, poseStack, bufferSource, light);
						poseStack.popPose();
					}
				}
			}
			bufferSource.endBatch();
		});
	}
}