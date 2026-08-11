package com.huseyn.elixircollector;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SpecialFormCalibrationTest {
    @Test public void knightCanUseNormalEvolutionOrHeroArtWithoutChangingBaseIdentity() {
        List<SpecialFormCalibration.Form> forms = SpecialFormCalibration.allowedForms("knight");
        assertTrue(forms.contains(SpecialFormCalibration.Form.NORMAL));
        assertTrue(forms.contains(SpecialFormCalibration.Form.EVO));
        assertTrue(forms.contains(SpecialFormCalibration.Form.HERO));
        assertEquals("knight__evo", SpecialFormCalibration.assetId(
                "knight", SpecialFormCalibration.Form.EVO));
        assertEquals("knight__hero", SpecialFormCalibration.assetId(
                "knight", SpecialFormCalibration.Form.HERO));
    }

    @Test public void championUsesOneBaseCardSlotAndWildSlotRulesAreEnforced() {
        assertTrue(SpecialFormCalibration.allowedForms("archer_queen")
                .contains(SpecialFormCalibration.Form.CHAMPION));
        Map<String, SpecialFormCalibration.Form> valid = new HashMap<>();
        valid.put("firecracker", SpecialFormCalibration.Form.EVO);
        valid.put("archer_queen", SpecialFormCalibration.Form.CHAMPION);
        valid.put("skeletons", SpecialFormCalibration.Form.EVO);
        assertNull(SpecialFormCalibration.validate(valid));

        valid.put("musketeer", SpecialFormCalibration.Form.HERO);
        assertNotNull(SpecialFormCalibration.validate(valid));
    }

    @Test public void currentVoidCostIsFive() {
        CardCatalog.Card card = CardCatalog.find("void");
        assertNotNull(card);
        assertEquals(5, card.cost);
    }
}
