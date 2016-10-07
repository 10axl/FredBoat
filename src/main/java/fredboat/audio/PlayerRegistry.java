package fredboat.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.dv8tion.jda.JDA;
import net.dv8tion.jda.entities.Guild;

public class PlayerRegistry {

    private static HashMap<String, GuildPlayer> registry = new HashMap<>();
    public static final float DEFAULT_VOLUME = 1f;
    public static JDA jda;

    public static void init(JDA api) {
        jda = api;
    }

    public static void put(String k, GuildPlayer v) {
        registry.put(k, v);
    }

    public static GuildPlayer get(Guild guild) {
        return get(guild.getId());
    }

    public static GuildPlayer get(String k) {
        GuildPlayer player = registry.get(k);
        if (player == null) {
            player = new GuildPlayer(jda, jda.getGuildById(k));
            player.setVolume(DEFAULT_VOLUME);
            registry.put(k, player);
        }
        return player;
    }

    public static GuildPlayer getExisting(Guild guild) {
        return getExisting(guild.getId());
    }

    public static GuildPlayer getExisting(String k) {
        if (registry.containsKey(k)) {
            return get(k);
        }
        return null;
    }

    public static GuildPlayer remove(String k) {
        return registry.remove(k);
    }

    public static HashMap<String, GuildPlayer> getRegistry() {
        return registry;
    }

    public static List<GuildPlayer> getPlayingPlayers() {
        ArrayList<GuildPlayer> plrs = new ArrayList<>();

        for (GuildPlayer plr : registry.values()) {
            if (plr.isPlaying()) {
                plrs.add(plr);
            }
        }

        return plrs;
    }

    public static void destroyPlayer(Guild g) {
        destroyPlayer(g.getId());
    }

    public static void destroyPlayer(String g) {
        GuildPlayer player = getExisting(g);
        if (player != null) {
            player.destroy();
            remove(g);
        }
    }

}
