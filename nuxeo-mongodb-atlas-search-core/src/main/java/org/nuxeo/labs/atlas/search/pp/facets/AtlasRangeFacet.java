package org.nuxeo.labs.atlas.search.pp.facets;

import com.mongodb.client.model.search.SearchOperator;
import org.bson.BsonValue;
import org.bson.Document;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.api.AggregateRangeDefinition;
import org.nuxeo.ecm.platform.query.core.BucketRange;

import java.util.*;

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
                boundaries.add(Double.NEGATIVE_INFINITY);
            } else {
                boundaries.add(range.getFrom());
            }
            if (range.getTo() == null) {
                boundaries.add(Double.POSITIVE_INFINITY);
            }
        });
        return new Document("type", "number")
                .append("path", getFieldName(definition.getDocumentField(),null))
                .append("boundaries", boundaries);
    }

    @Override
    public SearchOperator getSelectionFilter() {
        List<String> values = getValues();
        if (values != null && !values.isEmpty()) {
            String key = values.get(0);
            Optional<AggregateRangeDefinition> rangeDefinitionOpt = definition.getRanges().stream()
                    .filter(range -> range.getKey().equals(key)).findFirst();
            if (rangeDefinitionOpt.isPresent()) {
                AggregateRangeDefinition rangeDefinition = rangeDefinitionOpt.get();
                return SearchOperator.of(new Document("range",
                        new Document("path", getFieldName(definition.getDocumentField(), null))
                                .append("gte", normalizeFromValue(rangeDefinition.getFrom()))
                                .append("lt", normalizeToValue(rangeDefinition.getTo()))));
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public void parseAggregation(BsonValue facet) {
        List<String> filterValues = getValues();
        String filterKey = filterValues != null && !filterValues.isEmpty() ? filterValues.get(0) : null;
        List<BucketRange> parsedBucket = new ArrayList<>();
        BsonValue[] buckets = facet.asDocument().getArray("buckets").toArray(BsonValue[]::new);
        List<AggregateRangeDefinition> ranges = definition.getRanges();
        for(int i = 0; i < buckets.length; i++ ) {
            BsonValue bucket = buckets[i];
            AggregateRangeDefinition rangeDefinition = ranges.get(i);
            BucketRange bucketRange = new BucketRange(
                    rangeDefinition.getKey(),
                    normalizeFromValue(rangeDefinition.getFrom()),
                    normalizeToValue(rangeDefinition.getTo()),
                    bucket.asDocument().getInt64("count").getValue());

            if (filterKey == null || filterKey.equals(rangeDefinition.getKey()) ) {
                parsedBucket.add(bucketRange);
            }
        }
        setBuckets(parsedBucket);
    }

    public double normalizeFromValue(Double value) {
        return Objects.requireNonNullElse(value, Double.MIN_VALUE);
    }

    public double normalizeToValue(Double value) {
        return Objects.requireNonNullElse(value, Double.MAX_VALUE);
    }


}