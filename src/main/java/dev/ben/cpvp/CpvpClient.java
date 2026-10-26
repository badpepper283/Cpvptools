package dev.ben.cpvp;

import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class CpvpClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            AutoTotem.tick(mc);
            AnchorBomb.tick(mc);
            AutoCrystal.tick(mc);
        });

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            AnchorBomb.onUse(player, level, hand, hit);
            return InteractionResult.PASS;
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(CpvpClient::handleChat);
    }

    static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[CPVP] " + text));
    }

    private static String onOff(boolean b) { return b ? "ACIK" : "KAPALI"; }

    /** Chat komutlari: .totem .crystal .anchor .anchorsafe .set <ayar> <deger> .durum */
    private static boolean handleChat(String msg) {
        if (!msg.startsWith(".")) return true;
        String[] a = msg.substring(1).trim().split("\\s+");
        switch (a[0].toLowerCase()) {
            case "totem" -> { AutoTotem.enabled = !AutoTotem.enabled; say("AutoTotem " + onOff(AutoTotem.enabled)); }
            case "crystal" -> { AutoCrystal.enabled = !AutoCrystal.enabled; say("AutoCrystal " + onOff(AutoCrystal.enabled)); }
            case "anchor" -> { AnchorBomb.enabled = !AnchorBomb.enabled; say("AnchorBomb " + onOff(AnchorBomb.enabled)); }
            case "anchorsafe" -> { AnchorBomb.safeMode = !AnchorBomb.safeMode; say("Anchor guvenlik kontrolu " + onOff(AnchorBomb.safeMode)); }
            case "durum" -> say("Totem " + onOff(AutoTotem.enabled) + " | Crystal " + onOff(AutoCrystal.enabled)
                    + " | Anchor " + onOff(AnchorBomb.enabled) + " (guvenlik " + onOff(AnchorBomb.safeMode) + ")"
                    + " | mindmg=" + AutoCrystal.minTargetDamage + " selfmax=" + AutoCrystal.maxSelfDamage
                    + " anchorselfmax=" + AnchorBomb.maxSelfDamage + " delay=" + AutoCrystal.delay);
            case "set" -> {
                if (a.length < 3) { say("Kullanim: .set mindmg|selfmax|anchorselfmax|delay <sayi>"); break; }
                try {
                    float v = Float.parseFloat(a[2]);
                    switch (a[1].toLowerCase()) {
                        case "mindmg" -> AutoCrystal.minTargetDamage = v;
                        case "selfmax" -> AutoCrystal.maxSelfDamage = v;
                        case "anchorselfmax" -> AnchorBomb.maxSelfDamage = v;
                        case "delay" -> AutoCrystal.delay = (int) v;
                        default -> { say("Bilinmeyen ayar"); return false; }
                    }
                    say(a[1] + " = " + v);
                } catch (NumberFormatException e) { say("Gecersiz sayi"); }
            }
            default -> { return true; } // bilinmeyen: normal chat gibi gonder
        }
        return false;
    }
}

/** Patlama hasari tahmini (hard zorluk). Blast protection yok sayilir => biraz kotumser (guvenli taraf). */
final class Damage {
    private Damage() {}

    public static float explosion(Level level, Vec3 center, LivingEntity e, float power) {
        double q = power * 2.0;
        double dist = Math.sqrt(e.distanceToSqr(center)) / q;
        if (dist > 1.0) return 0f;

        double impact = (1.0 - dist) * exposure(level, center, e);
        float dmg = (float) ((int) ((impact * impact + impact) / 2.0 * 7.0 * q + 1.0));
        dmg *= 1.5f; // hard

        float armor = (float) e.getArmorValue();
        float tough = (float) e.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float f = 2.0f + tough / 4.0f;
        float g = Mth.clamp(armor - dmg / f, armor * 0.2f, 20.0f);
        dmg *= 1.0f - g / 25.0f;

        MobEffectInstance res = e.getEffect(MobEffects.RESISTANCE);
        if (res != null) {
            int lvl = Math.min(res.getAmplifier() + 1, 5);
            dmg *= 1.0f - lvl * 0.2f;
        }
        return Math.max(dmg, 0f);
    }

