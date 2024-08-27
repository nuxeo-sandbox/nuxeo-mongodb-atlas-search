package org.nuxeo.labs.atlas.search.pp.facets;

import com.mongodb.client.model.search.SearchOperator;
import org.bson.BsonValue;
import org.bson.Document;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.api.AggregateRangeDateDefinition;
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
        HashSet<Double> boundariesSet = new HashSet<>();
        definition.getRanges().forEach(range -> {
            boundariesSet.add(normalizeFromValue(range.getFrom()));
            boundariesSet.add(normalizeToValue(range.getTo()));
        });

        List<Double> boundaries = boundariesSet.stream().filter(Objects::nonNull)
                .sorted()
                .toList();

        if (boundaries.size() != (definition.getRanges().size()+1)) {
            throw new NuxeoException(String.format("bucket intervals for %s are disjoint. That's no good with atlas search",definition.getId()));
        }

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
        List<AggregateRangeDefinition> ranges = getOrderedRangeDefinitions();
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

    public List<AggregateRangeDefinition> getOrderedRangeDefinitions() {
        //build interval list
        TreeMap<Double, AggregateRangeDefinition> fromKeyMap = new TreeMap<>();

        definition.getRanges().forEach(range -> {
            fromKeyMap.put(normalizeFromValue(normalizeFromValue(range.getFrom())), range);
        });

        return fromKeyMap.values().stream().toList();
    }



}