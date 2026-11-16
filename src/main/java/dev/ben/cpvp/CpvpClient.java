package dev.ben.cpvp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class CpvpClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // HER TICK'TE ÇALIŞAN OTOMASYONLAR
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.level == null || mc.gameMode == null) return;
            
            Player target = TargetManager.getTarget(mc);
            
            AutoTotem.tick(mc);
            Sprint.tick(mc);
            
            if (target != null) {
                KillAura.tick(mc, target);    // Kılıçla vurur
                ComboBot.tick(mc, target);    // Vurduktan sonra obsi+kristal kombosu yapar
                AutoCrystal.tick(mc, target); // Etraftaki boş obsidyenlere kristal koyar
                AutoAnchor.tick(mc, target);  // Etrafa anchor koyup patlatır
            }
        });

        // TRACERS (Duvarların arkasından çizgi çekme)
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (Minecraft.getInstance().player != null && Minecraft.getInstance().level != null) {
                Tracers.render(context.matrixStack(), context.consumers(), Minecraft.getInstance().level);
            }
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(CpvpClient::handleChat);
    }

    static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§b[CPVP] §f" + text), true);
    }

    private static boolean handleChat(String msg) {
        if (!msg.startsWith(".")) return true;
        String[] a = msg.substring(1).trim().split("\\s+");
        switch (a[0].toLowerCase()) {
            case "killaura" -> { KillAura.enabled = !KillAura.enabled; say("KillAura " + (KillAura.enabled ? "§aACIK" : "§cKAPALI")); }
            case "combo" -> { ComboBot.enabled = !ComboBot.enabled; say("ComboBot " + (ComboBot.enabled ? "§aACIK" : "§cKAPALI")); }
            case "crystal" -> { AutoCrystal.enabled = !AutoCrystal.enabled; say("AutoCrystal " + (AutoCrystal.enabled ? "§aACIK" : "§cKAPALI")); }
            case "anchor" -> { AutoAnchor.enabled = !AutoAnchor.enabled; say("AutoAnchor " + (AutoAnchor.enabled ? "§aACIK" : "§cKAPALI")); }
            case "totem" -> { AutoTotem.enabled = !AutoTotem.enabled; say("AutoTotem " + (AutoTotem.enabled ? "§aACIK" : "§cKAPALI")); }
            case "tracers" -> { Tracers.enabled = !Tracers.enabled; say("Tracers " + (Tracers.enabled ? "§aACIK" : "§cKAPALI")); }
            case "sprint" -> { Sprint.enabled = !Sprint.enabled; say("Sprint " + (Sprint.enabled ? "§aACIK" : "§cKAPALI")); }
            case "durum" -> say("K.Aura:" + KillAura.enabled + " Combo:" + ComboBot.enabled + " Crys:" + AutoCrystal.enabled + " Anc:" + AutoAnchor.enabled + " Totem:" + AutoTotem.enabled);
            default -> { return true; }
        }
        return false;
    }
}

// ==========================================
// HEDEF BULUCU
// ==========================================
class TargetManager {
    public static Player getTarget(Minecraft mc) {
        Player best = null;
        double bestDist = 100.0;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double d = mc.player.distanceToSqr(p);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }
}

// ==========================================
// KILLAURA (Kılıç/Balta Değişimi ve Vurma)
// ==========================================
final class KillAura {
    public static boolean enabled = true;
    public static int cooldown = 0;
    public static boolean hasHit = false;

    public static void tick(Minecraft mc, Player target) {
        if (!enabled || cooldown > 0) { if(cooldown > 0) cooldown--; return; }

        if (mc.player.distanceToSqr(target) <= 9.0) {
            if (target.isBlocking() && !mc.player.getMainHandItem().is(Items.NETHERITE_AXE)) {
                int axeSlot = Inv.findHotbar(mc.player.getInventory(), Items.NETHERITE_AXE);
                if (axeSlot != -1) mc.player.getInventory().setSelectedSlot(axeSlot);
            } else if (!target.isBlocking() && !mc.player.getMainHandItem().is(Items.NETHERITE_SWORD)) {
                int swordSlot = Inv.findHotbar(mc.player.getInventory(), Items.NETHERITE_SWORD);
                if (swordSlot != -1) mc.player.getInventory().setSelectedSlot(swordSlot);
            }

            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
            
            hasHit = true; 
            cooldown = 4; 
        }
    }
}

// ==========================================
// COMBO BOT (Vurduktan sonra Adam Yere Düşmeden Patlatma)
// ==========================================
final class ComboBot {
    public static boolean enabled = true;
    private static int state = 0;
    private static int timer = 0; 
    
    public static void tick(Minecraft mc, Player target) {
        if (!enabled) return;

        if (KillAura.hasHit && state == 0) {
            state = 1;
            KillAura.hasHit = false;
            timer = 0;
        }

        if (state == 0) return;
        if (timer > 0) { timer--; return; }

        Inventory inv = mc.player.getInventory();
        int obsSlot = Inv.findHotbar(inv, Items.OBSIDIAN);
        int crysSlot = Inv.findHotbar(inv, Items.END_CRYSTAL);
        
        if (obsSlot == -1 || crysSlot == -1) { state = 0; return; }

        BlockPos targetPos = target.blockPosition();
        BlockPos placePos = targetPos.below(); 
        
        if (state == 1) {
            inv.setSelectedSlot(obsSlot);
            if (mc.level.getBlockState(placePos).isSolid()) {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(placePos), Direction.UP, placePos, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
                state = 2;
                timer = 1;
            } else state = 0; 
        } 
        else if (state == 2) {
            inv.setSelectedSlot(crysSlot);
            if (mc.level.getBlockState(placePos).is(Blocks.OBSIDIAN)) {
                BlockPos crysPos = placePos.above();
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(crysPos), Direction.UP, placePos, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
                state = 3;
                timer = 1;
            } else state = 0; 
        }
        else if (state == 3) {
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(5))) {
                if (c.blockPosition().closerThan(targetPos, 3.0)) {
                    mc.gameMode.attack(mc.player, c);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    break;
                }
            }
            state = 0; 
        }
    }
}