    private static float exposure(Level level, Vec3 center, LivingEntity e) {
        AABB box = e.getBoundingBox();
        double dx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double dy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double dz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double offX = (1.0 - Math.floor(1.0 / dx) * dx) / 2.0;
        double offZ = (1.0 - Math.floor(1.0 / dz) * dz) / 2.0;
        if (dx < 0 || dy < 0 || dz < 0) return 0f;

        int hit = 0, total = 0;
        for (double x = 0; x <= 1.0; x += dx) {
            for (double y = 0; y <= 1.0; y += dy) {
                for (double z = 0; z <= 1.0; z += dz) {
                    Vec3 p = new Vec3(
                            Mth.lerp(x, box.minX, box.maxX) + offX,
                            Mth.lerp(y, box.minY, box.maxY),
                            Mth.lerp(z, box.minZ, box.maxZ) + offZ);
                    ClipContext ctx = new ClipContext(p, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e);
                    if (level.clip(ctx).getType() == HitResult.Type.MISS) hit++;
                    total++;
                }
            }
        }
        return total == 0 ? 0f : (float) hit / (float) total;
    }
}

final class Inv {
    private Inv() {}

    /** Hotbar (0-8) icinde esya ara. Yoksa -1. */
    public static int findHotbar(Inventory inv, Item item) {
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }

    /** Hotbar + ana envanter (0-35) icinde esya ara. Yoksa -1. */
    public static int findAny(Inventory inv, Item item) {
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }

    /** Inventory index -> InventoryMenu slot numarasi. */
    public static int menuSlot(int invSlot) {
        return invSlot < 9 ? invSlot + 36 : invSlot;
    }
}

final class AutoTotem {
    public static boolean enabled = true;
    private static int cooldown = 0;

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.gameMode == null) return;
        if (cooldown > 0) { cooldown--; return; }

        LocalPlayer p = mc.player;
        if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;

        int slot = Inv.findAny(p.getInventory(), Items.TOTEM_OF_UNDYING);
        if (slot < 0) return;

        // SWAP + buton 40 = offhand ile degistir
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, Inv.menuSlot(slot), 40, ContainerInput.SWAP, p);
        cooldown = 0;
    }
}

final class AutoCrystal {
    public static boolean enabled = false;
    public static float minTargetDamage = 6f;   // hedefe en az bu kadar hasar
    public static float maxSelfDamage = 6f;     // kendine en fazla bu kadar hasar
    public static int delay = 1;                // tick
    public static double targetRange = 10.0;
    public static double reach = 4.5;

    private static int timer = 0;

    public static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (timer > 0) { timer--; return; }

        LocalPlayer me = mc.player;
        ClientLevel level = mc.level;
        Player target = findTarget(level, me);
        if (target == null) return;

        // ---- 1) KIR ----
        EndCrystal broken = null;
        float bestScore = -9999f;
        List<EndCrystal> crystals = level.getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(reach + 1));
        for (EndCrystal c : crystals) {
            if (me.getEyePosition().distanceTo(c.getBoundingBox().getCenter()) > reach) continue;
            float dT = Damage.explosion(level, c.position(), target, 6f);
            float dS = Damage.explosion(level, c.position(), me, 6f);
            if (dT < minTargetDamage || !safe(me, dS)) continue;
            if (dT - dS > bestScore) { bestScore = dT - dS; broken = c; }
        }
        if (broken != null) {
            mc.gameMode.attack(me, broken);
            me.swing(InteractionHand.MAIN_HAND);
        }

        // ---- 2) KOY ----
        InteractionHand hand;
        if (me.getOffhandItem().is(Items.END_CRYSTAL)) {
            hand = InteractionHand.OFF_HAND;
        } else if (me.getMainHandItem().is(Items.END_CRYSTAL)) {
            hand = InteractionHand.MAIN_HAND;
        } else {
            int s = Inv.findHotbar(me.getInventory(), Items.END_CRYSTAL);
            if (s < 0) { timer = delay; return; }
            me.getInventory().setSelectedSlot(s);
            hand = InteractionHand.MAIN_HAND;
        }

        BlockPos tp = target.blockPosition();
        BlockPos bestPos = null;
        float best = -9999f;
        for (int x = -4; x <= 4; x++) {
            for (int y = -3; y <= 1; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = tp.offset(x, y, z);
                    if (!level.getBlockState(pos).is(Blocks.OBSIDIAN) && !level.getBlockState(pos).is(Blocks.BEDROCK)) continue;
                    BlockPos up = pos.above();
                    if (!level.getBlockState(up).isAir() || !level.getBlockState(up.above()).isAir()) continue;

                    Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                    if (me.getEyePosition().distanceTo(center) > reach) continue;

                    final EndCrystal justBroken = broken;
                    AABB box = new AABB(pos.getX(), pos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 3, pos.getZ() + 1);
                    boolean blocked = !level.getEntities(me, box, e -> e != justBroken && !e.isSpectator()).isEmpty();
                    if (blocked) continue;

                    float dT = Damage.explosion(level, center, target, 6f);
                    float dS = Damage.explosion(level, center, me, 6f);
                    if (dT < minTargetDamage || !safe(me, dS)) continue;
                    if (dT - dS > best) { best = dT - dS; bestPos = pos; }
                }
            }
        }

        if (bestPos != null) {
            Vec3 hit = new Vec3(bestPos.getX() + 0.5, bestPos.getY() + 1.0, bestPos.getZ() + 0.5);
            mc.gameMode.useItemOn(me, hand, new BlockHitResult(hit, Direction.UP, bestPos, false));
            me.swing(hand);
        }
        timer = delay;
    }

    private static boolean safe(LocalPlayer me, float selfDamage) {
        return selfDamage <= maxSelfDamage && selfDamage < me.getHealth() + me.getAbsorptionAmount() - 1f;
    }

    private static Player findTarget(ClientLevel level, LocalPlayer me) {
        Player best = null;
        double bestDist = targetRange * targetRange;
        for (Player p : level.players()) {
            if (p == me || !p.isAlive() || p.isSpectator()) continue;
            double d = me.distanceToSqr(p);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }
}

