package com.pbuchman.duduhome.trip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Schema-3 orientation contract. It supplies evidence, never candidate or street preference. */
final class RoadHeading {
    static final String POLICY="same-osm-way-degree2-v1";
    static final String POLICY_HASH="a1165b6bb2205937f11fd955a518f41cbd2e0a8aa98ec1bcb20ff9d007a34c1c";
    // One micrometre only accommodates floating point chain accumulation at the 25 m bound.
    static boolean valid(double heading,double support) {
        return Double.isFinite(heading) && heading>=0 && heading<360
                && Double.isFinite(support) && support>=10 && support<=25.000001;
    }
    static boolean policy(Map<String,String> metadata) {
        String source=metadata.getOrDefault("source_sha256","");
        if (!source.matches("[0-9a-f]{64}") || !source.equals(metadata.get("short_heading_source_sha256"))
                || !POLICY.equals(metadata.get("short_heading_policy")) || !POLICY_HASH.equals(metadata.get("short_heading_policy_sha256"))) return false;
        return generation(source).equals(metadata.get("map_generation"));
    }
    static String generation(String source) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(("3\n"+source+"\n"+POLICY_HASH+"\n").getBytes(StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder();
            for (byte value:digest) result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) { return ""; }
    }
    static double penalty(double heading,double bearing) {
        double angle=Math.abs((heading-bearing+540)%360-180);
        return Math.min(angle,180-angle)/6;
    }
}
