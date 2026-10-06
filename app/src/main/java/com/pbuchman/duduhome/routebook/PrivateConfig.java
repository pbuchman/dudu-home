package com.pbuchman.duduhome.routebook;

import android.util.AtomicFile;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Map;

/** Separate one-shot staging file, no Roborock or PrivateImport changes. */
public final class PrivateConfig {
    public final String endpoint,token;
    public final boolean enabled;
    public final CollectorFilter.Limits limits;
    private PrivateConfig(String endpoint,String token,boolean enabled,CollectorFilter.Limits limits) {
        this.endpoint=endpoint; this.token=token; this.enabled=enabled; this.limits=limits;
    }
    public static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    public static byte[] read(File file) throws IOException {
        try(InputStream in=new FileInputStream(file)) { return bounded(in,16384); }
    }
    public static byte[] bounded(InputStream in,int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] chunk=new byte[4096]; int n;
        while((n=in.read(chunk))!=-1) { if(out.size()+n>max) throw new IOException("size limit"); out.write(chunk,0,n); }
        return out.toByteArray();
    }
    public static PrivateConfig parse(byte[] bytes,String device) throws IOException {
        Map<String,Object> m=Json.object(Json.parse(utf8(bytes)),"version","device_id","endpoint","token","enabled","sample_ms","max_age_ms","gap_ms","accuracy_m");
        if(Json.integer(m.get("version"))!=1||!device.equals(m.get("device_id"))||!(m.get("enabled") instanceof Boolean)) throw new IllegalArgumentException();
        String endpoint=Json.string(m.get("endpoint")),token=Json.string(m.get("token")); URI uri=URI.create(endpoint);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null
            ||!"/v1/ingest".equals(uri.getRawPath())||(uri.getPort()!=-1&&uri.getPort()!=443)
            ||!token.matches("[A-Za-z0-9._~+/-]{32,510}={0,2}")) throw new IllegalArgumentException();
        Object accuracy=m.get("accuracy_m"); if(!(accuracy instanceof Number)) throw new IllegalArgumentException();
        CollectorFilter.Limits limits=new CollectorFilter.Limits(Json.integer(m.get("sample_ms")),Json.integer(m.get("max_age_ms")),Json.integer(m.get("gap_ms")),((Number)accuracy).doubleValue());
        return new PrivateConfig(endpoint,token,(Boolean)m.get("enabled"),limits);
    }
    public static PrivateConfig load(File dir,String device) throws IOException {
        File stage=new File(dir,"routebook-import.json"); AtomicFile saved=new AtomicFile(new File(dir,"routebook-config.json"));
        if(stage.exists()) {
            byte[] bytes=read(stage); PrivateConfig config=parse(bytes,device);
            FileOutputStream out=null;
            try { out=saved.startWrite(); out.write(bytes); saved.finishWrite(out); }
            catch(IOException e) { saved.failWrite(out); throw e; }
            if(!stage.delete()) throw new IOException("staging cleanup");
            return config;
        }
        if(!saved.getBaseFile().exists()) return null;
        return parse(saved.readFully(),device);
    }
}
