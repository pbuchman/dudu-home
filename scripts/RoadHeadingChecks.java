package com.pbuchman.duduhome.trip;

import java.util.HashMap;

public final class RoadHeadingChecks {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) {
        check(RoadHeading.valid(0,10) && RoadHeading.valid(359.999,25) && RoadHeading.valid(180,25.0000001),"inclusive physical support with numerical tolerance");
        for(double heading:new double[]{-1,360,Double.NaN,Double.POSITIVE_INFINITY})check(!RoadHeading.valid(heading,14),"invalid heading rejected");
        for(double support:new double[]{0,9.99,25.00001,Double.NaN,Double.POSITIVE_INFINITY})check(!RoadHeading.valid(0,support),"invalid support rejected");
        check(RoadHeading.penalty(0,90)==15 && RoadHeading.penalty(0,270)==15,"perpendicular penalty in either direction");
        check(RoadHeading.penalty(0,0)==0 && RoadHeading.penalty(0,180)==0,"reverse driving remains supported");
        HashMap<String,String> metadata=new HashMap<>();String source="1".repeat(64);
        metadata.put("source_sha256",source);metadata.put("short_heading_source_sha256",source);
        metadata.put("short_heading_policy",RoadHeading.POLICY);metadata.put("short_heading_policy_sha256",RoadHeading.POLICY_HASH);
        metadata.put("map_generation",RoadHeading.generation(source));check(RoadHeading.policy(metadata),"versioned source bound policy");
        for(String key:new String[]{"source_sha256","short_heading_source_sha256","short_heading_policy","short_heading_policy_sha256","map_generation"}) {
            HashMap<String,String> missing=new HashMap<>(metadata);missing.remove(key);check(!RoadHeading.policy(missing),"missing policy evidence");
            missing.put(key,"wrong");check(!RoadHeading.policy(missing),"wrong policy evidence");
        }
        System.out.println("Road heading policy/finite-support/direction checks passed");
    }
}
