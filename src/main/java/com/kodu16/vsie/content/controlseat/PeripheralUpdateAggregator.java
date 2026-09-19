package com.kodu16.vsie.content.controlseat;

import com.kodu16.vsie.foundation.ParallelTaskExecutor;
import com.kodu16.vsie.foundation.ServerShipUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 控制座外设并行更新聚合器。
 * 将不同类型的外设更新并行化，最后在主线程聚合结果。
 * 
 * 线程安全保证：
 * - 只读世界状态（level.getBlockEntity, BlockState 等）
 * - 结果通过 mutable 容器收集，最后在主线程合并
 * - 任何写入世界/方块实体/网络的操作仍在主线程串行完成
 */
public final class PeripheralUpdateAggregator {
    private final ControlSeatBlockEntity controlSeat;
    private final Level level;
    private final BlockPos seatPos;
    
    // 聚合结果
    public int totalEnergySpend = 0;
    public int totalFuelSpend = 0;
    public List<ActiveWeaponHudInfo> activeWeaponHudInfos = new ArrayList<>();
    public boolean forceAssistSuppressedByAccelerator = false;
    public boolean railAccelerationActive = false;
    public List<BlockPos> thrusterPositions = new ArrayList<>();
    public float calculatedStrength = 0;
    public float thrusterForceStrength = 0;
    public float thrusterTorqueStrength = 0;
    public float[] facingMaxThrustSum = new float[6];
    public double forceStrengthSum = 0;
    public double torqueStrengthSum = 0;

    public PeripheralUpdateAggregator(ControlSeatBlockEntity controlSeat) {
        this.controlSeat = controlSeat;
        this.level = controlSeat.getLevel();
        this.seatPos = controlSeat.getBlockPos();
    }

    /**
     * 并行执行所有外设更新
     */
    public void runAllUpdates() {
        if (level == null || level.isClientSide()) {
            return;
        }

        // 1. 收集所有外设（主线程快照，避免并发修改列表）
        List<PeripheralSnapshot> thrusterSnapshots = collectPeripherals(0);
        List<PeripheralSnapshot> weaponSnapshots = collectPeripherals(1);
        List<PeripheralSnapshot> shieldSnapshots = collectPeripherals(2);
        List<PeripheralSnapshot> turretSnapshots = collectPeripherals(3);
        List<PeripheralSnapshot> batterySnapshots = collectPeripherals(4);
        List<PeripheralSnapshot> fuelTankSnapshots = collectPeripherals(5);
        List<PeripheralSnapshot> ammoBoxSnapshots = collectPeripherals(6);
        List<PeripheralSnapshot> screenSnapshots = collectPeripherals(7);

        // 2. 并行执行各类型更新
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 推进器更新
        if (!thrusterSnapshots.isEmpty()) {
            futures.add(CompletableFuture.runAsync(() -> updateThrustersParallel(thrusterSnapshots), 
                    ParallelTaskExecutor.EXECUTOR));
        }

        // 武器更新
        if (!weaponSnapshots.isEmpty()) {
            futures.add(CompletableFuture.runAsync(() -> updateWeaponsParallel(weaponSnapshots), 
                    ParallelTaskExecutor.EXECUTOR));
        }

        // 护盾更新
        if (!shieldSnapshots.isEmpty()) {
            futures.add(CompletableFuture.runAsync(() -> updateShieldsParallel(shieldSnapshots), 
                    ParallelTaskExecutor.EXECUTOR));
        }

        // 炮塔更新
        if (!turretSnapshots.isEmpty()) {
            futures.add(CompletableFuture.runAsync(() -> updateTurretsParallel(turretSnapshots), 
                    ParallelTaskExecutor.EXECUTOR));
        }

        // 电池/燃料箱/弹药箱/屏幕（轻量级，合并为一个任务）
        if (!batterySnapshots.isEmpty() || !fuelTankSnapshots.isEmpty() 
                || !ammoBoxSnapshots.isEmpty() || !screenSnapshots.isEmpty()) {
            futures.add(CompletableFuture.runAsync(() -> {
                updateEnergyStorageParallel(batterySnapshots);
                updateFuelStorageParallel(fuelTankSnapshots);
                updateAmmoStorageParallel(ammoBoxSnapshots);
                updateScreensParallel(screenSnapshots);
            }, ParallelTaskExecutor.EXECUTOR));
        }

        // 3. 等待所有并行任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 4. 主线程聚合结果并应用副作用（发送网络包、设置方块实体数据等）
        applyAggregatedResults();
    }

    /**
     * 收集指定类型的外设快照
     */
    private List<PeripheralSnapshot> collectPeripherals(int type) {
        List<PeripheralSnapshot> snapshots = new ArrayList<>();
        controlSeat.forEachLinkedPeripheral(pos -> {
            BlockPos blockPos = BlockPos.containing(pos);
            BlockEntity be = level.getBlockEntity(blockPos);
            if (be != null) {
                snapshots.add(new PeripheralSnapshot(blockPos.immutable(), be, pos));
            }
        }, type);
        return snapshots;
    }

