package com.saarthi.risks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full Spring context load: every production bean (weather, risks, shadow,
 * outlook) must wire without missing dependencies. Guards against
 * un-annotated collaborators that unit tests (manual construction) cannot see.
 */
@SpringBootTest
class CompositeContextTest {

    @Autowired
    CompositeRiskService composite;

    @Autowired
    RiskController controller;

    @Autowired
    FieldShadowService fieldShadow;

    @Autowired
    com.saarthi.shadow.ShadowCaptureService dryShadow;

    @Test
    void contextLoadsWithAllRiskBeans() {
        assertNotNull(composite);
        assertNotNull(controller);
        assertNotNull(fieldShadow);
        assertNotNull(dryShadow);
    }
}