/**
 * Sen anchor'u koyarsin, mod hemen glowstone ile sarj edip patlatir.
 * Kendisi anchor KOYMAZ. safeMode aciksa kendine hasar cok yuksekse patlatmaz (varsayilan: kapali).
 * Not: sneak (shift) basiliyken blok etkilesimi calismaz, sneak yapmadan koy.
 */
final class AnchorBomb {
    public static boolean enabled = true;
    public static boolean safeMode = false;   // false = hasar ne olursa olsun patlat
    public static float maxSelfDamage = 6f;

    private static BlockPos pending = null;
    private static int age = 0;

    /** UseBlockCallback'ten cagrilir: oyuncu anchor tutarak bir bloga sag tiklarsa. */
    public static void onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!enabled || !(level instanceof ClientLevel)) return;
        if (!player.getItemInHand(hand).is(Items.RESPAWN_ANCHOR)) return;

        BlockPos clicked = hit.getBlockPos();
        BlockPos target = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
        pending = target;
        age = 0;
    }

    public static void tick(Minecraft mc) {
        if (!enabled || pending == null || mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (++age > 20) { pending = null; return; }

        ClientLevel level = mc.level;
        LocalPlayer me = mc.player;
        if (!level.getBlockState(pending).is(Blocks.RESPAWN_ANCHOR)) return; // henuz yerlesmedi

        BlockPos pos = pending;
        pending = null;

        if (level.dimension() == Level.NETHER) return; // Nether'da anchor patlamaz

        Vec3 center = Vec3.atCenterOf(pos);
        if (me.getEyePosition().distanceTo(center) > 4.5) return;

        float self = Damage.explosion(level, center, me, 5f);
        if (safeMode && (self > maxSelfDamage || self >= me.getHealth() + me.getAbsorptionAmount() - 1f)) {
            CpvpClient.say("Anchor patlatilmadi, kendine hasar ~" + Math.round(self * 10f) / 10f);
            return;
        }

        Inventory inv = me.getInventory();
        int prev = inv.getSelectedSlot();
        BlockHitResult hit = new BlockHitResult(center, Direction.UP, pos, false);

        // 1) Glowstone ile sarj et
        InteractionHand chargeHand;
        if (me.getOffhandItem().is(Items.GLOWSTONE)) {
            chargeHand = InteractionHand.OFF_HAND;
        } else {
            int g = Inv.findHotbar(inv, Items.GLOWSTONE);
            if (g < 0) { CpvpClient.say("Hotbar'da glowstone yok"); return; }
            inv.setSelectedSlot(g);
            chargeHand = InteractionHand.MAIN_HAND;
        }
        mc.gameMode.useItemOn(me, chargeHand, hit);
        me.swing(chargeHand);

        // 2) Glowstone OLMAYAN elle tekrar tikla => patlar
        int boom = prev;
        if (inv.getItem(prev).is(Items.GLOWSTONE)) {
            boom = -1;
            for (int i = 0; i < 9; i++) if (!inv.getItem(i).is(Items.GLOWSTONE)) { boom = i; break; }
            if (boom < 0) return;
        }
        inv.setSelectedSlot(boom);
        mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, hit);
        me.swing(InteractionHand.MAIN_HAND);
    }
}
