package com.pbuchman.duduhome.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Synthetic, dependency-free durability/privacy/retention checks. No device or command dispatch. */
public final class DiagnosticChecks {
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    private static List<String> rows(Path path) throws Exception {
        List<String> result=new ArrayList<>();
        try(var files=Files.list(path)){for(Path file:files.toList())result.addAll(Files.readAllLines(file));}
        return result;
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1) {
            check(rows(Path.of(args[0])).stream().anyMatch(row -> row.contains("QUEUE_WAIT")),"separate process reads durable waiting evidence");
            return;
        }
        Path root=Files.createTempDirectory("dudu-journal-synthetic");
        try {
            long now=RollingJournal.MAX_AGE_MS*2;
            var journal=new RollingJournal(root.toFile(),"current.txt","previous.txt",8,128,120);
            // Reads before any future marker/command demonstrate synchronous persistence.
            check(journal.append(now,now+" 1 PROCESS=1 SEQ=1 ID=3 QUEUE_WAIT"),"append");
            check(rows(root).stream().anyMatch(s->s.contains("QUEUE_WAIT")),"durable before command");
            journal=new RollingJournal(root.toFile(),"current.txt","previous.txt",8,128,120);
            check(rows(root).size()==1,"recreated store keeps waiting evidence");
            Process reader=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-cp",System.getProperty("java.class.path"),DiagnosticChecks.class.getName(),root.toString()).inheritIO().start();
            boolean exited=reader.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
            if(!exited)reader.destroyForcibly();
            check(exited && reader.exitValue()==0,"separate JVM restart retains waiting evidence before send");
            for(int i=0;i<40;i++)check(journal.append(now+i, (now+i)+" 1 PROCESS=2 SEQ="+i+" QUEUE_WAIT ID=3"),"rotation append");
            try(var files=Files.list(root)){
                var all=files.toList();check(all.size()<=8,"file count");
                for(Path file:all)check(Files.size(file)<=128,"segment hard bound");
            }
            check(journal.append(now+RollingJournal.MAX_AGE_MS+100,
                    (now+RollingJournal.MAX_AGE_MS+100)+" 1 PROCESS=3 SEQ=1 FRESH"),"expiry append");
            check(rows(root).size()==1 && rows(root).get(0).contains("FRESH"),"row expiry after restart and append");
            check(!journal.append(now,"a".repeat(121)),"reject large row");
            check(!journal.append(now,"line\nbreak"),"reject multiline");
            Path recovery=root.resolve("recovery");Files.createDirectory(recovery);
            Files.writeString(recovery.resolve("current.txt"),now+" 1 COMPLETE\n"+now+" 2 PARTIAL");
            var recovered=new RollingJournal(recovery.toFile(),"current.txt","previous.txt",8,128,120);
            check(recovered.append(now,now+" 3 AFTER_RESTART"),"append after interrupted tail");
            var recoveredRows=rows(recovery);
            check(recoveredRows.size()==2 && recoveredRows.get(0).endsWith("COMPLETE")
                    && recoveredRows.get(1).endsWith("AFTER_RESTART"),"only incomplete tail discarded, no merged events");
            var menuGrace=new QueueBlockers();menuGrace.add("MENU_VISIBLE");menuGrace.add("YANOSIK_GRACE");
            check(menuGrace.reason().equals("MENU_VISIBLE") && menuGrace.mask()==144,"simultaneous menu+grace");
            var cleanupPriority=new QueueBlockers();cleanupPriority.add("PRIORITY");cleanupPriority.add("RESOURCE_CLEANUP");
            check(cleanupPriority.reason().equals("RESOURCE_CLEANUP") && cleanupPriority.mask()==65,"simultaneous cleanup+priority");
            check(new QueueBlockers().state().equals("READY"),"ready work is not falsely blocked");
            var labels=new PresentationCoalescer();
            check(labels.changed("overlay",true,"Synthetic locality","Synthetic road",1),"initial displayed label");
            check(!labels.changed("overlay",true,"Synthetic locality","Synthetic road",1),"same label on newer fix/frame is coalesced");
            check(labels.changed("overlay",false,"Synthetic locality","Synthetic road",1),"actual hide transition");
            check(labels.changed("overlay",true,"Synthetic locality","Synthetic road",2),"new generation is distinct");
            check(labels.changed("controller_reset_gps",false,"","",3),"reset without a new GPS fix");
            var coalescer=new BlockCoalescer();
            check(coalescer.emit(1,"MENU_VISIBLE",100),"first blocker");
            check(!coalescer.emit(1,"MENU_VISIBLE",60099),"coalesce under minute");
            check(coalescer.emit(1,"MENU_VISIBLE",60100),"minute summary");
            check(coalescer.emit(1,"RESOURCE_CLEANUP",60101),"changed blocker immediate");
            check(coalescer.emit(2,"MENU_VISIBLE",60101),"distinct attempts");
            check(CategoryFormat.valid("QUEUE_STATE ACTION=SPOTIFY STATE=WAITING REASON=MENU_VISIBLE BLOCKER_ID=0 BLOCKER_ACTION=NONE"),"typed category");
            for(String invalid:List.of("GPS LAT=1.5","TOKEN=ABC","STATE TOKEN=ABC","ACTION PHONE=000000000",
                    "ACTION LOGIN=EXAMPLE","ACTION TRANSCRIPT=TEXT","ACTION ID=1.234","ACTION ID=1\nSECRET","ACTION arbitrary"))
                check(!CategoryFormat.valid(invalid),"reject private/unstructured field");
            Path blocked=root.resolve("blocked");Files.createDirectory(blocked);
            Files.createDirectory(blocked.resolve("current.txt"));
            check(!new RollingJournal(blocked.toFile(),"current.txt","previous.txt",8,128,120).append(now,now+" 1 X"),
                    "storage failure returns controlled false without throwing or creating command state");
            Path link=root.resolve("linked");Files.createSymbolicLink(link,root);
            check(!new RollingJournal(link.toFile(),"current.txt","previous.txt",8,128,120).append(now,now+" 1 X"),"reject symlink directory");
            check(RollingJournal.timestamp("{\"schema\":2,\"wall_ms\":123,\"event\":\"synthetic\"}")==123,"private JSON expiry timestamp");
            Path capacity=root.resolve("expiry-capacity");Files.createDirectory(capacity);
            String mixed=now+" 1 OLD\n"+(now+5000)+" 2 RETAIN\n";
            Files.writeString(capacity.resolve("current.txt"),mixed);Files.writeString(capacity.resolve("previous.txt"),mixed);
            for(int n=2;n<8;n++)Files.writeString(capacity.resolve("previous.txt."+n),mixed);
            check(new RollingJournal(capacity.toFile(),"current.txt","previous.txt",8,128,120)
                    .append(now+RollingJournal.MAX_AGE_MS+1,(now+RollingJournal.MAX_AGE_MS+1)+" 3 NEW"),"partial expiry at full file capacity");
            try(var files=Files.list(capacity)){check(files.count()<=8,"expiry replacement respects eight-file capacity");}
            check(rows(capacity).stream().noneMatch(row->row.endsWith("OLD")),"partial rows expired at full capacity");
            Path volume=root.resolve("volume");Files.createDirectory(volume);
            String row=now+" 1 PROCESS=1 SEQ=1 VOLUME_TEST ID=1\n";
            String segment=row.repeat((1024*1024)/row.length());
            Files.writeString(volume.resolve("current.txt"),segment);
            Files.writeString(volume.resolve("previous.txt"),segment);
            for(int n=2;n<8;n++)Files.writeString(volume.resolve("previous.txt."+n),segment);
            long start=System.nanoTime();
            check(new RollingJournal(volume.toFile(),"current.txt","previous.txt",8,1024*1024,512)
                    .append(now,now+" 2 FIRST_APPEND"),"bounded full-volume first append");
            System.out.println("INFO: first append scanning at most eight MiB on host ms="+(System.nanoTime()-start)/1000000);
            System.out.println("PASS: immediate durability, restart, rotation, seven-day expiry, coalescing and privacy rejection");
        } finally {
            try(var files=Files.walk(root)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
