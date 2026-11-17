package dev.ben.cpvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * CPVP Tools 1.0.1 - Minecraft 1.21.11 / Fabric (Mojang mappings)
 * Komutlar (sohbete yaz): .killaura .combo .crystal .anchor .totem .sprint .durum
 */
public final class CpvpClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(CpvpClient::onTick);
        // ALLOW_CHAT: true = mesaj sunucuya gider, false = iptal. Komutlari iptal ediyoruz.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> !handleChat(message));
    }

    static void say(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§b[CPVP] §f" + s), false);
    }

    static String onOff(boolean b) {
        return b ? "§aACIK" : "§cKAPALI";
    }

    /** true dondururse mesaj bizim komutumuzdur ve sunucuya gonderilmez. */
    private static boolean handleChat(String msg) {
        if (!msg.startsWith(".")) return false;
        String[] a = msg.substring(1).trim().split("\\s+");
        switch (a[0].toLowerCase()) {
            case "killaura" -> { KillAura.enabled = !KillAura.enabled; say("KillAura " + onOff(KillAura.enabled)); }
            case "combo" -> { ComboBot.enabled = !ComboBot.enabled; say("Combo " + onOff(ComboBot.enabled)); }
            case "crystal" -> { AutoCrystal.enabled = !AutoCrystal.enabled; say("Crystal " + onOff(AutoCrystal.enabled)); }
            case "anchor" -> { AutoAnchor.enabled = !AutoAnchor.enabled; say("Anchor " + onOff(AutoAnchor.enabled)); }
            case "totem" -> { AutoTotem.enabled = !AutoTotem.enabled; say("Totem " + onOff(AutoTotem.enabled)); }
            case "sprint" -> { Sprint.enabled = !Sprint.enabled; say("Sprint " + onOff(Sprint.enabled)); }
            case "durum" -> say("KillAura " + onOff(KillAura.enabled) + " | Combo " + onOff(ComboBot.enabled)
                    + " | Crystal " + onOff(AutoCrystal.enabled) + " | Anchor " + onOff(AutoAnchor.enabled)
                    + " | Totem " + onOff(AutoTotem.enabled) + " | Sprint " + onOff(Sprint.enabled));
            default -> { return false; } // bilinmeyen ".xyz" normal mesaj gibi gider
        }
        return true;
    }

    private static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        Inv.busy = false; // her tick basinda sifirla: ayni tickte tek modul esya degistirir

        AutoTotem.tick(mc);
        Sprint.tick(mc);

        Player target = TargetManager.getTarget(mc);
        if (target == null) return;

        // Oncelik sirasi: anchor > crystal > combo > killaura
        AutoAnchor.tick(mc, target);
        AutoCrystal.tick(mc, target, false);
        ComboBot.tick(mc, target);
        KillAura.tick(mc, target);
    }
}

/* ------------------------------------------------------------------ */

final class Inv {
    /** Bu tick baska bir modul zaten bir sey yaptiysa true. */
    static boolean busy = false;

    static int findHotbar(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** Esyayi ele al (offhand'de varsa onu kullan). Basarili ise true. */
    static boolean equip(LocalPlayer p, Item item) {
        if (p.getOffhandItem().is(item) || p.getMainHandItem().is(item)) return true;
        int s = findHotbar(p, item);
        if (s < 0) return false;
        p.getInventory().setSelectedSlot(s);
        return true;
    }

    static InteractionHand handFor(LocalPlayer p, Item item) {
        return p.getMainHandItem().is(item) ? InteractionHand.MAIN_HAND
                : p.getOffhandItem().is(item) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /** Listedeki ilk bulunan esyayi ele alir (sirayla en iyiden kotuye). */
    static boolean equipBest(LocalPlayer p, Item[] prefs) {
        for (Item it : prefs) {
            int s = findHotbar(p, it);
            if (s >= 0) {
                if (!p.getMainHandItem().is(it)) p.getInventory().setSelectedSlot(s);
                return true;
            }
        }
        return false;
    }

    static boolean inRange(Vec3 from, Vec3 to, double max) {
        return from.distanceToSqr(to) <= max * max;
    }

    static boolean boxFree(ClientLevel w, AABB box) {
        return w.getEntities((Entity) null, box).isEmpty();
    }

    static void face(LocalPlayer p, Entity e) {
        Vec3 eye = p.getEyePosition();
        Vec3 t = e.getBoundingBox().getCenter();
        double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    static void clickTop(Minecraft mc, LocalPlayer p, InteractionHand hand, BlockPos clicked) {
        Vec3 hit = Vec3.atCenterOf(clicked).add(0, 0.5, 0);
        mc.gameMode.useItemOn(p, hand, new BlockHitResult(hit, Direction.UP, clicked, false));
        p.swing(hand);
    }
}

/* ------------------------------------------------------------------ */

final class TargetManager {
    static final double MAX_DIST = 10.0;

    static Player getTarget(Minecraft mc) {
        Player best = null;
        double bestD = MAX_DIST * MAX_DIST;
        for (Player other : mc.level.players()) {
            if (other == mc.player) continue;
            if (!other.isAlive() || other.isSpectator()) continue;
            double d = mc.player.distanceToSqr(other);
            if (d < bestD) {
                bestD = d;
                best = other;
            }
        }
        return best;
    }
}

/* ------------------------------------------------------------------ */

final class Sprint {
    static boolean enabled = true;

    static void tick(Minecraft mc) {
        if (!enabled) return;
        LocalPlayer p = mc.player;
        if (p.input.hasForwardImpulse() && !p.isShiftKeyDown() && !p.isUsingItem()
                && !p.horizontalCollision && p.getFoodData().getFoodLevel() > 6) {
            p.setSprinting(true);
        }
    }
}

/* ------------------------------------------------------------------ */

final class AutoTotem {
    static boolean enabled = true;
    static int wait = 0;

    static void tick(Minecraft mc) {
        if (!enabled) return;
        if (wait > 0) { wait--; return; }
        LocalPlayer p = mc.player;
        if (p.containerMenu != p.inventoryMenu) return; // baska konteyner aciksa dokunma
        if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;

        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                int slot = i < 9 ? 36 + i : i; // hotbar slotlari ekranda 36-44
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, 40, ClickType.SWAP, p);
                wait = 2; // eskiden 10 tickti; sunucu cevabi icin 2 yeter
                return;
            }
        }
    }
}

/* ------------------------------------------------------------------ */

final class KillAura {
    static boolean enabled = false;
    static boolean hasHit = false;