// ==========================================
// AUTO CRYSTAL (Etraftaki Obsidyenlere Kristal Koyar)
// ==========================================
final class AutoCrystal {
    public static boolean enabled = true;
    private static int cd = 0;

    public static void tick(Minecraft mc, Player target) {
        if (!enabled || cd > 0) { if(cd>0) cd--; return; }
        LocalPlayer me = mc.player;

        if (!me.getMainHandItem().is(Items.END_CRYSTAL) && !me.getOffhandItem().is(Items.END_CRYSTAL)) {
            int s = Inv.findHotbar(me.getInventory(), Items.END_CRYSTAL);
            if (s != -1) me.getInventory().setSelectedSlot(s); else return;
        }

        BlockPos tp = target.blockPosition();
        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 1; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos pos = tp.offset(x, y, z);
                    if (mc.level.getBlockState(pos).is(Blocks.OBSIDIAN) || mc.level.getBlockState(pos).is(Blocks.BEDROCK)) {
                        BlockPos up = pos.above();
                        if (mc.level.getBlockState(up).isAir() && mc.level.getBlockState(up.above()).isAir()) {
                            Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                            if (me.getEyePosition().distanceTo(center) > 4.5) continue;
                            
                            InteractionHand hand = me.getOffhandItem().is(Items.END_CRYSTAL) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                            mc.gameMode.useItemOn(me, hand, new BlockHitResult(center, Direction.UP, pos, false));
                            me.swing(hand);
                            cd = 3;
                            return;
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// AUTO ANCHOR (Hedefin yanına Anchor Koyar ve Patlatır)
// ==========================================
final class AutoAnchor {
    public static boolean enabled = true;
    private static int cd = 0;

    public static void tick(Minecraft mc, Player target) {
        if (!enabled || cd > 0) { if(cd>0) cd--; return; }
        
        Inventory inv = mc.player.getInventory();
        int anchorSlot = Inv.findHotbar(inv, Items.RESPAWN_ANCHOR);
        int glowSlot = Inv.findHotbar(inv, Items.GLOWSTONE);
        
        if (anchorSlot == -1 || glowSlot == -1) return;
        if (mc.player.getEyePosition().distanceTo(target.getEyePosition()) > 5.0) return;

        BlockPos targetPos = target.blockPosition();
        BlockPos placePos = null;

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos p = targetPos.offset(x, 0, z);
                if (mc.level.getBlockState(p).canBeReplaced() && mc.level.getBlockState(p.below()).isSolid()) {
                    placePos = p; break;
                }
            }
            if (placePos != null) break;
        }

        if (placePos != null) {
            int prevSlot = inv.getSelectedSlot();
            
            inv.setSelectedSlot(anchorSlot);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(placePos.below()), Direction.UP, placePos.below(), false));
            mc.player.swing(InteractionHand.MAIN_HAND);

            inv.setSelectedSlot(glowSlot);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(placePos), Direction.UP, placePos, false));
            mc.player.swing(InteractionHand.MAIN_HAND);

            int boomSlot = (prevSlot == glowSlot || prevSlot == anchorSlot) ? 0 : prevSlot;
            inv.setSelectedSlot(boomSlot);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(placePos), Direction.UP, placePos, false));
            mc.player.swing(InteractionHand.MAIN_HAND);

            inv.setSelectedSlot(prevSlot);
            cd = 10; 
        }
    }
}

// ==========================================
// YARDIMCI SINIFLAR
// ==========================================
final class AutoTotem {
    public static boolean enabled = true;
    private static int waitTicks = 0;
    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.gameMode == null) return;
        LocalPlayer p = mc.player;
        if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) { waitTicks = 0; return; }
        if (waitTicks > 0) { waitTicks--; return; }
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                int menuSlot = i < 9 ? i + 36 : i;
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot, 40, ClickType.SWAP, p);
                waitTicks = 10;
                return;
            }
        }
    }
}

final class Sprint {
    public static boolean enabled = true;
    public static void tick(Minecraft mc) {
        if (enabled && mc.player != null && mc.player.input.forwardImpulse > 0) {
            mc.player.setSprinting(true);
        }
    }
}

final class Tracers {
    public static boolean enabled = true;
    public static void render(PoseStack matrices, net.minecraft.client.renderer.MultiBufferSource vertexConsumers, ClientLevel level) {
        if (!enabled || Minecraft.getInstance().player == null) return;
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderType.lines());
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = matrices.last().pose();
        for (Player player : level.players()) {
            if (player == Minecraft.getInstance().player || !player.isAlive()) continue;
            Vec3 target = player.getBoundingBox().getCenter();
            Vec3 start = Minecraft.getInstance().player.getEyePosition();
            buffer.vertex(matrix, (float)start.x, (float)start.y, (float)start.z).color(255, 0, 0, 255).endVertex();
            buffer.vertex(matrix, (float)target.x, (float)target.y, (float)target.z).color(255, 0, 0, 255).endVertex();
        }
        matrices.popPose();
    }
}

final class Inv {
    public static int findHotbar(Inventory inv, Item item) {
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }
                }
