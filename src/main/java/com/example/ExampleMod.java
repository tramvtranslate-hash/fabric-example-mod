
package com.example;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ExampleMod implements ClientModInitializer {

    private static final String MOD_ID = "macro_mod";
    private static final int ROUNDS_PER_WARP = 3;

    private static KeyMapping toggleKey;
    private static KeyMapping menuKey;
    private static KeyMapping stopKey;

    private static boolean active = false;
    private static int phaseASeconds = 94;
    private static int phaseBSeconds = 94;

    private static int phase = 0;
    private static int rounds = 0;
    private static long phaseStartedAt = 0L;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "controls")
        );

        toggleKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.macro_mod.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_KP_9,
                category
            )
        );

        menuKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.macro_mod.menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                category
            )
        );

        stopKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.macro_mod.stop",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (stopKey.consumeClick()) {
                stopMacro(client);
            }

            while (menuKey.consumeClick()) {
                client.gui.setScreen(new MacroScreen());
            }

            while (toggleKey.consumeClick()) {
                if (active) {
                    stopMacro(client);
                } else {
                    startMacro(client);
                }
            }

            if (active) {
                updateMacro(client);
            }
        });
    }

    private static void startMacro(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }

        releaseKeys(client);
        active = true;
        phase = 0;
        rounds = 0;
        phaseStartedAt = System.nanoTime();

        client.player.sendSystemMessage(
            Component.literal("[Makro] Baslatildi.")
        );
    }

    private static void stopMacro(Minecraft client) {
        active = false;
        releaseKeys(client);

        if (client.player != null) {
            client.player.sendSystemMessage(
                Component.literal("[Makro] Durduruldu; tuslar birakildi.")
            );
        }
    }

    private static void updateMacro(Minecraft client) {
        if (client.player == null || client.level == null) {
            stopMacro(client);
            return;
        }

        long now = System.nanoTime();
        long duration = (long) getPhaseSeconds() * 1_000_000_000L;

        if (now - phaseStartedAt >= duration) {
            releaseKeys(client);
            phaseStartedAt = now;

            if (phase == 0) {
                phase = 1;
            } else {
                phase = 0;
                rounds++;

                if (rounds >= ROUNDS_PER_WARP) {
                    client.player.connection.sendChat("/warp garden");
                    rounds = 0;
                }
            }
        }

        if (phase == 0) {
            client.options.keyAttack.setDown(true);
            client.options.keyLeft.setDown(true);
            client.options.keyRight.setDown(false);
            client.options.keyDown.setDown(false);
        } else {
            client.options.keyAttack.setDown(true);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(true);
            client.options.keyDown.setDown(true);
        }
    }

    private static int getPhaseSeconds() {
        return phase == 0 ? phaseASeconds : phaseBSeconds;
    }

    private static void releaseKeys(Minecraft client) {
        client.options.keyAttack.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyDown.setDown(false);
    }

    private static class MacroScreen extends Screen {

        protected MacroScreen() {
            super(Component.literal("Makro Ayarlari"));
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int y = this.height / 2 - 65;

            this.addRenderableWidget(
                Button.builder(
                    Component.literal("A Asamasi: " + phaseASeconds + " sn"),
                    button -> {
                        phaseASeconds = Math.max(5, phaseASeconds - 5);
                        refresh();
                    }
                ).bounds(center - 110, y, 220, 20).build()
            );

            this.addRenderableWidget(
                Button.builder(
                    Component.literal("D + S Asamasi: " + phaseBSeconds + " sn"),
                    button -> {
                        phaseBSeconds = Math.max(5, phaseBSeconds - 5);
                        refresh();
                    }
                ).bounds(center - 110, y + 25, 220, 20).build()
            );

            this.addRenderableWidget(
                Button.builder(
                    Component.literal("A Asamasina +5 sn"),
                    button -> {
                        phaseASeconds = Math.min(3600, phaseASeconds + 5);
                        refresh();
                    }
                ).bounds(center - 110, y + 50, 220, 20).build()
            );

            this.addRenderableWidget(
                Button.builder(
                    Component.literal("D + S Asamasina +5 sn"),
                    button -> {
                        phaseBSeconds = Math.min(3600, phaseBSeconds + 5);
                        refresh();
                    }
                ).bounds(center - 110, y + 75, 220, 20).build()
            );

            this.addRenderableWidget(
                Button.builder(
                    Component.literal(active ? "Makroyu Durdur" : "Makroyu Baslat"),
                    button -> {
                        Minecraft client = Minecraft.getInstance();

                        if (active) {
                            stopMacro(client);
                        } else {
                            startMacro(client);
                        }

                        refresh();
                    }
                ).bounds(center - 110, y + 105, 220, 20).build()
            );

            this.addRenderableWidget(
                Button.builder(
                    Component.literal("Kapat"),
                    button -> Minecraft.getInstance().gui.setScreen(null)
                ).bounds(center - 110, y + 130, 220, 20).build()
            );
        }

        private void refresh() {
            this.clearWidgets();
            this.init();
        }

        @Override
        public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
        ) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);

            graphics.text(
                this.font,
                "Makro: " + (active ? "AKTIF" : "KAPALI"),
                this.width / 2 - 45,
                this.height / 2 - 90,
                0xFFFFFFFF,
                true
            );

            graphics.text(
                this.font,
                "Tur: " + (rounds + 1) + "/" + ROUNDS_PER_WARP,
                this.width / 2 - 30,
                this.height / 2 + 90,
                0xFFFFFFFF,
                true
            );

            graphics.text(
                this.font,
                "F8: Acil durdur | F7: Menu | Numpad 9: Ac/Kapat",
                this.width / 2 - 145,
                this.height / 2 + 105,
                0xFFFFCC55,
                true
            );
        }
    }
}
