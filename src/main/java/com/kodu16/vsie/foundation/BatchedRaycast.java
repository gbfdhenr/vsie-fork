package com.kodu16.vsie.foundation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import com.kodu16.vsie.foundation.LoadedChunkRaycast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 射线检测批处理器：收集同一 tick 内的多条射线，合并遍历区块，减少重复区块加载/遍历开销。
 * 适用场景：
 * - 直射能量武器（电弧/等离子/轨道炮/红外刀）
 * - 推进器火焰长度检测
 * - 炮塔搜索射线（可选）
 * 
 * 使用方式：
 * 1. tick 开始前：BatchedRaycast.startBatch()
 * 2. 每个武器/推进器：BatchedRaycast.submit(raycastRequest)
 * 3. tick 末尾：BatchedRaycast.processBatch(level, consumer)
 */
public final class BatchedRaycast {
    private static final ThreadLocal<List<RaycastRequest>> BATCH = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Boolean> BATCH_ACTIVE = ThreadLocal.withInitial(() -> false);

    private BatchedRaycast() {
    }

    public static void startBatch() {
        BATCH.get().clear();
        BATCH_ACTIVE.set(true);
    }

    public static void endBatch() {
        BATCH_ACTIVE.set(false);
    }

    public static boolean isBatchActive() {
        return BATCH_ACTIVE.get();
    }

    public static void submit(RaycastRequest request) {
        if (BATCH_ACTIVE.get()) {
            BATCH.get().add(request);
        } else {
            // 非批处理模式：直接执行
            request.executeImmediate();
        }
    }

    public static void processBatch(Level level, Consumer<RaycastRequest> resultConsumer) {
        List<RaycastRequest> requests = BATCH.get();
        if (requests.isEmpty()) {
            BATCH.remove();
            BATCH_ACTIVE.remove();
            return;
        }

        // 按起点区块分组，减少区块遍历次数
        requests.sort((a, b) -> {
            int chunkA = SectionPos.blockToSectionCoord((int) a.from.x) << 16 | SectionPos.blockToSectionCoord((int) a.from.z);
            int chunkB = SectionPos.blockToSectionCoord((int) b.from.x) << 16 | SectionPos.blockToSectionCoord((int) b.from.z);
            return Integer.compare(chunkA, chunkB);
        });

        for (RaycastRequest req : requests) {
            req.executeBatched(level);
            resultConsumer.accept(req);
        }

        BATCH.remove();
        BATCH_ACTIVE.remove();
    }

    public static final class RaycastRequest {
        public final Vec3 from;
        public final Vec3 to;
        public final float maxDistance;
        public final ClipContext.Block blockMode;
        public final ClipContext.Fluid fluidMode;
        public final CollisionContext context;
        public final Object owner; // 武器/推进器实例，用于回调

        // 结果字段
        public HitResult hitResult = HitResult.miss(Vec3.ZERO, Direction.DOWN, BlockPos.ZERO);
        public boolean hit = false;
        public float distance = 0;
        public Vec3 hitPos = Vec3.ZERO;
        public BlockPos hitBlockPos = BlockPos.ZERO;

        public RaycastRequest(Vec3 from, Vec3 direction, float maxDistance,
                              ClipContext.Block blockMode, ClipContext.Fluid fluidMode,
                              CollisionContext context, Object owner) {
            this.from = from;
            this.to = from.add(direction.normalize().scale(maxDistance));
            this.maxDistance = maxDistance;
            this.blockMode = blockMode;
            this.fluidMode = fluidMode;
            this.context = context;
            this.owner = owner;
        }

        public void executeImmediate() {
            Level level = getLevelFromOwner();
            if (level == null) return;
            BlockHitResult hit = LoadedChunkRaycast.clipIgnoringUnloadedChunks(
                    level, from, to, blockMode, fluidMode, context);
            applyResult(hit);
        }

        public void executeBatched(Level level) {
            BlockHitResult hit = LoadedChunkRaycast.clipIgnoringUnloadedChunks(
                    level, from, to, blockMode, fluidMode, context);
            applyResult(hit);
        }

        private void applyResult(BlockHitResult hit) {
            this.hitResult = hit;
            if (hit.getType() == HitResult.Type.BLOCK) {
                this.hit = true;
                this.hitBlockPos = hit.getBlockPos();
                this.hitPos = hit.getLocation();
                this.distance = (float) from.distanceTo(hitPos);
            } else {
                this.hit = false;
                this.hitPos = to;
                this.distance = maxDistance;
                this.hitBlockPos = BlockPos.ZERO;
            }
        }

        private Level getLevelFromOwner() {
            if (owner instanceof com.kodu16.vsie.content.weapon.AbstractWeaponBlockEntity be) {
                return be.getLevel();
            }
            if (owner instanceof com.kodu16.vsie.content.thruster.AbstractThrusterBlockEntity be) {
                return be.getLevel();
            }
            return null;
        }
    }

    // 便捷构造器
    public static RaycastRequest createWeaponRaycast(Vec3 from, Vec3 direction, float maxDistance, Object weapon) {
        return new RaycastRequest(from, direction, maxDistance,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty(), weapon);
    }

    public static RaycastRequest createThrusterRaycast(Vec3 from, Vec3 direction, float maxDistance, Object thruster) {
        return new RaycastRequest(from, direction, maxDistance,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty(), thruster);
    }
}