package org.nuxeo.labs.atlas.search;

import com.mongodb.client.model.search.SearchOperator;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.labs.atlas.search.pp.MongoDBAtlasSearchQueryConverter;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import static org.nuxeo.labs.atlas.search.pp.MongoDBAtlasSearchQueryConverter.getFieldName;

@RunWith(FeaturesRunner.class)
@Features({PlatformFeature.class})
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy({
        "nuxeo-mongodb-atlas-search-core",
        "org.nuxeo.ecm.core.storage.mongodb"
})
public class TestAtlasSearchQueryConverter {

    @Test
    public void testInconsistentFieldNameNormalization() {
        Assert.assertEquals("ecm:id",getFieldName("ecm:uuid",null));
        Assert.assertEquals("content.length",getFieldName("file:content/length",null));
        Assert.assertEquals("views",getFieldName("picture:views",null));
    }

    @Test
    public void testEqualTimeStamp() {
        String nxql = "SELECT * FROM Document WHERE dc:created = TIMESTAMP '2023-07-13T22:00:00.000Z'";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals("ISODate(\"2023-07-13T22:00:00.000Z\")",
                searchOperator.toBsonDocument().getDocument("equals").getString("value").getValue());
    }

    @Test
    public void testGreaterThanTimeStamp() {
        String nxql = "SELECT * FROM Document WHERE dc:created > TIMESTAMP '2023-07-13T22:00:00.000Z'";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals("ISODate(\"2023-07-13T22:00:00.000Z\")",
                searchOperator.toBsonDocument().getDocument("range").getString("gt").getValue());
    }


    @Test
    public void testEqualIsVersion() {
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.makeVersionFilter("=", NXQL.ECM_ISVERSION, true);
        Assert.assertNotNull(searchOperator);
        Assert.assertTrue(searchOperator.toBsonDocument().getDocument("equals").getBoolean("value").getValue());
    }

    @Test
    public void testEqualIsNotVersion() {
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.makeVersionFilter("=", NXQL.ECM_ISVERSION, false);
        System.out.println(searchOperator);
        Assert.assertNull(searchOperator);
    }

    @Test
    public void testSingleOr() {
        String nxql = "SELECT * FROM Document WHERE ecm:isVersion = 1 OR ecm:isTrashed = 1";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals(2,searchOperator.toBsonDocument().getDocument("compound").getArray("should").size());
    }

    @Test
    public void testSingleAnd() {
        String nxql = "SELECT * FROM Document WHERE ecm:isVersion = 1 AND ecm:isTrashed = 1";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals(2,searchOperator.toBsonDocument().getDocument("compound").getArray("must").size());
    }

    @Test
    public void testSingleNot() {
        String nxql = "SELECT * FROM Document WHERE NOT dc:title = 'My Doc'";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals(1,searchOperator.toBsonDocument().getDocument("compound").getArray("mustNot").size());
    }

    @Test
    public void testIn() {
        String nxql = "SELECT * FROM Document WHERE ecm:primaryType IN ('Domain', 'SectionRoot', 'TemplateRoot', 'WorkspaceRoot', 'Favorites')";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals(5, searchOperator.toBsonDocument().getDocument("in").getArray("value").size());
    }

    @Test
    public void testNotIn() {
        String nxql = "SELECT * FROM Document WHERE ecm:primaryType NOT IN ('Domain', 'SectionRoot', 'TemplateRoot', 'WorkspaceRoot', 'Favorites')";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals(1, searchOperator.toBsonDocument().getDocument("compound").getArray("mustNot").size());
    }

}
