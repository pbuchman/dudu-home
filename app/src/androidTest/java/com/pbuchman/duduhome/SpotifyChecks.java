package com.pbuchman.duduhome;

import com.pbuchman.duduhome.automation.SpotifyController;
import android.media.session.PlaybackState;
import java.util.ArrayList;
import java.util.List;
import static com.pbuchman.duduhome.automation.SpotifyController.Result.*;

/** Fake media transport/clock: no installed Spotify, music, network or physical action. */
final class SpotifyChecks {
    static void run() {
        Fixture f = new Fixture(); f.session.state=PlaybackState.STATE_PLAYING; f.start();
        check(f.results.equals(List.of(PLAYING)) && f.session.plays==0 && f.opens==1 && f.closed==1);
        f = new Fixture(); f.start(); check(f.session.plays==1 && f.results.isEmpty() && f.ms==10000);
        f.controller.start(); f.changed.run(); check(f.session.plays==1 && f.opens==1);
        f.session.state=PlaybackState.STATE_BUFFERING; f.changed.run(); check(f.results.isEmpty());
        f.session.state=PlaybackState.STATE_PLAYING; f.changed.run(); check(f.results.equals(List.of(PLAYING)));
        f.session.state=PlaybackState.STATE_PAUSED; f.changed.run(); f.timeout.run();
        check(f.session.plays==1 && f.results.size()==1); // Manual pause stays paused.
        f = new Fixture(); f.session.state=PlaybackState.STATE_BUFFERING; f.start();
        check(f.session.plays==0); f.timeout.run(); check(f.results.equals(List.of(TIMEOUT)));
        f = new Fixture(); f.matches=List.of(); f.start(); check(f.ms==15000);
        f.timeout.run(); f.matches=List.of(f.session); f.changed.run();
        check(f.results.equals(List.of(NO_SESSION)) && f.session.plays==0);
        f = new Fixture(); f.matches=List.of(); f.start(); f.matches=List.of(f.session); f.changed.run();
        check(f.session.plays==1 && f.ms==10000);
        f = new Fixture(); f.session.local=false; f.start(); check(f.results.equals(List.of(REMOTE)) && f.session.plays==0);
        f = new Fixture(); f.matches=List.of(f.session,new FakeSession()); f.start();
        check(f.results.equals(List.of(AMBIGUOUS)) && f.session.plays==0);
        f = new Fixture(); f.permission=false; f.start(); check(f.results.equals(List.of(NO_ACCESS)) && f.opens==0);
        f = new Fixture(); f.present=false; f.start(); check(f.results.equals(List.of(MISSING)));
        f = new Fixture(); f.session.state=PlaybackState.STATE_BUFFERING; f.start();
        f.permission=false; f.changed.run(); check(f.results.equals(List.of(NO_ACCESS)));
        f = new Fixture(); f.session.throwsOnPlay=true; f.start(); f.changed.run();
        check(f.results.equals(List.of(FAILED)) && f.session.plays==1);
        f = new Fixture(); f.session.actions=0; f.start(); check(f.session.plays==0);
        f.timeout.run(); check(f.results.equals(List.of(TIMEOUT)));
        f = new Fixture(); f.session.state=null; f.start(); check(f.session.plays==0);
        f.controller.cancel(); f.session.state=PlaybackState.STATE_PAUSED; f.changed.run();
        check(f.results.equals(List.of(CANCELLED)) && f.session.plays==0);
        f = new Fixture(); f.session.state=PlaybackState.STATE_BUFFERING; f.start();
        f.allowed=false; f.session.state=PlaybackState.STATE_PAUSED; f.changed.run(); check(f.session.plays==0);
        f.allowed=true; f.controller.priorityChanged(); check(f.session.plays==1);
        f = new Fixture(); f.session.state=PlaybackState.STATE_BUFFERING; f.start();
        f.matches=List.of(new FakeSession()); f.changed.run(); check(f.results.equals(List.of(UNKNOWN)));
        f = new Fixture(); f.start(); f.matches=List.of(); f.changed.run(); check(f.results.equals(List.of(NO_SESSION)));
    }
    static final class FakeSession implements SpotifyController.Session {
        Object key=new Object(); boolean local=true,throwsOnPlay;
        Integer state=PlaybackState.STATE_PAUSED; long actions=PlaybackState.ACTION_PLAY; int plays;
        public Object key(){return key;} public boolean local(){return local;}
        public Integer state(){return state;} public long actions(){return actions;}
        public void play(){plays++; if(throwsOnPlay)throw new IllegalStateException();}
    }
    static final class Fixture implements SpotifyController.Access, SpotifyController.Timer {
        FakeSession session=new FakeSession(); List<SpotifyController.Session> matches=List.of(session);
        List<SpotifyController.Result> results=new ArrayList<>();
        boolean permission=true,present=true,allowed=true; int opens,closed; long ms;
        Runnable changed=()->{},timeout=()->{};
        SpotifyController controller=new SpotifyController(this,this,()->allowed,()->{},results::add);
        void start(){controller.start();}
        public boolean permitted(){return permission;}
        public boolean open(){opens++;return present;}
        public List<SpotifyController.Session> sessions(){return matches;}
        public void observe(Runnable r){changed=r;}
        public void close(){closed++;}
        public void replace(Runnable r,long delay){timeout=r;ms=delay;}
        public void cancel(){}
    }
    private static void check(boolean ok){if(!ok)throw new AssertionError("Spotify regression");}
}