    static final double RANGE = 3.0;
    static final Item[] SWORDS = {
            Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD };
    static final Item[] AXES = {
            Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
            Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE };

    static void tick(Minecraft mc, Player target) {
        if (!enabled || Inv.busy) return;
        LocalPlayer p = mc.player;
        if (p.distanceToSqr(target) > RANGE * RANGE) return;

        // Hedef kalkan tutuyorsa balta, yoksa kilic. Bulunamazsa digerine dus.
        boolean ok = target.isBlocking()
                ? (Inv.equipBest(p, AXES) || Inv.equipBest(p, SWORDS))
                : (Inv.equipBest(p, SWORDS) || Inv.equipBest(p, AXES));
        if (!ok) return;

        // ESKI HATA: sabit 4 tick bekliyordu -> cok dusuk hasar. Artik saldiri gucu dolana kadar bekler.
        if (p.getAttackStrengthScale(0.5f) < 0.95f) return;

        Inv.face(p, target);
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        hasHit = true;
        Inv.busy = true;
    }
}

/* ------------------------------------------------------------------ */

final class AutoCrystal {
    static boolean enabled = false;
    static int cd = 0;

    /** Sunucunun kabul edecegi guvenli menzil (merkez mesafesi). */
    static final double RANGE = 3.8;

    /** @return bu tick bir sey yaptiysa true */
    static boolean tick(Minecraft mc, Player target, boolean force) {
        if ((!enabled && !force) || Inv.busy) return false;
        LocalPlayer p = mc.player;
        ClientLevel w = mc.level;

        // 1) ESKI HATA: kristal hic kirilmiyordu. Once menzildeki kristali patlat.
        List<EndCrystal> crystals = w.getEntitiesOfClass(
                EndCrystal.class, target.getBoundingBox().inflate(8.0), Entity::isAlive);
        EndCrystal bestCrystal = null;
        double bestD = Double.MAX_VALUE;
        for (EndCrystal c : crystals) {
            if (p.distanceTo(c) > RANGE) continue;
            double d = c.distanceToSqr(target);
            if (d < bestD) { bestD = d; bestCrystal = c; }
        }
        if (bestCrystal != null) {
            mc.gameMode.attack(p, bestCrystal);
            p.swing(InteractionHand.MAIN_HAND);
            Inv.busy = true;
            cd = 2;
            return true;
        }

        // 2) Yeni kristal koy. Zaten ortada kristal varsa (menzil disi dahil) spam yapma.
        if (cd > 0) { cd--; return false; }
        if (!crystals.isEmpty()) return false;
        if (Inv.findHotbar(p, Items.END_CRYSTAL) < 0 && !p.getOffhandItem().is(Items.END_CRYSTAL)) return false;

        BlockPos spot = findSpot(w, p, target);
        if (spot == null) return false;
        if (!Inv.equip(p, Items.END_CRYSTAL)) return false;

        InteractionHand hand = Inv.handFor(p, Items.END_CRYSTAL);
        Vec3 hit = Vec3.atCenterOf(spot).add(0, 0.5, 0);
        mc.gameMode.useItemOn(p, hand, new BlockHitResult(hit, Direction.UP, spot, false));
        p.swing(hand);
        Inv.busy = true;
        cd = 2; // sunucudan kristalin gelmesini bekle
        return true;
    }

