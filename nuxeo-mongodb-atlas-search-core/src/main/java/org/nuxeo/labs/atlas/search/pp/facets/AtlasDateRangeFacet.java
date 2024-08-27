package org.nuxeo.labs.atlas.search.pp.facets;

import com.mongodb.client.model.search.SearchOperator;
import org.bson.BsonValue;
import org.bson.Document;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.api.AggregateRangeDateDefinition;
import org.nuxeo.ecm.platform.query.core.BucketRangeDate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.nuxeo.labs.atlas.search.pp.MongoDBAtlasSearchQueryConverter.getFieldName;

public class AtlasDateRangeFacet extends AtlasFacetBase<BucketRangeDate> {

    public static final Pattern DATE_EXPRESSION_PATTERN = Pattern.compile("now([+-])([0-9]+)([dMHy])");

    public AtlasDateRangeFacet(AggregateDefinition definition, DocumentModel searchDocument) {
        super(definition, searchDocument);
    }

    @Override
    public Document getFacet() {
        //build interval list
        HashSet<String> dateBoundaries = new HashSet<>();

        definition.getDateRanges().forEach(range -> {
            dateBoundaries.add(range.getFromAsString() != null ? range.getFromAsString() : "min");
            dateBoundaries.add(range.getToAsString() != null ? range.getToAsString() : "max");
        });

        List<Date> boundaries = dateBoundaries.stream().filter(Objects::nonNull)
                .map(AtlasDateRangeFacet::parseRelativeDate).sorted()
                .map(value -> Date.from(Instant.parse(value)))
                .toList();

        if (boundaries.size() != (definition.getDateRanges().size()+1)) {
            throw new NuxeoException(String.format("bucket intervals for %s are disjoint. That's no good with atlas search",definition.getId()));
        }

        return new Document("type", "date")
                .append("path", getFieldName(definition.getDocumentField(),null))
                .append("boundaries", boundaries);
    }

    @Override
    public SearchOperator getSelectionFilter() {
        List<String> values = getValues();
        if (values != null && !values.isEmpty()) {
            String key = values.get(0);
            Optional<AggregateRangeDateDefinition> rangeDefinitionOpt = definition.getDateRanges().stream()
                    .filter(range -> range.getKey().equals(key)).findFirst();
            if (rangeDefinitionOpt.isPresent()) {
                AggregateRangeDateDefinition rangeDefinition = rangeDefinitionOpt.get();
                return SearchOperator.of(new Document("range",
                        new Document("path", getFieldName(definition.getDocumentField(), null))
                                .append("gte", Date.from(Instant.parse(normalizeFromValue(rangeDefinition.getFromAsString()))))
                                .append("lt", Date.from(Instant.parse(normalizeToValue(rangeDefinition.getToAsString()))))));
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
        List<BucketRangeDate> parsedBucket = new ArrayList<>();
        BsonValue[] buckets = facet.asDocument().getArray("buckets").toArray(BsonValue[]::new);
        List<AggregateRangeDateDefinition> ranges = getOrderedRangeDefinitions();
        for(int i = 0; i < buckets.length; i++ ) {
            BsonValue bucket = buckets[i];
            AggregateRangeDateDefinition rangeDefinition = ranges.get(i);
            BucketRangeDate bucketRange = new BucketRangeDate(
                    rangeDefinition.getKey(),
                    null,
                    null,
                    bucket.asDocument().getInt64("count").getValue());

            if (filterKey == null || filterKey.equals(rangeDefinition.getKey()) ) {
                parsedBucket.add(bucketRange);
            }
        }
        setBuckets(parsedBucket);
    }

    public String normalizeFromValue(String value) {
        return parseRelativeDate(value != null ? value : "min");
    }

    public String normalizeToValue(String value) {
        return parseRelativeDate(value != null ? value : "max");
    }

    public static String parseRelativeDate(String expression) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        if ("now".equals(expression)) {
            return LocalDateTime.now().format(formatter);
        } else if ("min".equals(expression)) {
            return LocalDateTime.of(1970,1,1,0,0).format(formatter);
        } if ("max".equals(expression)) {
            return LocalDateTime.of(2099,1,1,0,0).format(formatter);
        } else {
            Matcher matcher = DATE_EXPRESSION_PATTERN.matcher(expression);
            if (matcher.matches()) {
                LocalDateTime localDate =  LocalDateTime.now();
                String op = matcher.group(1);
                int value = Integer.parseInt(matcher.group(2));
                String unit = matcher.group(3);

                if ("-".equals(op)) {
                    value = -value;
                }

                if ("H".equals(unit)) {
                    localDate = localDate.plus(Duration.of(value, ChronoUnit.HOURS));
                } else if ("d".equals(unit)) {
                    localDate = localDate.plusDays(value);
                } else if ("M".equals(unit)) {
                    localDate = localDate.plusMonths(value);
                } else if ("y".equals(unit)) {
                    localDate = localDate.plusYears(value);
                }

                return localDate.format(formatter);
            } else {
                return null;
            }
        }
    }

    public List<AggregateRangeDateDefinition> getOrderedRangeDefinitions() {
        //build interval list
        TreeMap<String, AggregateRangeDateDefinition> fromKeyMap = new TreeMap<>();

        definition.getDateRanges().forEach(range -> {
            fromKeyMap.put(normalizeFromValue(range.getFromAsString()), range);
        });

        return fromKeyMap.values().stream().toList();
    }

}