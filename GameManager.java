package com.yourname.deadplugin.game;

import com.yourname.deadplugin.DeadPlugin;
import com.yourname.deadplugin.generator.GeneratorEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

/**
 * GameManager — центральный менеджер мини-игры.
 * Отвечает за:
 *  - Отслеживание игроков в лобби
 *  - Запуск таймера обратного отсчёта
 *  - Распределение ролей (Монстр / Выжившие)
 *  - Спавн генераторов
 */
public class GameManager {

    // ─── Константы ──────────────────────────────────────────────────────────────

    /** Сколько игроков нужно для старта игры */
    private static final int REQUIRED_PLAYERS = 4;

    /** Продолжительность обратного отсчёта в секундах */
    private static final int COUNTDOWN_SECONDS = 20;

    // ─── Состояние игры ──────────────────────────────────────────────────────────

    /** Возможные состояния игрового цикла */
    public enum GameState {
        WAITING,    // Ждём игроков в лобби
        COUNTDOWN,  // Идёт обратный отсчёт
        RUNNING,    // Игра идёт
        ENDED       // Игра окончена
    }

    private GameState state = GameState.WAITING;

    // ─── Игроки и роли ───────────────────────────────────────────────────────────

    /** Список игроков в текущей лобби/игре */
    private final List<Player> players = new ArrayList<>();

    /** UUID игрока, который является Монстром */
    private UUID monsterUUID;

    /** UUID выживших игроков */
    private final Set<UUID> survivorUUIDs = new HashSet<>();

    // ─── Таймер и объекты игры ───────────────────────────────────────────────────

    /** Ссылка на задачу обратного отсчёта — нужна, чтобы отменить её при необходимости */
    private BukkitTask countdownTask;

    /** Список заспавненных генераторов */
    private final List<GeneratorEntity> generators = new ArrayList<>();

    // ─── Зависимости ─────────────────────────────────────────────────────────────

    private final DeadPlugin plugin;

    public GameManager(DeadPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Методы управления лобби ─────────────────────────────────────────────────

    /**
     * Вызывается, когда игрок заходит на сервер (из PlayerJoinListener — добавишь позже).
     * Добавляет игрока в лобби и проверяет, можно ли начать отсчёт.
     */
    public void addPlayer(Player player) {
        // Игру можно начать только из состояния WAITING
        if (state != GameState.WAITING) {
            player.sendMessage(Component.text("Игра уже идёт!", NamedTextColor.RED));
            return;
        }

        players.add(player);
        Bukkit.broadcastMessage("§e" + player.getName() + " вошёл в лобби. ["
                + players.size() + "/" + REQUIRED_PLAYERS + "]");

        // Как только набрался нужный состав — запускаем отсчёт
        if (players.size() >= REQUIRED_PLAYERS) {
            startCountdown();
        }
    }

    /**
     * Удаляет игрока из лобби или прерывает игру, если она уже шла.
     */
    public void removePlayer(Player player) {
        players.remove(player);

        if (state == GameState.COUNTDOWN) {
            // Если игрок вышел во время отсчёта — отменяем его
            cancelCountdown();
            Bukkit.broadcastMessage("§cИгрок вышел. Отсчёт прерван. ["
                    + players.size() + "/" + REQUIRED_PLAYERS + "]");
        }
    }

    // ─── Обратный отсчёт ─────────────────────────────────────────────────────────

    /**
     * Запускает таймер обратного отсчёта.
     * Используем BukkitScheduler.runTaskTimer() — он вызывает код каждые N тиков.
     * 20 тиков = 1 секунда.
     */
    private void startCountdown() {
        state = GameState.COUNTDOWN;
        Bukkit.broadcastMessage("§aНабрано " + REQUIRED_PLAYERS + " игроков! Игра начнётся через "
                + COUNTDOWN_SECONDS + " секунд.");

        // Используем массив, чтобы изменять счётчик внутри лямбды (effectively final)
        final int[] secondsLeft = {COUNTDOWN_SECONDS};

        // runTaskTimer(plugin, задержкаПередПервымВызовом, интервал)
        // 0L = первый вызов немедленно, 20L = каждые 20 тиков (1 сек)
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {

            if (secondsLeft[0] <= 0) {
                // Время вышло — начинаем игру
                countdownTask.cancel();
                startGame();
                return;
            }

            // Каждые 5 секунд (и на последних 3) рассылаем сообщение
            if (secondsLeft[0] % 5 == 0 || secondsLeft[0] <= 3) {
                Bukkit.broadcastMessage("§eИгра начнётся через §c" + secondsLeft[0] + " §eсек.");
            }

            secondsLeft[0]--;

        }, 0L, 20L);
    }

