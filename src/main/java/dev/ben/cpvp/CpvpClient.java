package dev.ben.cpvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class CpvpClient implements ClientModInitializer {

    public static boolean comboEnabled = true;

    @Override
    public void onInitializeClient() {
        // Her tick'te çalışır
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.level == null || mc.gameMode == null) return;

            Player target = getTarget(mc);
            if (target != null) {
                ComboBot.tick(mc, target);
            }
            AutoTotem.tick(mc);
        });

        // Chat komutu
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            if (msg.equalsIgnoreCase(".combo")) {
                comboEnabled = !comboEnabled;
                Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("§b[CPVP] ComboBot: " + (comboEnabled ? "§aACIK" : "§cKAPALI")), true);
                return false;
            }
            return true;
        });
    }

    private static Player getTarget(Minecraft mc) {
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
// VUR - OBSİDYEN - KRİSTAL - PATLAT KOMBOSU
// ==========================================
class ComboBot {
    // 0=Vur, 1=Baz bul/Obsi koy, 2=Kristal koy, 3=Patlat
    private static int state = 0;
    private static int timer = 0;
    private static BlockPos base = null; // kristalin duracağı obsidyen

    public static void tick(Minecraft mc, Player target) {
        if (!CpvpClient.comboEnabled) return;
        if (mc.player.distanceToSqr(target) > 9.0) { state = 0; return; }

        Inventory inv = mc.player.getInventory();
        BlockPos targetPos = target.blockPosition();

        if (timer > 0) { timer--; return; }

        // ---------- 0: KILIÇ / BALTA İLE VUR ----------
        if (state == 0) {
            if (target.isBlocking() && !mc.player.getMainHandItem().is(Items.NETHERITE_AXE)) {
                int axe = Inv.findHotbar(inv, Items.NETHERITE_AXE);
                if (axe != -1) inv.setSelectedSlot(axe);
            } else if (!target.isBlocking() && !mc.player.getMainHandItem().is(Items.NETHERITE_SWORD)) {
                int sword = Inv.findHotbar(inv, Items.NETHERITE_SWORD);
                if (sword != -1) inv.setSelectedSlot(sword);
            }

            // Cooldown dolmadan vurma (zayıf vuruş olmasın)
            if (mc.player.getAttackStrengthScale(0.5f) < 0.9f) return;

            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
            state = 1;
            timer = 1;
        }
        // ---------- 1: KRİSTAL İÇİN BAZ BUL, YOKSA OBSİDYEN KOY ----------
        else if (state == 1) {
            base = findExistingBase(mc, targetPos);
            if (base != null) { state = 2; return; }

            int obs = Inv.findHotbar(inv, Items.OBSIDIAN);
            if (obs == -1) { state = 0; return; }

            // Hedefin YANINDAKİ boş hücreye koy (altına değil)
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos cell = targetPos.relative(d);
                BlockPos support = cell.below();
                if (!mc.level.getBlockState(cell).isAir()) continue;
                if (!mc.level.getBlockState(cell.above()).isAir()) continue;
                if (!mc.level.getBlockState(cell.above(2)).isAir()) continue;
                if (!mc.level.getBlockState(support).isFaceSturdy(mc.level, support, Direction.UP)) continue;
                if (occupied(mc, cell)) continue; // içinde oyuncu/entity varsa blok konmaz

                inv.setSelectedSlot(obs);
                var res = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
                mc.player.displayClientMessage(Component.literal("§7[DEBUG] obsi: " + res), false);
                base = cell;
                state = 2;
                timer = 2; // sunucu bloğu işlesin
                return;
            }
            state = 0; // uygun yer yok
        }
        // ---------- 2: KRİSTALİ OBSİDYENİN ÜSTÜNE KOY ----------
        else if (state == 2) {
            if (base == null || !isCrystalBase(mc, base) || occupied(mc, base)) {
                String blk = base == null ? "null" : mc.level.getBlockState(base).getBlock().getName().getString();
                boolean occ = base != null && occupied(mc, base);
                mc.player.displayClientMessage(Component.literal("§7[DEBUG] baz sorunlu: blok=" + blk + " entityVar=" + occ), false);
                state = 0;
                return;
            }

            int crys = Inv.findHotbar(inv, Items.END_CRYSTAL);
            if (crys == -1) { state = 0; return; }

            inv.setSelectedSlot(crys);
            var res = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false));
            mc.player.swing(InteractionHand.MAIN_HAND);
            mc.player.displayClientMessage(Component.literal("§7[DEBUG] kristal: " + res), false);
            state = 3;
            timer = 2;
        }
        // ---------- 3: KRİSTALİ PATLAT (self-damage bilerek açık) ----------
        else if (state == 3) {
            if (base != null) {
                BlockPos crysPos = base.above();
                int found = 0;
                for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class,
                        new AABB(crysPos).inflate(1.0))) {
                    found++;
                    mc.gameMode.attack(mc.player, c);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    break;
                }
                mc.player.displayClientMessage(Component.literal("§7[DEBUG] sahnede kristal: " + found), false);
            }
            base = null;
            state = 0;
        }
    }

    // Hücrede (obsidyen + 2 blok kristal alanı) oyuncu/entity var mı
    private static boolean occupied(Minecraft mc, BlockPos cell) {
        AABB box = new AABB(cell.getX(), cell.getY(), cell.getZ(),
                            cell.getX() + 1, cell.getY() + 3, cell.getZ() + 1);
        return !mc.level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> !e.isSpectator()).isEmpty();
    }

    // Kristal konabilecek obsidyen/bedrock: üstü ve onun üstü boş olmalı
    private static boolean isCrystalBase(Minecraft mc, BlockPos pos) {
        var bs = mc.level.getBlockState(pos);
        if (!bs.is(Blocks.OBSIDIAN) && !bs.is(Blocks.BEDROCK)) return false;
        return mc.level.getBlockState(pos.above()).isAir()
            && mc.level.getBlockState(pos.above(2)).isAir();
    }

    // Hedefin etrafında hazır bir obsidyen var mı (ayak seviyesi ve bir alt)
    private static BlockPos findExistingBase(Minecraft mc, BlockPos targetPos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos a = targetPos.relative(d);
            if (isCrystalBase(mc, a) && !occupied(mc, a)) return a;
            BlockPos b = a.below();
            if (isCrystalBase(mc, b) && !occupied(mc, b)) return b;
        }
        return null;
    }
}

// ==========================================
// OTOMATİK TOTEM
// ==========================================
class AutoTotem {
    private static int waitTicks = 0;

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) return;
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

// ==========================================
// YARDIMCI SINIF
// ==========================================
class Inv {
    public static int findHotbar(Inventory inv, Item item) {
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }
}
