package com.saarthi.soil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * SoilGrids soil properties via the official ISRIC WCS subset service
 * ({@code https://maps.isric.org/mapserv?map=/map/{property}.map}).
 *
 * <p>Why WCS and not the REST point query: the
 * {@code rest.isric.org …/properties/query} endpoint is unusable from the
 * environments SAARTHI runs in, while the WCS service answers small
 * single-coverage subsets reliably. One WCS request serves ONE property, so a
 * block lookup is six small requests; each property is independently
 * fail-soft — a property that errors stays {@code null} while the properties
 * that succeeded are still returned. Nothing is ever estimated, averaged from
 * neighbours, or substituted from another dataset.
 *
 * <p>Verified against the live service (GetCapabilities + DescribeCoverage):
 * <ul>
 *   <li>coverages {@code {phh2o,sand,silt,clay,soc,cec}_0-5cm_Q0.5} (median,
 *       0–5 cm), single 16-bit band, native {@code image/tiff};</li>
 *   <li>subset/output CRS {@code EPSG:4326} with {@code SUBSET=Lat/SUBSET=Lon}
 *       ranges around the block coordinate (≈2 km box, native 250 m cells —
 *       a point value is read, never a centroid average);</li>
 *   <li>responses are tiled or stripped TIFFs, uncompressed or deflate
 *       compressed, predictor 1 or 2 — all parsed with the JDK only
 *       ({@link Inflater}), no GDAL or other native/large dependency.</li>
 * </ul>
 *
 * <p>Scale (ISRIC-declared, cross-checked against the bundled Sunam block
 * means): {@code phh2o}, {@code soc} and {@code cec} rasters are stored
 * ×10, so the mapped value is divided by 10 (pH, g/kg, cmol(c)/kg).
 * Texture rasters ({@code sand}/{@code silt}/{@code clay}) are stored at
 * %×10, which is numerically identical to g/kg — the exact scale the bundled
 * {@code risk/soil_context.json} means already use — so they are served
 * as-is and stay directly comparable with the bundled values.
 */
@Component
public class SoilGridsWcsClient {

    private static final Logger log = LoggerFactory.getLogger(SoilGridsWcsClient.class);

    static final String DEFAULT_BASE_URL = "https://maps.isric.org";
    static final String DEPTH_QUANTILE = "0-5cm_Q0.5";

    /** WCS property → served until conversion. Order is request order. */
    enum Property {
        PH("phh2o", 10.0, 20, 140),
        SAND("sand", 1.0, 0, 1000),
        SILT("silt", 1.0, 0, 1000),
        CLAY("clay", 1.0, 0, 1000),
        SOC("soc", 10.0, 0, 5000),
        CEC("cec", 10.0, 0, 2000);

        final String wcsProperty;
        final double divisor;
        final int rawMin;
        final int rawMax;

        Property(String wcsProperty, double divisor, int rawMin, int rawMax) {
            this.wcsProperty = wcsProperty;
            this.divisor = divisor;
            this.rawMin = rawMin;
            this.rawMax = rawMax;
        }

        /** Raw stored value → served value, or {@code null} when implausible. */
        Double convert(int raw) {
            if (raw < 0 || raw > 65533 || raw < rawMin || raw > rawMax) return null;
            double v = raw / divisor;
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        }
    }

    /**
     * One block's WCS soil values. Every field is a value ISRIC actually
     * returned for this coordinate, or {@code null}. {@code available} is true
     * when at least one property parsed; {@code allCached} is true only when
     * every served value came from the coordinate cache.
     */
    public record WcsSoil(Double ph, Double sandGkg, Double siltGkg, Double clayGkg,
            Double socGkg, Double cecCmolKg, boolean available, boolean allCached,
            String source) {
        static WcsSoil unavailable() {
            return new WcsSoil(null, null, null, null, null, null, false, false,
                    "SoilGrids WCS unavailable for this block");
        }
    }

    @Value("${saarthi.soil.wcs-base-url:https://maps.isric.org}")
    private String baseUrl = DEFAULT_BASE_URL;

    @Value("${saarthi.soil.wcs-timeout-seconds:60}")
    private int timeoutSeconds = 60;

    @Value("${saarthi.soil.wcs-cache-ttl-hours:168}")
    private long cacheTtlHours = 168;

    @Value("${saarthi.soil.wcs-failure-cache-ttl-minutes:15}")
    private long failureCacheTtlMinutes = 15;

    /** Half-size of the requested subset box, in degrees (~1 km each way). */
    @Value("${saarthi.soil.wcs-half-box-degrees:0.01}")
    private double halfBoxDegrees = 0.01;

    /** Hard cap on a single coverage response held in memory. */
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /**
     * Wall-clock budget for all six property fetches of one lookup. The
     * properties are independent coverages, so they are fetched concurrently
     * and a slow ISRIC delays the block by the slowest property, not the sum.
     * Whatever is not back within the budget stays {@code null} (fail-soft),
     * so one slow upstream can never stall a page indefinitely.
     */
    static final int LOOKUP_BUDGET_SECONDS = 45;

    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "soilgrids-wcs");
                t.setDaemon(true);
                return t;
            });

    private final HttpClient httpClient;
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    public SoilGridsWcsClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Test seam: inject base URL / timeouts / client without Spring. Public so
     * CropAtlas assembly tests (a different package) can point the shared
     * source at a stub service.
     */
    public SoilGridsWcsClient(String baseUrl, int timeoutSeconds, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = httpClient;
    }

    /**
     * Soil values for one coordinate. Fail-soft per property: a property the
     * service cannot serve stays {@code null} while successful properties are
     * still returned. Never throws for transport/parse problems (only for
     * out-of-range coordinates, which are a caller bug).
     */
    public WcsSoil lookup(double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException(
                    "Coordinates out of range (" + lat + ", " + lon + ")");
        }
        java.util.List<java.util.concurrent.Callable<PropResult>> tasks = new java.util.ArrayList<>();
        final double fLat = lat;
        final double fLon = lon;
        for (Property p : Property.values()) {
            final Property fp = p;
            tasks.add(() -> valueFor(fp, fLat, fLon));
        }
        PropResult[] got;
        try {
            java.util.List<java.util.concurrent.Future<PropResult>> done =
                    POOL.invokeAll(tasks, LOOKUP_BUDGET_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            got = new PropResult[Property.values().length];
            for (int i = 0; i < done.size(); i++) {
                PropResult r = null;
                try {
                    if (done.get(i).isDone() && !done.get(i).isCancelled()) {
                        r = done.get(i).get();
                    }
                } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    r = null;
                }
                got[i] = r == null ? new PropResult(null, false) : r;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            got = new PropResult[Property.values().length];
            for (int i = 0; i < got.length; i++) got[i] = new PropResult(null, false);
        }
        PropResult ph = got[Property.PH.ordinal()];
        PropResult sand = got[Property.SAND.ordinal()];
        PropResult silt = got[Property.SILT.ordinal()];
        PropResult clay = got[Property.CLAY.ordinal()];
        PropResult soc = got[Property.SOC.ordinal()];
        PropResult cec = got[Property.CEC.ordinal()];
        boolean available = ph.value() != null || sand.value() != null || silt.value() != null
                || clay.value() != null || soc.value() != null || cec.value() != null;
        // CACHED only when every SERVED property came from a pre-existing cache
        // entry; any fresh network value (or any failure) keeps REFERENCE.
        boolean cached = available
                && (ph.value() == null || ph.fromCache())
                && (sand.value() == null || sand.fromCache())
                && (silt.value() == null || silt.fromCache())
                && (clay.value() == null || clay.fromCache())
                && (soc.value() == null || soc.fromCache())
                && (cec.value() == null || cec.fromCache());
        String source = "SoilGrids / ISRIC WCS (maps.isric.org), 0–5 cm median (Q0.5)"
                + " at the block coordinate — context only, not a field measurement";
        return new WcsSoil(ph.value(), sand.value(), silt.value(), clay.value(), soc.value(),
                cec.value(), available, cached, source);
    }

    // ---- per-property fetch with caching ----

    private record PropResult(Double value, boolean fromCache) {
    }

    private PropResult valueFor(Property prop, double lat, double lon) {
        String key = keyFor(prop, lat, lon);
        CachedValue hit = cache.get(key);
        if (hit != null && !isExpired(hit)) {
            return new PropResult(hit.value(), true);
        }
        Double fresh = fetch(prop, lat, lon);
        cache.put(key, new CachedValue(fresh, Instant.now(),
                fresh == null ? Duration.ofMinutes(failureCacheTtlMinutes)
                        : Duration.ofHours(cacheTtlHours)));
        return new PropResult(fresh, false);
    }

    private static String keyFor(Property prop, double lat, double lon) {
        return prop.name() + String.format(Locale.ROOT, ":%.4f,%.4f", lat, lon);
    }

    private boolean isExpired(CachedValue c) {
        return Duration.between(c.cachedAt(), Instant.now()).compareTo(c.ttl()) >= 0;
    }

    private Double fetch(Property prop, double lat, double lon) {
        String url = coverageUrl(prop, lat, lon);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "image/tiff")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp =
                    httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300
                    || resp.body() == null || resp.body().length == 0) {
                log.debug("SoilGrids WCS {} unavailable (HTTP {}); property omitted",
                        prop.wcsProperty, resp.statusCode());
                return null;
            }
            if (resp.body().length > MAX_RESPONSE_BYTES) {
                log.debug("SoilGrids WCS {} response exceeded the size cap; property omitted",
                        prop.wcsProperty);
                return null;
            }
            Double raw = centerRaw(resp.body());
            if (raw == null) {
                log.debug("SoilGrids WCS {} returned no usable pixel; property omitted",
                        prop.wcsProperty);
                return null;
            }
            Double v = prop.convert(raw.intValue());
            if (v == null) {
                log.debug("SoilGrids WCS {} pixel outside plausible range; property omitted",
                        prop.wcsProperty);
            }
            return v;
        } catch (Exception e) {
            log.debug("SoilGrids WCS {} lookup failed ({}); property omitted",
                    prop.wcsProperty, e.getMessage());
            return null;
        }
    }

    String coverageUrl(Property prop, double lat, double lon) {
        double h = halfBoxDegrees <= 0 || halfBoxDegrees > 0.5 ? 0.01 : halfBoxDegrees;
        return String.format(Locale.ROOT,
                "%s/mapserv?map=/map/%s.map&SERVICE=WCS&VERSION=2.0.1&REQUEST=GetCoverage"
                        + "&COVERAGEID=%s_%s&FORMAT=image/tiff"
                        + "&SUBSETTINGCRS=http://www.opengis.net/def/crs/EPSG/0/4326"
                        + "&OUTPUTCRS=http://www.opengis.net/def/crs/EPSG/0/4326"
                        + "&SUBSET=Lat(%.4f,%.4f)&SUBSET=Lon(%.4f,%.4f)",
                baseUrl, prop.wcsProperty, prop.wcsProperty, DEPTH_QUANTILE,
                lat - h, lat + h, lon - h, lon + h);
    }

    // ---- minimal single-band 16-bit TIFF reader ----

    /**
     * Raw stored value of the centre pixel of a small WCS TIFF subset, or
     * {@code null} when the bytes are not a readable single-band 16-bit TIFF.
     * An XML error document, an unsupported compression, or any structural
     * surprise yields {@code null} — never a number made up from the error.
     */
    static Double centerRaw(byte[] tiff) {
        try {
            return parseCenter(tiff);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double parseCenter(byte[] b) {
        if (b == null || b.length < 64 || b.length > MAX_RESPONSE_BYTES) return null;
        if (b[0] == '<') return null; // OWS exception XML, not a coverage.
        boolean little;
        if (b[0] == 'I' && b[1] == 'I') little = true;
        else if (b[0] == 'M' && b[1] == 'M') little = false;
        else return null;
        Reader r = new Reader(b, little);
        if (r.u16(2) != 42) return null;
        long ifd = r.u32(4);
        if (ifd < 8 || ifd > b.length - 2) return null;
        int n = r.u16((int) ifd);
        if (n <= 0 || n > 64) return null;
        java.util.Map<Integer, long[]> tags = new java.util.HashMap<>();
        java.util.Map<Integer, byte[]> ascii = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            int o = (int) ifd + 2 + i * 12;
            if (o + 12 > b.length) return null;
            int tag = r.u16(o);
            int type = r.u16(o + 2);
            long count = r.u32(o + 4);
            if (type == 2) { // ASCII (e.g. GDAL_NODATA)
                ascii.put(tag, r.bytes(o + 8, count));
            } else if ((type == 3 || type == 4) && count >= 1 && count <= 1024) {
                long[] vals = new long[(int) count];
                int size = type == 3 ? 2 : 4;
                if ((long) count * size <= 4) {
                    for (int k = 0; k < count; k++) {
                        vals[k] = type == 3 ? r.u16(o + 8 + k * 2) : r.u32(o + 8 + k * 4);
                    }
                } else {
                    long off = r.u32(o + 8);
                    if (off < 0 || off + count * size > b.length) return null;
                    for (int k = 0; k < count; k++) {
                        vals[k] = type == 3 ? r.u16((int) off + k * 2) : r.u32((int) off + k * 4);
                    }
                }
                tags.put(tag, vals);
            }
        }
        long[] width = tags.get(256);
        long[] height = tags.get(257);
        if (width == null || height == null) return null;
        int w = (int) width[0];
        int h = (int) height[0];
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
        long[] bps = tags.get(258);
        if (bps == null || bps[0] != 16) return null; // 16-bit single band only.
        long[] spp = tags.get(277);
        if (spp != null && spp[0] != 1) return null;
        long[] sampleFormat = tags.get(339);
        if (sampleFormat != null && sampleFormat[0] != 1 && sampleFormat[0] != 2) return null;
        long[] compression = tags.get(259);
        long comp = compression == null ? 1 : compression[0];
        if (comp != 1 && comp != 8) return null; // none or deflate only.
        long[] predictor = tags.get(317);
        long pred = predictor == null ? 1 : predictor[0];
        if (pred != 1 && pred != 2) return null; // none or horizontal differencing.

        int cx = w / 2;
        int cy = h / 2;
        byte[] samples;
        int samplesPerRow;
        if (tags.containsKey(273)) { // stripped
            long[] offsets = tags.get(273);
            long[] counts = tags.get(279);
            if (counts == null || offsets.length != counts.length) return null;
            long[] rowsPerStrip = tags.get(278);
            long rps = rowsPerStrip == null ? h : rowsPerStrip[0];
            if (rps <= 0) return null;
            int strip = (int) (cy / rps);
            if (strip >= offsets.length) return null;
            samples = slice(b, offsets[strip], counts[strip]);
            if (samples == null) return null;
            samplesPerRow = w;
            int rowInStrip = (int) (cy % rps);
            samples = depredictRow(samples, samplesPerRow, rowInStrip, (int) comp, pred != 1);
            if (samples == null) return null;
            int raw = ((samples[cx * 2] & 0xFF) | ((samples[cx * 2 + 1] & 0xFF) << 8));
            return noDataChecked(raw, ascii.get(421));
        }
        if (tags.containsKey(324)) { // tiled
            long[] offsets = tags.get(324);
            long[] counts = tags.get(325);
            long[] tileW = tags.get(322);
            long[] tileH = tags.get(323);
            if (counts == null || tileW == null || tileH == null
                    || offsets.length != counts.length) return null;
            int tw = (int) tileW[0];
            int th = (int) tileH[0];
            if (tw <= 0 || th <= 0 || tw > 4096 || th > 4096) return null;
            int tilesAcross = (w + tw - 1) / tw;
            int tile = (cy / th) * tilesAcross + (cx / tw);
            if (tile >= offsets.length) return null;
            samples = slice(b, offsets[tile], counts[tile]);
            if (samples == null) return null;
            samples = inflate(samples, (int) comp);
            if (samples == null) return null;
            int tx = cx % tw;
            int ty = cy % th;
            samplesPerRow = tw;
            // depredictRow returns the decoded bytes of row ty only.
            samples = depredictRow(samples, samplesPerRow, ty, 1, pred != 1);
            if (samples == null) return null;
            int raw = ((samples[tx * 2] & 0xFF) | ((samples[tx * 2 + 1] & 0xFF) << 8));
            return noDataChecked(raw, ascii.get(421));
        }
        return null;
    }

    /** GDAL_NODATA match → {@code null}; otherwise the unsigned raw value. */
    private static Double noDataChecked(int raw, byte[] noDataAscii) {
        if (noDataAscii != null) {
            String s = new String(noDataAscii, StandardCharsets.US_ASCII).trim()
                    .replace("\0", "");
            try {
                if (!s.isEmpty() && Double.parseDouble(s) == raw) return null;
            } catch (NumberFormatException ignored) {
                // An unparsable nodata tag must not discard real data.
            }
        }
        return (double) raw;
    }

    private static byte[] slice(byte[] b, long off, long len) {
        if (off < 0 || len <= 0 || len > 256L * 1024 * 1024 || off + len > b.length) return null;
        byte[] out = new byte[(int) len];
        System.arraycopy(b, (int) off, out, 0, (int) len);
        return out;
    }

    private static byte[] inflate(byte[] data, int comp) {
        if (comp == 1) return data;
        if (data == null || data.length < 8) return null;
        Inflater inflater = new Inflater(); // zlib-wrapped deflate, as TIFF writes it.
        inflater.setInput(data);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                Math.min(data.length * 4, 64 * 1024 * 1024));
        byte[] buf = new byte[65536];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput()) break;
                    return null;
                }
                out.write(buf, 0, n);
                if (out.size() > 64 * 1024 * 1024) return null;
            }
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /**
     * Undo horizontal-differencing prediction for one row (or verify length
     * when no prediction), returning the row's decoded bytes.
     */
    private static byte[] depredictRow(byte[] data, int samplesPerRow, int row,
            int comp, boolean predicted) {
        byte[] flat = inflate(data, comp);
        if (flat == null) return null;
        long need = (long) (row + 1) * samplesPerRow * 2L;
        if (flat.length < need) return null;
        byte[] rowBytes = new byte[samplesPerRow * 2];
        System.arraycopy(flat, row * samplesPerRow * 2, rowBytes, 0, rowBytes.length);
        if (predicted) {
            int acc = 0;
            for (int i = 0; i < samplesPerRow; i++) {
                int d = (rowBytes[i * 2] & 0xFF) | ((rowBytes[i * 2 + 1] & 0xFF) << 8);
                acc = (acc + d) & 0xFFFF;
                rowBytes[i * 2] = (byte) (acc & 0xFF);
                rowBytes[i * 2 + 1] = (byte) ((acc >>> 8) & 0xFF);
            }
        }
        return rowBytes;
    }

    /** Endian-aware unsigned reader over the TIFF bytes. */
    private static final class Reader {
        private final byte[] b;
        private final boolean little;

        Reader(byte[] b, boolean little) {
            this.b = b;
            this.little = little;
        }

        int u16(int o) {
            int a = b[o] & 0xFF;
            int c = b[o + 1] & 0xFF;
            return little ? (a | (c << 8)) : ((a << 8) | c);
        }

        long u32(int o) {
            long a = b[o] & 0xFF;
            long c = b[o + 1] & 0xFF;
            long d = b[o + 2] & 0xFF;
            long e = b[o + 3] & 0xFF;
            return little ? (a | (c << 8) | (d << 16) | (e << 24))
                    : ((a << 24) | (c << 16) | (d << 8) | e);
        }

        byte[] bytes(int valueOffset, long count) {
            if (count <= 0 || count > 256) return null;
            if (count <= 4) {
                byte[] out = new byte[(int) count];
                for (int i = 0; i < count; i++) out[i] = b[valueOffset + i];
                return out;
            }
            long off = u32(valueOffset);
            return slice(b, off, count);
        }
    }

    private record CachedValue(Double value, Instant cachedAt, Duration ttl) {
    }
}
