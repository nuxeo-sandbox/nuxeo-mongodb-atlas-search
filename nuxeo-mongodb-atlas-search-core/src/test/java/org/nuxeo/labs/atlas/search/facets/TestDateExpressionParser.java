package org.nuxeo.labs.atlas.search.facets;

import org.junit.Assert;
import org.junit.Test;
import org.nuxeo.labs.atlas.search.pp.facets.AtlasDateRangeFacet;

public class TestDateExpressionParser {

    @Test
    public void testNow() {
        String expression = "now";
        String date = AtlasDateRangeFacet.parseRelativeDate(expression);
        Assert.assertNotNull(date);
    }

    @Test
    public void testPlusOneHour() {
        String expression = "now+1H";
        String date = AtlasDateRangeFacet.parseRelativeDate(expression);
        Assert.assertNotNull(date);
    }

    @Test
    public void testMinusOneHour() {
        String expression = "now-1H";
        String date = AtlasDateRangeFacet.parseRelativeDate(expression);
        Assert.assertNotNull(date);
    }

    @Test
    public void testPlusOneDay() {
        String expression = "now+1d";
        String date = AtlasDateRangeFacet.parseRelativeDate(expression);
        Assert.assertNotNull(date);
    }
}
