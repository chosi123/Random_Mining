package org.chosi123.random_mining;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.chosi123.random_mining.command.RandomMiningCommand;
import org.chosi123.random_mining.config.RandomMiningConfig;
import org.chosi123.random_mining.hardness.HardnessTable;
import org.chosi123.random_mining.hardness.RerollService;
import org.chosi123.random_mining.listener.MiningListener;
import org.chosi123.random_mining.listener.PlayerStateListener;
import org.chosi123.random_mining.mining.BreakSpeedLock;
import org.chosi123.random_mining.mining.MiningManager;
import org.chosi123.random_mining.punish.PunishmentService;

/**
 * random_mining — 블럭 경도가 주기적으로 무작위 재배치되고, 채굴을 취소하면 즉사하는 플러그인.
 *
 * <p>동작 원리는 세 조각이다.</p>
 * <ol>
 *   <li>{@link BreakSpeedLock} 이 {@code block_break_speed} 어트리뷰트를 0 으로 만들어
 *       바닐라 채굴을 서버·클라이언트 양쪽에서 완전히 정지시킨다.</li>
 *   <li>{@link MiningManager} 가 매 틱 직접 진행도를 굴리고, 다 차면 블럭을 부순다.</li>
 *   <li>클라이언트가 스스로 파괴를 끝낼 수 없으므로, 마우스를 떼면 반드시 취소 패킷이
 *       날아온다. 그 순간이 곧 "채굴 포기" 이고 즉사 판정이 걸린다.</li>
 * </ol>
 */
public final class Random_mining extends JavaPlugin {

    private RandomMiningConfig config;
    private HardnessTable hardnessTable;
    private BreakSpeedLock breakSpeedLock;
    private MiningManager miningManager;
    private PunishmentService punishmentService;
    private RerollService rerollService;

    @Override
    public void onEnable() {
        config = RandomMiningConfig.load(this);

        hardnessTable = new HardnessTable(config);
        breakSpeedLock = new BreakSpeedLock(this);
        punishmentService = new PunishmentService(this, config);
        miningManager = new MiningManager(this, config, hardnessTable, breakSpeedLock, punishmentService);
        rerollService = new RerollService(this, config, hardnessTable, miningManager);

        getServer().getPluginManager().registerEvents(new MiningListener(miningManager), this);
        getServer().getPluginManager().registerEvents(new PlayerStateListener(this, miningManager, punishmentService), this);

        PluginCommand command = getCommand("randommining");
        if (command != null) {
            RandomMiningCommand handler = new RandomMiningCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("plugin.yml 에 randommining 명령어가 등록되어 있지 않습니다.");
        }

        miningManager.start();
        rerollService.start(config);
        // /reload 나 핫 리로드로 켜졌을 때 이미 접속해 있는 플레이어도 잠가야 한다.
        miningManager.refreshAllLocks();

        warnAboutSpawnProtection();

        getLogger().info("활성화 완료 — 모드 " + config.mode()
                + ", 재배치 주기 " + config.rerollIntervalSeconds() + "초"
                + ", 벌칙 " + (config.deathEnabled() ? "ON" : "OFF")
                + ", 룰렛 " + (config.rouletteEnabled() ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        if (rerollService != null) rerollService.stop();
        if (punishmentService != null) punishmentService.clear();
        if (miningManager != null) {
            miningManager.stop();
            // 어트리뷰트를 남겨두면 플러그인 없이 접속했을 때 채굴이 영영 안 된다.
            miningManager.removeAllLocks();
        }
    }

    /**
     * 스폰 보호가 켜져 있으면 경고한다.
     *
     * <p>보호 구역 안에서는 서버가 BlockDamageEvent 를 발생시키지 않아서, OP 가 아닌
     * 플레이어는 채굴이 통째로 막힌다. 조용히 먹통이 되면 플러그인 버그로 오해하기 쉽다.</p>
     */
    private void warnAboutSpawnProtection() {
        int radius = getServer().getSpawnRadius();
        if (radius <= 0 || getServer().getOperators().isEmpty()) return;
        getLogger().warning("server.properties 의 spawn-protection 이 " + radius + " 입니다. "
                + "스폰 주변 " + (radius * 2 + 1) + "x" + (radius * 2 + 1) + " 구역에서는 "
                + "OP 가 아닌 플레이어의 채굴이 완전히 막힙니다. "
                + "이 플러그인을 쓰려면 spawn-protection=0 으로 두는 것을 권장합니다.");
    }

    /** config.yml 을 다시 읽고 모든 구성 요소에 반영한다. */
    public void reloadPlugin() {
        config = RandomMiningConfig.load(this);
        hardnessTable.applyConfig(config);
        punishmentService.applyConfig(config);
        miningManager.applyConfig(config);
        rerollService.start(config);
        miningManager.refreshAllLocks();
    }

    public RandomMiningConfig config() { return config; }
    public HardnessTable hardnessTable() { return hardnessTable; }
    public MiningManager miningManager() { return miningManager; }
    public PunishmentService punishmentService() { return punishmentService; }
    public RerollService rerollService() { return rerollService; }
}
