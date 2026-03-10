package xaeroplus.module.impl;

import baritone.api.BaritoneAPI;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaeroplus.event.ClientTickEvent;
import xaeroplus.module.Module;
import xaeroplus.module.ModuleManager;
import xaeroplus.settings.Settings;
import xaeroplus.util.BaritoneHelper;
import xaeroplus.util.ChunkUtils;

public class ElytraTrailFollower extends Module {
    private int timer = 0;
    private double lastLookX = 0;
    private double lastLookZ = 0;
    private boolean hasLastLook = false;
    private boolean isPaused = false;

    @EventHandler
    public void onClientTick(ClientTickEvent.Post event) {
        if (!BaritoneHelper.isBaritonePresent()) return;
        if (!BaritoneHelper.isBaritoneElytraPresent()) return;
        
        // Respect #stop: if baritone is not active and we were supposed to be following, 
        // we might have been stopped manually.
        var elytraProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess();
        if (timer > 0 && !elytraProcess.isActive()) {
            // If the user manually stops baritone, elytraProcess.isActive() becomes false.
            // We should probably stay paused until the user restarts or we find a new reason to move.
            // However, the user specifically asked for #stop to work.
            // Let's just not restart if it's not active.
            isPaused = true;
        }

        if (timer++ < Settings.REGISTRY.elytraTrailFollowerTickDelay.get()) return;
        timer = 0;

        if (isPaused) {
            // Check if we should re-activate. If the user manually starts pathing again, we can resume.
            if (elytraProcess.isActive()) {
                isPaused = false;
            } else {
                return;
            }
        }

        followTrail();
    }

    @Override
    public void onEnable() {
        isPaused = false;
        hasLastLook = false;
    }

    private void followTrail() {
        if (mc.player == null) return;
        final ResourceKey<Level> dim = ChunkUtils.getActualDimension();
        final int playerChunkX = ChunkUtils.actualPlayerChunkX();
        final int playerChunkZ = ChunkUtils.actualPlayerChunkZ();
        final double radius = Settings.REGISTRY.elytraTrailFollowerSearchRadius.get();

        int bestChunkX = 0;
        int bestChunkZ = 0;
        boolean found = false;

        // Current look direction
        float yaw = mc.player.getYRot();
        double currentLookX = -Math.sin(Math.toRadians(yaw));
        double currentLookZ = Math.cos(Math.toRadians(yaw));

        // Use current look if it finds something, otherwise fallback to last known good direction
        found = search(playerChunkX, playerChunkZ, radius, currentLookX, currentLookZ, dim);
        
        if (found) {
            bestChunkX = tempBestX;
            bestChunkZ = tempBestZ;
            lastLookX = currentLookX;
            lastLookZ = currentLookZ;
            hasLastLook = true;
        } else if (hasLastLook) {
            // Fallback to last known good direction
            found = search(playerChunkX, playerChunkZ, radius, lastLookX, lastLookZ, dim);
            if (found) {
                bestChunkX = tempBestX;
                bestChunkZ = tempBestZ;
            }
        }

        if (found) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(new BlockPos(
                ChunkUtils.chunkCoordToCoord(bestChunkX) + 8,
                64,
                ChunkUtils.chunkCoordToCoord(bestChunkZ) + 8
            ));
            isPaused = false;
        }
    }

    private int tempBestX, tempBestZ;
    private double tempMaxDistSq;
    private boolean search(int playerChunkX, int playerChunkZ, double radius, double lX, double lZ, ResourceKey<Level> dim) {
        tempMaxDistSq = 0;
        boolean found = false;
        for (int x = (int) -radius; x <= radius; x++) {
            for (int z = (int) -radius; z <= radius; z++) {
                if (x == 0 && z == 0) continue;
                int chunkX = playerChunkX + x;
                int chunkZ = playerChunkZ + z;
                if (isOldChunk(chunkX, chunkZ, dim)) {
                    double distSq = (double) x * x + (double) z * z;
                    double dot = x * lX + z * lZ;
                    if (dot > 0 && distSq > tempMaxDistSq) {
                        tempMaxDistSq = distSq;
                        tempBestX = chunkX;
                        tempBestZ = chunkZ;
                        found = true;
                    }
                }
            }
        }
        return found;
    }

    private boolean isOldChunk(int x, int z, ResourceKey<Level> dim) {
        // Check both OldChunks and PaletteNewChunks inverse (which also marks old chunks)
        return ModuleManager.getModule(OldChunks.class).isOldChunk(x, z, dim)
            || ModuleManager.getModule(PaletteNewChunks.class).isInverseNewChunk(x, z, dim);
    }
}
