package com.pbuchman.duduhome.navigation;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Atomic, no-backup storage. Imports never dispatch an action or retain document access. */
public final class NavigationStore {
    private final AtomicFile file;
    public NavigationStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "navigation.json")); }
    public static NavigationConfig readDocument(InputStream input) throws Exception {
        if (input == null) throw new IOException("Navigation document unavailable");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (bytes.size() + count > NavigationConfig.MAX_BYTES) throw new IOException("Navigation document too large");
            bytes.write(chunk, 0, count);
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        return NavigationConfig.parse(text);
    }
    public NavigationConfig load() throws Exception {
        synchronized (NavigationStore.class) {
            try (InputStream input = file.openRead()) { return readDocument(input); }
            catch (FileNotFoundException missing) {
                if (!file.getBaseFile().exists()) return NavigationConfig.empty();
                throw missing;
            }
        }
    }
    public void save(NavigationConfig config) throws Exception {
        byte[] bytes = config.serialize().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > NavigationConfig.MAX_BYTES) throw new IOException("Navigation document too large");
        synchronized (NavigationStore.class) {
            FileOutputStream output = null;
            try {
                output = file.startWrite(); output.write(bytes); file.finishWrite(output);
            } catch (Exception error) { if (output != null) file.failWrite(output); throw error; }
        }
    }
}
