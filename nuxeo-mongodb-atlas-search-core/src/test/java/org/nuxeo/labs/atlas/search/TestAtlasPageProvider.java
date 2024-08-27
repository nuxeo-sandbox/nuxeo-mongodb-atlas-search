package org.nuxeo.labs.atlas.search;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.core.util.PageProviderHelper;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderDefinition;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import java.io.Serializable;
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
public class TestAtlasPageProvider {

    @Test
    public void testWithAgg() {
        HashMap<String, String> namedParameters = new HashMap<>();
        namedParameters.put("system_fulltext", "file");
        PageProvider<DocumentModel> pp = getPP(namedParameters);
        List<DocumentModel> results = pp.getCurrentPage();
        Assert.assertFalse(results.isEmpty());
        Assert.assertTrue(pp.getResultsCount() > 0);
        Map<String, Aggregate<? extends Bucket>> aggregates = pp.getAggregates();
        Assert.assertEquals(3, aggregates.size());
    }

    @Test
    public void testWithoutAgg() {
        HashMap<String, String> namedParameters = new HashMap<>();
        namedParameters.put("system_fulltext", "file");
        PageProvider<DocumentModel> pp = getPP(namedParameters);
        Map<String, Serializable> props = pp.getProperties();
        props.put(PageProvider.SKIP_AGGREGATES_PROP,true);
        pp.setProperties(props);
        List<DocumentModel> results = pp.getCurrentPage();
        Assert.assertFalse(results.isEmpty());
        Assert.assertTrue(pp.getResultsCount() > 0);
        Map<String, Aggregate<? extends Bucket>> aggregates = pp.getAggregates();
        Assert.assertTrue(aggregates == null || aggregates.isEmpty());
    }

    @Test
    public void testGtNumber() {
        HashMap<String, String> namedParameters = new HashMap<>();
        namedParameters.put("common_size_gt", "100");
        PageProvider<DocumentModel> pp = getPP(namedParameters);
        Map<String, Serializable> props = pp.getProperties();
        props.put(PageProvider.SKIP_AGGREGATES_PROP,true);
        pp.setProperties(props);
        List<DocumentModel> results = pp.getCurrentPage();
        Assert.assertFalse(results.isEmpty());
    }

    @Test
    public void testGtDate() {
        HashMap<String, String> namedParameters = new HashMap<>();
        namedParameters.put("system_fulltext", "file");
        namedParameters.put("dc_modified_gt", "1970-01-01T00:00:00.000Z");
        PageProvider<DocumentModel> pp = getPP(namedParameters);
        Map<String, Serializable> props = pp.getProperties();
        props.put(PageProvider.SKIP_AGGREGATES_PROP,true);
        pp.setProperties(props);
        List<DocumentModel> results = pp.getCurrentPage();
        Assert.assertFalse(results.isEmpty());
    }

    public PageProvider<DocumentModel> getPP(Map<String, String> namedParameters) {
        CoreSession session = CoreInstance.getCoreSession("default");
        PageProviderDefinition def = PageProviderHelper.getPageProviderDefinition("simple-atlas-search");
        return (PageProvider<DocumentModel>) PageProviderHelper.getPageProvider(session, def, namedParameters);
    }

}