    /** Отменяет отсчёт и возвращает игру в состояние WAITING */
    private void cancelCountdown() {
        if (countdownTask != null && !countdownTask.isCancelled()) {
            countdownTask.cancel();
        }
        state = GameState.WAITING;
    }

    // ─── Старт игры ──────────────────────────────────────────────────────────────

    /**
     * Запускает игру:
     * 1. Распределяет роли
     * 2. Спавнит генераторы
     */
    private void startGame() {
        state = GameState.RUNNING;
        assignRoles();
        spawnGenerators();
    }

    // ─── Распределение ролей ─────────────────────────────────────────────────────

    /**
     * Случайным образом выбирает 1 Монстра, остальные — Выжившие.
     * Каждому игроку показывается Title (заголовок на экране).
     */
    private void assignRoles() {
        // Копируем список и перемешиваем
        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        // Первый в перемешанном списке — Монстр
        Player monster = shuffled.get(0);
        monsterUUID = monster.getUniqueId();

        // Остальные — Выжившие
        survivorUUIDs.clear();
        for (int i = 1; i < shuffled.size(); i++) {
            survivorUUIDs.add(shuffled.get(i).getUniqueId());
        }

        // Показываем Title Монстру
        // Title.title(заголовок, подзаголовок, времена показа)
        monster.showTitle(Title.title(
                Component.text("ТЫ — МОНСТР", NamedTextColor.RED),
                Component.text("Найди и убей всех выживших!", NamedTextColor.DARK_RED),
                Title.Times.times(
                        Duration.ofMillis(500),  // время появления
                        Duration.ofSeconds(3),   // время показа
                        Duration.ofMillis(500)   // время исчезновения
                )
        ));

        // Показываем Title каждому Выжившему
        for (int i = 1; i < shuffled.size(); i++) {
            Player survivor = shuffled.get(i);
            survivor.showTitle(Title.title(
                    Component.text("ТЫ — ВЫЖИВШИЙ", NamedTextColor.GREEN),
                    Component.text("Почини генераторы и сбеги!", NamedTextColor.DARK_GREEN),
                    Title.Times.times(
                            Duration.ofMillis(500),
                            Duration.ofSeconds(3),
                            Duration.ofMillis(500)
                    )
            ));
        }

        plugin.getLogger().info("Роли распределены. Монстр: " + monster.getName());
    }

    // ─── Спавн генераторов ───────────────────────────────────────────────────────

    /**
     * Спавнит генераторы в заранее заданных точках карты.
     * Координаты пропиши под свою карту!
     */
    private void spawnGenerators() {
        // Список координат, где должны появиться генераторы
        // Замени "world" на имя твоего мира и подбери нужные XYZ
        List<Location> spawnPoints = List.of(
                new Location(Bukkit.getWorld("world"), 100, 64, 100),
                new Location(Bukkit.getWorld("world"), 150, 64, 80),
                new Location(Bukkit.getWorld("world"), 80,  64, 150)
        );

        for (Location loc : spawnPoints) {
            // Создаём объект генератора и спавним его через ModelEngine
            GeneratorEntity gen = new GeneratorEntity(plugin, loc);
            gen.spawn(); // <- здесь происходит обращение к ModelEngine API
            generators.add(gen);
        }

        plugin.getLogger().info("Заспавнено генераторов: " + generators.size());
    }

    // ─── Остановка игры ──────────────────────────────────────────────────────────

    /**
     * Останавливает игру и убирает все объекты.
     * Вызывается при onDisable() или по команде /deadgame stop.
     */
    public void stopGame() {
        cancelCountdown();

        // Удаляем все заспавненные генераторы
        for (GeneratorEntity gen : generators) {
            gen.remove();
        }
        generators.clear();
        players.clear();
        survivorUUIDs.clear();
        monsterUUID = null;
        state = GameState.WAITING;
    }

    // ─── Геттеры ─────────────────────────────────────────────────────────────────

    public GameState getState() { return state; }
    public List<Player> getPlayers() { return players; }
    public List<GeneratorEntity> getGenerators() { return generators; }

    public boolean isMonster(Player player) {
        return player.getUniqueId().equals(monsterUUID);
    }

    public boolean isSurvivor(Player player) {
        return survivorUUIDs.contains(player.getUniqueId());
    }
}
