package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class ExampleMod implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private boolean active = false;
    private long startTick = 0;

    private static final long PHASE_1_TICKS = 1800; // 1.5 dk (90s * 20 tick)
    private static final long PHASE_2_TICKS = 1840; // 1 dk 32s (92s * 20 tick)
    private static final long TOTAL_ROUND_TICKS = PHASE_1_TICKS + PHASE_2_TICKS; // 3640 tick

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Makroyu Başlat/Durdur",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_KP_9,
                "Makro Modu"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (toggleKey.wasPressed()) {
                active = !active;
                startTick = 0;

                if (!active) {
                    resetKeys(client);
                    client.player.sendMessage(Text.literal("§c[Makro] Durduruldu."), false);
                } else {
                    client.player.sendMessage(Text.literal("§a[Makro] Başlatıldı!"), false);
                }
            }

            if (active) {
                runMacroLogic(client);
            }
        });
    }

    private void runMacroLogic(MinecraftClient client) {
        startTick++;

        long currentRoundTick = (startTick - 1) % TOTAL_ROUND_TICKS;
        long completedRounds = (startTick - 1) / TOTAL_ROUND_TICKS;

        if (completedRounds >= 3) {
            resetKeys(client);
            if (client.player != null) {
                client.player.networkHandler.sendChatMessage("/warp garden");
                client.player.sendMessage(Text.literal("§b[Makro] 3 tur tamamlandı, /warp garden gönderildi."), false);
            }
            active = false;
            return;
        }

        if (currentRoundTick < PHASE_1_TICKS) {
            client.options.leftKey.setPressed(true);       // A tuşu
            client.options.attackKey.setPressed(true);     // Sol tık
            client.options.rightKey.setPressed(false);     // D tuşu serbest
            client.options.backKey.setPressed(false);      // S tuşu serbest
        } else {
            client.options.leftKey.setPressed(false);      // A tuşu serbest
            client.options.attackKey.setPressed(true);     // Sol tık devam
            client.options.rightKey.setPressed(true);      // D tuşu
            client.options.backKey.setPressed(true);       // S tuşu
        }
    }

    private void resetKeys(MinecraftClient client) {
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }
}
