package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.Intent;

import com.pbuchman.duduhome.navigation.*;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.ui.MainActivity;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** All values are synthetic ocean points. Executed only behind SafetyChecks' emulator guard. */
final class MapsChecks {
    static final String FIXTURE = "{\"schema_version\":1,\"slots\":["
            + "{\"slot\":1,\"destination\":{\"label\":\"Fixture A\",\"icon\":\"home\",\"latitude\":0,\"longitude\":0}},"
            + "{\"slot\":2,\"destination\":{\"label\":\"Fixture B\",\"icon\":\"squash\",\"latitude\":0.001,\"longitude\":0.002}},"
            + "{\"slot\":3,\"destination\":null}]}";
    static void run(Instrumentation i) throws Exception {
        NavigationConfig config=NavigationConfig.parse(FIXTURE);
        require(config.destination(2)==null, "empty slot");
        require(NavigationConfig.parse(config.serialize()).destination(1).label().equals("Fixture B"), "round trip");
        for(String bad:new String[]{FIXTURE+"{}", FIXTURE.replace("\"slot\":2","\"slot\":1"),
                FIXTURE.replace("\"latitude\":0,", "\"latitude\":true,"),
                FIXTURE.replace("\"longitude\":0}","\"longitude\":181}"),
                FIXTURE.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"),
                FIXTURE.replace("Fixture A","bad\\nline"), FIXTURE.replace("\"home\"","\"unknown\""),
                FIXTURE.replace("\"latitude\":0,", "\"latitude\":NaN,")}) {
            boolean rejected=false;
            try {NavigationConfig.parse(bad);}catch(Exception expected){rejected=true;}
            require(rejected,"malformed or ambiguous document rejected");
        }
        boolean oversized=false;
        try {NavigationStore.readDocument(new ByteArrayInputStream(new byte[16385]));}catch(Exception expected){oversized=true;}
        require(oversized,"bounded document read");
        Intent intent=MapsLauncher.intent(config.destination(1));
        require(Intent.ACTION_VIEW.equals(intent.getAction()) && MapsLauncher.PACKAGE.equals(intent.getPackage()),"explicit Maps only");
        require("google.navigation:q=0.001,0.002&mode=d".equals(intent.getDataString()),"exact driving destination");
        int[] opens={0}, protects={0}; boolean[] available={true};
        MapsLauncher launcher=new MapsLauncher(new MapsLauncher.Transport(){
            public boolean available(Intent v){return available[0];}
            public void open(Intent v){opens[0]++;}
        },()->{protects[0]++;return true;});
        require(launcher.launch(config.destination(0))==MapsLauncher.Result.REQUESTED && opens[0]==1 && protects[0]==1,"single request");
        available[0]=false;
        require(launcher.launch(config.destination(0))==MapsLauncher.Result.MISSING && opens[0]==1 && protects[0]==1,"missing app does not consume");
        MapsLauncher.Transport present = new MapsLauncher.Transport() {
            public boolean available(Intent v) { return true; }
            public void open(Intent v) { opens[0]++; throw new IllegalStateException(); }
        };
        require(new MapsLauncher(present, () -> false).launch(config.destination(0)) == MapsLauncher.Result.STORAGE_FAILED
                && opens[0] == 1, "storage failure prevents external request");
        require(new MapsLauncher(present, () -> true).launch(config.destination(0)) == MapsLauncher.Result.FAILED
                && opens[0] == 2, "launch failure has no fallback or retry");
        boolean utf8 = false;
        try { NavigationStore.readDocument(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28})); }
        catch (Exception expected) { utf8 = true; }
        require(utf8, "invalid UTF-8 rejected");
        var prefs=i.getTargetContext().getSharedPreferences("test_manual_navigation",0);prefs.edit().clear().commit();
        JourneySession session=new JourneySession(prefs,77);
        require(session.ensureBoot() && session.chooseManualNavigation(),"durable manual priority");
        require(new JourneySession(prefs,77).manualNavigationChosen() && !session.reserve() && !session.reserve(JourneySession.Target.SPOTIFY),"survives restart");
        session.observeAwakeCycle(FirstWakeChecks.cold());
        require(session.manualNavigationChosen(),"baseline never reopens media");
        require(session.observeAwakeCycle(FirstWakeChecks.cycle(1))==JourneySession.ObservationResult.REARMED && !session.manualNavigationChosen(),"new wake rearms");
        require(session.chooseManualNavigation(), "manual choice after observed wake");
        require(session.observeAwakeCycle(FirstWakeChecks.cycle(2)) == JourneySession.ObservationResult.BASELINE_COUNTER
                && session.manualNavigationChosen(), "delayed current-cycle read never undoes manual choice");
        AutomationCoordinator queue=new AutomationCoordinator();
        queue.enqueue(1,AutomationCoordinator.Type.CLEANING,HomeEvent.OUTBOUND_CHECKPOINT,0,1);
        queue.enqueue(2,AutomationCoordinator.Type.SPOTIFY,null,0,1);
        require(queue.discardMedia().size()==1 && queue.hasHomeWaiting(),"manual navigation preserves home work");
        NavigationStore store=new NavigationStore(i.getTargetContext());
        store.save(config);
        byte[] before=store.load().serialize().getBytes(StandardCharsets.UTF_8);
        try {store.save(NavigationConfig.parse("invalid"));}catch(Exception expected){/* retain old document */}
        require(java.util.Arrays.equals(before,store.load().serialize().getBytes(StandardCharsets.UTF_8)),"failed import retains old file");
        MainActivity screen=(MainActivity)i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(screen.findViewById(R.id.navigation_slot_1)!=null && screen.findViewById(R.id.navigation_slot_3)!=null,"three tiles rendered");
        require(screen.findViewById(R.id.navigation_slot_1).getContentDescription().toString().contains("Fixture A"),"private label rendered");
        require(screen.findViewById(R.id.navigation_slot_3).getContentDescription().toString().contains("Miejsce 3"),"empty state rendered");
        i.runOnMainSync(screen::finish);
        store.save(NavigationConfig.empty());
        com.pbuchman.duduhome.ui.NavigationImportChecks.run(i);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
