package com.yourname.deadplugin.generator;

import com.ria.modelengine.api.ModelEngineAPI;
import com.ria.modelengine.api.model.ActiveModel;
import com.ria.modelengine.api.model.Model;
import com.ria.modelengine.api.model.ModeledEntity;
import com.yourname.deadplugin.DeadPlugin;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;

/**
 * GeneratorEntity — представляет один генератор на карте.
 *
 * Для отображения 3D-модели используем ModelEngine API:
 *  - ModelEngineAPI          — главная точка входа в API
 *  - ModeledEntity           — «контейнер» модели, привязанный к Bukkit-сущности
 *  - ActiveModel             — конкретная загруженная модель (наш "dbd_generator")
 *
 * Схема работы ModelEngine:
 *   BukkitEntity (ArmorStand) → ModeledEntity → ActiveModel → анимации/кости
 */
public class GeneratorEntity {

    // ID модели в ModelEngine — должен совпадать с именем папки в resource pack
    private static final String MODEL_ID = "dbd_generator";

    // Название анимации из Blockbench, которую запускаем после починки
    private static final String ANIM_WORKING = "working";

    private final DeadPlugin plugin;
    private final Location location;

    // Bukkit-сущность, к которой привязана модель (невидимый ArmorStand)
    private ArmorStand baseEntity;

    // Объект ModelEngine, хранящий все активные модели данной сущности
    private ModeledEntity modeledEntity;

    // Активная модель генератора
    private ActiveModel activeModel;

    // Текущий прогресс починки (0.0 — 1.0)
    private double repairProgress = 0.0;

    // Починен ли генератор
    private boolean repaired = false;

    public GeneratorEntity(DeadPlugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
    }

    /**
     * Спавнит Bukkit-сущность и создаёт ModelEngine-модель поверх неё.
     *
     * ШАГ 1: Создаём базовую Bukkit-сущность (ArmorStand).
     *         ModelEngine использует её как «якорь» для модели в мире.
     *         ArmorStand невидим и без гравитации — игроки его не видят напрямую.
     *
     * ШАГ 2: Через ModelEngineAPI.getOrCreateModeledEntity() получаем
     *         объект ModeledEntity, который «оборачивает» нашу сущность.
     *
     * ШАГ 3: Создаём ActiveModel по ID ("dbd_generator") и добавляем её
     *         в ModeledEntity. С этого момента модель видна игрокам.
     */
    public void spawn() {
        // ШАГ 1: спавним невидимый ArmorStand как якорь для модели
        baseEntity = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        baseEntity.setVisible(false);        // ArmorStand невидим
        baseEntity.setGravity(false);        // не падает вниз
        baseEntity.setInvulnerable(true);    // не получает урон
        baseEntity.setSmall(true);           // маленький, чтобы не мешать коллизиям

        // ШАГ 2: Получаем или создаём ModeledEntity для нашего ArmorStand.
        // ModelEngine сам отслеживает, привязана ли уже модель к этой сущности.
        // ModelEngineAPI — статический класс-синглтон, точка входа во весь API.
        modeledEntity = ModelEngineAPI.getOrCreateModeledEntity(baseEntity);

        // ШАГ 3: Создаём ActiveModel по ID.
        // ModelEngineAPI.createActiveModel(id) загружает модель из resource pack
        // и возвращает готовый объект. Если модель с таким ID не найдена — вернёт null!
        Model blueprint = ModelEngineAPI.getModel(MODEL_ID);
        if (blueprint == null) {
            plugin.getLogger().severe("ModelEngine: модель '" + MODEL_ID + "' не найдена! "
                    + "Убедись, что resource pack загружен и ID модели совпадает.");
            return;
        }

        // Создаём «живой» экземпляр модели из blueprint
        activeModel = ModelEngineAPI.createActiveModel(blueprint);

        // Добавляем модель в ModeledEntity — теперь она отображается в мире
        modeledEntity.addModel(activeModel, true);
        // true = заменить существующую модель с таким же ID, если есть

        plugin.getLogger().info("Генератор заспавнен на " + locationToString(location));
    }

    /**
     * Обновляет прогресс починки.
     * Вызывается из GeneratorListener каждую секунду, пока игрок зажимает Shift.
     *
     * @param delta на сколько увеличить прогресс (0.0 — 1.0)
     * @return true, если генератор только что починен (достиг 100%)
     */
    public boolean addRepairProgress(double delta) {
        if (repaired) return false;

        repairProgress = Math.min(1.0, repairProgress + delta);

        // Если достигли 100% — запускаем анимацию "working"
        if (repairProgress >= 1.0) {
            repaired = true;
            playWorkingAnimation();
            return true;
        }

        return false;
    }

    /**
     * Запускает анимацию "working" через ModelEngine API.
     *
     * activeModel.getAnimationHandler() — менеджер анимаций конкретной модели.
     * playAnimation(имя, скорость, приоритет, lerp) — запускает анимацию:
     *   - имя      : должно совпадать с названием анимации в Blockbench
     *   - скорость : 1.0 = нормальная скорость
     *   - приоритет: 0 = обычный приоритет (чем выше — тем важнее)
     *   - lerp     : плавность перехода между анимациями (0.0–1.0)
     */
    private void playWorkingAnimation() {
        if (activeModel == null) return;

        // Обращаемся к менеджеру анимаций ActiveModel
        activeModel.getAnimationHandler()
                .playAnimation(
                        ANIM_WORKING, // имя анимации из Blockbench
                        1.0,          // скорость воспроизведения
                        1.0,          // вес (приоритет смешивания)
                        0.1,          // скорость интерполяции (lerp)
                        true          // зациклить анимацию
                );

        plugin.getLogger().info("Генератор починен! Запущена анимация '" + ANIM_WORKING + "'.");
    }

    /**
     * Удаляет модель и базовую сущность из мира.
     * Вызывается при остановке игры.
     */
    public void remove() {
        // Сначала убираем ModeledEntity, чтобы ModelEngine «отвязал» модель
        if (modeledEntity != null) {
            modeledEntity.destroy(); // удаляет все модели и освобождает ресурсы
        }
        // Затем удаляем Bukkit-сущность
        if (baseEntity != null && !baseEntity.isDead()) {
            baseEntity.remove();
        }
    }

    // ─── Геттеры ─────────────────────────────────────────────────────────────────

    public double getRepairProgress() { return repairProgress; }
    public boolean isRepaired() { return repaired; }
    public Location getLocation() { return location; }

    /** Проверяет, находится ли игрок достаточно близко к генератору для починки */
    public boolean isNearby(org.bukkit.entity.Player player) {
        // Радиус взаимодействия — 3 блока
        return player.getLocation().distanceSquared(location) <= 9.0; // 3^2 = 9
    }

    private String locationToString(Location loc) {
        return String.format("(%.1f, %.1f, %.1f) в мире '%s'",
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getWorld() != null ? loc.getWorld().getName() : "null");
    }
}
