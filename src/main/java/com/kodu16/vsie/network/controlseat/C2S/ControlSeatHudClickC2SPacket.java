package com.kodu16.vsie.network.controlseat.C2S;

import com.kodu16.vsie.content.controlseat.block.ControlSeatBlockEntity;
import com.kodu16.vsie.content.controlseat.server.ControlSeatServerData;
import com.kodu16.vsie.content.controlseat.server.ServerShipHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S packet for HUD crosshair clicks.
 * Sent when player clicks a HUD UI element with the center-screen crosshair.
 */
public class ControlSeatHudClickC2SPacket implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ControlSeatHudClickC2SPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vsie", "controlseat_c2s_hudclick"));
    public static final StreamCodec<FriendlyByteBuf, ControlSeatHudClickC2SPacket> STREAM_CODEC =
            CustomPacketPayload.codec(ControlSeatHudClickC2SPacket::encode, ControlSeatHudClickC2SPacket::decode);

    private static final Logger LOGGER = LogUtils.getLogger();

    public final BlockPos pos;
    public final UUID seatEntityId;
    public final int elementId; // Matches HudElement.ordinal()

    public ControlSeatHudClickC2SPacket(BlockPos pos, UUID seatEntityId, int elementId) {
        this.pos = pos;
        this.seatEntityId = seatEntityId;
        this.elementId = elementId;
    }

    public static void encode(ControlSeatHudClickC2SPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeUUID(pkt.seatEntityId);
        buf.writeVarInt(pkt.elementId);
    }

    public static ControlSeatHudClickC2SPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        UUID seatEntityId = buf.readUUID();
        int elementId = buf.readVarInt();
        return new ControlSeatHudClickC2SPacket(pos, seatEntityId, elementId);
    }

    public static void handle(ControlSeatHudClickC2SPacket pkt, IPayloadContext context) {
        handle(pkt, () -> new NetworkEvent.Context(context));
    }

    public static void handle(ControlSeatHudClickC2SPacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;

            ServerLevel level = sender.serverLevel();
            BlockPos pos = pkt.pos;
            ControlSeatBlockEntity controlSeat = ControlSeatPacketResolver.resolve(sender, pos, pkt.seatEntityId);
            if (controlSeat == null) {
                return;
            }

            ControlSeatServerData serverData = controlSeat.getServerData();

            // Handle HUD element clicks
            switch (pkt.elementId) {
                case 0 -> { // THROTTLE - not directly clickable, ignore
                }
                case 1 -> { // SHIELD
                    if (serverData.shieldcooldowntime <= 0.0D) {
                        serverData.isshieldon = !serverData.isshieldon;
                    } else {
                        serverData.isshieldon = true;
                    }
                }
                case 2 -> { // FORCE_ASSIST
                    if (!serverData.isForceAssistSuppressedByAccelerator) {
                        serverData.isforceassiston = !serverData.isforceassiston;
                    } else {
                        serverData.isforceassiston = false;
                    }
                }
                case 3 -> { // TORQUE_ASSIST
                    serverData.istorqueassiston = !serverData.istorqueassiston;
                }
                case 4 -> { // ANTI_GRAVITY
                    serverData.isantigravityon = !serverData.isantigravityon;
                }
                case 5 -> { // WARP - trigger warp preparation or cancel
                    if (!serverData.isWarpPreparing && !serverData.hasPendingWarpTeleport) {
                        // Start warp preparation (same as KEY_START_WARP)
                        serverData.isWarpPreparing = true;
                        serverData.warpAlignmentControlX = 0;
                        serverData.warpAlignmentControlY = 0;
                    } else if (serverData.isWarpPreparing) {
                        // Cancel warp preparation
                        serverData.isWarpPreparing = false;
                        serverData.warpTargetName = "";
                    }
                }
                case 6 -> { // AUTO_LEVEL
                    if (!serverData.isWarpPreparing && !serverData.hasPendingWarpTeleport) {
                        serverData.isAutoLevelOn = !serverData.isAutoLevelOn;
                    }
                }
                case 7 -> { // DEEP_SPACE
                    // Handled by DeepSpace integration if available
                    if (com.kodu16.vsie.integration.deepspace.DeepSpaceHudBridge.available()) {
                        com.kodu16.vsie.integration.deepspace.DeepSpaceHudRenderer.setEnabled(
                                !com.kodu16.vsie.integration.deepspace.DeepSpaceHudRenderer.isEnabled()
                        );
                    }
                }
                case 8 -> { // HYPER_RELAY
                    if (com.kodu16.vsie.integration.deepspace.DeepSpaceHudBridge.available()) {
                        com.kodu16.vsie.integration.deepspace.DeepSpaceHudBridge.requestHyperRelayJump();
                    }
                }
                case 9 -> serverData.channel1 = !serverData.getChannel1(); // CHANNEL_1
                case 10 -> serverData.channel2 = !serverData.getChannel2(); // CHANNEL_2
                case 11 -> serverData.channel3 = !serverData.getChannel3(); // CHANNEL_3
                case 12 -> serverData.channel4 = !serverData.getChannel4(); // CHANNEL_4
                default -> {
                    // Weapon cooldown elements (100+)
                    if (pkt.elementId >= 100 && pkt.elementId < 110) {
                        int weaponIndex = pkt.elementId - 100;
                        if (weaponIndex >= 0 && weaponIndex < serverData.activeWeaponHudInfos.size()) {
                            // Click on weapon cooldown - could trigger manual fire or mode switch
                            // For now, toggle fire ready state for visual feedback
                            var weaponInfo = serverData.activeWeaponHudInfos.get(weaponIndex);
                            // Weapon fire is handled by mouseLpress in ControlSeatC2SPacket
                            // This click could be used for weapon mode switching in the future
                        }
                    }
                }
            }

            serverData.refreshWeaponChannelEncode();
            controlSeat.setChanged();
        });
        ctx.setPacketHandled(true);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}