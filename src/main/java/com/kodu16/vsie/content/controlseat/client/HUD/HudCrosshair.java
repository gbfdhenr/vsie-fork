package com.kodu16.vsie.content.controlseat.client.HUD;

import com.kodu16.vsie.content.controlseat.client.ControlSeatClientData;
import com.kodu16.vsie.content.controlseat.client.Input.ClientDataManager;
import com.kodu16.vsie.content.controlseat.entity.ControlSeatMountEntity;
import com.kodu16.vsie.foundation.ServerShipUtils;
import com.kodu16.vsie.network.controlseat.C2S.ControlSeatHudClickC2SPacket;
import com.kodu16.vsie.registries.ModNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Optional;

/**
 * World-space HUD crosshair system.
 * Casts a ray from camera through screen center to the HUD plane,
 * detects which UI element is under the crosshair, and sends click packets.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = "vsie", bus = EventBusSubscriber.Bus.GAME)
public class HudCrosshair {

    private static final Minecraft mc = Minecraft.getInstance();

    // HUD plane constants (must match ControlSeatWorldHudRenderer)
    private static final float HUD_VIEW_DISTANCE = 0.9F;
    private static final float HUD_EYE_HEIGHT_FROM_MOUNT = 1.87F;

    // Crosshair state
    private static HudElement hoveredElement = null;
    private static long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 150; // Prevent double-clicks

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Player player = mc.player;
        if (player == null || mc.level == null) {
            hoveredElement = null;
            return;
        }

        // Only run when riding a control seat
        if (!(player.getVehicle() instanceof ControlSeatMountEntity mountEntity)) {
            hoveredElement = null;
            return;
        }

        BlockPos seatPos = mountEntity.getBoundBlockPos();
        if (seatPos == null) {
            hoveredElement = null;
            return;
        }

        ControlSeatClientData data = ClientDataManager.getClientDataForSeat(player, seatPos);
        if (data == null) {
            hoveredElement = null;
            return;
        }

        // Compute HUD plane in world space
        Optional<HudPlane> hudPlaneOpt = computeHudPlane(mc.level, seatPos, data);
        if (hudPlaneOpt.isEmpty()) {
            hoveredElement = null;
            return;
        }
        HudPlane hudPlane = hudPlaneOpt.get();

        // Raycast from camera through screen center
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 cameraDir = mc.gameRenderer.getMainCamera().getLookVector();

        // Find intersection with HUD plane
        Optional<Vec3> intersectionOpt = raycastPlane(cameraPos, cameraDir, hudPlane);
        if (intersectionOpt.isEmpty()) {
            hoveredElement = null;
            return;
        }
        Vec3 intersectionWorld = intersectionOpt.get();

        // Transform intersection to HUD local coordinates (GUI pixels relative to HUD center)
        Vector3d localCoords = worldToHudLocal(intersectionWorld, hudPlane);
        float hudX = (float) localCoords.x;
        float hudY = (float) localCoords.y;

        // Test against UI element bounds
        HudElement hitElement = testElementBounds(hudX, hudY, data, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        hoveredElement = hitElement;

        // Handle left click
        boolean leftPressed = InputConstants.isKeyDown(mc.getWindow().getWindow(), InputConstants.MOUSE_LEFT);
        if (leftPressed && hitElement != null && System.currentTimeMillis() - lastClickTime > CLICK_COOLDOWN_MS) {
            lastClickTime = System.currentTimeMillis();
            sendHudClickPacket(seatPos, mountEntity.getUUID(), hitElement, data);
        }
    }

    /**
     * Represents the HUD plane in world space.
     */
    private record HudPlane(Vec3 origin, Vec3 normal, Vec3 right, Vec3 up, float worldScale) {
    }

    /**
     * Computes the HUD plane matching ControlSeatWorldHudRenderer logic.
     */
    private static Optional<HudPlane> computeHudPlane(net.minecraft.client.multiplayer.ClientLevel level, BlockPos seatPos, ControlSeatClientData data) {
        BlockEntity blockEntity = level.getBlockEntity(seatPos);
        if (!(blockEntity instanceof com.kodu16.vsie.content.controlseat.block.ControlSeatBlockEntity controlSeat)) {
            return Optional.empty();
        }

        BlockState state = level.getBlockState(seatPos);
        Vec3 seatOrigin = resolveRenderedSeatMountPosition(level, seatPos, state);
        Vec3 forward = resolveRenderedSeatForward(level, seatPos, state);
        Vec3 up = resolveRenderedSeatUp(level, seatPos);
        Vec3 right = resolveRenderedSeatRight(forward, up);

        if (seatOrigin == null || forward == null || up == null || right == null) {
            return Optional.empty();
        }

        // HUD anchor (same as ControlSeatWorldHudRenderer.resolveHudAnchor)
        Vec3 hudAnchor = seatOrigin.add(up.scale(HUD_EYE_HEIGHT_FROM_MOUNT)).add(forward.scale(HUD_VIEW_DISTANCE));

        // HUD plane normal faces the pilot (opposite to forward)
        Vec3 normal = forward.scale(-1);

        // World scale (same as ControlSeatWorldHudRenderer.computeHudWorldScale)
        int guiScaledHeight = mc.getWindow().getGuiScaledHeight();
        float safeHeight = Math.max(guiScaledHeight, 1);
        float fovDegrees = mc.options.fov().get().floatValue();
        double halfFovRadians = 0.5D * fovDegrees * net.minecraft.util.Mth.DEG_TO_RAD;
        float worldScale = (float) ((2.0D * HUD_VIEW_DISTANCE * Math.tan(halfFovRadians)) / safeHeight);

        return Optional.of(new HudPlane(hudAnchor, normal, right, up, worldScale));
    }

    /**
     * Raycast from camera through screen center to HUD plane.
     */
    private static Optional<Vec3> raycastPlane(Vec3 rayOrigin, Vec3 rayDir, HudPlane plane) {
        double denom = rayDir.dot(plane.normal);
        if (Math.abs(denom) < 1e-6) {
            return Optional.empty(); // Ray parallel to plane
        }
        double t = plane.origin.subtract(rayOrigin).dot(plane.normal) / denom;
        if (t < 0) {
            return Optional.empty(); // Intersection behind camera
        }
        return Optional.of(rayOrigin.add(rayDir.scale(t)));
    }

    /**
     * Transform world intersection point to HUD local coordinates (GUI pixels).
     * Inverse of: translate(hudAnchor) -> alignHudToSeatPlane -> scale(worldScale, -worldScale, worldScale)
     */
    private static Vector3d worldToHudLocal(Vec3 worldPoint, HudPlane plane) {
        // Translate to HUD anchor
        Vec3 relative = worldPoint.subtract(plane.origin);

        // Project onto HUD right/up axes (inverse of alignHudToSeatPlane)
        // alignHudToSeatPlane builds matrix: [right, orthoUp, -forward]
        // where orthoUp = right x forward
        Vec3 orthoUp = plane.right.cross(plane.normal.scale(-1)).normalize(); // right x forward

        double localX = relative.dot(plane.right);
        double localY = relative.dot(orthoUp);
        // Z is along -forward (into screen), we don't need it for 2D hit testing

        // Inverse scale (worldScale -> GUI pixels)
        float invScale = 1.0f / plane.worldScale;
        return new Vector3d(localX * invScale, -localY * invScale, 0); // Y inverted due to -worldScale in render
    }

    /**
     * Test HUD local coordinates against UI element bounds.
     * Coordinates match ControlSeatWorldHudRenderer.renderHudPanel layout.
     */
    private static HudElement testElementBounds(float hudX, float hudY, ControlSeatClientData data, int sw, int sh) {
        int centerX = sw / 2;
        int centerY = sh / 2;

        // Throttle area
        int throttleCenterX = centerX - (3 * centerX / 10);
        int throttleY = centerY + (centerY / 3);
        int throttleBarLeftX = throttleCenterX - 25;
        int throttleBarRightX = throttleCenterX + 25;
        int throttleBarTop = throttleY - 10;
        int throttleBarBottom = throttleY + 10;
        if (hudX >= throttleBarLeftX && hudX <= throttleBarRightX && hudY >= throttleBarTop && hudY <= throttleBarBottom) {
            return HudElement.THROTTLE;
        }

        // Switch grid (4x2 + warp + level + deepspace)
        int switchBaseX = throttleCenterX;
        int switchY = throttleY + 22;
        int switchGapX = 52;
        int switchGapY = 18;
        int leftSwitchX = switchBaseX - switchGapX / 2;
        int rightSwitchX = switchBaseX + switchGapX / 2;
        int btnW = 36; // MODE_BUTTON_WIDTH
        int btnH = 10; // MODE_BUTTON_HEIGHT

        // Row 0: Shield (left), Force (right)
        if (testButton(hudX, hudY, leftSwitchX, switchY, btnW, btnH)) return HudElement.SHIELD;
        if (testButton(hudX, hudY, rightSwitchX, switchY, btnW, btnH)) return HudElement.FORCE_ASSIST;

        // Row 1: Torque (left), AntiG (right)
        if (testButton(hudX, hudY, leftSwitchX, switchY + switchGapY, btnW, btnH)) return HudElement.TORQUE_ASSIST;
        if (testButton(hudX, hudY, rightSwitchX, switchY + switchGapY, btnW, btnH)) return HudElement.ANTI_GRAVITY;

        // Row 2: Warp (left), Level (right)
        if (testButton(hudX, hudY, leftSwitchX, switchY + switchGapY * 2, btnW, btnH)) return HudElement.WARP;
        if (testButton(hudX, hudY, rightSwitchX, switchY + switchGapY * 2, btnW, btnH)) return HudElement.AUTO_LEVEL;

        // Row 3: DeepSpace (left), HyperRelay (right) - if available
        if (com.kodu16.vsie.integration.deepspace.DeepSpaceHudBridge.available()) {
            if (testButton(hudX, hudY, leftSwitchX, switchY + switchGapY * 3, btnW, btnH)) return HudElement.DEEP_SPACE;
            if (testButton(hudX, hudY, rightSwitchX, switchY + switchGapY * 3, btnW, btnH)) return HudElement.HYPER_RELAY;
        }

        // Right info arc: channels 1-4
        int rightArcCenterX = centerX + (3 * centerX / 10);
        int channelY = throttleY - 16 + 28; // rightInfoY + 28
        int chBtnSize = 10;
        if (testButton(hudX, hudY, rightArcCenterX - 24, channelY, chBtnSize, chBtnSize)) return HudElement.CHANNEL_1;
        if (testButton(hudX, hudY, rightArcCenterX - 8, channelY, chBtnSize, chBtnSize)) return HudElement.CHANNEL_2;
        if (testButton(hudX, hudY, rightArcCenterX + 8, channelY, chBtnSize, chBtnSize)) return HudElement.CHANNEL_3;
        if (testButton(hudX, hudY, rightArcCenterX + 24, channelY, chBtnSize, chBtnSize)) return HudElement.CHANNEL_4;

        // Weapon cooldowns (right side) - simplified hit test
        int rightStatusArcRight = centerX + centerX / 20 + 68;
        int weaponStartX = rightStatusArcRight + 10;
        int weaponStartY = centerY - 18;
        int weaponLineHeight = 14;
        int weaponBarWidth = 52;
        for (int i = 0; i < data.activeWeaponHudInfos.size(); i++) {
            int rowY = weaponStartY + i * weaponLineHeight;
            if (hudX >= weaponStartX && hudX <= weaponStartX + weaponBarWidth &&
                hudY >= rowY - 5 && hudY <= rowY + 5) {
                return HudElement.WEAPON_COOLDOWN_BASE.plus(i);
            }
        }

        return null;
    }

    private static boolean testButton(float x, float y, int btnCenterX, int btnCenterY, int btnW, int btnH) {
        return x >= btnCenterX - btnW / 2f && x <= btnCenterX + btnW / 2f &&
               y >= btnCenterY - btnH / 2f && y <= btnCenterY + btnH / 2f;
    }

    /**
     * Send HUD click packet to server.
     */
    private static void sendHudClickPacket(BlockPos seatPos, java.util.UUID seatEntityId, HudElement element, ControlSeatClientData data) {
        ModNetworking.sendToServer(new ControlSeatHudClickC2SPacket(seatPos, seatEntityId, element.ordinal()));
    }

    // ===== Coordinate helpers (copied from ControlSeatWorldHudRenderer) =====

    private static Vec3 resolveRenderedSeatMountPosition(net.minecraft.client.multiplayer.ClientLevel level, BlockPos seatPos, BlockState state) {
        Vec3 localMountPos = ControlSeatMountEntity.getSeatMountPosition(seatPos, state);
        dev.ryanhcode.sable.sublevel.SubLevel subLevel = ServerShipUtils.getSubLevelAtBlockPos(level, seatPos);
        if (subLevel instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSubLevel) {
            return clientSubLevel.renderPose().transformPosition(localMountPos);
        }
        return subLevel == null ? localMountPos : subLevel.logicalPose().transformPosition(localMountPos);
    }

    private static Vec3 resolveRenderedSeatForward(net.minecraft.client.multiplayer.ClientLevel level, BlockPos seatPos, BlockState state) {
        Direction facing = state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING) : Direction.NORTH;
        Vec3 localForward = Vec3.atLowerCornerOf(facing.getOpposite().getNormal());
        return transformSeatAxis(level, seatPos, localForward);
    }

    private static Vec3 resolveRenderedSeatUp(net.minecraft.client.multiplayer.ClientLevel level, BlockPos seatPos) {
        return transformSeatAxis(level, seatPos, new Vec3(0.0D, 1.0D, 0.0D));
    }

    private static Vec3 resolveRenderedSeatRight(Vec3 forward, Vec3 up) {
        if (forward == null || up == null) return null;
        Vec3 right = forward.cross(up);
        if (right.lengthSqr() < 1.0E-8D) return null;
        return right.normalize();
    }

    private static Vec3 transformSeatAxis(net.minecraft.client.multiplayer.ClientLevel level, BlockPos seatPos, Vec3 localAxis) {
        dev.ryanhcode.sable.sublevel.SubLevel subLevel = ServerShipUtils.getSubLevelAtBlockPos(level, seatPos);
        if (subLevel instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSubLevel) {
            return normalize(clientSubLevel.renderPose().transformNormal(new Vector3d(localAxis.x, localAxis.y, localAxis.z)));
        }
        if (subLevel != null) {
            return normalize(subLevel.logicalPose().transformNormal(new Vector3d(localAxis.x, localAxis.y, localAxis.z)));
        }
        return localAxis.normalize();
    }

    private static Vec3 normalize(Vector3d vector) {
        Vector3d normalized = new Vector3d(vector);
        if (normalized.lengthSquared() < 1.0E-8D) {
            return new Vec3(0.0D, 0.0D, 0.0D);
        }
        normalized.normalize();
        return new Vec3(normalized.x, normalized.y, normalized.z);
    }

    /**
     * HUD element identifiers for click handling.
     * Ordinal values must match server-side handler.
     */
    public enum HudElement {
        NONE(-1),
        THROTTLE(0),
        SHIELD(1),
        FORCE_ASSIST(2),
        TORQUE_ASSIST(3),
        ANTI_GRAVITY(4),
        WARP(5),
        AUTO_LEVEL(6),
        DEEP_SPACE(7),
        HYPER_RELAY(8),
        CHANNEL_1(9),
        CHANNEL_2(10),
        CHANNEL_3(11),
        CHANNEL_4(12),
        WEAPON_COOLDOWN_BASE(100); // + index for individual weapons

        private final int baseOrdinal;

        HudElement(int baseOrdinal) {
            this.baseOrdinal = baseOrdinal;
        }

        public int ordinal() {
            return baseOrdinal;
        }

        public HudElement plus(int index) {
            return switch (this) {
                case WEAPON_COOLDOWN_BASE -> values()[baseOrdinal + index];
                default -> this;
            };
        }

        public static HudElement fromOrdinal(int ordinal) {
            for (HudElement e : values()) {
                if (e.baseOrdinal == ordinal || (e == WEAPON_COOLDOWN_BASE && ordinal >= 100 && ordinal < 100 + 10)) {
                    return e;
                }
            }
            return NONE;
        }
    }

    /**
     * Get currently hovered element (for crosshair rendering feedback).
     */
    public static HudElement getHoveredElement() {
        return hoveredElement;
    }
}