package org.nuxeo.labs.atlas.search;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.core.util.PageProviderHelper;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderDefinition;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import javax.inject.Inject;
import java.util.HashMap;

@RunWith(FeaturesRunner.class)
@Features({PlatformFeature.class})
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy({
        "nuxeo-mongodb-atlas-search-core",
        "nuxeo-mongodb-atlas-search-core:test-mongodb-connection-config.xml"
})
public class TestAtlasPageProvider {

    @Inject
    protected CoreSession session;

    @Test
    public void testPP() {
        PageProviderDefinition def = PageProviderHelper.getPageProviderDefinition("simple-atlas-search");
        HashMap<String,String> namedParameters = new HashMap<>();
        PageProvider<?> pp = PageProviderHelper.getPageProvider(session, def, namedParameters);
        pp.getCurrentPage();
    }

}
