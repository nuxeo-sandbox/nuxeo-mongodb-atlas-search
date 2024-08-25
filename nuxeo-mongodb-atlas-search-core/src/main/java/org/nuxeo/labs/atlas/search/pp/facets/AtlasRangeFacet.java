package org.nuxeo.labs.atlas.search.pp.facets;

import com.mongodb.client.model.search.SearchOperator;
import org.bson.BsonValue;
import org.bson.Document;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.api.AggregateRangeDefinition;
import org.nuxeo.ecm.platform.query.core.BucketRange;
import org.nuxeo.ecm.platform.query.core.BucketTerm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.nuxeo.labs.atlas.search.pp.MongoDBAtlasSearchQueryConverter.getFieldName;

public class AtlasRangeFacet extends AtlasFacetBase<BucketRange> {

    public AtlasRangeFacet(AggregateDefinition definition, DocumentModel searchDocument) {
        super(definition, searchDocument);
    }

    @Override
    public Document getFacet() {
        List<Double> boundaries = new ArrayList<>();
        definition.getRanges().forEach(range -> {
            if (range.getFrom() == null) {
                boundaries.add(Double.MIN_VALUE);
            } else {
                boundaries.add(range.getFrom());
            }
            if (range.getTo() == null) {
                boundaries.add(Double.MAX_VALUE);
            }
        });
        return new Document("type", "number")
                .append("path", getFieldName(definition.getDocumentField(),null))
                .append("boundaries", boundaries);
    }

    @Override
    public SearchOperator getSelectionFilter() {
        List<String> values = (List<String>) searchDocument.getProperty(
                definition.getSearchField().getSchema(),
                definition.getSearchField().getName());

        if (values != null && !values.isEmpty()) {
            String rangeName = values.get(0);
            AggregateRangeDefinition rangeDefinition = definition.getRanges().stream().filter(range -> rangeName.equals(range.getKey())).findFirst().get();
            return
                    SearchOperator.of(new Document("range",
                            new Document("path", getFieldName(definition.getDocumentField(), null))
                                    .append("boundaries", List.of(rangeDefinition.getFrom(), rangeDefinition.getTo()))));
        } else {
            return null;
        }
    }

    @Override
    public void parseAggregation(BsonValue facet) {
        BsonValue[] buckets = facet.asDocument().getArray("buckets").toArray(BsonValue[]::new);
        List<BucketRange> parsedBucket = Arrays.stream(buckets).map(bucket ->
                new BucketRange(
                        Double.toString(bucket.asDocument().getDouble("_id").getValue()),
                        0L,Long.valueOf(0),
                        bucket.asDocument().getInt64("count").getValue()
                )).toList();
        setBuckets(parsedBucket);
    }

}