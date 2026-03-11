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
    private int currentTickDelay = 20;
    private final java.util.HashMap<Long, Integer> visitedChunks = new java.util.HashMap<>();
    private long currentChunkCoords = 0;
    private Integer userGoalX = null;
    private Integer userGoalZ = null;

    @EventHandler
    public void onClientTick(ClientTickEvent.Post event) {
        if (!BaritoneHelper.isBaritonePresent()) return;
        if (!BaritoneHelper.isBaritoneElytraPresent()) return;
        
        // Respect #stop: if baritone is not active and we were supposed to be following, 
        // we might have been stopped manually.
        var elytraProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess();
        boolean isActive = elytraProcess.isActive();
        
        if (mc.player != null && isActive) {
            long pos = net.minecraft.world.level.ChunkPos.asLong(ChunkUtils.actualPlayerChunkX(), ChunkUtils.actualPlayerChunkZ());
            if (pos != currentChunkCoords) {
                currentChunkCoords = pos;
                visitedChunks.put(pos, visitedChunks.getOrDefault(pos, 0) + 1);
            }
        }

        if (timer++ < currentTickDelay) return;
        timer = 0;

        if (isPaused) {
            // Check if we should re-activate. If the user manually starts pathing again, we can resume.
            if (elytraProcess.isActive()) {
                isPaused = false;
                hasLastLook = false; // Capture initial direction again
                visitedChunks.clear(); // Clear visited chunks on new activation
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
        visitedChunks.clear();
    }

    public void setUserGoal(int goalX, int goalZ) {
        this.userGoalX = goalX;
        this.userGoalZ = goalZ;
        this.hasLastLook = false; // Force recalc of look direction based on this precise new goal and current pos
        this.visitedChunks.clear();
        if (mc.player != null) {
            long pos = net.minecraft.world.level.ChunkPos.asLong(ChunkUtils.actualPlayerChunkX(), ChunkUtils.actualPlayerChunkZ());
            this.currentChunkCoords = pos;
            this.visitedChunks.put(pos, 1);
        }
        this.isPaused = false;
        this.currentTickDelay = (int) (double) Settings.REGISTRY.elytraTrailFollowerTickDelay.get();
        this.timer = this.currentTickDelay; // force immediate tick
    }

    private void captureInitialDirection() {
        if (mc.player == null) return;
        
        if (userGoalX != null && userGoalZ != null) {
            double dx = userGoalX.doubleValue() - mc.player.getX();
            double dz = userGoalZ.doubleValue() - mc.player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 0.1) {
                lastLookX = dx / dist;
                lastLookZ = dz / dist;
                hasLastLook = true;
                return;
            }
        }

        float yaw = mc.player.getYRot();
        lastLookX = -Math.sin(Math.toRadians(yaw));
        lastLookZ = Math.cos(Math.toRadians(yaw));
        hasLastLook = true;
    }


    private void followTrail() {
        if (mc.player == null) return;
        final ResourceKey<Level> dim = ChunkUtils.getActualDimension();
        final int playerChunkX = ChunkUtils.actualPlayerChunkX();
        final int playerChunkZ = ChunkUtils.actualPlayerChunkZ();
        double radius = Settings.REGISTRY.elytraTrailFollowerSearchRadius.get();
        Settings.ElytraTrailFollowerAlgorithm algo = Settings.REGISTRY.elytraTrailFollowerAlgorithmSetting.get();
        boolean smartMode = Settings.REGISTRY.elytraTrailFollowerSmartMode.get();

        currentTickDelay = (int) (double) Settings.REGISTRY.elytraTrailFollowerTickDelay.get();

        if (smartMode && mc.player != null) {
            var playerInfo = mc.player.connection.getPlayerInfo(mc.player.getUUID());
            if (playerInfo != null && playerInfo.getLatency() > 80) {
                currentTickDelay = Math.min((int)(currentTickDelay * 1.5), 60);
            }
            double speed = Math.sqrt(mc.player.getDeltaMovement().x * mc.player.getDeltaMovement().x + mc.player.getDeltaMovement().z * mc.player.getDeltaMovement().z);
            if (speed > 1.2) {
                radius = Math.min(radius * 1.5, 32.0); // look further ahead
            }
        }

        int bestChunkX = 0;
        int bestChunkZ = 0;
        boolean found = false;

        if (!hasLastLook) {
            captureInitialDirection();
        }

        found = search(playerChunkX, playerChunkZ, radius, lastLookX, lastLookZ, dim, algo);
        
        if (found) {
            bestChunkX = tempBestX;
            bestChunkZ = tempBestZ;
        }

        if (found) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(new BlockPos(
                ChunkUtils.chunkCoordToCoord(bestChunkX) + 8,
                64,
                ChunkUtils.chunkCoordToCoord(bestChunkZ) + 8
            ));
            isPaused = false;
        } else {
            // Cancel baritone if we hit a dead end, to avoid it taking over and flying blindly to userGoal over NewChunks
            if (!isPaused && BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            }
            isPaused = true;
        }
    }

    private int tempBestX, tempBestZ;
    private double tempMaxScore;
    private boolean search(int playerChunkX, int playerChunkZ, double radius, double lX, double lZ, ResourceKey<Level> dim, Settings.ElytraTrailFollowerAlgorithm algo) {
        tempMaxScore = -Double.MAX_VALUE;
        boolean found = false;
        boolean useTremaux = algo == Settings.ElytraTrailFollowerAlgorithm.TREMAUX;
        boolean useAStar = algo == Settings.ElytraTrailFollowerAlgorithm.A_STAR;
        int r = (int) radius;

        java.util.List<net.minecraft.world.level.ChunkPos> reachableChunks = new java.util.ArrayList<>();
        int targetGoalChunkX = userGoalX != null ? ChunkUtils.coordToChunkCoord(userGoalX) : playerChunkX;
        int targetGoalChunkZ = userGoalZ != null ? ChunkUtils.coordToChunkCoord(userGoalZ) : playerChunkZ;

        class AStarNode implements Comparable<AStarNode> {
            final net.minecraft.world.level.ChunkPos pos;
            final int gCost;
            final double hCost;
            final double fCost;
            
            AStarNode(net.minecraft.world.level.ChunkPos pos, int gCost) {
                this.pos = pos;
                this.gCost = gCost;
                if (useAStar && userGoalX != null && userGoalZ != null) {
                    double dx = pos.x - targetGoalChunkX;
                    double dz = pos.z - targetGoalChunkZ;
                    this.hCost = Math.sqrt(dx * dx + dz * dz);
                } else {
                    this.hCost = 0;
                }
                this.fCost = gCost + hCost;
            }

            @Override
            public int compareTo(AStarNode o) {
                return Double.compare(this.fCost, o.fCost);
            }
        }

        java.util.PriorityQueue<AStarNode> queue = new java.util.PriorityQueue<>();
        java.util.HashSet<Long> visitedBfs = new java.util.HashSet<>();
        long startPos = net.minecraft.world.level.ChunkPos.asLong(playerChunkX, playerChunkZ);
        
        queue.add(new AStarNode(new net.minecraft.world.level.ChunkPos(playerChunkX, playerChunkZ), 0));
        visitedBfs.add(startPos);

        int[][] dirs = {{1,0}, {-1,0}, {0,1}, {0,-1}, {1,1}, {1,-1}, {-1,1}, {-1,-1}};
        while (!queue.isEmpty()) {
            AStarNode currNode = queue.poll();
            net.minecraft.world.level.ChunkPos curr = currNode.pos;
            
            if (curr.x != playerChunkX || curr.z != playerChunkZ) {
                reachableChunks.add(curr);
            }
            
            for (int[] dir : dirs) {
                int nx = curr.x + dir[0];
                int nz = curr.z + dir[1];
                if (Math.abs(nx - playerChunkX) > r || Math.abs(nz - playerChunkZ) > r) continue;
                long nPos = net.minecraft.world.level.ChunkPos.asLong(nx, nz);
                if (visitedBfs.add(nPos)) {
                    if (isOldChunk(nx, nz, dim) && !isAnyNewChunk(nx, nz, dim)) {
                        int visits = visitedChunks.getOrDefault(nPos, 0);
                        boolean blocked = false;
                        if (useTremaux) {
                            if (visits >= 2) blocked = true;
                        } else { // DIRECT or A_STAR
                            if (visits > 0) blocked = true;
                        }
                        
                        if (!blocked) {
                            queue.add(new AStarNode(new net.minecraft.world.level.ChunkPos(nx, nz), currNode.gCost + 1));
                        }
                    }
                }
            }
        }

        for (net.minecraft.world.level.ChunkPos candidate : reachableChunks) {
            int chunkX = candidate.x;
            int chunkZ = candidate.z;
            int x = chunkX - playerChunkX;
            int z = chunkZ - playerChunkZ;
            long candidatePos = candidate.toLong();
            int visits = visitedChunks.getOrDefault(candidatePos, 0);
            
            if (useTremaux) {
                if (visits >= 2) continue;
            } else {
                if (visits > 0) continue;
            }

            double distSq = (double) x * x + (double) z * z;
            double dot = x * lX + z * lZ;
            double score = distSq;
            
            if (useAStar && userGoalX != null && userGoalZ != null) {
                double dx = chunkX - targetGoalChunkX;
                double dz = chunkZ - targetGoalChunkZ;
                double distToGoal = Math.sqrt(dx * dx + dz * dz);
                score = -distToGoal * 1000 + distSq; // Strongly prefer nodes closer to goal, break ties with further from player
            } else {
                score += dot * 100;
                if (dot <= 0) score -= 10000;
            }

            if (useTremaux && visits == 1) {
                score -= 50000;
            }

            if (score > tempMaxScore) {
                tempMaxScore = score;
                tempBestX = chunkX;
                tempBestZ = chunkZ;
                found = true;
            }
        }
        return found;
    }

    private boolean isOldChunk(int x, int z, ResourceKey<Level> dim) {
        // Check both OldChunks and PaletteNewChunks inverse (which also marks old chunks)
        return ModuleManager.getModule(OldChunks.class).isOldChunk(x, z, dim)
            || ModuleManager.getModule(PaletteNewChunks.class).isInverseNewChunk(x, z, dim);
    }

    private boolean isAnyNewChunk(int x, int z, ResourceKey<Level> dim) {
        return ModuleManager.getModule(PaletteNewChunks.class).isNewChunk(x, z, dim)
            || ModuleManager.getModule(LiquidNewChunks.class).isNewChunk(x, z, dim);
    }
    
    // isLineOfSightClear removed as BFS guarantees continuous paths inherently
}
