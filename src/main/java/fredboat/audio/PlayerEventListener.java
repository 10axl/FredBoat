package fredboat.audio;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.dv8tion.jda.player.hooks.PlayerListenerAdapter;
import net.dv8tion.jda.player.hooks.events.NextEvent;
import net.dv8tion.jda.player.hooks.events.PauseEvent;
import net.dv8tion.jda.player.hooks.events.PlayEvent;
import net.dv8tion.jda.player.hooks.events.SkipEvent;
import net.dv8tion.jda.player.source.AudioSource;
import net.dv8tion.jda.player.source.RemoteSource;
import org.slf4j.LoggerFactory;

public class PlayerEventListener extends PlayerListenerAdapter {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PlayerEventListener.class);
    
    public final GuildPlayer player;
    public Pattern youtubeIdPattern = Pattern.compile("youtube.com\\/watch\\?v=(.+)");

    public PlayerEventListener(GuildPlayer player) {
        this.player = player;
    }
    
    @Override
    public void onPause(PauseEvent event) {
        player.lastTimePaused = System.currentTimeMillis();
    }
    
    public void onNewTrackPlaying(){
        AudioSource current = player.getCurrentAudioSource();
        if(current != null && current instanceof RemoteSource){
            Matcher m = youtubeIdPattern.matcher(current.getSource());
            if(m.find()){
                player.lastYoutubeVideoId = m.group(1);
            }
        }
    }

    @Override
    public void onPlay(PlayEvent event) {
        onNewTrackPlaying();
        log.debug("Play!");
    }

    @Override
    public void onNext(NextEvent event) {
        onNewTrackPlaying();
        log.debug("Next!");
    }

    @Override
    public void onSkip(SkipEvent event) {
        onNewTrackPlaying();
        log.debug("Skip!");
    }
    
}
