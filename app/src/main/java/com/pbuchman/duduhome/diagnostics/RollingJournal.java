package com.pbuchman.duduhome.diagnostics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Private bounded append-only evidence, never a command queue. No Android dependency. */
public final class RollingJournal {
    public static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private final File directory;
    private final String current, previous;
    private final int files, maxRowBytes;
    private final long maxBytes;
    private long lastPrune = Long.MIN_VALUE, nextExpiry=Long.MIN_VALUE;

    public RollingJournal(File directory, String current, String previous, int files, long maxBytes, int maxRowBytes) {
        if (files < 1 || maxBytes < maxRowBytes || maxRowBytes < 1
                || !current.matches("[a-z.-]+") || !previous.matches("[a-z.-]+"))
            throw new IllegalArgumentException("JOURNAL_CONFIGURATION");
        this.directory=directory; this.current=current; this.previous=previous;
        this.files=files; this.maxBytes=maxBytes; this.maxRowBytes=maxRowBytes;
    }
    private File file(int index) {
        return new File(directory,index==0 ? current : index==1 ? previous : previous+"."+index);
    }
    private static boolean safe(File file) { return !Files.isSymbolicLink(file.toPath()) && (!file.exists() || file.isFile()); }
    private static void ownerOnly(File file) throws IOException {
        if (!file.setReadable(false,false) || !file.setWritable(false,false)
                || !file.setReadable(true,true) || !file.setWritable(true,true)) throw new IOException("JOURNAL_PERMISSIONS");
    }
    /** Returns only after flush+fsync; errors never leak private values. */
    public synchronized boolean append(long wall, String line) {
        byte[] row=(line+"\n").getBytes(StandardCharsets.UTF_8);
        if (row.length>maxRowBytes || line.indexOf('\n')>=0 || line.indexOf('\r')>=0) return false;
        try {
            if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) return false;
            for (int i=0;i<files;i++) if (!safe(file(i))) return false;
            if (lastPrune==Long.MIN_VALUE || wall<lastPrune || wall>=nextExpiry) {
                prune(wall); lastPrune=wall;
            }
            if (file(0).length()+row.length>maxBytes) {
                if (file(files-1).exists() && !file(files-1).delete()) return false;
                for (int i=files-2;i>=0;i--) if (file(i).exists() && !file(i).renameTo(file(i+1))) return false;
            }
            try (FileOutputStream output=new FileOutputStream(file(0),true)) {
                ownerOnly(file(0)); output.write(row); output.flush(); output.getFD().sync();
            }
            nextExpiry=Math.min(nextExpiry,wall+MAX_AGE_MS+1);
            return true;
        } catch (IOException | SecurityException ignored) { lastPrune=Long.MIN_VALUE;return false; }
    }
    /** The leading wall timestamp is identical in both generic text and private JSON rows. */
    static long timestamp(String line) {
        try {
            if (line.startsWith("{")) {
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\"wall_ms\\\"\\s*:\\s*([0-9]+)").matcher(line);
                return m.find() ? Long.parseLong(m.group(1)) : -1;
            }
            return Long.parseLong(line.substring(0,line.indexOf(' ')));
        } catch (RuntimeException ignored) { return -1; }
    }
    /** A killed append can leave an incomplete last row; never concatenate a new event to it. */
    private void recoverInterruptedTail(File target) throws IOException {
        try(java.io.RandomAccessFile data=new java.io.RandomAccessFile(target,"rw")) {
            long length=data.length();
            if(length==0)return;
            data.seek(length-1);if(data.read()=='\n')return;
            long first=Math.max(0,length-maxRowBytes-1),end=-1;
            for(long cursor=length-1;cursor>=first;cursor--){data.seek(cursor);if(data.read()=='\n'){end=cursor;break;}}
            data.setLength(end+1);data.getFD().sync();
        }
    }
    private void prune(long wall) throws IOException {
        long cutoff=wall-MAX_AGE_MS;
        nextExpiry=Long.MAX_VALUE;
        File interruptedPrune=new File(directory,current+".pruning");
        if(!safe(interruptedPrune) || (interruptedPrune.exists() && !interruptedPrune.delete()))
            throw new IOException("JOURNAL_INTERRUPTED_PRUNE");
        for (int i=0;i<files;i++) {
            File target=file(i);
            if (!target.exists()) continue;
            if (target.length()>maxBytes) { if(!target.delete()) throw new IOException("JOURNAL_OVERSIZE"); continue; }
            recoverInterruptedTail(target);
            List<String> lines=Files.readAllLines(target.toPath(),StandardCharsets.UTF_8), retained=new ArrayList<>();
            for (String row:lines) { long time=timestamp(row); if (time>=0 && time>=cutoff) { retained.add(row); nextExpiry=Math.min(nextExpiry,time+MAX_AGE_MS+1); } }
            if (retained.size()==lines.size()) continue;
            if (retained.isEmpty()) { if(!target.delete())throw new IOException("JOURNAL_EXPIRY"); continue; }
            // The temporary replacement counts toward the hard file/byte budget too.
            int present=0;
            for(int n=0;n<files;n++)if(file(n).exists())present++;
            if(present>=files) {
                boolean freed=false;
                for(int n=files-1;n>=0;n--) {
                    File oldest=file(n);
                    if(!oldest.equals(target) && oldest.exists()) { freed=oldest.delete();break; }
                }
                if(!freed)throw new IOException("JOURNAL_PRUNE_CAPACITY");
            }
            File temporary=new File(directory,current+".pruning");
            if (!safe(temporary)) throw new IOException("JOURNAL_PATH");
            try (FileOutputStream output=new FileOutputStream(temporary,false)) {
                ownerOnly(temporary);
                for (String row:retained) output.write((row+"\n").getBytes(StandardCharsets.UTF_8));
                output.flush(); output.getFD().sync();
            }
            java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
