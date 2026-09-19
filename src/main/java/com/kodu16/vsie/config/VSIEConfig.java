package com.kodu16.vsie.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public class VSIEConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        COMMON = new Common(builder);
        COMMON_SPEC = builder.build();
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    public static class Common {
        public final ModConfigSpec.IntValue maxBulletLifetimeTicks;
        public final ModConfigSpec.IntValue maxUnloadedChunkWaitTicks;

        Common(ModConfigSpec.Builder builder) {
            builder.push("bullet");

            maxBulletLifetimeTicks = builder
                    .comment("Maximum lifetime of bullet entities in ticks (20 ticks = 1 second)")
                    .comment("Default: 10000 (~8.3 minutes). Increase for longer-range projectiles (e.g., 65536 blocks).")
                    .defineInRange("maxLifetimeTicks", 10000, 1, Integer.MAX_VALUE);

            maxUnloadedChunkWaitTicks = builder
                    .comment("Maximum ticks a bullet can continuously stay in unloaded chunks before being removed")
                    .comment("Default: 100 (5 seconds). Set to 0 to disable unloaded chunk timeout.")
                    .defineInRange("maxUnloadedChunkWaitTicks", 100, 0, Integer.MAX_VALUE);

            builder.pop();
        }
    }
}