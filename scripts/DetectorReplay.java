package pl.piotrbuchman.dudugate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads a PRIVATE, local-metre TSV; stdout contains events, never coordinates. */
public final class DetectorReplay {
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        String[] g = lines.get(0).split("\t");
        HomeDetector.Geometry geometry = new HomeDetector.Geometry(point(g, 0), point(g, 2), point(g, 4), point(g, 6));
        HomeDetector detector = new HomeDetector(geometry, 0);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t");
            long t = Long.parseLong(f[0]);
            for (HomeEvent event : detector.accept(new HomeDetector.Fix(t, point(f, 1),
                    Double.parseDouble(f[3]), Double.parseDouble(f[4]), Long.parseLong(f[5]), false))) {
                System.out.println(t + "\t" + event);
            }
        }
    }
    private static HomeDetector.Point point(String[] s, int i) {
        return new HomeDetector.Point(Double.parseDouble(s[i]), Double.parseDouble(s[i+1]));
    }
}
