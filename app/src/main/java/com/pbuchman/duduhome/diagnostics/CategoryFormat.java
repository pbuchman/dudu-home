package com.pbuchman.duduhome.diagnostics;

import java.util.Set;

/** Closed field vocabulary prevents accidentally logging payloads through the category API. */
public final class CategoryFormat {
    private CategoryFormat() { }
    private static final Set<String> NUMERIC=Set.of("ID","STARTUP","GENERATION","CYCLE","ELIGIBLE","FIXES","POOR","HOME",
            "FLAGS","BLOCKS","ELAPSED_MS","BLOCKER_ID","WAIT_MS","VENDOR_CYCLE");
    private static final Set<String> ENUM=Set.of("ACCURACY","AGE","SPEED","STATE","REASON","ACTION","BLOCKER_ACTION");
    public static boolean valid(String category) {
        if (category==null || category.length()>240 || category.isEmpty()) return false;
        String[] words=category.split(" ",-1);
        if (!words[0].matches("[A-Z][A-Z0-9_]{1,239}(=[0-9]{1,18})?")) return false;
        // The sole historical counter-only category is a vendor cycle observation.
        if (words[0].contains("=") && !words[0].matches("VENDOR_CYCLE=[0-9]{1,18}")) return false;
        for (int i=1;i<words.length;i++) {
            String[] field=words[i].split("=",-1);
            if (field.length!=2) return false;
            if (NUMERIC.contains(field[0])) { if(!field[1].matches("-?[0-9]{1,18}"))return false; }
            else if (ENUM.contains(field[0])) { if(!field[1].matches("[A-Z][A-Z0-9_]{0,63}"))return false; }
            else return false;
        }
        return true;
    }
}
