package org.nuxeo.labs.atlas.search;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(FeaturesRunner.class)
@Features({PlatformFeature.class})
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy({
        "nuxeo-mongodb-atlas-search-core",
        "org.nuxeo.ecm.core.storage.mongodb",
        "nuxeo-mongodb-atlas-search-core:test-mongodb-connection-config.xml"
})
public class TestAtlasPageProvider {

    @Test
    public void testPP() {
        CoreSession session = CoreInstance.getCoreSession("default");
        PageProviderDefinition def = PageProviderHelper.getPageProviderDefinition("simple-atlas-search");
        HashMap<String,String> namedParameters = new HashMap<>();
        namedParameters.put("input_text","apple");
        namedParameters.put("ecm_primarytype_agg","File");
        PageProvider<DocumentModel> pp = (PageProvider<DocumentModel>) PageProviderHelper.getPageProvider(session, def, namedParameters);
        List<DocumentModel> results = pp.getCurrentPage();
        System.out.println(results);
        Map<String, Aggregate<? extends Bucket>> aggregates = pp.getAggregates();
        System.out.println(aggregates);
    }

}
