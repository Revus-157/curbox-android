package neth.iecal.curbox;

import android.app.Service;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Separate test-APK UID; pure Java because this process has no target-app classloader. */
public final class SyntheticPlayingMediaService extends Service {
    private MediaSession session;
    @Override public void onCreate() {
        super.onCreate();
        if (!Build.MODEL.contains("sdk") || !getPackageName().endsWith(".debug.test"))
            throw new IllegalStateException("Synthetic player requires the isolated emulator test APK");
        session = new MediaSession(this, "unrelated-synthetic-player");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPause() {
                session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build());
            }
        }, new Handler(Looper.getMainLooper()));
        session.setActive(true);
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build());
    }
    @Override public IBinder onBind(Intent intent) { return new Binder(); }
    @Override public void onDestroy() {
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }
}
