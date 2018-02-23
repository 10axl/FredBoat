package fredboat.main;

import fredboat.agent.FredBoatAgent;
import fredboat.agent.StatsAgent;
import fredboat.audio.player.LavalinkManager;
import fredboat.audio.queue.MusicPersistenceHandler;
import fredboat.config.property.*;
import fredboat.db.DatabaseManager;
import fredboat.db.EntityIO;
import fredboat.event.EventListenerBoat;
import fredboat.feature.metrics.Metrics;
import fredboat.metrics.OkHttpEventMetrics;
import fredboat.util.rest.Http;
import net.dv8tion.jda.bot.sharding.ShardManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Class responsible for controlling FredBoat at large
 */
@Component
public class BotController {

    public static final Http HTTP = new Http(Http.DEFAULT_BUILDER.newBuilder()
            .eventListener(new OkHttpEventMetrics("default", Metrics.httpEventCounter))
            .build());

    private static final Logger log = LoggerFactory.getLogger(BotController.class);

    private final PropertyConfigProvider configProvider;
    private final LavalinkManager lavalinkManager;
    private final ShardManager shardManager;
    //central event listener that all events by all shards pass through
    private final EventListenerBoat mainEventListener;
    private final ShutdownHandler shutdownHandler;
    private final DatabaseManager databaseManager;

    //unlimited threads = http://i.imgur.com/H3b7H1S.gif
    //use this executor for various small async tasks
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final StatsAgent statsAgent = new StatsAgent("bot metrics");
    private EntityIO entityIO;


    public BotController(PropertyConfigProvider configProvider, LavalinkManager lavalinkManager, ShardManager shardManager,
                         EventListenerBoat eventListenerBoat, ShutdownHandler shutdownHandler, DatabaseManager databaseManager) {
        this.configProvider = configProvider;
        this.lavalinkManager = lavalinkManager;
        this.shardManager = shardManager;
        this.mainEventListener = eventListenerBoat;
        this.shutdownHandler = shutdownHandler;
        this.databaseManager = databaseManager;

        Runtime.getRuntime().addShutdownHook(new Thread(createShutdownHook(shutdownHandler), "FredBoat main shutdownhook"));
        Metrics.instance().threadPoolCollector.addPool("main-executor", (ThreadPoolExecutor) executor);
    }

    public AppConfig getAppConfig() {
        return configProvider.getAppConfig();
    }

    public AudioSourcesConfig getAudioSourcesConfig() {
        return configProvider.getAudioSourcesConfig();
    }

    public Credentials getCredentials() {
        return configProvider.getCredentials();
    }

    public DatabaseConfig getDatabaseConfig() {
        return configProvider.getDatabaseConfig();
    }

    public EventLoggerConfig getEventLoggerConfig() {
        return configProvider.getEventLoggerConfig();
    }

    public LavalinkConfig getLavalinkConfig() {
        return configProvider.getLavalinkConfig();
    }

    public LavalinkManager getLavalinkManager() {
        return lavalinkManager;
    }

    public ShutdownHandler getShutdownHandler() {
        return shutdownHandler;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    @Nonnull
    public ExecutorService getExecutor() {
        return executor;
    }

    public EventListenerBoat getMainEventListener() {
        return mainEventListener;
    }

    @Nonnull
    protected StatsAgent getStatsAgent() {
        return statsAgent;
    }

    // Can be null during init, but usually not
    public ShardManager getShardManager() {
        return shardManager;
    }

    @Nonnull
    public EntityIO getEntityIO() {
        return entityIO;
    }

    public void setEntityIO(@Nonnull EntityIO entityIO) {
        this.entityIO = entityIO;
    }

    //Shutdown hook
    private Runnable createShutdownHook(ShutdownHandler shutdownHandler) {
        return () -> {
            int shutdownCode = shutdownHandler.getShutdownCode();
            int code = shutdownCode != ShutdownHandler.UNKNOWN_SHUTDOWN_CODE ? shutdownCode : -1;

            FredBoatAgent.shutdown();

            try {
                MusicPersistenceHandler.handlePreShutdown(code);
            } catch (Exception e) {
                log.error("Critical error while handling music persistence.", e);
            }

            executor.shutdown();
        };
    }
}
