package com.danncd.ifc;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ItemFrameCache {

    public static final Map<String, Map<ChunkPos, Map<BlockPos, ItemFrame>>> LIVE_CACHE = new ConcurrentHashMap<>();
    public static final Map<String, Map<ChunkPos, Map<BlockPos, CompoundTag>>> DISK_CACHE = new ConcurrentHashMap<>();

    public static final Map<BlockPos, ItemFrame> LOADED_REAL_FRAMES = new ConcurrentHashMap<>();

    public static void addFrame(String worldKey, BlockPos pos, ItemFrame frame) {
        ChunkPos chunkPos = new ChunkPos(pos);

        LIVE_CACHE.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkPos, k -> new ConcurrentHashMap<>())
                .put(pos, frame);

        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putByte("face", (byte) frame.getDirection().get3DDataValue());
        tag.putBoolean("glow", frame instanceof GlowItemFrame);
        tag.putByte("rot", (byte) frame.getRotation());

        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, frame.level().registryAccess());
            Tag itemTag = ItemStack.CODEC.encodeStart(ops, frame.getItem()).getOrThrow();
            tag.put("item", itemTag);
        } catch (Exception e) {
            System.err.println("Failed to serialize item frame at " + pos);
        }

        DISK_CACHE.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkPos, k -> new ConcurrentHashMap<>())
                .put(pos, tag);
    }

    public static ItemFrame getOrBuildFrame(String worldKey, BlockPos pos, Minecraft mc) {
        ChunkPos chunkPos = new ChunkPos(pos);

        var liveWorld = LIVE_CACHE.get(worldKey);
        if (liveWorld != null) {
            var liveChunk = liveWorld.get(chunkPos);
            if (liveChunk != null && liveChunk.containsKey(pos)) {
                return liveChunk.get(pos);
            }
        }

        var diskWorld = DISK_CACHE.get(worldKey);
        if (diskWorld != null && diskWorld.containsKey(chunkPos) && mc.level != null) {
            var diskChunk = diskWorld.get(chunkPos);
            if (diskChunk != null && diskChunk.containsKey(pos)) {
                CompoundTag tag = diskChunk.get(pos);

                byte faceByte = tag.getByte("face").orElse((byte) 0);
                Direction dir = Direction.from3DDataValue(faceByte);
                boolean isGlow = tag.getBoolean("glow").orElse(false);

                ItemFrame dummy;
                if (isGlow) {
                    dummy = new GlowItemFrame(mc.level, pos, dir);
                } else {
                    dummy = new ItemFrame(mc.level, pos, dir);
                }

                Tag itemTag = tag.get("item");
                if (itemTag != null) {
                    try {
                        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
                        ItemStack stack = ItemStack.CODEC.parse(ops, itemTag).getOrThrow();
                        dummy.setItem(stack);
                    } catch (Exception e) {
                        dummy.setItem(ItemStack.EMPTY);
                    }
                } else {
                    dummy.setItem(ItemStack.EMPTY);
                }

                dummy.setRotation(tag.getByte("rot").orElse((byte) 0));

                LIVE_CACHE.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(chunkPos, k -> new ConcurrentHashMap<>())
                        .put(pos, dummy);

                return dummy;
            }
        }
        return null;
    }

    private static Path getSaveFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("itemframecache.dat");
    }

    public static void saveToDisk() {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<String, Map<ChunkPos, Map<BlockPos, CompoundTag>>> worldEntry : DISK_CACHE.entrySet()) {

            ListTag list = new ListTag();
            for (Map<BlockPos, CompoundTag> chunkMap : worldEntry.getValue().values()) {
                chunkMap.values().forEach(list::add);
            }
            root.put(worldEntry.getKey(), list);
        }
        try {
            NbtIo.writeCompressed(root, getSaveFile());
            System.err.println("[Item Frame Cache] Successfully saved frames to disk.");
        } catch (Exception e) {
            System.err.println("[Item Frame Cache] Failed to save to disk!");
            e.printStackTrace();
        }
    }

    public static void loadFromDisk() {
        File file = getSaveFile().toFile();
        if (!file.exists()) return;

        try {
            CompoundTag root = NbtIo.readCompressed(file.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            for (String worldKey : root.keySet()) {

                Map<ChunkPos, Map<BlockPos, CompoundTag>> worldMap = new ConcurrentHashMap<>();
                Tag listTag = root.get(worldKey);

                if (listTag instanceof ListTag list) {
                    for (Tag t : list) {
                        if (t instanceof CompoundTag tag) {
                            int x = tag.getInt("x").orElse(0);
                            int y = tag.getInt("y").orElse(0);
                            int z = tag.getInt("z").orElse(0);

                            BlockPos pos = new BlockPos(x, y, z);
                            ChunkPos chunkPos = new ChunkPos(pos);

                            worldMap.computeIfAbsent(chunkPos, k -> new ConcurrentHashMap<>()).put(pos, tag);
                        }
                    }
                }
                DISK_CACHE.put(worldKey, worldMap);
            }
            System.err.println("[Item Frame Cache] Loaded frames from disk (Chunk Partitioned).");
        } catch (Exception e) {
            System.err.println("[Item Frame Cache] Failed to load from disk!");
            e.printStackTrace();
        }
    }

    public static void removeFrame(String worldKey, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        if (LIVE_CACHE.containsKey(worldKey)) {
            var liveChunk = LIVE_CACHE.get(worldKey).get(chunkPos);
            if (liveChunk != null) liveChunk.remove(pos);
        }
        if (DISK_CACHE.containsKey(worldKey)) {
            var diskChunk = DISK_CACHE.get(worldKey).get(chunkPos);
            if (diskChunk != null) diskChunk.remove(pos);
        }
    }
}