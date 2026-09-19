package com.kodu16.vsie.foundation;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Matrix3dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 子层级物理/姿态缓存：每 tick 刷新一次，供控制座、武器、推进器等复用。
 * 避免重复调用 subLevel.logicalPose()、getMassTracker()、getInertiaTensor() 等昂贵操作。
 */
public final class SubLevelPhysicsCache {
    private static final Map<UUID, SubLevelSnapshot> CACHE = new HashMap<>();
    private static long currentTick = -1;

    private SubLevelPhysicsCache() {
    }

    /**
     * 在每个服务端 tick 开始时调用，推进缓存版本。
     */
    public static void nextTick(long gameTime) {
        if (gameTime != currentTick) {
            currentTick = gameTime;
            CACHE.clear();
        }
    }

    /**
     * 获取或计算子层级快照
     */
    public static SubLevelSnapshot get(ServerSubLevel subLevel) {
        UUID key = subLevel.getUniqueId();
        return CACHE.computeIfAbsent(key, k -> new SubLevelSnapshot(subLevel));
    }

    /**
     * 获取子层级快照（可能为 null，如果 subLevel 不是 ServerSubLevel）
     */
    public static SubLevelSnapshot getIfPresent(SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            return null;
        }
        return get(serverSubLevel);
    }

    public static final class SubLevelSnapshot {
        public final UUID subLevelId;
        public final Vector3d position;
        public final Quaterniondc orientation;
        public final Matrix3dc inverseInertiaTensor; // 惯性张量逆，用于角加速度计算
        public final double mass;
        public final double averageInertia;
        public final Vector3d linearVelocity;
        public final Vector3d angularVelocity;
        public final boolean valid;

        private SubLevelSnapshot(ServerSubLevel subLevel) {
            this.subLevelId = subLevel.getUniqueId();
            this.position = subLevel.logicalPose().position();
            this.orientation = subLevel.logicalPose().orientation();

            MassData massData = subLevel.getMassTracker();
            if (massData != null && !massData.isInvalid()) {
                this.mass = massData.getMass();
                Matrix3dc inertia = massData.getInertiaTensor();
                this.averageInertia = averageInertia(inertia);
                
                // 计算逆惯性张量（用于 torque -> angularAcceleration）
                this.inverseInertiaTensor = invertInertia(inertia);
                
                RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
                if (handle != null && handle.isValid()) {
                    this.linearVelocity = handle.getLinearVelocity(new Vector3d());
                    this.angularVelocity = handle.getAngularVelocity(new Vector3d());
                } else {
                    this.linearVelocity = new Vector3d();
                    this.angularVelocity = new Vector3d();
                }
                this.valid = true;
            } else {
                this.mass = 0;
                this.averageInertia = 0;
                this.inverseInertiaTensor = null;
                this.linearVelocity = new Vector3d();
                this.angularVelocity = new Vector3d();
                this.valid = false;
            }
        }

        /**
         * 将局部向量转换为世界向量
         */
        public Vector3d toWorldDirection(Vector3d localDir, Vector3d out) {
            orientation.transformNormal(localDir, out);
            return out.normalize();
        }

        /**
         * 将局部位置转换为世界位置
         */
        public Vector3d toWorldPosition(Vector3d localPos, Vector3d out) {
            out.set(localPos);
            orientation.transform(out);
            out.add(position);
            return out;
        }

        /**
         * 将世界向量转换为局部向量
         */
        public Vector3d toLocalDirection(Vector3d worldDir, Vector3d out) {
            // 逆旋转 = 共轭四元数
            Quaterniondc inv = new org.joml.Quaterniond(orientation).conjugate();
            inv.transformNormal(worldDir, out);
            return out.normalize();
        }

        /**
         * 应用惯性张量逆：angularAccel = inverseInertia * torque
         */
        public Vector3d applyInverseInertia(Vector3d torque, Vector3d out) {
            if (inverseInertiaTensor != null) {
                inverseInertiaTensor.transform(torque, out);
            } else {
                out.set(0, 0, 0);
            }
            return out;
        }

        private static double averageInertia(Matrix3dc inertia) {
            return (inertia.m00() + inertia.m11() + inertia.m22()) / 3.0D;
        }

        private static Matrix3dc invertInertia(Matrix3dc inertia) {
            // 3x3 对称矩阵求逆
            double m00 = inertia.m00(), m01 = inertia.m01(), m02 = inertia.m02();
            double m11 = inertia.m11(), m12 = inertia.m12();
            double m22 = inertia.m22();

            double det = m00 * (m11 * m22 - m12 * m12)
                       - m01 * (m01 * m22 - m12 * m02)
                       + m02 * (m01 * m12 - m11 * m02);

            if (Math.abs(det) < 1e-12) {
                return null; // 奇异矩阵
            }

            double invDet = 1.0 / det;
            org.joml.Matrix3d inv = new org.joml.Matrix3d();
            inv.m00((m11 * m22 - m12 * m12) * invDet);
            inv.m01((m02 * m12 - m01 * m22) * invDet);
            inv.m02((m01 * m12 - m02 * m11) * invDet);
            inv.m11((m00 * m22 - m02 * m02) * invDet);
            inv.m12((m01 * m02 - m00 * m12) * invDet);
            inv.m22((m00 * m11 - m01 * m01) * invDet);
            return inv;
        }
    }
}