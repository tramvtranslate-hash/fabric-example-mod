package com.example;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mushroom Farm macro for Minecraft 26.2 / Fabric.
 *
 * The macro is client-side only. Movement and timing are intentionally handled
 * on the client thread; configuration is stored in config/macro_mod.properties.
 */
public final class ExampleMod implements ClientModInitializer {
    private static final String MOD_ID = "macro_mod";
    private static final int ROUNDS_PER_WARP = 3;
    private static final int MIN_PHASE_SECONDS = 1;
    private static final int MAX_PHASE_SECONDS = 3600;
    private static final long STUCK_THRESHOLD_NS = 3_500_000_000L;
    private static final long POSITION_SAMPLE_NS = 250_000_000L;
    private static final double MIN_MOVEMENT_DISTANCE_SQUARED = 0.0009; // 3 cm
    private static final long RECOVERY_JUMP_NS = 200_000_000L;
    private static final long RECOVERY_FORWARD_NS = 1_000_000_000L;
    private static final long RECOVERY_CHAT_DELAY_NS = 3_000_000_000L;

    private static KeyMapping toggleKey;
    private static KeyMapping menuKey;
    private static KeyMapping stopKey;

    private static boolean active;
    private static int leftSeconds = 94;
    private static int rightSeconds = 94;
    private static int phase; // 0 = left, 1 = right
    private static int completedRounds;
    private static long phaseStartedAt;
    private static long lastPositionSampleAt;
    private static long lastProgressAt;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static String status = "Hazir";
    private static RecoveryState recoveryState = RecoveryState.NONE;
    private static long recoveryStartedAt;
    private static boolean recoveryChatSent;

    private static final List<String> chatMessages = new ArrayList<>(
        List.of("wth", "?", "yooo", "sup")
    );
    private static final Path CONFIG_PATH =
        FabricLoader.getInstance().getConfigDir().resolve("macro_mod.properties");

    private enum RecoveryState {
        NONE,
        JUMP_AND_FORWARD,
        WAIT_BEFORE_CHAT
    }

