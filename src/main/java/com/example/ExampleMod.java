
package com.example;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ExampleMod implements ClientModInitializer {

    private static KeyMapping toggleKey;
    private boolean active = false;
    private long tick = 0;

    private static final long PHASE_TICKS = 92L * 20L;
    private static final long ROUND_TICKS = PHASE_TICKS * 2L;
    private static final long TOTAL_TICKS = ROUND_TICKS * 3L;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("examplemod", "macro")
        );

        toggleKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.examplemod.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_KP_9,
                category
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                active = !active;
                tick = 0;
                resetKeys(client);

                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal(
                        active ? "[Makro] Başlatıldı!" : "[Makro] Durduruldu."
                    ));
                }
            }

            if (active && client.player != null) {
                runMacro(client);
            }
        });
    }

    private void runMacro(Minecraft client) {
        long current = tick % ROUND_TICKS;
        long completedRounds = tick / ROUND_TICKS;

        if (tick >= TOTAL_TICKS) {
            resetKeys(client);
            client.player.connection.sendChat("/warp garden");
            client.player.sendSystemMessage(
                Component.literal("[Makro] 3 tur tamamlandı.")
            );
            active = false;
            return;
        }

        client.options.keyAttack.setDown(true);

        if (current < PHASE_TICKS) {
            client.options.keyLeft.setDown(true);
            client.options.keyRight.setDown(false);
            client.options.keyBack.setDown(false);
        } else {
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(true);
            client.options.keyBack.setDown(true);
        }

        tick++;
    }

    private void resetKeys(Minecraft client) {
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyBack.setDown(false);
        client.options.keyAttack.setDown(false);
    }
}
