package dev.ben.cpvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * CPVP Tools 1.0.1 - Minecraft 1.21.11 / Fabric (Yarn mappings)
 * Komutlar (sohbete yaz): .killaura .combo .crystal .anchor .totem .sprint .durum
 */
public final class CpvpClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(CpvpClient::onTick);
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> !handleChat(message));
    }

    static void say(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("§7[CPVP] " + s), false);
    }

    static String onOff(boolean b) {
        return b ? "§aACIK" : "§cKAPALI";
    }

    /** true dondururse mesaj bizim komutumuzdur ve sunucuya gonderilmez. */
    private static boolean handleChat(String msg) {
        if (!msg.startsWith(".")) return false;
        String[] p = msg.substring(1).trim().split("\\s+");
        switch (p[0].toLowerCase()) {
            case "killaura" -> { KillAura.enabled = !KillAura.enabled; say("KillAura: " + onOff(KillAura.enabled)); }
            case "combo" -> { ComboBot.enabled = !ComboBot.enabled; say("Combo: " + onOff(ComboBot.enabled)); }
            case "crystal" -> { AutoCrystal.enabled = !AutoCrystal.enabled; say("Crystal: " + onOff(AutoCrystal.enabled)); }
            case "anchor" -> { AutoAnchor.enabled = !AutoAnchor.enabled; say("Anchor: " + onOff(AutoAnchor.enabled)); }
            case "totem" -> { AutoTotem.enabled = !AutoTotem.enabled; say("Totem: " + onOff(AutoTotem.enabled)); }
            case "sprint" -> { Sprint.enabled = !Sprint.enabled; say("Sprint: " + onOff(Sprint.enabled)); }
            case "durum" -> say("KillAura " + onOff(KillAura.enabled) + " | Combo " + onOff(ComboBot.enabled)
                    + " | Crystal " + onOff(AutoCrystal.enabled) + " | Anchor " + onOff(AutoAnchor.enabled)
                    + " | Totem " + onOff(AutoTotem.enabled));
            default -> { return false; } // bilinmeyen ".xyz" normal mesaj gibi gider
        }
        return true;
    }

    private static void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        Inv.busy = false; // her tick basinda sifirla: ayni tickte tek modul esya degistirir

        AutoTotem.tick(mc);
        Sprint.tick(mc);

        PlayerEntity target = TargetManager.getTarget(mc);
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

    static int findHotbar(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    /** Esyayi ele al (offhand'de varsa onu kullan). Basarili ise true. */
    static boolean equip(ClientPlayerEntity p, Item item) {
        if (p.getOffHandStack().isOf(item) || p.getMainHandStack().isOf(item)) return true;
        int s = findHotbar(p, item);
        if (s < 0) return false;
        p.getInventory().setSelectedSlot(s);
        return true;
    }

    static Hand handFor(ClientPlayerEntity p, Item item) {
        return p.getMainHandStack().isOf(item) ? Hand.MAIN_HAND
                : p.getOffHandStack().isOf(item) ? Hand.OFF_HAND : Hand.MAIN_HAND;
    }

    /** Listedeki ilk bulunan esyayi ele alir (sirayla en iyiden kotuye). */
    static boolean equipBest(ClientPlayerEntity p, Item[] prefs) {
        for (Item it : prefs) {
            if (findHotbar(p, it) >= 0) {
                if (p.getMainHandStack().isOf(it)) return true;
                p.getInventory().setSelectedSlot(findHotbar(p, it));
                return true;
            }
        }
        return false;
    }

    static boolean inRange(Vec3d from, Vec3d to, double max) {
        return from.squaredDistanceTo(to) <= max * max;
    }

    static boolean boxFree(ClientWorld w, Box box) {
        return w.getOtherEntities(null, box).isEmpty();
    }

    static void face(ClientPlayerEntity p, Entity e) {
        Vec3d eye = p.getEyePos();
        Vec3d t = e.getBoundingBox().getCenter();
        double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    static void clickTop(MinecraftClient mc, ClientPlayerEntity p, Hand hand, BlockPos clicked) {
        Vec3d hit = Vec3d.ofCenter(clicked).add(0, 0.5, 0);
        mc.interactionManager.interactBlock(p, hand, new BlockHitResult(hit, Direction.UP, clicked, false));
        p.swingHand(hand);
    }
}

/* ------------------------------------------------------------------ */

final class TargetManager {
    static final double MAX_DIST = 10.0;

    static PlayerEntity getTarget(MinecraftClient mc) {
        PlayerEntity best = null;
        double bestD = MAX_DIST * MAX_DIST;
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other == mc.player) continue;
            if (!other.isAlive() || other.isSpectator()) continue;
            double d = mc.player.squaredDistanceTo(other);
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

    static void tick(MinecraftClient mc) {
        if (!enabled) return;
        ClientPlayerEntity p = mc.player;
        if (mc.options.forwardKey.isPressed() && !p.isSneaking() && !p.isUsingItem()
                && !p.horizontalCollision && p.getHungerManager().getFoodLevel() > 6) {
            p.setSprinting(true);
        }
    }
}

/* ------------------------------------------------------------------ */

final class AutoTotem {
    static boolean enabled = true;
    static int wait = 0;

    static void tick(MinecraftClient mc) {
        if (!enabled) return;
        if (wait > 0) { wait--; return; }
        ClientPlayerEntity p = mc.player;
        if (p.currentScreenHandler != p.playerScreenHandler) return; // baska konteyner aciksa dokunma
        if (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;

        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                int slot = i < 9 ? 36 + i : i; // hotbar slotlari ekranda 36-44
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
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

    static void tick(MinecraftClient mc, PlayerEntity target) {
        if (!enabled || Inv.busy) return;
        ClientPlayerEntity p = mc.player;
        if (p.squaredDistanceTo(target) > RANGE * RANGE) return;

        // Hedef kalkan tutuyorsa balta, yoksa kilic. Bulunamazsa digerine dus.
        boolean ok = target.isBlocking()
                ? (Inv.equipBest(p, AXES) || Inv.equipBest(p, SWORDS))
                : (Inv.equipBest(p, SWORDS) || Inv.equipBest(p, AXES));
        if (!ok) return;

        // ESKI HATA: sabit 4 tick bekliyordu -> cok dusuk hasar. Artik saldiri gucu dolana kadar bekler.
        if (p.getAttackCooldownProgress(0.5f) < 0.95f) return;

        Inv.face(p, target);
        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
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
    static boolean tick(MinecraftClient mc, PlayerEntity target, boolean force) {
        if ((!enabled && !force) || Inv.busy) return false;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;

        // 1) ESKI HATA: kristal hic kirilmiyordu. Once menzildeki kristali patlat.
        List<EndCrystalEntity> crystals = w.getEntitiesByClass(
                EndCrystalEntity.class, target.getBoundingBox().expand(8.0), Entity::isAlive);
        EndCrystalEntity bestCrystal = null;
        double bestD = Double.MAX_VALUE;
        for (EndCrystalEntity c : crystals) {
            if (p.distanceTo(c) > RANGE) continue;
            double d = c.squaredDistanceTo(target);
            if (d < bestD) { bestD = d; bestCrystal = c; }
        }
        if (bestCrystal != null) {
            mc.interactionManager.attackEntity(p, bestCrystal);
            p.swingHand(Hand.MAIN_HAND);
            Inv.busy = true;
            cd = 2;
            return true;
        }

        // 2) Yeni kristal koy. Zaten ortada kristal varsa (menzil disi dahil) spam yapma.
        if (cd > 0) { cd--; return false; }
        if (!crystals.isEmpty()) return false;
        if (Inv.findHotbar(p, Items.END_CRYSTAL) < 0 && !p.getOffHandStack().isOf(Items.END_CRYSTAL)) return false;

        BlockPos spot = findSpot(w, p, target);
        if (spot == null) return false;
        if (!Inv.equip(p, Items.END_CRYSTAL)) return false;

        Hand hand = Inv.handFor(p, Items.END_CRYSTAL);
        Vec3d hit = Vec3d.ofCenter(spot).add(0, 0.5, 0);
        mc.interactionManager.interactBlock(p, hand, new BlockHitResult(hit, Direction.UP, spot, false));
        p.swingHand(hand);
        Inv.busy = true;
        cd = 2; // sunucudan kristalin gelmesini bekle
        return true;
    }

    /** Hedefe en yakin, bize en uzak uygun obsidyen/bedrock blogu. (Basit sezgisel; gercek hasar hesabi degil.) */
    static BlockPos findSpot(ClientWorld w, ClientPlayerEntity p, PlayerEntity target) {
        BlockPos base = target.getBlockPos();
        Vec3d eye = p.getEyePos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = base.add(dx, dy, dz);
                    if (!w.getBlockState(pos).isOf(Blocks.OBSIDIAN) && !w.getBlockState(pos).isOf(Blocks.BEDROCK)) continue;
                    BlockPos up = pos.up();
                    if (!w.getBlockState(up).isAir() || !w.getBlockState(up.up()).isAir()) continue;

                    // ESKI HATA: icinde oyuncu/esya olan yere kristal koymaya calisiyordu (oyun reddeder).
                    Box crystalBox = new Box(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1);
                    if (!Inv.boxFree(w, crystalBox)) continue;

                    Vec3d crystalCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                    if (!Inv.inRange(eye, crystalCenter, RANGE)) continue; // hem koyabil hem kirabil

                    double targetDist = crystalCenter.distanceTo(target.getPos());
                    double selfDist = crystalCenter.distanceTo(p.getPos());
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

    static void tick(MinecraftClient mc, PlayerEntity target) {
        if (!enabled || Inv.busy) return;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;

        // Respawn anchor sadece Nether disinda patlar.
        if (w.getRegistryKey() == World.NETHER) { stage = 0; return; }
        if (cd > 0) { cd--; return; }

        if (stage == 0) {
            if (Inv.findHotbar(p, Items.RESPAWN_ANCHOR) < 0 || Inv.findHotbar(p, Items.GLOWSTONE) < 0) return;
            BlockPos spot = findSpot(w, p, target);
            if (spot == null) return;
            if (!Inv.equip(p, Items.RESPAWN_ANCHOR)) return;
            Inv.clickTop(mc, p, Hand.MAIN_HAND, spot.down());
            anchorPos = spot;
            stage = 1;
            cd = 1; // sunucunun blogu yerlestirmesini bekle
            Inv.busy = true;
            return;
        }

        if (anchorPos == null || !w.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
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
                if (!p.getInventory().getStack(i).isOf(Items.GLOWSTONE)) { slot = i; break; }
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

    static void interactAnchor(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d hit = Vec3d.ofCenter(anchorPos);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, anchorPos, false));
        p.swingHand(Hand.MAIN_HAND);
    }

    /** Hedefin yaninda (kendi ayagi degil), altinda duz blok olan bos yer. */
    static BlockPos findSpot(ClientWorld w, ClientPlayerEntity p, PlayerEntity target) {
        BlockPos base = target.getBlockPos();
        Vec3d eye = p.getEyePos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue; // hedefin icine konmaz
                BlockPos pos = base.add(dx, 0, dz);
                BlockPos below = pos.down();
                if (!w.getBlockState(pos).isAir()) continue;
                if (!w.getBlockState(below).isSolidBlock(w, below)) continue;
                if (!Inv.boxFree(w, new Box(pos))) continue;

                Vec3d hit = Vec3d.ofCenter(below).add(0, 0.5, 0);
                if (!Inv.inRange(eye, hit, 4.4)) continue;

                Vec3d center = Vec3d.ofCenter(pos);
                double targetDist = center.distanceTo(target.getPos());
                double selfDist = center.distanceTo(p.getPos());
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

    static void tick(MinecraftClient mc, PlayerEntity target) {
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
