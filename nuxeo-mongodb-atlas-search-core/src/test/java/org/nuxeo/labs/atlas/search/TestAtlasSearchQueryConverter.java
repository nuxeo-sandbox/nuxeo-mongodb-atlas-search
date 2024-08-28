package org.nuxeo.labs.atlas.search;

import com.mongodb.client.model.search.SearchOperator;
import org.bson.BsonDocument;
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

import java.util.List;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_MIXIN_TYPES;
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
        Assert.assertEquals(KEY_ID,getFieldName(NXQL.ECM_UUID,null));
        Assert.assertEquals(KEY_MIXIN_TYPES,getFieldName(NXQL.ECM_MIXINTYPE,null));
        Assert.assertEquals("content.length",getFieldName("file:content/length",null));
        Assert.assertEquals("views",getFieldName("picture:views",null));
    }

    @Test
    public void testEqualsTimeStamp() {
        String nxql = "SELECT * FROM Document WHERE dc:created = TIMESTAMP '2023-07-13T22:00:00.000Z'";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        BsonDocument equals = searchOperator.toBsonDocument().getDocument("equals");
        Assert.assertEquals("dc:created", equals.getString("path").getValue());
        Assert.assertTrue(equals.getDateTime("value").getValue() > 0);
    }

    @Test
    public void testEqualsDate() {
        String nxql = "SELECT * FROM Document WHERE dc:created = DATE '2023-07-13'";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        BsonDocument equals = searchOperator.toBsonDocument().getDocument("equals");
        Assert.assertEquals("dc:created", equals.getString("path").getValue());
        Assert.assertTrue(equals.getDateTime("value").getValue() > 0);
    }

    @Test
    public void testEqualSystemStringField() {
        String nxql = "SELECT * FROM Document WHERE ecm:name = 'Hello'";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        BsonDocument equals = searchOperator.toBsonDocument().getDocument("equals");
        Assert.assertEquals("ecm:name", equals.getString("path").getValue());
        Assert.assertEquals("Hello",
                equals.getString("value").getValue());
    }

    @Test
    public void testEqualIsVersion() {
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.makeBooleanEcmPropertyFilter("=", NXQL.ECM_ISVERSION, true);
        Assert.assertNotNull(searchOperator);
        BsonDocument equals = searchOperator.toBsonDocument().getDocument("equals");
        Assert.assertEquals(NXQL.ECM_ISVERSION, equals.getString("path").getValue());
        Assert.assertTrue(equals.getBoolean("value").getValue());
    }

    @Test
    public void testEqualIsNotVersion() {
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.makeBooleanEcmPropertyFilter("=", NXQL.ECM_ISVERSION, false);
        System.out.println(searchOperator);
        Assert.assertEquals(1,searchOperator.toBsonDocument().getDocument("compound").getArray("mustNot").size());
        BsonDocument mustnot = searchOperator.toBsonDocument().getDocument("compound").getArray("mustNot").get(0).asDocument();
        Assert.assertEquals(NXQL.ECM_ISVERSION, mustnot.getDocument("equals").getString("path").getValue());
    }

    @Test
    public void testHasMixIn() {
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.makeMixinTypesFilter("=", NXQL.ECM_MIXINTYPE, List.of("HiddenInNavigation"));
        System.out.println(searchOperator);
        Assert.assertEquals(1,searchOperator.toBsonDocument().getDocument("compound").getArray("should").size());
    }

    @Test
    public void testDoesNotHasMixIn() {
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.makeMixinTypesFilter("!=", NXQL.ECM_MIXINTYPE, List.of("HiddenInNavigation"));
        System.out.println(searchOperator);
        Assert.assertEquals(1,searchOperator.toBsonDocument().getDocument("compound").getArray("must").size());
        //Assert.assertEquals(1,searchOperator.toBsonDocument().getDocument("compound").getArray("mustNot").size());
    }

    @Test
    public void testOR() {
        String nxql = "SELECT * FROM Document WHERE ecm:isVersion = 1 OR ecm:isTrashed = 1";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals(2,searchOperator.toBsonDocument().getDocument("compound").getArray("should").size());
    }

    @Test
    public void testAND() {
        String nxql = "SELECT * FROM Document WHERE ecm:isVersion = 1 AND ecm:isTrashed = 1";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals(2,searchOperator.toBsonDocument().getDocument("compound").getArray("must").size());
    }

    @Test
    public void testNOT() {
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
        BsonDocument in = searchOperator.toBsonDocument().getDocument("in");
        Assert.assertEquals("ecm:primaryType", in.getString("path").getValue());
        Assert.assertEquals(5, in.getArray("value").size());
    }

    @Test
    public void testNotIn() {
        String nxql = "SELECT * FROM Document WHERE ecm:primaryType NOT IN ('Domain', 'SectionRoot', 'TemplateRoot', 'WorkspaceRoot', 'Favorites')";
        SearchOperator searchOperator = MongoDBAtlasSearchQueryConverter.toAtlasQuery(nxql);
        System.out.println(searchOperator);
        Assert.assertEquals(1, searchOperator.toBsonDocument().getDocument("compound").getArray("mustNot").size());
    }

}
