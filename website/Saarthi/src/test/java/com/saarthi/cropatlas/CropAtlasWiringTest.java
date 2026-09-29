package com.saarthi.cropatlas;

import com.saarthi.soil.SoilGridsWcsClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The shared WCS soil client must actually reach the CropAtlas soil source in
 * the Spring context — an unwired optional dependency would silently degrade
 * every block to bundled/unavailable with no error anywhere.
 */
@SpringBootTest
class CropAtlasWiringTest {

    @Autowired
    CropAtlasSoilSource soilSource;

    @Autowired
    SoilGridsWcsClient wcsClient;

    @Test
    void theWcsClientIsWiredIntoTheSharedSoilSource() throws Exception {
        assertNotNull(wcsClient, "SoilGridsWcsClient bean must exist");
        Field f = CropAtlasSoilSource.class.getDeclaredField("soilWcs");
        f.setAccessible(true);
        assertNotNull(f.get(soilSource),
                "CropAtlasSoilSource.soilWcs must be injected, not left null");
    }
}
