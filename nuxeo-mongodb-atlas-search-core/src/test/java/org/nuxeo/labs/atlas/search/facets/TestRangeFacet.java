package org.nuxeo.labs.atlas.search.facets;

import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.core.util.PageProviderHelper;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderDefinition;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.labs.atlas.search.pp.MongoDBAtlasSearchPP;
import org.nuxeo.labs.atlas.search.pp.facets.AtlasFacetBase;
import org.nuxeo.labs.atlas.search.pp.facets.AtlasRangeFacet;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(FeaturesRunner.class)
@Features({PlatformFeature.class})
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy({
        "nuxeo-mongodb-atlas-search-core",
        "org.nuxeo.ecm.core.storage.mongodb",
        "nuxeo-mongodb-atlas-search-core:test-mongodb-connection-config.xml",
        "nuxeo-mongodb-atlas-search-core:simple-mongodb-atlas-search-pp-contrib.xml"
})
public class TestRangeFacet {

    @Inject
    CoreSession session;

    @Test
    public void testRangeFacet() {
        HashMap<String, String> namedParameters = new HashMap<>();
        MongoDBAtlasSearchPP pp = (MongoDBAtlasSearchPP) getPP(namedParameters);
        AtlasFacetBase facet = pp.getAggregate("common_size_agg");
        Assert.assertTrue(facet instanceof AtlasRangeFacet);
        Document document = facet.getFacet();
        Assert.assertEquals("number", document.getString("type"));
        Assert.assertEquals("content.length", document.getString("path"));
        List<Double> boundaries = (List<Double>) document.get("boundaries", List.class);
        Assert.assertEquals(6,boundaries.size());
    }

    @Test
    public void testEmptyRangeFilter() {
        HashMap<String, String> namedParameters = new HashMap<>();
        MongoDBAtlasSearchPP pp = (MongoDBAtlasSearchPP) getPP(namedParameters);
        AtlasFacetBase facet = pp.getAggregate("common_size_agg");
        Assert.assertNull(facet.getSelectionFilter());
    }

    @Test
    public void testRangeFilter() {
        HashMap<String, String> namedParameters = new HashMap<>();
        namedParameters.put("common_size_agg","[\"small\"]");
        MongoDBAtlasSearchPP pp = (MongoDBAtlasSearchPP) getPP(namedParameters);
        AtlasFacetBase facet = pp.getAggregate("common_size_agg");
        BsonDocument filter = facet.getSelectionFilter().toBsonDocument().getDocument("range");
        Assert.assertEquals(102400.0, filter.getDouble("gte").getValue(),Double.MIN_VALUE);
        Assert.assertEquals(1048576.0, filter.getDouble("lt").getValue(), Double.MIN_VALUE);
    }

    public PageProvider<DocumentModel> getPP(Map<String, String> namedParameters) {
        PageProviderDefinition def = PageProviderHelper.getPageProviderDefinition("simple-atlas-search");
        return (PageProvider<DocumentModel>) PageProviderHelper.getPageProvider(session, def, namedParameters);
    }
}
