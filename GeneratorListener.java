package com.yourname.deadplugin.listeners;

import com.yourname.deadplugin.DeadPlugin;
import com.yourname.deadplugin.game.GameManager;
import com.yourname.deadplugin.generator.GeneratorEntity;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GeneratorListener — слушает события игроков и управляет процессом починки.
 *
 * Логика:
 * - Игрок зажимает Shift рядом с генератором → запускается тик починки каждую секунду
 * - Прогресс отображается через BossBar (полоска над инвентарём)
 * - Игрок отпускает Shift → починка останавливается
 * - При 100% → запускается анимация генератора
 */
public class GeneratorListener implements Listener {

    // Скорость починки за одну секунду (10% = 10 секунд до 100%)
    private static final double REPAIR_PER_SECOND = 0.10;

    private final GameManager gameManager;

    // Текущие BossBar-ы игроков (UUID → BossBar)
    // Нужны, чтобы обновлять прогресс и удалять полосу по окончании
    private final Map<UUID, BossBar> repairBossBars = new HashMap<>();

    // Текущие задачи тика починки (UUID → BukkitTask)
    // Нужны, чтобы отменить задачу, когда игрок отпускает Shift
    private final Map<UUID, BukkitTask> repairTasks = new HashMap<>();

    public GeneratorListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    /**
     * Обрабатывает нажатие/отпускание Shift.
     *
     * PlayerToggleSneakEvent вызывается при КАЖДОМ изменении состояния приседания:
     * - isSneaking() == true  → игрок НАЧАЛ приседать (зажал Shift)
     * - isSneaking() == false → игрок ПЕРЕСТАЛ приседать (отпустил Shift)
     */
    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();

        // Проверяем, что игра идёт и игрок является выжившим
        if (gameManager.getState() != GameManager.GameState.RUNNING) return;
        if (!gameManager.isSurvivor(player)) return;

        if (event.isSneaking()) {
            // Игрок зажал Shift — ищем ближайший непочинённый генератор
            GeneratorEntity nearbyGen = findNearbyGenerator(player);
            if (nearbyGen != null) {
                startRepairing(player, nearbyGen);
            }
        } else {
            // Игрок отпустил Shift — останавливаем починку
            stopRepairing(player);
        }
    }

    /**
     * Запускает процесс починки: создаёт BossBar и запускает тик каждую секунду.
     */
    private void startRepairing(Player player, GeneratorEntity generator) {
        UUID uuid = player.getUniqueId();

        // Не запускаем повторно, если уже чинит
        if (repairTasks.containsKey(uuid)) return;

        // Создаём BossBar для отображения прогресса
        // BossBar.bossBar(заголовок, прогресс_0-1, цвет, стиль)
        BossBar bar = BossBar.bossBar(
                Component.text("Починка генератора..."),
                (float) generator.getRepairProgress(), // начальный прогресс
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS
        );

        // Показываем BossBar игроку через Adventure API
        player.showBossBar(bar);
        repairBossBars.put(uuid, bar);

        // Запускаем задачу: каждые 20 тиков (1 сек) добавляем прогресс
        BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskTimer(
                DeadPlugin.getInstance(),
                () -> {
                    // Безопасность: если игрок вышел — останавливаем
                    if (!player.isOnline()) {
                        stopRepairing(player);
                        return;
                    }

                    // Добавляем прогресс починки
                    boolean justRepaired = generator.addRepairProgress(REPAIR_PER_SECOND);

                    // Обновляем значение полоски (0.0 — 1.0)
                    bar.progress((float) generator.getRepairProgress());

                    if (justRepaired) {
                        // Генератор починен!
                        bar.name(Component.text("✔ Генератор починен!"));
                        // Убираем BossBar через 2 секунды
                        org.bukkit.Bukkit.getScheduler().runTaskLater(
                                DeadPlugin.getInstance(),
                                () -> stopRepairing(player),
                                40L // 40 тиков = 2 секунды
                        );
                        // Оповещаем всех игроков
                        org.bukkit.Bukkit.broadcastMessage(
                                "§a" + player.getName() + " починил генератор!"
                        );
                    }
                },
                0L,  // задержка перед первым вызовом
                20L  // интервал: 20 тиков = 1 секунда
        );

        repairTasks.put(uuid, task);
    }

    /**
     * Останавливает починку: отменяет задачу и убирает BossBar.
     */
    private void stopRepairing(Player player) {
        UUID uuid = player.getUniqueId();

        // Отменяем тик починки
        BukkitTask task = repairTasks.remove(uuid);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }

        // Убираем BossBar
        BossBar bar = repairBossBars.remove(uuid);
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /**
     * Ищет ближайший непочинённый генератор в радиусе взаимодействия.
     * @return GeneratorEntity или null, если рядом ничего нет
     */
    private GeneratorEntity findNearbyGenerator(Player player) {
        for (GeneratorEntity gen : gameManager.getGenerators()) {
            if (!gen.isRepaired() && gen.isNearby(player)) {
                return gen;
            }
        }
        return null;
    }
}