    /** Hedefe en yakin, bize en uzak uygun obsidyen/bedrock blogu. (Basit sezgisel; gercek hasar hesabi degil.) */
    static BlockPos findSpot(ClientLevel w, LocalPlayer p, Player target) {
        BlockPos base = target.blockPosition();
        Vec3 eye = p.getEyePosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (!w.getBlockState(pos).is(Blocks.OBSIDIAN) && !w.getBlockState(pos).is(Blocks.BEDROCK)) continue;
                    BlockPos up = pos.above();
                    if (!w.getBlockState(up).isAir() || !w.getBlockState(up.above()).isAir()) continue;

                    // ESKI HATA: icinde oyuncu/esya olan yere kristal koymaya calisiyordu (oyun reddeder).
                    AABB crystalBox = new AABB(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1);
                    if (!Inv.boxFree(w, crystalBox)) continue;

                    Vec3 crystalCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                    if (!Inv.inRange(eye, crystalCenter, RANGE)) continue; // hem koyabil hem kirabil

                    double targetDist = crystalCenter.distanceTo(target.position());
                    double selfDist = crystalCenter.distanceTo(p.position());
                    if (targetDist > 6.0 || selfDist < 2.0) continue;

                    double score = targetDist - 0.5 * selfDist; // dusuk = iyi
                    if (score < bestScore) { bestScore = score; best = pos; }
                }
            }
        }
        return best;
    }
}

/* ------------------------------------------------------------------ */

final class AutoAnchor {
    static boolean enabled = false;
    static int stage = 0;
    static int cd = 0;
    static BlockPos anchorPos = null;

    static void tick(Minecraft mc, Player target) {
        if (!enabled || Inv.busy) return;
        LocalPlayer p = mc.player;
        ClientLevel w = mc.level;

        // Respawn anchor sadece Nether disinda patlar.
        if (w.dimension() == Level.NETHER) { stage = 0; return; }
        if (cd > 0) { cd--; return; }

        if (stage == 0) {
            if (Inv.findHotbar(p, Items.RESPAWN_ANCHOR) < 0 || Inv.findHotbar(p, Items.GLOWSTONE) < 0) return;
            BlockPos spot = findSpot(w, p, target);
            if (spot == null) return;
            if (!Inv.equip(p, Items.RESPAWN_ANCHOR)) return;
            Inv.clickTop(mc, p, InteractionHand.MAIN_HAND, spot.below());
            anchorPos = spot;
            stage = 1;
            cd = 1; // sunucunun blogu yerlestirmesini bekle
            Inv.busy = true;
            return;
        }

        if (anchorPos == null || !w.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
            stage = 0; anchorPos = null; return;
        }

        if (stage == 1) { // sarj et
            if (!Inv.equip(p, Items.GLOWSTONE)) { stage = 0; return; }
            interactAnchor(mc, p);
            stage = 2;
            cd = 1;
            Inv.busy = true;
            return;
        }

        if (stage == 2) { // patlat: elde glowstone OLMAMALI
            int slot = -1;
            for (int i = 0; i < 9; i++) {
                if (!p.getInventory().getItem(i).is(Items.GLOWSTONE)) { slot = i; break; }
            }
            if (slot < 0) { stage = 0; return; }
            p.getInventory().setSelectedSlot(slot);
            interactAnchor(mc, p);
            stage = 0;
            anchorPos = null;
            cd = 8;
            Inv.busy = true;
        }
    }

    static void interactAnchor(Minecraft mc, LocalPlayer p) {
        Vec3 hit = Vec3.atCenterOf(anchorPos);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, anchorPos, false));
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** Hedefin yaninda (kendi ayagi degil), altinda duz blok olan bos yer. */
    static BlockPos findSpot(ClientLevel w, LocalPlayer p, Player target) {
        BlockPos base = target.blockPosition();
        Vec3 eye = p.getEyePosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue; // hedefin icine konmaz
                BlockPos pos = base.offset(dx, 0, dz);
                BlockPos below = pos.below();
                if (!w.getBlockState(pos).isAir()) continue;
                if (!w.getBlockState(below).isSolid()) continue;
                if (!Inv.boxFree(w, new AABB(pos))) continue;

                Vec3 hit = Vec3.atCenterOf(below).add(0, 0.5, 0);
                if (!Inv.inRange(eye, hit, 4.4)) continue;

                Vec3 center = Vec3.atCenterOf(pos);
                double targetDist = center.distanceTo(target.position());
                double selfDist = center.distanceTo(p.position());
                if (selfDist < 3.0 || targetDist > 3.5) continue; // kendimize zarar vermeyelim

                double score = targetDist - selfDist;
                if (score < bestScore) { bestScore = score; best = pos; }
            }
        }
        return best;
    }
}

/* ------------------------------------------------------------------ */

/**
 * Vurus sonrasi kisa bir "patlama penceresi": KillAura bir vurus yapinca, Crystal acik olmasa bile
 * 20 tick boyunca kristal koy + kir. (Eski surum hedefin ayagina obsidyen koymaya calisip takiliyordu.)
 * Not: KillAura'nin acik olmasi gerekir, cunku pencereyi onun vurusu tetikler.
 */
final class ComboBot {
    static boolean enabled = false;
    static int burst = 0;

    static void tick(Minecraft mc, Player target) {
        if (!enabled) return;
        if (KillAura.hasHit) {
            KillAura.hasHit = false;
            burst = 20;
        }
        if (burst > 0) {
            burst--;
            AutoCrystal.tick(mc, target, true);
        }
    }
}
