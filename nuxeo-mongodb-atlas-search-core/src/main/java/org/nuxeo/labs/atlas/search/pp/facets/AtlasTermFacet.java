package org.nuxeo.labs.atlas.search.pp.facets;

import com.mongodb.client.model.search.SearchOperator;
import org.bson.BsonValue;
import org.bson.Document;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.core.BucketTerm;

import java.util.Arrays;
import java.util.List;

import static org.nuxeo.labs.atlas.search.pp.MongoDBAtlasSearchQueryConverter.getFieldName;

/**
 * @since 6.0
 */
public class AtlasTermFacet extends AtlasFacetBase<BucketTerm> {

    public AtlasTermFacet(AggregateDefinition definition, DocumentModel searchDocument) {
        super(definition, searchDocument);
    }

    @Override
    public Document getFacet() {
        return new Document("type", "string").append("path", getFieldName(getField(), null));
    }

    @Override
    public SearchOperator getSelectionFilter() {
        List<String> values = (List<String>) getSearchDocument().getProperty(
                definition.getSearchField().getSchema(),
                definition.getSearchField().getName());

        if (values != null && !values.isEmpty()) {
            return SearchOperator.of(new Document("in",
                    new Document("path", getFieldName(definition.getDocumentField(), null))
                            .append("value", values)));
        } else {
            return null;
        }
    }

    @Override
    public void parseAggregation(BsonValue facet) {
        BsonValue[] buckets = facet.asDocument().getArray("buckets").toArray(BsonValue[]::new);
        List<BucketTerm> parsedBucket = Arrays.stream(buckets).map(bucket ->
                new BucketTerm(
                        bucket.asDocument().getString("_id").getValue(),
                        bucket.asDocument().getInt64("count").getValue()
                )).toList();
        setBuckets(parsedBucket);
    }

}
