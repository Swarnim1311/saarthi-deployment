package com.saarthi.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saarthi.service.RealForecastService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Block-level spatial sampling over the authoritative 6-block Bhuvan polygons.
 *
 * <p>Method (documented, approximate): per block, take a 3×3 grid spanning the
 * polygon bounding box, keep the candidates that fall inside the polygon's
 * exterior ring (ray-casting; interior holes are NOT excluded — they are
 * treated as inside, which is acceptable for the six Sangrur blocks as none
 * contain holes), and average the point forecasts. Open-Meteo
 * only exposes coordinate forecasts — not its native grid — so true
 * area-weighted grid/polygon intersection is impossible; this uniform
 * multi-point mean is the documented best-available approximation. Centroid
 * is used ONLY as a labelled fallback when no grid candidate falls inside
 * (should not happen for the 6 Sangrur blocks) — never presented as the
 * scientific solution.
 */
@Component
public class BlockSampler {

    static final int GRID = 3;

    private final RealForecastService forecastService;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Map<String, BlockSamples> cache;

    @Autowired
    public BlockSampler(RealForecastService forecastService) {
        this.forecastService = forecastService;
    }

    /** Test seam: build directly from a GeoJSON string without Spring. */
    public BlockSampler(String geoJson) {
        this.forecastService = null;
        this.cache = buildSamples(geoJson);
    }

    /** Block name → sample points + method label. */
    public Map<String, BlockSamples> samples() {
        Map<String, BlockSamples> c = cache;
        if (c == null) {
            synchronized (this) {
                c = cache;
                if (c == null) {
                    c = buildSamples(forecastService.getGeoJson());
                    cache = c;
                }
            }
        }
        return c;
    }

    /** All sample points, in deterministic block order. */
    public List<SampleRef> allPoints() {
        List<SampleRef> out = new ArrayList<>();
        java.util.Set<String> done = new java.util.LinkedHashSet<>();
        // Legacy order first: byte-identical behaviour for the 6 Sangrur blocks.
        for (String block : RealForecastService.BLOCKS) {
            BlockSamples s = samples().get(block);
            if (s == null) continue;
            done.add(block);
            for (WeatherProvider.SamplePoint p : s.points()) {
                out.add(new SampleRef(block, p));
            }
        }
        // Any sample keys beyond the legacy six (future dynamic blocks).
        for (Map.Entry<String, BlockSamples> e : samples().entrySet()) {
            if (done.contains(e.getKey())) continue;
            for (WeatherProvider.SamplePoint p : e.getValue().points()) {
                out.add(new SampleRef(e.getKey(), p));
            }
        }
        return out;
    }

    public record BlockSamples(List<WeatherProvider.SamplePoint> points, String spatialMethod) {}
    public record SampleRef(String blockName, WeatherProvider.SamplePoint point) {}

    /**
     * Label for single-centroid blocks on the dynamic registry path: one
     * honest point sample — NEVER presented as polygon averaging.
     */
    public static final String CENTROID_METHOD =
            "single_point_centroid (registry centroid; NOT polygon-averaged)";

    /** Single-point samples for a registry centroid (dynamic path; no GeoJSON). */
    public static BlockSamples centroidSamples(double latitude, double longitude) {
        return new BlockSamples(
                List.of(new WeatherProvider.SamplePoint(latitude, longitude)),
                CENTROID_METHOD);
    }

    Map<String, BlockSamples> buildSamples(String geoJson) {
        Map<String, BlockSamples> out = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(geoJson);
            for (JsonNode feature : root.path("features")) {
                String name = feature.path("properties").path("block_name").asText(null);
                if (name == null) continue;
                JsonNode geom = feature.path("geometry");
                List<double[][]> polygons = extractPolygons(geom);
                if (polygons.isEmpty()) continue;
                double[] bbox = bbox(polygons);
                List<WeatherProvider.SamplePoint> inside = gridInside(polygons, bbox);
                String method;
                if (inside.isEmpty()) {
                    // Labelled fallback only; keeps the block servable instead of failing.
                    inside = List.of(new WeatherProvider.SamplePoint(
                            (bbox[1] + bbox[3]) / 2.0, (bbox[0] + bbox[2]) / 2.0));
                    method = "centroid_fallback (bbox centre; no grid candidate inside polygon)";
                } else {
                    method = "multipoint_mean_n" + inside.size() + "_3x3_bbox_filtered"
                            + " (uniform candidates kept by point-in-polygon; simple mean)";
                }
                out.put(name, new BlockSamples(inside, method));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build block samples from GeoJSON: " + e.getMessage(), e);
        }
        return out;
    }

    /** Exterior rings (ring[0]) of every polygon; holes (ring[1..]) kept for exclusion tests. */
    static List<double[][][]> ringsOf(JsonNode geom) {
        List<double[][][]> out = new ArrayList<>();
        String type = geom.path("type").asText("");
        if ("MultiPolygon".equals(type)) {
            for (JsonNode poly : geom.path("coordinates")) {
                out.add(toRings(poly));
            }
        } else if ("Polygon".equals(type)) {
            out.add(toRings(geom.path("coordinates")));
        }
        return out;
    }

    private static List<double[][]> extractPolygons(JsonNode geom) {
        List<double[][]> out = new ArrayList<>();
        for (double[][][] rings : ringsOf(geom)) {
            if (rings.length > 0 && rings[0].length > 0) out.add(rings[0]);
        }
        return out;
    }

    private static double[][][] toRings(JsonNode poly) {
        List<double[][]> rings = new ArrayList<>();
        for (JsonNode ring : poly) {
            List<double[]> pts = new ArrayList<>();
            for (JsonNode c : ring) {
                pts.add(new double[]{c.path(0).asDouble(), c.path(1).asDouble()});
            }
            rings.add(pts.toArray(new double[0][]));
        }
        return rings.toArray(new double[0][][]);
    }

    private static double[] bbox(List<double[][]> polygons) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[][] ring : polygons) {
            for (double[] p : ring) {
                if (p[0] < minX) minX = p[0];
                if (p[0] > maxX) maxX = p[0];
                if (p[1] < minY) minY = p[1];
                if (p[1] > maxY) maxY = p[1];
            }
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private List<WeatherProvider.SamplePoint> gridInside(List<double[][]> polygons, double[] bbox) {
        List<WeatherProvider.SamplePoint> out = new ArrayList<>();
        for (int ix = 0; ix < GRID; ix++) {
            for (int iy = 0; iy < GRID; iy++) {
                double x = bbox[0] + (bbox[2] - bbox[0]) * (ix + 0.5) / GRID;
                double y = bbox[1] + (bbox[3] - bbox[1]) * (iy + 0.5) / GRID;
                if (insideAny(polygons, x, y)) {
                    out.add(new WeatherProvider.SamplePoint(y, x));
                }
            }
        }
        return out;
    }

    /** Point-in-exterior-ring test (holes ignored → slightly conservative inclusion). */
    static boolean insideAny(List<double[][]> polygons, double x, double y) {
        for (double[][] ring : polygons) {
            if (rayCast(ring, x, y)) return true;
        }
        return false;
    }

    static boolean rayCast(double[][] ring, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], yi = ring[i][1];
            double xj = ring[j][0], yj = ring[j][1];
            if ((yi > y) != (yj > y)
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }
}
