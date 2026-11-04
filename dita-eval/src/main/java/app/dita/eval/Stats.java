package app.dita.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Descriptive statistics only (Stage 0 makes no inferential claims). */
public final class Stats {
    private Stats() {}

    public static double mean(Collection<? extends Number> xs) {
        return xs.isEmpty() ? Double.NaN : xs.stream().mapToDouble(Number::doubleValue).average().orElse(Double.NaN);
    }

    /** Nearest-rank percentile, p in (0,100]. */
    public static double percentile(Collection<? extends Number> xs, double p) {
        if (xs.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>();
        xs.forEach(x -> s.add(x.doubleValue()));
        s.sort(null);
        int rank = (int) Math.ceil(p / 100.0 * s.size());
        return s.get(Math.max(0, Math.min(s.size() - 1, rank - 1)));
    }

    public static double max(Collection<? extends Number> xs) {
        return xs.stream().mapToDouble(Number::doubleValue).max().orElse(Double.NaN);
    }

    public static double round(double v, int places) {
        if (Double.isNaN(v)) return v;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    public static double rate(long num, long den) { return den == 0 ? Double.NaN : (double) num / den; }
}