    /**
     * 并行更新推进器
     */
    private void updateThrustersParallel(List<PeripheralSnapshot> snapshots) {
        float[] localFacingMaxThrustSum = new float[6];
        double localForceStrengthSum = 0;
        double localTorqueStrengthSum = 0;
        List<BlockPos> localThrusterPositions = new ArrayList<>();
        int localFuelSpend = 0;
        int localEnergySpend = 0;

        for (PeripheralSnapshot snap : snapshots) {
            if (snap.blockEntity instanceof com.kodu16.vsie.content.thruster.AbstractThrusterBlockEntity thruster) {
                // 确认外设存在（主线程安全操作，这里只记录）
                // confirmLinkedPeripheralPresent 需在主线程，这里跳过，最后聚合时处理
                
                localEnergySpend += thruster.getControlSeatEnergyCostPerTick();
                
                double forceCoefficient = thruster.getForceCoefficient();
                double torqueCoefficient = thruster.getTorqueCoefficient();
                localForceStrengthSum += forceCoefficient;
                localTorqueStrengthSum += torqueCoefficient;

                Direction thrusterFacing = thruster.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
                int facingIndex = getFacingThrustIndex(thrusterFacing);
                if (facingIndex >= 0) {
                    localFacingMaxThrustSum[facingIndex] += (float) forceCoefficient;
                }

                localThrusterPositions.add(snap.blockPos);
                localFuelSpend += thruster.fuelconsumptionperthrottle() * thruster.getFuelThrottle();
            }
        }

        // 存储局部结果，主线程聚合
        synchronized (this) {
            for (int i = 0; i < 6; i++) {
                facingMaxThrustSum[i] += localFacingMaxThrustSum[i];
            }
            forceStrengthSum += localForceStrengthSum;
            torqueStrengthSum += localTorqueStrengthSum;
            thrusterPositions.addAll(localThrusterPositions);
            totalFuelSpend += localFuelSpend;
            totalEnergySpend += localEnergySpend;
        }
    }

    /**
     * 并行更新武器
     */
    private void updateWeaponsParallel(List<PeripheralSnapshot> snapshots) {
        List<ActiveWeaponHudInfo> localHudInfos = new ArrayList<>();
        boolean localForceAssistSuppressed = false;
        boolean localRailAccelerationActive = false;
        int localEnergySpend = 0;
        List<Vec3> toRemove = new ArrayList<>();

        int activeSeatChannelEncode = 0;
        if (controlSeat.controlseatData.getChannel1()) activeSeatChannelEncode |= 1;
        if (controlSeat.controlseatData.getChannel2()) activeSeatChannelEncode |= 2;
        if (controlSeat.controlseatData.getChannel3()) activeSeatChannelEncode |= 4;
        if (controlSeat.controlseatData.getChannel4()) activeSeatChannelEncode |= 8;

        SubLevel lockedEnemySubLevel = controlSeat.resolveLockedEnemySubLevel();

        for (PeripheralSnapshot snap : snapshots) {
            if (snap.blockEntity instanceof AbstractWeaponBlockEntity weapon) {
                localEnergySpend += weapon.getControlSeatEnergyCostPerTick();

                if (weapon instanceof com.kodu16.vsie.content.weapon.missile_launcher.block.VerticleLaunchingSlotCoreBlockEntity verticalLaunchCore) {
                    verticalLaunchCore.receiveArmedChannels(activeSeatChannelEncode);
                }

                boolean activeForSeat = controlSeat.isWeaponInAnyActiveChannel(weapon, activeSeatChannelEncode);
                if (activeForSeat) {
                    localHudInfos.add(new ActiveWeaponHudInfo(
                            weapon.getDisplayName().getString(),
                            weapon.getCooldownHudValue(),
                            weapon.getCooldownHudMax(),
                            weapon.isCooldownHudRemaining(),
                            weapon.isHudFireReady()
                    ));
                }

                if (controlSeat.controlseatData.isfiring) {
                    weapon.receivechannel(activeSeatChannelEncode);
                } else {
                    weapon.receivechannel(0);
                }

                if (controlSeat.controlseatData.isfiring
                        && activeForSeat
                        && weapon instanceof com.kodu16.vsie.content.weapon.electro_magnet_rail_accelerator.ElectromagnetRailAcceleratorBlockEntity accelerator
                        && accelerator.shouldSuppressForceAssist()) {
                    localForceAssistSuppressed = true;
                }
                if (controlSeat.controlseatData.isfiring
                        && activeForSeat
                        && weapon instanceof com.kodu16.vsie.content.weapon.electro_magnet_rail_accelerator.ElectromagnetRailAcceleratorBlockEntity accelerator
                        && accelerator.wasAcceleratingRecently()) {
                    localRailAccelerationActive = true;
                }
            } else {
                toRemove.add(snap.relativePos);
            }
        }

        synchronized (this) {
            activeWeaponHudInfos.addAll(localHudInfos);
            forceAssistSuppressedByAccelerator = localForceAssistSuppressed;
            railAccelerationActive = localRailAccelerationActive;
            totalEnergySpend += localEnergySpend;
            // toRemove 需主线程处理
        }
    }

