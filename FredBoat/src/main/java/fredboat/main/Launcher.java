package fredboat.main;

import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary;
import fredboat.agent.*;
import fredboat.api.API;
import fredboat.command.admin.SentryDsnCommand;
import fredboat.commandmeta.CommandInitializer;
import fredboat.commandmeta.CommandRegistry;
import fredboat.config.DatabaseConfig;
import fredboat.config.PropertyConfigProvider;
import fredboat.db.DatabaseManager;
import fredboat.db.EntityIO;
import fredboat.feature.I18n;
import fredboat.feature.metrics.Metrics;
import fredboat.shared.constant.BotConstants;
import fredboat.shared.constant.ExitCodes;
import fredboat.util.AppInfo;
import fredboat.util.DiscordUtil;
import fredboat.util.GitRepoState;
import fredboat.util.TextUtils;
import fredboat.util.rest.Http;
import fredboat.util.rest.OpenWeatherAPI;
import fredboat.util.rest.models.weather.RetrievedWeather;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDAInfo;
import okhttp3.Credentials;
import okhttp3.Response;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import space.npstr.sqlsauce.DatabaseConnection;
import space.npstr.sqlsauce.DatabaseException;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * The class responsible for launching FredBoat
 */
@SpringBootApplication
@EnableAutoConfiguration(exclude = { //we handle these ourselves
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
@ComponentScan(basePackages = {"fredboat.main", "fredboat.config", "fredboat.audio.player", "fredboat.event"})
public class Launcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Launcher.class);
    public static final long START_TIME = System.currentTimeMillis();
    private static BotController BC; //temporary hack access to the bot context
    private final PropertyConfigProvider configProvider;
    private final BotController botController;

    public static void main(String[] args) throws IllegalArgumentException, DatabaseException {
        //just post the info to the console
        if (args.length > 0 &&
                (args[0].equalsIgnoreCase("-v")
                        || args[0].equalsIgnoreCase("--version")
                        || args[0].equalsIgnoreCase("-version"))) {
            System.out.println("Version flag detected. Printing version info, then exiting.");
            System.out.println(getVersionInfo());
            System.out.println("Version info printed, exiting.");
            return;
        }
        log.info(getVersionInfo());
        String javaVersionMinor = null;
        try {
            javaVersionMinor = System.getProperty("java.version").split("\\.")[1];
        } catch (Exception e) {
            log.error("Exception while checking if java 8", e);
        }

        if (!Objects.equals(javaVersionMinor, "8")) {
            log.warn("\n\t\t __      ___   ___ _  _ ___ _  _  ___ \n" +
                    "\t\t \\ \\    / /_\\ | _ \\ \\| |_ _| \\| |/ __|\n" +
                    "\t\t  \\ \\/\\/ / _ \\|   / .` || || .` | (_ |\n" +
                    "\t\t   \\_/\\_/_/ \\_\\_|_\\_|\\_|___|_|\\_|\\___|\n" +
                    "\t\t                                      ");
            log.warn("FredBoat only officially supports Java 8. You are running Java {}", System.getProperty("java.version"));
        }

        System.setProperty("spring.main.web-application-type", "none"); //todo enable again after spark API is migrated
        SpringApplication.run(Launcher.class, args);
    }

    public static BotController getBotController() {
        return BC;
    }

    public Launcher(BotController botController, PropertyConfigProvider configProvider) {
        this.botController = botController;
        Launcher.BC = botController;
        this.configProvider = configProvider;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        //create the sentry appender as early as possible
        String sentryDsn = configProvider.getCredentials().getSentryDsn();
        if (!sentryDsn.isEmpty()) {
            SentryDsnCommand.turnOn(sentryDsn);
        } else {
            SentryDsnCommand.turnOff();
        }

        Metrics.setup();

        I18n.start();

        try {
            API.start();
        } catch (Exception e) {
            log.info("Failed to ignite Spark, FredBoat API unavailable", e);
        }

        //dont run migrations or validate the db from the patron bot
        boolean migrateAndValidate = DiscordUtil.getBotId(configProvider.getCredentials()) == BotConstants.PATRON_BOT_ID;
        DatabaseConfig dbConf = configProvider.getDatabaseConfig();
        DatabaseManager dbManager = new DatabaseManager(Metrics.instance().hibernateStats, Metrics.instance().hikariStats,
                dbConf.getHikariPoolSize(), configProvider.getAppConfig().getDistribution().name(), migrateAndValidate,
                dbConf.getMainJdbcUrl(), dbConf.getMainSshTunnelConfig(),
                dbConf.getCacheJdbcUrl(), dbConf.getCacheSshTunnelConfig(),
                (puName, dataSource, properties, entityPackages) -> {
                    LocalContainerEntityManagerFactoryBean emfb = new LocalContainerEntityManagerFactoryBean();
                    emfb.setDataSource(dataSource);
                    emfb.setPackagesToScan(entityPackages.toArray(new String[entityPackages.size()]));

                    JpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
                    emfb.setJpaVendorAdapter(vendorAdapter);
                    emfb.setJpaProperties(properties);

                    emfb.afterPropertiesSet(); //initiate creation of the native emf
                    return emfb.getNativeEntityManagerFactory();
                });

        //attempt to connect to the database a few times
        // this is relevant in a dockerized environment because after a reboot there is no guarantee that the db
        // container will be started before the fredboat one
        int dbConnectionAttempts = 0;
        DatabaseConnection mainDbConn = null;
        while ((mainDbConn == null || !mainDbConn.isAvailable()) && dbConnectionAttempts++ < 10) {
            try {
                if (mainDbConn != null) {
                    mainDbConn.shutdown();
                }
                mainDbConn = dbManager.getMainDbConn();
            } catch (Exception e) {
                log.info("Could not connect to the database. Retrying in a moment...", e);
                Thread.sleep(6000);
            }
        }
        if (mainDbConn == null || !mainDbConn.isAvailable()) {
            log.error("Could not establish database connection. Exiting...");
            botController.getShutdownHandler().shutdown(ExitCodes.EXIT_CODE_ERROR);
            return;
        }
        FredBoatAgent.start(new DBConnectionWatchdogAgent(mainDbConn));

        try {
            dbManager.getCacheDbConn();
        } catch (Exception e) {
            log.error("Exception when connecting to cache db", e);
            botController.getShutdownHandler().shutdown(ExitCodes.EXIT_CODE_ERROR);
        }
        Metrics.instance().hibernateStats.register(); //call this exactly once after all db connections have been created
        botController.setDatabaseManager(dbManager);
        botController.setEntityIO(new EntityIO(dbManager.getMainDbWrapper(), dbManager.getCacheDbWrapper(), configProvider));

        //Commands
        CommandInitializer.initCommands();
        log.info("Loaded commands, registry size is " + CommandRegistry.getTotalSize());

        if (!configProvider.getAppConfig().isPatronDistribution()) {
            log.info("Starting VoiceChannelCleanupAgent.");
            FredBoatAgent.start(new VoiceChannelCleanupAgent());
        } else {
            log.info("Skipped setting up the VoiceChannelCleanupAgent, " +
                    "either running Patron distro or overridden by temp config");
        }

        ExecutorService executor = botController.getExecutor();

        //Check MAL creds
        executor.submit(this::hasValidMALLogin);

        //Check imgur creds
        executor.submit(this::hasValidImgurCredentials);

        //Check OpenWeather key
        executor.submit(this::hasValidOpenWeatherKey);

        String carbonKey = configProvider.getCredentials().getCarbonKey();
        if (configProvider.getAppConfig().isMusicDistribution() && !carbonKey.isEmpty()) {
            FredBoatAgent.start(new CarbonitexAgent(carbonKey));
        }

        enableMetrics();
    }

    // ################################################################################
    // ##                     Login / credential tests
    // ################################################################################

    private boolean hasValidMALLogin() {
        String malUser = configProvider.getCredentials().getMalUser();
        String malPassWord = configProvider.getCredentials().getMalPassword();
        if (malUser.isEmpty() || malPassWord.isEmpty()) {
            log.info("MAL credentials not found. MAL related commands will not be available.");
            return false;
        }

        Http.SimpleRequest request = BotController.HTTP.get("https://myanimelist.net/api/account/verify_credentials.xml")
                .auth(Credentials.basic(malUser, malPassWord));

        try (Response response = request.execute()) {
            if (response.isSuccessful()) {
                log.info("MAL login successful");
                return true;
            } else {
                //noinspection ConstantConditions
                log.warn("MAL login failed with {}\n{}", response.toString(), response.body().string());
            }
        } catch (IOException e) {
            log.warn("MAL login failed, it seems to be down.", e);
        }
        return false;
    }

    private boolean hasValidImgurCredentials() {
        String imgurClientId = configProvider.getCredentials().getImgurClientId();
        if (imgurClientId.isEmpty()) {
            log.info("Imgur credentials not found. Commands relying on Imgur will not work properly.");
            return false;
        }
        Http.SimpleRequest request = BotController.HTTP.get("https://api.imgur.com/3/credits")
                .auth("Client-ID " + imgurClientId);
        try (Response response = request.execute()) {
            //noinspection ConstantConditions
            String content = response.body().string();
            if (response.isSuccessful()) {
                JSONObject data = new JSONObject(content).getJSONObject("data");
                //https://api.imgur.com/#limits
                //at the time of the introduction of this code imgur offers daily 12500 and hourly 500 GET requests for open source software
                //hitting the daily limit 5 times in a month will blacklist the app for the rest of the month
                //we use 3 requests per hour (and per restart of the bot), so there should be no problems with imgur's rate limit
                int hourlyLimit = data.getInt("UserLimit");
                int hourlyLeft = data.getInt("UserRemaining");
                long seconds = data.getLong("UserReset") - (System.currentTimeMillis() / 1000);
                String timeTillReset = String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, (seconds % 60));
                int dailyLimit = data.getInt("ClientLimit");
                int dailyLeft = data.getInt("ClientRemaining");
                log.info("Imgur credentials are valid. " + hourlyLeft + "/" + hourlyLimit +
                        " requests remaining this hour, resetting in " + timeTillReset + ", " +
                        dailyLeft + "/" + dailyLimit + " requests remaining today.");
                return true;
            } else {
                log.warn("Imgur login failed with {}\n{}", response.toString(), content);
            }
        } catch (IOException e) {
            log.warn("Imgur login failed, it seems to be down.", e);
        }
        return false;
    }

    /**
     * Method to check if there is an error to retrieve open weather data.
     *
     * @return True if it can retrieve data, else return false.
     */
    private boolean hasValidOpenWeatherKey() {
        if ("".equals(configProvider.getCredentials().getOpenWeatherKey())) {
            log.warn("Open Weather API credentials not found. Weather related commands will not work properly.");
            return false;
        }

        OpenWeatherAPI api = new OpenWeatherAPI();
        RetrievedWeather weather = api.getCurrentWeatherByCity("san francisco");

        boolean isSuccess = !(weather == null || weather.isError());

        if (isSuccess) {
            log.info("Open Weather API check successful");
        } else {
            log.warn("Open Weather API check failed. It may be down, the provided credentials may be invalid, or temporarily blocked.");
        }
        return isSuccess;
    }

    //returns true if all registered shards are reporting back as CONNECTED, false otherwise
    private boolean areWeReadyYet() {
        for (JDA.Status status : botController.getShardManager().getStatuses().values()) {
            if (status != JDA.Status.CONNECTED) {
                return false;
            }
        }

        return true;
    }

    private void enableMetrics() throws InterruptedException {
        //wait for all shards to ready up before requesting a total count of jda entities
        while (!areWeReadyYet()) {
            Thread.sleep(1000);
        }

        StatsAgent statsAgent = botController.getStatsAgent();
        //force some metrics to be populated, then turn on metrics to be served
        try {
            BotMetrics.JdaEntityCounts jdaEntityCountsTotal = BotMetrics.getJdaEntityCountsTotal();
            try {
                jdaEntityCountsTotal.count(() -> botController.getShardManager().getShards());
            } catch (Exception ignored) {
            }

            statsAgent.addAction(new BotMetrics.JdaEntityStatsCounter(
                    () -> jdaEntityCountsTotal.count(() -> botController.getShardManager().getShards())));

            if (DiscordUtil.isOfficialBot(configProvider.getCredentials())) {
                BotMetrics.DockerStats dockerStats = BotMetrics.getDockerStats();
                try {
                    dockerStats.fetch();
                } catch (Exception ignored) {
                }
                statsAgent.addAction(dockerStats::fetch);
            }
        } finally {
            FredBoatAgent.start(statsAgent);
            API.turnOnMetrics();
        }
    }

    private static String getVersionInfo() {
        return "\n\n" +
                "  ______            _ ____              _   \n" +
                " |  ____|          | |  _ \\            | |  \n" +
                " | |__ _ __ ___  __| | |_) | ___   __ _| |_ \n" +
                " |  __| '__/ _ \\/ _` |  _ < / _ \\ / _` | __|\n" +
                " | |  | | |  __/ (_| | |_) | (_) | (_| | |_ \n" +
                " |_|  |_|  \\___|\\__,_|____/ \\___/ \\__,_|\\__|\n\n"

                + "\n\tVersion:       " + AppInfo.getAppInfo().VERSION
                + "\n\tBuild:         " + AppInfo.getAppInfo().BUILD_NUMBER
                + "\n\tCommit:        " + GitRepoState.getGitRepositoryState().commitIdAbbrev + " (" + GitRepoState.getGitRepositoryState().branch + ")"
                + "\n\tCommit time:   " + TextUtils.asTimeInCentralEurope(GitRepoState.getGitRepositoryState().commitTime * 1000)
                + "\n\tJVM:           " + System.getProperty("java.version")
                + "\n\tJDA:           " + JDAInfo.VERSION
                + "\n\tLavaplayer     " + PlayerLibrary.VERSION
                + "\n";
    }
}
