package ru.hunterthread;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public final class HunterThread extends JavaPlugin implements Listener {
    private NamespacedKey itemKey, projectileKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "hunter_thread_item");
        projectileKey = new NamespacedKey(this, "hunter_thread_projectile");
        getServer().getPluginManager().registerEvents(this, this);
    }

    private ItemStack createThread(int amount) {
        ItemStack item = new ItemStack(Material.SNOWBALL, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§4§l✦ §c§lНить охотника §4§l✦");
        meta.setLore(List.of("§8━━━━━━━━━━━━━━━━━━━━━━━━", "§7Попади во врага, чтобы связать",
            "§7его с собой кровавой нитью.", "", "§c➤ §7Если цель убежит слишком далеко,",
            "§7нить оборвётся и притянет её назад.", "§8━━━━━━━━━━━━━━━━━━━━━━━━"));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte)1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isThread(ItemStack item) {
        return item != null && item.getType() == Material.SNOWBALL && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)
            || !(snowball.getShooter() instanceof Player player)
            || !isThread(snowball.getItem())) return;
        long now = System.currentTimeMillis();
        long until = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < until) {
            event.setCancelled(true);
            player.sendMessage("§4✦ §cНить восстанавливается: §f" + ((until - now + 999) / 1000) + " сек.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
            return;
        }
        cooldowns.put(player.getUniqueId(), now + Math.max(0, getConfig().getLong("cooldown-seconds", 25)) * 1000);
        snowball.getPersistentDataContainer().set(projectileKey, PersistentDataType.BYTE, (byte)1);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 1.8f);
        new BukkitRunnable() {
            public void run() {
                if (!snowball.isValid() || snowball.isDead()) { cancel(); return; }
                Location loc = snowball.getLocation();
                loc.getWorld().spawnParticle(Particle.DUST, loc, 3, 0.05, 0.05, 0.05,
                    new Particle.DustOptions(Color.fromRGB(210,20,45), 1.1f));
                loc.getWorld().spawnParticle(Particle.SMOKE, loc, 1, 0.02, 0.02, 0.02, 0.001);
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)
            || !snowball.getPersistentDataContainer().has(projectileKey, PersistentDataType.BYTE)
            || !(snowball.getShooter() instanceof Player hunter)
            || !(event.getHitEntity() instanceof Player target)
            || hunter.equals(target) || !hunter.isOnline() || !target.isOnline()
            || !hunter.getWorld().equals(target.getWorld())) return;
        startThread(hunter, target);
    }

    private void startThread(Player hunter, Player target) {
        double maxDistance = Math.max(1, getConfig().getDouble("max-distance", 12));
        int duration = Math.max(1, getConfig().getInt("duration-seconds", 8)) * 20;
        Location hit = target.getLocation().add(0, 1, 0);
        hit.getWorld().spawnParticle(Particle.DUST, hit, 45, 0.4, 0.5, 0.4,
            new Particle.DustOptions(Color.fromRGB(255,35,60), 1.5f));
        hit.getWorld().spawnParticle(Particle.SMOKE, hit, 20, 0.3, 0.4, 0.3, 0.02);
        hit.getWorld().playSound(hit, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.5f);
        hit.getWorld().playSound(hit, Sound.BLOCK_CHAIN_PLACE, 1.2f, 0.7f);
        hunter.sendMessage("§4✦ §cНить охотника связала тебя с §f" + target.getName() + "§c!");
        target.sendMessage("§4✦ §cТы связан нитью охотника! §7Не позволяй ей натянуться.");
        new BukkitRunnable() {
            int ticks = 0;
            public void run() {
                if (!hunter.isOnline() || !target.isOnline() || hunter.isDead() || target.isDead()
                    || !hunter.getWorld().equals(target.getWorld())) { cancel(); return; }
                if (ticks >= duration) {
                    target.getWorld().spawnParticle(Particle.SMOKE, target.getLocation().add(0,1,0), 12, 0.2,0.3,0.2,0.01);
                    hunter.sendMessage("§8✦ §7Нить охотника угасла.");
                    target.sendMessage("§8✦ §7Нить охотника угасла.");
                    cancel(); return;
                }
                Location from = hunter.getLocation().add(0,1,0), to = target.getLocation().add(0,1,0);
                Vector difference = to.toVector().subtract(from.toVector());
                double distance = difference.length();
                if (distance >= maxDistance) { snap(hunter, target, from, to); cancel(); return; }
                if (distance > 0.01) {
                    boolean tense = distance >= maxDistance * 0.75;
                    Vector direction = difference.clone().normalize();
                    int points = Math.min(32, Math.max(2, (int)(distance * 3)));
                    Particle.DustOptions dust = new Particle.DustOptions(
                        tense ? Color.fromRGB(255,45,45) : Color.fromRGB(150,12,35), tense ? 1.35f : 0.9f);
                    for (int i=0; i<=points; i++) {
                        double f = (double)i / points;
                        Location point = from.clone().add(direction.clone().multiply(distance*f))
                            .add(0, Math.sin(f*Math.PI*3 + ticks*0.2)*(tense ? 0.04 : 0.12), 0);
                        from.getWorld().spawnParticle(Particle.DUST, point, 1, 0,0,0,dust);
                        if (tense && i%5==0) from.getWorld().spawnParticle(Particle.CRIT, point, 1, 0.03,0.03,0.03,0);
                    }
                    if (tense && ticks%20==0) {
                        target.playSound(target.getLocation(), Sound.BLOCK_CHAIN_HIT, 0.8f, 1.6f);
                        target.sendActionBar(net.kyori.adventure.text.Component.text("⚠ Нить натягивается!", net.kyori.adventure.text.format.NamedTextColor.RED));
                    }
                }
                ticks += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void snap(Player hunter, Player target, Location from, Location to) {
        Vector pull = from.toVector().subtract(to.toVector());
        if (pull.lengthSquared() > 0.0001) {
            pull.normalize().multiply(Math.max(0, getConfig().getDouble("pull-strength", 1.15)));
            pull.setY(Math.max(0.25, Math.min(0.55, pull.getY()+0.3)));
            target.setVelocity(pull);
        }
        World world = target.getWorld();
        world.spawnParticle(Particle.DUST, to, 60, 0.5,0.5,0.5,
            new Particle.DustOptions(Color.fromRGB(255,30,45), 1.8f));
        world.spawnParticle(Particle.CRIT, to, 30, 0.4,0.4,0.4,0.15);
        world.spawnParticle(Particle.SMOKE, to, 25, 0.4,0.4,0.4,0.03);
        world.playSound(to, Sound.BLOCK_CHAIN_BREAK, 1.4f, 0.6f);
        world.playSound(to, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
        hunter.sendMessage("§4✦ §cНить оборвалась и притянула цель!");
        target.sendMessage("§4✦ §cНить оборвалась и потянула тебя назад!");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§cИспользование: /hunterthread give [игрок] [количество]"); return true;
        }
        Player recipient = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
        if (recipient == null) { sender.sendMessage("§cУкажите игрока, который находится на сервере."); return true; }
        int amount = 1;
        if (args.length >= 3) {
            try { amount = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) { sender.sendMessage("§cКоличество должно быть от 1 до 64."); return true; }
        }
        if (amount < 1 || amount > 64) { sender.sendMessage("§cКоличество должно быть от 1 до 64."); return true; }
        for (ItemStack item : recipient.getInventory().addItem(createThread(amount)).values())
            recipient.getWorld().dropItemNaturally(recipient.getLocation(), item);
        sender.sendMessage("§4✦ §7Выдано §c" + amount + " §7шт. игроку §f" + recipient.getName() + "§7.");
        return true;
    }
}