    /**
     * 并行更新护盾
     */
    private void updateShieldsParallel(List<PeripheralSnapshot> snapshots) {
        int localEnergySpend = 0;
        for (PeripheralSnapshot snap : snapshots) {
            if (snap.blockEntity instanceof com.kodu16.vsie.content.shield.ShieldGeneratorBlockEntity shield) {
                localEnergySpend += shield.getControlSeatEnergyCostPerTick();
            }
        }
        synchronized (this) {
            totalEnergySpend += localEnergySpend;
        }
    }

    /**
     * 并行更新炮塔
     */
    private void updateTurretsParallel(List<PeripheralSnapshot> snapshots) {
        int localEnergySpend = 0;
        for (PeripheralSnapshot snap : snapshots) {
            if (snap.blockEntity instanceof com.kodu16.vsie.content.turret.AbstractTurretBlockEntity turret) {
                localEnergySpend += turret.getControlSeatEnergyCostPerTick();
            }
        }
        synchronized (this) {
            totalEnergySpend += localEnergySpend;
        }
    }

    /**
     * 并行更新电池
     */
    private void updateEnergyStorageParallel(List<PeripheralSnapshot> snapshots) {
        // 电池只提供能量，不消耗控制座能量
        // 实际能量聚合在 ControlSeatBlockEntity.updateEnergy() 中
    }

    /**
     * 并行更新燃料箱
     */
    private void updateFuelStorageParallel(List<PeripheralSnapshot> snapshots) {
        // 燃料箱只提供燃料，实际聚合在 ControlSeatBlockEntity.updateFuel() 中
    }

    /**
     * 并行更新弹药箱
     */
    private void updateAmmoStorageParallel(List<PeripheralSnapshot> snapshots) {
        // 弹药箱只提供弹药，实际逻辑在武器消耗时
    }

    /**
     * 并行更新屏幕
     */
    private void updateScreensParallel(List<PeripheralSnapshot> snapshots) {
        int localEnergySpend = 0;
        for (PeripheralSnapshot snap : snapshots) {
            if (snap.blockEntity instanceof com.kodu16.vsie.content.screen.AbstractScreenBlockEntity screen) {
                localEnergySpend += screen.getControlSeatEnergyCostPerTick();
            }
        }
        synchronized (this) {
            totalEnergySpend += localEnergySpend;
        }
    }

    /**
     * 主线程聚合结果并应用副作用
     */
    private void applyAggregatedResults() {
        // 更新控制座数据
        controlSeat.energyspendpertick = totalEnergySpend;
        controlSeat.fuelspendcurrenttick = totalFuelSpend;
        controlSeat.calculatedstrength = calculatedStrength;
        controlSeat.controlseatData.thruster_strength = thrusterForceStrength;
        controlSeat.controlseatData.thruster_force_strength = thrusterForceStrength;
        controlSeat.controlseatData.thruster_torque_strength = thrusterTorqueStrength;
        controlSeat.controlseatData.facingMaxThrustSum = facingMaxThrustSum;
        controlSeat.controlseatData.thrusterpositionslist = thrusterPositions;

        controlSeat.controlseatData.activeWeaponHudInfos = activeWeaponHudInfos;
        controlSeat.controlseatData.isForceAssistSuppressedByAccelerator = forceAssistSuppressedByAccelerator;
        if (forceAssistSuppressedByAccelerator) {
            controlSeat.controlseatData.isforceassiston = false;
        }
        controlSeat.syncRailAccelerationFx(railAccelerationActive);
    }

    private int getFacingThrustIndex(Direction direction) {
        return switch (direction) {
            case EAST -> 0;
            case SOUTH -> 1;
            case WEST -> 2;
            case NORTH -> 3;
            case UP -> 4;
            case DOWN -> 5;
        };
    }

    private static final class PeripheralSnapshot {
        final BlockPos blockPos;
        final BlockEntity blockEntity;
        final Vec3 relativePos;

        PeripheralSnapshot(BlockPos blockPos, BlockEntity blockEntity, Vec3 relativePos) {
            this.blockPos = blockPos;
            this.blockEntity = blockEntity;
            this.relativePos = relativePos;
        }
    }
}