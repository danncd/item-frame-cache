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

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ItemFrameCache {


    public static final Map<String, Map<BlockPos, ItemFrame>> LIVE_CACHE = new ConcurrentHashMap<>();

    public static final Map<String, Map<BlockPos, CompoundTag>> DISK_CACHE = new ConcurrentHashMap<>();

    public static final Set<BlockPos> LOADED_REAL_FRAMES = ConcurrentHashMap.newKeySet();

    public static void addFrame(String worldKey, BlockPos pos, ItemFrame frame) {

        LIVE_CACHE.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>()).put(pos, frame);

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

        DISK_CACHE.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>()).put(pos, tag);
    }

    public static ItemFrame getOrBuildFrame(String worldKey, BlockPos pos, Minecraft mc) {
        Map<BlockPos, ItemFrame> liveWorld = LIVE_CACHE.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>());
        if (liveWorld.containsKey(pos)) {
            return liveWorld.get(pos);
        }

        Map<BlockPos, CompoundTag> diskWorld = DISK_CACHE.get(worldKey);
        if (diskWorld != null && diskWorld.containsKey(pos) && mc.level != null) {
            CompoundTag tag = diskWorld.get(pos);

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
                    System.err.println("Failed to parse item for frame at " + pos);
                }
            } else {
                dummy.setItem(ItemStack.EMPTY);
            }

            dummy.setRotation(tag.getByte("rot").orElse((byte) 0));
            liveWorld.put(pos, dummy);

            return dummy;
        }

        return null;
    }

    private static Path getSaveFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("itemframecache.dat");
    }

    public static void saveToDisk() {
        CompoundTag root = new CompoundTag();

        for (Map.Entry<String, Map<BlockPos, CompoundTag>> worldEntry : DISK_CACHE.entrySet()) {

            ListTag list = new ListTag();
            worldEntry.getValue().values().forEach(list::add);

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
                Map<BlockPos, CompoundTag> worldMap = new ConcurrentHashMap<>();

                Tag listTag = root.get(worldKey);
                if (listTag instanceof ListTag list) {
                    for (Tag t : list) {
                        if (t instanceof CompoundTag tag) {

                            int x = tag.getInt("x").orElse(0);
                            int y = tag.getInt("y").orElse(0);
                            int z = tag.getInt("z").orElse(0);

                            worldMap.put(new BlockPos(x, y, z), tag);

                        }
                    }
                }
                DISK_CACHE.put(worldKey, worldMap);
            }
            System.err.println("[Item Frame Cache] Loaded frames from disk.");

        } catch (Exception e) {
            System.err.println("[Item Frame Cache] Failed to load from disk!");
            e.printStackTrace();
        }
    }

    public static void removeFrame(String worldKey, BlockPos pos) {
        if (LIVE_CACHE.containsKey(worldKey)) LIVE_CACHE.get(worldKey).remove(pos);
        if (DISK_CACHE.containsKey(worldKey)) DISK_CACHE.get(worldKey).remove(pos);
    }
}
