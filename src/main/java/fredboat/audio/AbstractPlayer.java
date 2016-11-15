package fredboat.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import fredboat.FredBoat;
import fredboat.audio.queue.ITrackProvider;
import fredboat.audio.source.PlaylistImportSourceManager;
import fredboat.util.DistributionEnum;
import net.dv8tion.jda.audio.AudioSendHandler;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractPlayer extends AudioEventAdapter implements AudioSendHandler {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(AbstractPlayer.class);

    private static AudioPlayerManager playerManager;
    AudioPlayer player;
    ITrackProvider audioTrackProvider;
    private AudioFrame lastFrame = null;

    @SuppressWarnings("LeakingThisInConstructor")
    protected AbstractPlayer() {
        initAudioPlayerManager();
        player = playerManager.createPlayer();

        player.addListener(this);
    }

    private static void initAudioPlayerManager() {
        if (playerManager == null) {
            playerManager = new DefaultAudioPlayerManager();
            registerSourceManagers(playerManager);

            //Patrons get higher quality
            AudioConfiguration.ResamplingQuality quality = FredBoat.distribution == DistributionEnum.PATRON ? AudioConfiguration.ResamplingQuality.HIGH : AudioConfiguration.ResamplingQuality.LOW;
            playerManager.getConfiguration().setResamplingQuality(quality);
            playerManager.enableGcMonitoring();
        }
    }

    public static AudioPlayerManager registerSourceManagers(AudioPlayerManager mng) {
        mng.registerSourceManager(new YoutubeAudioSourceManager());
        mng.registerSourceManager(new SoundCloudAudioSourceManager());
        mng.registerSourceManager(new BandcampAudioSourceManager());
        mng.registerSourceManager(new PlaylistImportSourceManager());
        mng.registerSourceManager(new TwitchStreamAudioSourceManager());
        mng.registerSourceManager(new VimeoAudioSourceManager());
        mng.registerSourceManager(new HttpAudioSourceManager());
        
        return mng;
    }

    public void play() {
        if (player.isPaused()) {
            player.setPaused(false);
        }
        if (player.getPlayingTrack() == null) {
            play0(false);
        }

    }

    public void setPause(boolean pause) {
        if (pause) {
            player.setPaused(true);
        } else {
            player.setPaused(false);
            play();
        }
    }

    public void pause() {
        player.setPaused(true);
    }

    public void stop() {
        player.stopTrack();
    }

    public void skip() {
        player.stopTrack();
        play0(true);
    }

    public boolean isQueueEmpty() {
        return getPlayingTrack() == null && audioTrackProvider.isEmpty();
    }

    public long getCurrentTimestamp() {
        return player.getPlayingTrack().getPosition();
    }

    public AudioTrack getPlayingTrack() {
        if (player.getPlayingTrack() == null) {
            play0(true);//Ensure we have something to return, unless the queue is really empty
        }
        return player.getPlayingTrack();
    }

    public List<AudioTrack> getQueuedTracks() {
        return audioTrackProvider.getAsList();
    }

    public List<AudioTrack> getRemainingTracks() {
        //Includes currently playing track, which comes first
        if (getPlayingTrack() != null) {
            ArrayList<AudioTrack> list = new ArrayList<>();
            list.add(getPlayingTrack());
            list.addAll(getQueuedTracks());
            return list;
        } else {
            return getQueuedTracks();
        }
    }

    public void setVolume(float vol) {
        player.setVolume((int) (vol * 100));
    }

    public float getVolume() {
        return ((float) player.getVolume()) / 100;
    }

    public void setAudioTrackProvider(ITrackProvider audioTrackProvider) {
        this.audioTrackProvider = audioTrackProvider;
    }

    public ITrackProvider getAudioTrackProvider() {
        return audioTrackProvider;
    }

    public static AudioPlayerManager getPlayerManager() {
        initAudioPlayerManager();
        return playerManager;
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        //If we *were* interrupted, we would just invoke play0()
        if (endReason == AudioTrackEndReason.FINISHED) {
            play0(false);
        }
    }

    public void play0(boolean skipped) {
        if (audioTrackProvider != null) {
            player.playTrack(audioTrackProvider.provideAudioTrack(skipped));
        } else {
            log.warn("TrackProvider doesn't exist");
        }
    }

    public void destroy() {
        player.destroy();
    }

    @Override
    public byte[] provide20MsAudio() {
        return lastFrame.data;
    }

    @Override
    public boolean canProvide() {
        lastFrame = player.provide();

        return lastFrame != null;
    }

    @Override
    public boolean isOpus() {
        return true;
    }

    public boolean isPlaying() {
        return player.getPlayingTrack() != null && !player.isPaused();
    }

    public boolean isPaused() {
        return player.isPaused();
    }

}