    @Override
    public void onInitializeClient() {
        loadConfig();

        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "controls")
        );

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.macro_mod.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_9,
            category
        ));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.macro_mod.menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            category
        ));
        stopKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.macro_mod.stop",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (stopKey.consumeClick()) {
                stopMacro(client, "Acil durduruldu");
            }
            while (menuKey.consumeClick()) {
                client.gui.setScreen(new MacroScreen());
            }
            while (toggleKey.consumeClick()) {
                if (active) {
                    stopMacro(client, "Kullanici tarafindan durduruldu");
                } else {
                    startMacro(client);
                }
            }

            if (active) {
                updateMacro(client);
                // The normal game attack loop is suspended while a Screen is open.
                // Keep the attack loop alive for the macro while F7 is displayed.
                // Note: this needs in-game testing because GUI clicks and mining can overlap.
                if (client.screen instanceof MacroScreen && client.player != null
                    && client.level != null && recoveryState == RecoveryState.NONE) {
                    client.gameMode.continueAttack(false);
                }
            }
        });

        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(MOD_ID, "status_hud"),
            ExampleMod::renderHud
        );
    }

    private static void startMacro(Minecraft client) {
        if (client.player == null || client.level == null) {
            status = "Dunya icinde olmalisin";
            return;
        }

        releaseKeys(client);
        active = true;
        phase = 0;
        completedRounds = 0;
        recoveryState = RecoveryState.NONE;
        recoveryChatSent = false;
        long now = System.nanoTime();
        phaseStartedAt = now;
        lastProgressAt = now;
        lastPositionSampleAt = now;
        rememberPosition(client.player);
        status = "Makro aktif";
        tellPlayer(client, "[Mushroom Farm] Makro baslatildi.");
    }

    private static void stopMacro(Minecraft client, String reason) {
        active = false;
        recoveryState = RecoveryState.NONE;
        releaseKeys(client);
        status = reason;
        if (client.player != null) {
            tellPlayer(client, "[Mushroom Farm] " + reason + ".");
        }
    }

    private static void updateMacro(Minecraft client) {
        if (client.player == null || client.level == null) {
            active = false;
            recoveryState = RecoveryState.NONE;
            releaseKeys(client);
            status = "Dunya veya oyuncu yok";
            return;
        }

        long now = System.nanoTime();

        if (recoveryState != RecoveryState.NONE) {
            updateRecovery(client, now);
            return;
        }

        long phaseDuration = (long) (phase == 0 ? leftSeconds : rightSeconds)
            * 1_000_000_000L;

        if (now - phaseStartedAt >= phaseDuration) {
            releaseKeys(client);
            if (phase == 0) {
                phase = 1;
                status = phaseLabel();
            } else {
                phase = 0;
                completedRounds++;

                if (completedRounds >= ROUNDS_PER_WARP) {
                    client.player.connection.sendChat("/warp garden");
                    completedRounds = 0;
                    status = "Warp garden gonderildi";
                } else {
                    status = (completedRounds + 1) + ". Tur — Sol";
                }
            }

            // Start the next phase from the actual transition time.
            phaseStartedAt = now;
            lastProgressAt = now;
            lastPositionSampleAt = now;
            rememberPosition(client.player);
        }

        applyPhaseKeys(client);
        detectStuck(client, now);
    }

    private static void applyPhaseKeys(Minecraft client) {
        client.options.keyAttack.setDown(true);
        client.options.keyJump.setDown(false);

        if (phase == 0) {
            client.options.keyLeft.setDown(true);
            client.options.keyRight.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyUp.setDown(false);
        } else {
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(true);
            client.options.keyDown.setDown(true);
            client.options.keyUp.setDown(false);
        }
    }

    private static void detectStuck(Minecraft client, long now) {
        Player player = client.player;
        if (player == null || now - lastPositionSampleAt < POSITION_SAMPLE_NS) {
            return;
        }

        double dx = player.getX() - lastX;
        double dy = player.getY() - lastY;
        double dz = player.getZ() - lastZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;

        if (distanceSquared > MIN_MOVEMENT_DISTANCE_SQUARED) {
            lastProgressAt = now;
        }

        rememberPosition(player);
        lastPositionSampleAt = now;

        if (now - lastProgressAt >= STUCK_THRESHOLD_NS) {
            beginRecovery(client, now);
        }
    }

    private static void beginRecovery(Minecraft client, long now) {
        releaseKeys(client);
        recoveryState = RecoveryState.JUMP_AND_FORWARD;
        recoveryStartedAt = now;
        recoveryChatSent = false;
        status = "Engel algilandi - kurtarma deneniyor";
        tellPlayer(client, "[Mushroom Farm] Hareket engeli algilandi.");
    }

    private static void updateRecovery(Minecraft client, long now) {
        long elapsed = now - recoveryStartedAt;

        if (recoveryState == RecoveryState.JUMP_AND_FORWARD) {
            // Jump briefly, then keep walking forward until the one-second
            // recovery movement window ends.
            client.options.keyAttack.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyUp.setDown(elapsed < RECOVERY_FORWARD_NS);
            client.options.keyJump.setDown(elapsed < RECOVERY_JUMP_NS);

            if (elapsed >= RECOVERY_FORWARD_NS) {
                releaseKeys(client);
                recoveryState = RecoveryState.WAIT_BEFORE_CHAT;
                recoveryStartedAt = now;
                status = "Kurtarma basarisiz olabilir - mesaj bekleniyor";
            }
            return;
        }

        releaseKeys(client);
        if (now - recoveryStartedAt >= RECOVERY_CHAT_DELAY_NS && !recoveryChatSent) {
            recoveryChatSent = true;
            if (client.player != null && !chatMessages.isEmpty()) {
                String message = chatMessages.get(
                    ThreadLocalRandom.current().nextInt(chatMessages.size())
                );
                client.player.connection.sendChat(message);
            }
            stopMacro(client, "Engel sonrasi makro iptal edildi");
        }
    }

    private static void rememberPosition(Player player) {
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
    }

    private static void releaseKeys(Minecraft client) {
        if (client == null) return;
        client.options.keyAttack.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void tellPlayer(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(message));
        }
    }

    private static int remainingSeconds() {
        if (!active) return 0;
        long duration = (long) (phase == 0 ? leftSeconds : rightSeconds) * 1_000_000_000L;
        long remaining = Math.max(0L, duration - (System.nanoTime() - phaseStartedAt));
        return (int) ((remaining + 999_999_999L) / 1_000_000_000L);
    }

    private static String formatTime(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String phaseLabel() {
        return (completedRounds + 1) + ". Tur — " + (phase == 0 ? "Sol" : "Sag");
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        int x = 5;
        int y = Math.max(5, client.getWindow().getGuiScaledHeight() / 2 - 42);
        int width = 150;
        int height = 84;

        graphics.fill(x, y, x + width, y + height, 0xD9141D17);
        graphics.fill(x, y, x + width, y + 2, 0xFF79B982);
        graphics.outline(x, y, width, height, 0xFF385541);

        graphics.text(client.font, "MUSHROOM FARM", x + 8, y + 7, 0xFFA5D6A7, true);
        graphics.text(client.font, active ? phaseLabel() : "Makro kapali",
            x + 8, y + 23, 0xFFFFFFFF, true);
        graphics.text(client.font, "Sure: " + (active ? formatTime(remainingSeconds()) : "--:--"),
            x + 8, y + 38, 0xFFBDE8C3, true);
        graphics.text(client.font, "Tur: " + (completedRounds + 1) + " / " + ROUNDS_PER_WARP,
            x + 8, y + 52, 0xFFE3EDE4, true);
        graphics.text(client.font, status, x + 8, y + 67, 0xFFB7C6B9, true);
    }

    private static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) return;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
            leftSeconds = clamp(parseInt(properties.getProperty("leftSeconds"), 94),
                MIN_PHASE_SECONDS, MAX_PHASE_SECONDS);
            rightSeconds = clamp(parseInt(properties.getProperty("rightSeconds"), 94),
                MIN_PHASE_SECONDS, MAX_PHASE_SECONDS);

            String savedMessages = properties.getProperty("chatMessages", "wth|?|yooo|sup");
            List<String> loaded = new ArrayList<>();
            for (String item : savedMessages.split("\\|", -1)) {
                String cleaned = item.trim();
                if (!cleaned.isEmpty() && cleaned.length() <= 80) {
                    loaded.add(cleaned);
                }
            }
            chatMessages.clear();
            chatMessages.addAll(loaded);
        } catch (IOException exception) {
            System.err.println("[Mushroom Farm] Ayarlar okunamadi: " + exception.getMessage());
        }
    }

    private static void saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("leftSeconds", Integer.toString(leftSeconds));
        properties.setProperty("rightSeconds", Integer.toString(rightSeconds));
        properties.setProperty("chatMessages", String.join("|", chatMessages));

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (OutputStream output = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(output, "Mushroom Farm macro settings");
            }
        } catch (IOException exception) {
            System.err.println("[Mushroom Farm] Ayarlar kaydedilemedi: " + exception.getMessage());
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class MacroScreen extends Screen {
        private EditBox messageInput;

        protected MacroScreen() {
            super(Component.literal("Mushroom Farm"));
        }

        @Override
        public boolean isPauseScreen() {
            // Keep client ticks running while this screen is open.
            return false;
        }

        @Override
        protected void init() {
            int panelWidth = 420;
            int left = (this.width - panelWidth) / 2;
            int top = Math.max(10, (this.height - 330) / 2);

            int buttonWidth = 42;
            int rowY = top + 76;

            this.addRenderableWidget(Button.builder(Component.literal("−"), b -> {
                leftSeconds = clamp(leftSeconds - 1, MIN_PHASE_SECONDS, MAX_PHASE_SECONDS);
                saveConfig();
                rebuildMenu();
            }).bounds(left + 208, rowY, buttonWidth, 20).build());

            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                leftSeconds = clamp(leftSeconds + 1, MIN_PHASE_SECONDS, MAX_PHASE_SECONDS);
                saveConfig();
                rebuildMenu();
            }).bounds(left + 254, rowY, buttonWidth, 20).build());

            this.addRenderableWidget(Button.builder(Component.literal("−"), b -> {
                rightSeconds = clamp(rightSeconds - 1, MIN_PHASE_SECONDS, MAX_PHASE_SECONDS);
                saveConfig();
                rebuildMenu();
            }).bounds(left + 208, rowY + 30, buttonWidth, 20).build());

            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                rightSeconds = clamp(rightSeconds + 1, MIN_PHASE_SECONDS, MAX_PHASE_SECONDS);
                saveConfig();
                rebuildMenu();
            }).bounds(left + 254, rowY + 30, buttonWidth, 20).build());

            messageInput = new EditBox(
                this.font, left + 16, top + 194, 275, 20,
                Component.literal("Yeni sohbet mesaji")
            );
            messageInput.setMaxLength(80);
            messageInput.setHint(Component.literal("Mesaj yaz..."));
            this.addRenderableWidget(messageInput);

            this.addRenderableWidget(Button.builder(Component.literal("Ekle"), b -> {
                String message = messageInput.getValue().trim();
                if (!message.isEmpty() && !message.contains("|") && chatMessages.size() < 30) {
                    chatMessages.add(message);
                    saveConfig();
                    rebuildMenu();
                }
            }).bounds(left + 300, top + 194, 104, 20).build());

            this.addRenderableWidget(Button.builder(Component.literal("Son mesaji sil"), b -> {
                if (!chatMessages.isEmpty()) {
                    chatMessages.remove(chatMessages.size() - 1);
                    saveConfig();
                    rebuildMenu();
                }
            }).bounds(left + 16, top + 220, 130, 20).build());

            this.addRenderableWidget(Button.builder(
                Component.literal(active ? "Makroyu durdur" : "Makroyu baslat"),
                b -> {
                    Minecraft client = Minecraft.getInstance();
                    if (active) stopMacro(client, "Menuden durduruldu");
                    else startMacro(client);
                    rebuildMenu();
                }
            ).bounds(left + 16, top + 265, 190, 22).build());

            this.addRenderableWidget(Button.builder(Component.literal("ACIL DURDUR (F8)"), b -> {
                stopMacro(Minecraft.getInstance(), "Acil durduruldu");
                rebuildMenu();
            }).bounds(left + 212, top + 265, 192, 22).build());

            this.addRenderableWidget(Button.builder(Component.literal("Kapat"), b ->
                Minecraft.getInstance().gui.setScreen(null)
            ).bounds(left + 300, top + 300, 104, 20).build());
        }

        private void rebuildMenu() {
            this.clearWidgets();
            this.init();
        }

        @Override
        public void extractBackground(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
        ) {
            super.extractBackground(graphics, mouseX, mouseY, delta);

            int panelWidth = 420;
            int panelHeight = 330;
            int left = (this.width - panelWidth) / 2;
            int top = Math.max(10, (this.height - panelHeight) / 2);

            graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF0141D17);
            graphics.outline(left, top, panelWidth, panelHeight, 0xFF54765B);
            graphics.fill(left, top, left + panelWidth, top + 3, 0xFF79B982);
        }

        @Override
        public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
        ) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);

            int panelWidth = 420;
            int left = (this.width - panelWidth) / 2;
            int top = Math.max(10, (this.height - 330) / 2);

            graphics.text(this.font, "MUSHROOM FARM", left + 16, top + 12, 0xFFA5D6A7, true);
            graphics.text(this.font, "MAKRO KONTROL PANELI", left + 16, top + 27, 0xFFB8C8BA, true);
            graphics.text(this.font, "Durum: " + status, left + 220, top + 17, active ? 0xFF8FE39C : 0xFFD0D8D0, true);

            graphics.text(this.font, phaseLabel(), left + 16, top + 80, 0xFFFFFFFF, true);
            graphics.text(this.font, leftSeconds + " saniye", left + 125, top + 80, 0xFFBDE8C3, true);
            graphics.text(this.font, (completedRounds + 1) + ". Tur — Sag", left + 16, top + 110, 0xFFFFFFFF, true);
            graphics.text(this.font, rightSeconds + " saniye", left + 125, top + 110, 0xFFBDE8C3, true);

            graphics.text(this.font, "Rastgele sohbet mesajlari:", left + 16, top + 158, 0xFFA5D6A7, true);
            String joined = String.join("   ", chatMessages);
            if (joined.length() > 50) joined = joined.substring(0, 47) + "...";
            graphics.text(this.font, joined, left + 16, top + 174, 0xFFE3EDE4, true);

            graphics.text(this.font, "Engel algilaninca: zipla + 1 sn ileri, 3 sn bekle, mesaj at ve dur.",
                left + 16, top + 248, 0xFFB8C8BA, true);
            graphics.text(this.font, "Numpad 9: baslat/durdur   |   F7: panel   |   F8: acil durdur",
                left + 16, top + 290, 0xFFB8C8BA, true);
        }
    }
}
