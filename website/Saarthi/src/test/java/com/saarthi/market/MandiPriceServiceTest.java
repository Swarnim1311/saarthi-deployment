package com.saarthi.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mandi-price behaviour with NO network: parsing, district preference, freshness
 * labelling and the unconfigured state. Nothing here invents a price — the only
 * prices asserted are the ones inside the recorded official response below.
 */
class MandiPriceServiceTest {

    private static final String RECORDS = "{\"count\":\"2\","
            + "\"records\":["
            + "{\"commodity\":\"Maize\",\"market\":\"Sangrur\",\"district\":\"Sangrur\","
            + "\"state\":\"Punjab\",\"arrival_date\":\"%s\",\"min_price\":\"2100\","
            + "\"modal_price\":\"2250\",\"max_price\":\"2400\"},"
            + "{\"commodity\":\"Maize\",\"market\":\"Barnala\",\"district\":\"Sangrur\","
            + "\"state\":\"Punjab\",\"arrival_date\":\"%s\",\"min_price\":\"2050\","
            + "\"modal_price\":\"2200\",\"max_price\":\"2350\"}]}";

    private static String fresh() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static MandiPriceService service() {
        MandiPriceService s = new MandiPriceService();
        s.setConfiguredApiKey("");
        return s;
    }

    @Test
    void withoutAnApiKeyTheServiceNeverCallsOutAndSaysNotConfigured() {
        MandiPriceService s = service();
        assertNull(s.resolveApiKey());
        Map<String, Object> out = s.latest("maize", "Punjab", "Sangrur");
        assertEquals(Boolean.FALSE, out.get("available"));
        assertEquals("not_configured", out.get("reason"));
        assertNull(out.get("modal_price"), "no price may appear without a configured source");
    }

    @Test
    void anUnmappedCropIsUnavailableRatherThanGuessed() {
        MandiPriceService s = service();
        s.setConfiguredApiKey("test-key");
        Map<String, Object> out = s.latest("mango", "Kerala", null);
        assertEquals(Boolean.FALSE, out.get("available"));
        assertEquals("no_commodity_mapping", out.get("reason"));
    }

    @Test
    void recordsAreParsedExactlyAsTheOfficialFeedStatedThem() {
        MandiPriceService s = service();
        Map<String, Object> parsed = s.parse(String.format(RECORDS, fresh(), fresh()), "Maize");
        assertNotNull(parsed);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) parsed.get("rows");
        assertEquals(2, rows.size());
        assertEquals("Maize", rows.get(0).get("commodity"));
        assertEquals("Sangrur", rows.get(0).get("market"));
        assertEquals("Punjab", rows.get(0).get("state"));
        assertEquals("2250", rows.get(0).get("modal_price"));
        assertEquals("quintal", rows.get(0).get("unit"));
    }

    @Test
    void theRequestedDistrictMandiIsPreferred() {
        MandiPriceService s = service();
        Map<String, Object> parsed = s.parse(String.format(RECORDS, fresh(), fresh()), "Maize");
        Map<String, Object> chosen = s.prefer(parsed, "Sangrur");
        assertEquals(Boolean.TRUE, chosen.get("available"));
        assertEquals("2250", chosen.get("modal_price"), "first record is the chosen mandi");

        // A district that is not in the response still gets a real record, never
        // another district's value relabelled.
        Map<String, Object> other = s.prefer(parsed, "Ludhiana");
        assertEquals(Boolean.TRUE, other.get("available"));
        assertEquals("Sangrur", other.get("district"));
    }

    @Test
    void anEmptyResponseIsNoRecentRecordNotAFabricatedPrice() {
        MandiPriceService s = service();
        assertNull(s.parse("{\"count\":\"0\",\"records\":[]}", "Maize"));
    }
@Test
    void anOldRecordKeepsItsRealDateAndIsLabelledAsOld() {
        MandiPriceService s = service();
        String old = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(40)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
        Map<String, Object> parsed = s.parse(String.format(RECORDS, old, old), "Maize");
        Map<String, Object> chosen = s.prefer(parsed, "Sangrur");
        assertEquals(Boolean.TRUE, chosen.get("available"));
        assertEquals(old, chosen.get("date"), "the real record date must be preserved");
        String note = String.valueOf(chosen.get("note"));
        assertTrue(note.contains(old), "an old record must say how old it is: " + note);
    }

    @Test
    void aRecordWithNoDateIsNeverPresentedAsCurrent() {
        MandiPriceService s = service();
        String body = "{\"count\":\"1\",\"records\":[{\"commodity\":\"Maize\","
                + "\"market\":\"Sangrur\",\"district\":\"Sangrur\",\"state\":\"Punjab\","
                + "\"min_price\":\"2100\",\"modal_price\":\"2250\",\"max_price\":\"2400\"}]}";
        Map<String, Object> chosen = s.prefer(s.parse(body, "Maize"), "Sangrur");
        assertNull(chosen.get("date"));
        assertTrue(String.valueOf(chosen.get("note")).contains("no readable date"),
                "an undated record must be flagged: " + chosen.get("note"));
    }

    @Test
    void aFreshRecordIsNotAnnotatedAsStale() {
        assertNull(MandiPriceService.freshnessNote(fresh()));
    }

    @Test
    void commodityFiltersAreRestrictedToTheReviewedList() {
        assertTrue(MandiPriceService.COMMODITY_ALIASES.containsKey("wheat"));
        assertTrue(MandiPriceService.COMMODITY_ALIASES.containsKey("cotton"));
        assertFalse(MandiPriceService.COMMODITY_ALIASES.containsKey("mango"),
                "an unreviewed crop must never be sent as a commodity filter");
    }
}