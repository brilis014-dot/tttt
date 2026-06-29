package com.yourname.deadplugin;

import com.yourname.deadplugin.game.GameManager;
import com.yourname.deadplugin.listeners.GeneratorListener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * DeadPlugin — главный класс плагина, точка входа.
 * Bukkit/Paper вызывает onEnable() при старте сервера (или /reload),
 * и onDisable() при остановке сервера.
 */
public class DeadPlugin extends JavaPlugin {

    // Статическая ссылка на экземпляр плагина — удобно для доступа из других классов
    private static DeadPlugin instance;

    // Менеджер игры — центральный класс, управляющий состоянием мини-игры
    private GameManager gameManager;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("=== DeadPlugin запускается... ===");

        // Создаём менеджер игры и передаём ему ссылку на плагин
        gameManager = new GameManager(this);

        // Регистрируем слушатель событий генератора
        // (второй аргумент — плагин-владелец, нужен Bukkit для управления событиями)
        getServer().getPluginManager().registerEvents(
                new GeneratorListener(gameManager),
                this
        );

        getLogger().info("=== DeadPlugin успешно загружен! ===");
    }

    @Override
    public void onDisable() {
        // Останавливаем игру при выключении плагина, чтобы не остались висеть таймеры
        if (gameManager != null) {
            gameManager.stopGame();
        }
        getLogger().info("=== DeadPlugin выключен. ===");
    }

    /**
     * Статический геттер — позволяет любому классу получить экземпляр плагина
     * через DeadPlugin.getInstance()
     */
    public static DeadPlugin getInstance() {
        return instance;
    }

    public GameManager getGameManager() {
        return gameManager;
    }
}
