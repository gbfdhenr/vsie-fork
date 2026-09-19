package com.kodu16.vsie.network.controlseat.S2C;

import com.kodu16.vsie.content.controlseat.ActiveWeaponHudInfo;
import com.kodu16.vsie.content.controlseat.client.ControlSeatClientData;
import com.kodu16.vsie.content.controlseat.client.Input.ClientDataManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 统一控制座状态包：合并高频(位置/姿态/油门 50ms)、中频(能量/燃料/护盾/武器 250ms)、
 * 低频(输入通道 250ms)、雷达(500ms)数据。
 * 
 * 发送策略：
 * - 每 50ms：发送完整包（高频字段必填，其余字段可选/增量）
 * - 实际实现中通过字段掩码标识哪些字段已更新
 */
public class ControlSeatStateS2CPacket implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ControlSeatStateS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vsie", "controlseat_s2c_state"));
    public static final StreamCodec<FriendlyByteBuf, ControlSeatStateS2CPacket> STREAM_CODEC =
            CustomPacketPayload.codec(ControlSeatStateS2CPacket::write, ControlSeatStateS2CPacket::decode);

    // ========== 字段掩码（标识本包包含哪些字段组） ==========
    public static final int MASK_HIGH_FREQ   = 0x01; // 位置/姿态/油门/视角锁定/G力/船速/中心/速度 (50ms)
    public static final int MASK_RESOURCE    = 0x02; // 能量/燃料/E710 (250ms)
    public static final int MASK_SHIELD      = 0x04; // 护盾状态 (250ms)
    public static final int MASK_ASSIST      = 0x08; // 辅助标志/反重力/自动水平/跃迁 (250ms)
    public static final int MASK_WEAPON      = 0x10; // 武器HUD信息 (250ms)
    public static final int MASK_INPUT       = 0x20; // 输入通道编码 (250ms)
    public static final int MASK_RADAR       = 0x40; // 雷达船只数据 (500ms)
    public static final int MASK_FULL        = 0x7F; // 全量

    private final BlockPos pos;
    private final UUID seatEntityId;
    private final int fieldMask; // 组合掩码

    // High frequency (50ms)
    private final Vector3d shipFacing;
    private final Vector3d shipUp;
    private final int throttle;
    private final boolean isViewLocked;
    private final double shipSpeed;
    private final Vector3d structureCenterWorld;
    private final Vector3d structureVelocityWorld;
    private final double seatGForce;

    // Resource (250ms)
    private final int energyAvailable;
    private final int energyTotal;
    private final int fuelAvailable;
    private final int fuelTotal;
    private final int e710Available;
    private final int warpE710CostMb;
    private final boolean warpE710Insufficient;

    // Shield (250ms)
    private final boolean shieldOn;
    private final int shieldAvailable;
    private final int shieldTotal;
    private final boolean shieldOverloaded;

    // Assist/Flags (250ms)
    private final boolean forceAssistOn;
    private final boolean torqueAssistOn;
    private final boolean forceAssistSuppressedByAccelerator;
    private final boolean antiGravityOn;
    private final boolean autoLevelOn;
    private final boolean warpPreparing;
    private final boolean pendingWarpTeleport;
    private final String warpTargetName;
    private final double warpAlignmentControlX;
    private final double warpAlignmentControlY;

    // Weapon (250ms)
    private final List<ActiveWeaponHudInfo> activeWeaponHudInfos;

    // Input (250ms)
    private final int channelEncode;

    // Radar (500ms)
    private final String enemy;
    private final String ally;
    private final String lockedEnemySlug;
    private final List<RadarShipInfo> radarShips;

    public ControlSeatStateS2CPacket(Builder builder) {
        this.pos = builder.pos;
        this.seatEntityId = builder.seatEntityId;
        this.fieldMask = builder.fieldMask;

        this.shipFacing = builder.shipFacing;
        this.shipUp = builder.shipUp;
        this.throttle = builder.throttle;
        this.isViewLocked = builder.isViewLocked;
        this.shipSpeed = builder.shipSpeed;
        this.structureCenterWorld = builder.structureCenterWorld;
        this.structureVelocityWorld = builder.structureVelocityWorld;
        this.seatGForce = builder.seatGForce;

        this.energyAvailable = builder.energyAvailable;
        this.energyTotal = builder.energyTotal;
        this.fuelAvailable = builder.fuelAvailable;
        this.fuelTotal = builder.fuelTotal;
        this.e710Available = builder.e710Available;
        this.warpE710CostMb = builder.warpE710CostMb;
        this.warpE710Insufficient = builder.warpE710Insufficient;

        this.shieldOn = builder.shieldOn;
        this.shieldAvailable = builder.shieldAvailable;
        this.shieldTotal = builder.shieldTotal;
        this.shieldOverloaded = builder.shieldOverloaded;

        this.forceAssistOn = builder.forceAssistOn;
        this.torqueAssistOn = builder.torqueAssistOn;
        this.forceAssistSuppressedByAccelerator = builder.forceAssistSuppressedByAccelerator;
        this.antiGravityOn = builder.antiGravityOn;
        this.autoLevelOn = builder.autoLevelOn;
        this.warpPreparing = builder.warpPreparing;
        this.pendingWarpTeleport = builder.pendingWarpTeleport;
        this.warpTargetName = builder.warpTargetName;
        this.warpAlignmentControlX = builder.warpAlignmentControlX;
        this.warpAlignmentControlY = builder.warpAlignmentControlY;

        this.activeWeaponHudInfos = builder.activeWeaponHudInfos;
        this.channelEncode = builder.channelEncode;

        this.enemy = builder.enemy;
        this.ally = builder.ally;
        this.lockedEnemySlug = builder.lockedEnemySlug;
        this.radarShips = builder.radarShips;
    }

    // Builder 模式便于灵活构造
    public static class Builder {
        private BlockPos pos;
        private UUID seatEntityId;
        private int fieldMask = 0;

        // High freq
        private Vector3d shipFacing = new Vector3d();
        private Vector3d shipUp = new Vector3d();
        private int throttle = 0;
        private boolean isViewLocked = false;
        private double shipSpeed = 0;
        private Vector3d structureCenterWorld = new Vector3d();
        private Vector3d structureVelocityWorld = new Vector3d();
        private double seatGForce = 0;

        // Resource
        private int energyAvailable = 0;
        private int energyTotal = 0;
        private int fuelAvailable = 0;
        private int fuelTotal = 0;
        private int e710Available = 0;
        private int warpE710CostMb = 0;
        private boolean warpE710Insufficient = false;

        // Shield
        private boolean shieldOn = false;
        private int shieldAvailable = 0;
        private int shieldTotal = 0;
        private boolean shieldOverloaded = false;

        // Assist/Flags
        private boolean forceAssistOn = false;
        private boolean torqueAssistOn = false;
        private boolean forceAssistSuppressedByAccelerator = false;
        private boolean antiGravityOn = false;
        private boolean autoLevelOn = false;
        private boolean warpPreparing = false;
        private boolean pendingWarpTeleport = false;
        private String warpTargetName = "";
        private double warpAlignmentControlX = 0;
        private double warpAlignmentControlY = 0;

        // Weapon
        private List<ActiveWeaponHudInfo> activeWeaponHudInfos = new ArrayList<>();

        // Input
        private int channelEncode = 0;

        // Radar
        private String enemy = "";
        private String ally = "";
        private String lockedEnemySlug = "";
        private List<RadarShipInfo> radarShips = new ArrayList<>();

        public Builder(BlockPos pos, UUID seatEntityId) {
            this.pos = pos;
            this.seatEntityId = seatEntityId;
        }

        public Builder highFreq(Vector3d facing, Vector3d up, int throttle, boolean viewLocked,
                                double speed, Vector3d center, Vector3d velocity, double gForce) {
            this.fieldMask |= MASK_HIGH_FREQ;
            this.shipFacing = facing;
            this.shipUp = up;
            this.throttle = throttle;
            this.isViewLocked = viewLocked;
            this.shipSpeed = speed;
            this.structureCenterWorld = center;
            this.structureVelocityWorld = velocity;
            this.seatGForce = gForce;
            return this;
        }

        public Builder resources(int energyAvail, int energyTot, int fuelAvail, int fuelTot,
                                 int e710Avail, int warpCost, boolean warpInsuff) {
            this.fieldMask |= MASK_RESOURCE;
            this.energyAvailable = energyAvail;
            this.energyTotal = energyTot;
            this.fuelAvailable = fuelAvail;
            this.fuelTotal = fuelTot;
            this.e710Available = e710Avail;
            this.warpE710CostMb = warpCost;
            this.warpE710Insufficient = warpInsuff;
            return this;
        }

        public Builder shield(boolean on, int avail, int total, boolean overloaded) {
            this.fieldMask |= MASK_SHIELD;
            this.shieldOn = on;
            this.shieldAvailable = avail;
            this.shieldTotal = total;
            this.shieldOverloaded = overloaded;
            return this;
        }

        public Builder assists(boolean force, boolean torque, boolean forceSuppressed,
                               boolean antiGrav, boolean autoLevel,
                               boolean warpPrep, boolean pendingWarp, String warpName,
                               double alignX, double alignY) {
            this.fieldMask |= MASK_ASSIST;
            this.forceAssistOn = force;
            this.torqueAssistOn = torque;
            this.forceAssistSuppressedByAccelerator = forceSuppressed;
            this.antiGravityOn = antiGrav;
            this.autoLevelOn = autoLevel;
            this.warpPreparing = warpPrep;
            this.pendingWarpTeleport = pendingWarp;
            this.warpTargetName = warpName;
            this.warpAlignmentControlX = alignX;
            this.warpAlignmentControlY = alignY;
            return this;
        }

        public Builder weapons(List<ActiveWeaponHudInfo> infos) {
            this.fieldMask |= MASK_WEAPON;
            this.activeWeaponHudInfos = new ArrayList<>(infos);
            return this;
        }

        public Builder input(int encode) {
            this.fieldMask |= MASK_INPUT;
            this.channelEncode = encode;
            return this;
        }

        public Builder radar(String enemy, String ally, String lockedSlug, List<RadarShipInfo> ships) {
            this.fieldMask |= MASK_RADAR;
            this.enemy = enemy;
            this.ally = ally;
            this.lockedEnemySlug = lockedSlug;
            this.radarShips = new ArrayList<>(ships);
            return this;
        }

        public ControlSeatStateS2CPacket build() {
            return new ControlSeatStateS2CPacket(this);
        }
    }

    public static class RadarShipInfo {
        public final long id;
        public final String slug;
        public final String dimension;
        public final double x, y, z;
        public final int targetIndex;

        public RadarShipInfo(long id, String slug, String dimension,
                             double x, double y, double z, int targetIndex) {
            this.id = id;
            this.slug = slug;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.targetIndex = targetIndex;
        }
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUUID(seatEntityId);
        buf.writeVarInt(fieldMask);

        // High frequency (always present if MASK_HIGH_FREQ)
        if ((fieldMask & MASK_HIGH_FREQ) != 0) {
            buf.writeDouble(shipFacing.x);
            buf.writeDouble(shipFacing.y);
            buf.writeDouble(shipFacing.z);
            buf.writeDouble(shipUp.x);
            buf.writeDouble(shipUp.y);
            buf.writeDouble(shipUp.z);
            buf.writeInt(throttle);
            buf.writeBoolean(isViewLocked);
            buf.writeDouble(shipSpeed);
            buf.writeDouble(structureCenterWorld.x);
            buf.writeDouble(structureCenterWorld.y);
            buf.writeDouble(structureCenterWorld.z);
            buf.writeDouble(structureVelocityWorld.x);
            buf.writeDouble(structureVelocityWorld.y);
            buf.writeDouble(structureVelocityWorld.z);
            buf.writeDouble(seatGForce);
        }

        // Resources
        if ((fieldMask & MASK_RESOURCE) != 0) {
            buf.writeInt(energyAvailable);
            buf.writeInt(energyTotal);
            buf.writeInt(fuelAvailable);
            buf.writeInt(fuelTotal);
            buf.writeInt(e710Available);
            buf.writeInt(warpE710CostMb);
            buf.writeBoolean(warpE710Insufficient);
        }

        // Shield
        if ((fieldMask & MASK_SHIELD) != 0) {
            buf.writeBoolean(shieldOn);
            buf.writeInt(shieldAvailable);
            buf.writeInt(shieldTotal);
            buf.writeBoolean(shieldOverloaded);
        }

        // Assist/Flags
        if ((fieldMask & MASK_ASSIST) != 0) {
            buf.writeBoolean(forceAssistOn);
            buf.writeBoolean(torqueAssistOn);
            buf.writeBoolean(forceAssistSuppressedByAccelerator);
            buf.writeBoolean(antiGravityOn);
            buf.writeBoolean(autoLevelOn);
            buf.writeBoolean(warpPreparing);
            buf.writeBoolean(pendingWarpTeleport);
            buf.writeUtf(warpTargetName == null ? "" : warpTargetName);
            buf.writeDouble(warpAlignmentControlX);
            buf.writeDouble(warpAlignmentControlY);
        }

        // Weapons
        if ((fieldMask & MASK_WEAPON) != 0) {
            buf.writeInt(activeWeaponHudInfos.size());
            for (ActiveWeaponHudInfo info : activeWeaponHudInfos) {
                buf.writeUtf(info.displayName);
                buf.writeInt(info.currentTick);
                buf.writeInt(info.maxCooldown);
                buf.writeBoolean(info.remainingCooldown);
                buf.writeBoolean(info.fireReady);
            }
        }

        // Input
        if ((fieldMask & MASK_INPUT) != 0) {
            buf.writeInt(channelEncode);
        }

        // Radar
        if ((fieldMask & MASK_RADAR) != 0) {
            buf.writeUtf(enemy);
            buf.writeUtf(ally);
            buf.writeUtf(lockedEnemySlug);
            buf.writeInt(radarShips.size());
            for (RadarShipInfo ship : radarShips) {
                buf.writeVarLong(ship.id);
                buf.writeUtf(ship.slug);
                buf.writeUtf(ship.dimension);
                buf.writeDouble(ship.x);
                buf.writeDouble(ship.y);
                buf.writeDouble(ship.z);
                buf.writeVarInt(ship.targetIndex);
            }
        }
    }

    public static ControlSeatStateS2CPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        UUID seatEntityId = buf.readUUID();
        int fieldMask = buf.readVarInt();

        // High freq
        Vector3d shipFacing = new Vector3d();
        Vector3d shipUp = new Vector3d();
        int throttle = 0;
        boolean isViewLocked = false;
        double shipSpeed = 0;
        Vector3d structureCenterWorld = new Vector3d();
        Vector3d structureVelocityWorld = new Vector3d();
        double seatGForce = 0;

        if ((fieldMask & MASK_HIGH_FREQ) != 0) {
            shipFacing = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            shipUp = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            throttle = buf.readInt();
            isViewLocked = buf.readBoolean();
            shipSpeed = buf.readDouble();
            structureCenterWorld = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            structureVelocityWorld = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            seatGForce = buf.readDouble();
        }

        // Resources
        int energyAvailable = 0, energyTotal = 0, fuelAvailable = 0, fuelTotal = 0;
        int e710Available = 0, warpE710CostMb = 0;
        boolean warpE710Insufficient = false;
        if ((fieldMask & MASK_RESOURCE) != 0) {
            energyAvailable = buf.readInt();
            energyTotal = buf.readInt();
            fuelAvailable = buf.readInt();
            fuelTotal = buf.readInt();
            e710Available = buf.readInt();
            warpE710CostMb = buf.readInt();
            warpE710Insufficient = buf.readBoolean();
        }

        // Shield
        boolean shieldOn = false;
        int shieldAvailable = 0, shieldTotal = 0;
        boolean shieldOverloaded = false;
        if ((fieldMask & MASK_SHIELD) != 0) {
            shieldOn = buf.readBoolean();
            shieldAvailable = buf.readInt();
            shieldTotal = buf.readInt();
            shieldOverloaded = buf.readBoolean();
        }

        // Assist/Flags
        boolean forceAssistOn = false, torqueAssistOn = false, forceAssistSuppressed = false;
        boolean antiGravityOn = false, autoLevelOn = false, warpPreparing = false, pendingWarp = false;
        String warpTargetName = "";
        double warpAlignmentX = 0, warpAlignmentY = 0;
        if ((fieldMask & MASK_ASSIST) != 0) {
            forceAssistOn = buf.readBoolean();
            torqueAssistOn = buf.readBoolean();
            forceAssistSuppressed = buf.readBoolean();
            antiGravityOn = buf.readBoolean();
            autoLevelOn = buf.readBoolean();
            warpPreparing = buf.readBoolean();
            pendingWarp = buf.readBoolean();
            warpTargetName = buf.readUtf();
            warpAlignmentX = buf.readDouble();
            warpAlignmentY = buf.readDouble();
        }

        // Weapons
        List<ActiveWeaponHudInfo> weaponInfos = new ArrayList<>();
        if ((fieldMask & MASK_WEAPON) != 0) {
            int size = buf.readInt();
            for (int i = 0; i < size; i++) {
                String name = buf.readUtf();
                int ct = buf.readInt();
                int mc = buf.readInt();
                boolean rc = buf.readBoolean();
                boolean fr = buf.readBoolean();
                weaponInfos.add(new ActiveWeaponHudInfo(name, ct, mc, rc, fr));
            }
        }

        // Input
        int channelEncode = 0;
        if ((fieldMask & MASK_INPUT) != 0) {
            channelEncode = buf.readInt();
        }

        // Radar
        String enemy = "", ally = "", lockedSlug = "";
        List<RadarShipInfo> radarShips = new ArrayList<>();
        if ((fieldMask & MASK_RADAR) != 0) {
            enemy = buf.readUtf();
            ally = buf.readUtf();
            lockedSlug = buf.readUtf();
            int size = buf.readInt();
            for (int i = 0; i < size; i++) {
                long id = buf.readVarLong();
                String slug = buf.readUtf();
                String dim = buf.readUtf();
                double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
                int idx = buf.readVarInt();
                radarShips.add(new RadarShipInfo(id, slug, dim, x, y, z, idx));
            }
        }

        return new Builder(pos, seatEntityId)
                .highFreq(shipFacing, shipUp, throttle, isViewLocked, shipSpeed,
                        structureCenterWorld, structureVelocityWorld, seatGForce)
                .resources(energyAvailable, energyTotal, fuelAvailable, fuelTotal,
                        e710Available, warpE710CostMb, warpE710Insufficient)
                .shield(shieldOn, shieldAvailable, shieldTotal, shieldOverloaded)
                .assists(forceAssistOn, torqueAssistOn, forceAssistSuppressed,
                        antiGravityOn, autoLevelOn, warpPreparing, pendingWarp,
                        warpTargetName, warpAlignmentX, warpAlignmentY)
                .weapons(weaponInfos)
                .input(channelEncode)
                .radar(enemy, ally, lockedSlug, radarShips)
                .build();
    }

    public static void handle(ControlSeatStateS2CPacket pkt, IPayloadContext context) {
        ClientHandler.handle(pkt, context);
    }

    private static final class ClientHandler {
        private static void handle(ControlSeatStateS2CPacket pkt, IPayloadContext context) {
            context.enqueueWork(() -> {
                Minecraft mc = Minecraft.getInstance();
                Player player = mc.player;
                if (player == null) return;

                ControlSeatClientData clientData =
                        ClientDataManager.getClientDataForSeat(player, pkt.pos, pkt.seatEntityId);
                if (clientData == null) {
                    LogUtils.getLogger().warn("ControlSeatStateS2C: no client data for seat {}", pkt.pos);
                    return;
                }

                int mask = pkt.fieldMask;

                if ((mask & MASK_HIGH_FREQ) != 0) {
                    clientData.updateShipVectors(pkt.shipFacing, pkt.shipUp);
                    clientData.setUserUUID(player.getUUID());
                    clientData.throttle = pkt.throttle;
                    clientData.applyServerViewLock(pkt.isViewLocked);
                    clientData.shipSpeed = pkt.shipSpeed;
                    clientData.structureCenterWorld = new Vector3d(pkt.structureCenterWorld);
                    clientData.structureVelocityWorld = new Vector3d(pkt.structureVelocityWorld);
                    clientData.seatGForce = pkt.seatGForce;
                }

                if ((mask & MASK_RESOURCE) != 0) {
                    clientData.energyavalible = pkt.energyAvailable;
                    clientData.energytotal = pkt.energyTotal;
                    clientData.fuelavalible = pkt.fuelAvailable;
                    clientData.fueltotal = pkt.fuelTotal;
                    clientData.e710avalible = pkt.e710Available;
                    clientData.warpE710CostMb = pkt.warpE710CostMb;
                    clientData.warpE710Insufficient = pkt.warpE710Insufficient;
                }

                if ((mask & MASK_SHIELD) != 0) {
                    clientData.shieldon = pkt.shieldOn;
                    clientData.shieldavalible = pkt.shieldAvailable;
                    clientData.shieldtotal = pkt.shieldTotal;
                    clientData.isShieldOverloaded = pkt.shieldOverloaded;
                }

                if ((mask & MASK_ASSIST) != 0) {
                    clientData.isforceassiston = pkt.forceAssistOn;
                    clientData.istorqueassiston = pkt.torqueAssistOn;
                    clientData.isForceAssistSuppressedByAccelerator = pkt.forceAssistSuppressedByAccelerator;
                    clientData.isantigravityon = pkt.antiGravityOn;
                    clientData.isAutoLevelOn = pkt.autoLevelOn;
                    clientData.isWarpPreparing = pkt.warpPreparing;
                    clientData.hasPendingWarpTeleport = pkt.pendingWarpTeleport;
                    clientData.warpTargetName = pkt.warpTargetName;
                    clientData.warpAlignmentControlX = pkt.warpAlignmentControlX;
                    clientData.warpAlignmentControlY = pkt.warpAlignmentControlY;
                }

                if ((mask & MASK_WEAPON) != 0) {
                    clientData.activeWeaponHudInfos = new ArrayList<>(pkt.activeWeaponHudInfos);
                }

                if ((mask & MASK_INPUT) != 0) {
                    clientData.channel1 = (pkt.channelEncode & 1) != 0;
                    clientData.channel2 = (pkt.channelEncode & 2) != 0;
                    clientData.channel3 = (pkt.channelEncode & 4) != 0;
                    clientData.channel4 = (pkt.channelEncode & 8) != 0;
                }

                if ((mask & MASK_RADAR) != 0) {
                    clientData.enemy = pkt.enemy;
                    clientData.ally = pkt.ally;
                    clientData.lockedenemyslug = pkt.lockedEnemySlug;
                    // RadarShipInfo -> Map 转换保持兼容性
                    java.util.Map<String, Object> shipsData = new java.util.LinkedHashMap<>();
                    for (RadarShipInfo ship : pkt.radarShips) {
                        java.util.Map<String, Object> attr = new java.util.HashMap<>();
                        attr.put("id", ship.id);
                        attr.put("slug", ship.slug);
                        attr.put("dimension", ship.dimension);
                        attr.put("x", ship.x);
                        attr.put("y", ship.y);
                        attr.put("z", ship.z);
                        attr.put("targetIndex", ship.targetIndex);
                        shipsData.put(String.valueOf(ship.id), attr);
                    }
                    clientData.shipsData = shipsData;
                }
            });
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // 便捷 getter
    public BlockPos getPos() { return pos; }
    public UUID getSeatEntityId() { return seatEntityId; }
    public int getFieldMask() { return fieldMask; }
    public Vector3d getShipFacing() { return shipFacing; }
    public Vector3d getShipUp() { return shipUp; }
    public int getThrottle() { return throttle; }
    public boolean isViewLocked() { return isViewLocked; }
    public double getShipSpeed() { return shipSpeed; }
    public Vector3d getStructureCenterWorld() { return structureCenterWorld; }
    public Vector3d getStructureVelocityWorld() { return structureVelocityWorld; }
    public double getSeatGForce() { return seatGForce; }
}