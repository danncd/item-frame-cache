package com.danncd.ifc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;

public class ItemFrameCacheClient implements ClientModInitializer {

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
		});

		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity == Minecraft.getInstance().player) {
				ItemFrameCache.LOADED_REAL_FRAMES.clear();
			}
		});

		ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ItemFrame frame) {
				ItemFrameCache.LOADED_REAL_FRAMES.add(frame.blockPosition());

				if (!frame.getItem().isEmpty()) {
					String worldKey = getCurrentWorldKey(Minecraft.getInstance());
					ItemFrameCache.addFrame(worldKey, frame.blockPosition(), frame);
				}
			}
		});

		ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof ItemFrame frame) {
				ItemFrameCache.LOADED_REAL_FRAMES.remove(frame.blockPosition());

				String worldKey = getCurrentWorldKey(Minecraft.getInstance());

				if (frame.getItem().isEmpty()) {
					ItemFrameCache.removeFrame(worldKey, frame.blockPosition());
				} else {
					ItemFrameCache.addFrame(worldKey, frame.blockPosition(), frame);
				}
			}
		});
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return;

			String worldKey = getCurrentWorldKey(mc);
			var diskMap = ItemFrameCache.DISK_CACHE.get(worldKey);

			if (diskMap == null || diskMap.isEmpty()) return;

			PoseStack poseStack = context.matrixStack();
			Vec3 camera = context.camera().getPosition();
			MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

			for (BlockPos pos : diskMap.keySet()) {

				if (ItemFrameCache.LOADED_REAL_FRAMES.contains(pos)) {
					continue;
				}

				ItemFrame dummy = ItemFrameCache.getOrBuildFrame(worldKey, pos, mc);
				if (dummy == null) continue;

				poseStack.pushPose();
				poseStack.translate(
						dummy.getX() - camera.x,
						dummy.getY() - camera.y,
						dummy.getZ() - camera.z
				);

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

				renderer.render(
						state,
						poseStack,
						bufferSource,
						light
				);

				poseStack.popPose();
			}

			bufferSource.endBatch();
		});
	}
}