package org.nuxeo.labs.atlas.search.pp;

import com.mongodb.client.model.search.CompoundSearchOperator;
import com.mongodb.client.model.search.SearchOperator;

import com.mongodb.client.model.search.SearchPath;
import org.bson.Document;
import org.nuxeo.ecm.core.api.*;
import org.nuxeo.ecm.core.query.QueryParseException;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.query.sql.SQLQueryParser;
import org.nuxeo.ecm.core.query.sql.model.*;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.primitives.BooleanType;
import org.nuxeo.ecm.core.schema.utils.DateParser;
import org.nuxeo.ecm.core.security.SecurityService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.ecm.core.storage.sql.jdbc.NXQLQueryMaker;

import java.io.StringReader;
import java.time.ZonedDateTime;
import java.util.*;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ANCESTOR_IDS;

public class MongoDBAtlasSearchQueryConverter {

    public static final String SELECT_ALL = "SELECT * FROM Document";

    public static final String SELECT_ALL_WHERE = "SELECT * FROM Document WHERE ";


    private MongoDBAtlasSearchQueryConverter() {
    }

    public static SearchOperator toAtlasQuery(final String nxql) {
        return toAtlasQuery(nxql, null);
    }

    public static SearchOperator toAtlasQuery(final String nxql, final CoreSession session) {
        final LinkedList<ExpressionBuilder> builders = new LinkedList<>();
        SQLQuery nxqlQuery = getSqlQuery(nxql);
        if (session != null) {
            nxqlQuery = addSecurityPolicy(session, nxqlQuery);
        }
        final ExpressionBuilder ret = new ExpressionBuilder(null);
        builders.add(ret);
        final ArrayList<String> fromList = new ArrayList<>();
        nxqlQuery.accept(new DefaultQueryVisitor() {

            @Override
            public void visitFromClause(FromClause node) {
                FromList elements = node.elements;
                SchemaManager schemaManager = Framework.getService(SchemaManager.class);

                for (String type : elements.values()) {
                    if (NXQLQueryMaker.TYPE_DOCUMENT.equalsIgnoreCase(type)) {
                        // From Document means all doc types
                        fromList.clear();
                        return;
                    }
                    Set<String> types = schemaManager.getDocumentTypeNamesExtending(type);
                    if (types != null) {
                        fromList.addAll(types);
                    }
                }
            }

            @Override
            public void visitMultiExpression(MultiExpression node) {
                for (Iterator<Predicate> it = node.predicates.iterator(); it.hasNext(); ) {
                    it.next().accept(this);
                    if (it.hasNext()) {
                        node.operator.accept(this);
                    }
                }
            }

            @Override
            public void visitSelectClause(SelectClause node) {
                // NOP
            }

            @Override
            public void visitExpression(Expression node) {
                Operator op = node.operator;
                if (op == Operator.AND || op == Operator.OR || op == Operator.NOT) {
                    builders.add(new ExpressionBuilder(op.toString()));
                    super.visitExpression(node);
                    ExpressionBuilder expr = builders.removeLast();
                    if (!builders.isEmpty()) {
                        builders.getLast().merge(expr);
                    }
                } else {
                    Reference ref = node.lvalue instanceof Reference ? (Reference) node.lvalue : null;
                    String name = ref != null ? ref.name : node.lvalue.toString();
                    String value = null;
                    if  (node.rvalue instanceof DateLiteral) {
                        value = String.format("ISODate(\"%s\")",((DateLiteral) node.rvalue).asString());
                    } else if (node.rvalue instanceof Literal) {
                        value = ((Literal) node.rvalue).asString();
                    } else if (node.rvalue instanceof Function) {
                        Function function = (Function) node.rvalue;
                        String func = function.name;
                        if (NXQL.NOW_FUNCTION.equalsIgnoreCase(func)) {
                            String periodAndDurationText;
                            if (function.args == null || function.args.size() != 1) {
                                periodAndDurationText = null;
                            } else {
                                periodAndDurationText = ((StringLiteral) function.args.get(0)).value;
                            }
                            ZonedDateTime dateTime = NXQL.nowPlusPeriodAndDuration(periodAndDurationText);
                            Calendar calendar = GregorianCalendar.from(dateTime);
                            value = DateParser.formatW3CDateTime(calendar);
                        } else {
                            throw new IllegalArgumentException("Unknown function: " + func);
                        }
                    } else if (node.rvalue != null) {
                        value = node.rvalue.toString();
                    }
                    Object[] values = null;
                    if (node.rvalue instanceof LiteralList) {
                        LiteralList items = (LiteralList) node.rvalue;
                        values = new Object[items.size()];
                        int i = 0;
                        for (Literal item : items) {
                            values[i++] = item.asString();
                        }
                    }
                    // add expression to the last builder
                    EsHint hint = (ref != null) ? ref.esHint : null;
                    builders.getLast()
                            .add(makeQueryFromSimpleExpression(op.toString(), name, value, values, hint, session));
                }
            }
        });

        SearchOperator searchOperator = ret.get();
        if (!fromList.isEmpty()) {
            return SearchOperator.compound().must(List.of(searchOperator)).filter(List.of(
                    makeQueryFromSimpleExpression("IN", NXQL.ECM_PRIMARYTYPE, null,
                            fromList.toArray(), null, null).filter));
        }
        return searchOperator;
    }

    public static SQLQuery getSqlQuery(String nxql) {
        String query = completeQueryWithSelect(nxql);
        SQLQuery nxqlQuery;
        try {
            nxqlQuery = SQLQueryParser.parse(new StringReader(query));
        } catch (QueryParseException e) {
            e.addInfo("Query: " + query);
            throw e;
        }
        return nxqlQuery;
    }

    public static SQLQuery addSecurityPolicy(CoreSession session, SQLQuery query) {
        Collection<SQLQuery.Transformer> transformers = Framework.getService(SecurityService.class)
                .getPoliciesQueryTransformers(
                        session.getRepositoryName());
        for (SQLQuery.Transformer trans : transformers) {
            query = trans.transform(session.getPrincipal(), query);
        }
        return query;
    }

    public static String completeQueryWithSelect(String nxql) {
        String query = (nxql == null) ? "" : nxql.trim();
        if (query.isEmpty()) {
            query = SELECT_ALL;
        } else if (!query.toLowerCase().startsWith("select ")) {
            query = SELECT_ALL_WHERE + nxql;
        }
        return query;
    }

    public static QueryAndFilter makeQueryFromSimpleExpression(String op, String nxqlName, Object value,
                                                               Object[] values, EsHint hint, CoreSession session) {
        SearchOperator query = null;
        SearchOperator filter = null;

        String name = getFieldName(nxqlName, hint);
        /*if (hint != null && hint.operator != null) {
            if (ArrayUtils.isNotEmpty(values)) {
                filter = makeHintQuery(name, values, hint);
            } else {
                query = makeHintQuery(name, value, hint);
            }
        } else*/
        if (nxqlName.startsWith(NXQL.ECM_FULLTEXT) && ("=".equals(op) || "!=".equals(op) || "<>".equals(op)
                || "LIKE".equals(op) || "NOT LIKE".equals(op))) {
            query = makeFulltextQuery(nxqlName, (String) value, hint);
            if ("!=".equals(op) || "<>".equals(op) || "NOT LIKE".equals(op)) {
                filter = SearchOperator.compound().mustNot(List.of(query));
                query = null;
            }
        } else if (nxqlName.startsWith(NXQL.ECM_ANCESTORID)) {
            filter = SearchOperator.of(new Document("equals", new Document("path", KEY_ANCESTOR_IDS).append("value", value)));
            if ("!=".equals(op) || "<>".equals(op)) {
                filter = SearchOperator.compound().mustNot(List.of(filter));
            }
        } else if (nxqlName.equals(NXQL.ECM_ISTRASHED)) {
            filter = makeTrashedFilter(op, name, (String) value);
        } else if (nxqlName.equals(NXQL.ECM_ISVERSION)) {
            filter = makeVersionFilter(op, name, checkBoolValue(nxqlName, value));
        } else
            switch (op) {
                case "=":
                    filter = SearchOperator.of(new Document("equals", new Document("path", name).append("value", checkBoolValue(nxqlName, value))));
                    break;
                case "<>":
                case "!=":
                    filter = SearchOperator.compound().mustNot(
                            List.of(SearchOperator.of(new Document("equals", new Document("path", name).append("value", checkBoolValue(nxqlName, value))))));
                    break;
                case ">":
                    filter = SearchOperator.numberRange(SearchPath.fieldPath(name)).gt((Number) value);
                    break;
                case "<":
                    filter = SearchOperator.numberRange(SearchPath.fieldPath(name)).lt((Number) value);
                    break;
                case ">=":
                    filter = SearchOperator.numberRange(SearchPath.fieldPath(name)).gte((Number) value);
                    break;
                case "<=":
                    filter = SearchOperator.numberRange(SearchPath.fieldPath(name)).lte((Number) value);
                    break;
                case "BETWEEN":
                case "NOT BETWEEN":
                    filter = SearchOperator.numberRange(SearchPath.fieldPath(name)).gteLte((Number)values[0], (Number)values[1]);
                    if (op.startsWith("NOT")) {
                        filter = SearchOperator.compound().mustNot(List.of(filter));
                    }
                    break;
                case "IN":
                case "NOT IN":
                    filter =  SearchOperator.of(new Document("in", new Document("path",SearchPath.fieldPath(name)).append("value", Arrays.stream(values).toList())));
                    if (op.startsWith("NOT")) {
                        filter = SearchOperator.compound().mustNot(List.of(filter));
                    }
                    break;
                case "IS NULL":
                    filter = SearchOperator.compound().mustNot(List.of(SearchOperator.exists(SearchPath.fieldPath(name))));
                    break;
                case "IS NOT NULL":
                    filter = SearchOperator.exists(SearchPath.fieldPath(name));
                    break;
                /*case "LIKE":
                case "ILIKE":
                case "NOT LIKE":
                case "NOT ILIKE":
                    query = makeLikeQuery(op, name, (String) value, hint);
                    if (op.startsWith("NOT")) {
                        filter = QueryBuilders.boolQuery().mustNot(query);
                        query = null;
                    }
                    break;
                case "STARTSWITH":
                    filter = makeStartsWithQuery(name, value);
                    break;*/
                default:
                    throw new UnsupportedOperationException("Operator: '" + op + "' is unknown");
            }
        return new QueryAndFilter(query, filter);
    }

    public static String getFieldName(String name, EsHint hint) {
        if (hint != null && hint.index != null) {
            return hint.index;
        }
        // compat
        if (NXQL.ECM_ISVERSION_OLD.equals(name)) {
            name = NXQL.ECM_ISVERSION;
        }
        // complex field
        name = name.replace("/*", "");
        name = name.replace("/", ".");
        return name;
    }

    public static SearchOperator makeFulltextQuery(String nxqlName, String value, EsHint hint) {
        return SearchOperator.text(SearchPath.wildcardPath("*"), value);
    }

    public static SearchOperator makeTrashedFilter(String op, String name, String value) {
        boolean equalsDeleted;
        switch (op) {
            case "=":
                equalsDeleted = true;
                break;
            case "<>":
            case "!=":
                equalsDeleted = false;
                break;
            default:
                throw new IllegalArgumentException(NXQL.ECM_ISTRASHED + " requires = or <> operator");
        }
        if ("0".equals(value)) {
            equalsDeleted = !equalsDeleted;
        } else if ("1".equals(value)) {
            // equalsDeleted unchanged
        } else {
            throw new IllegalArgumentException(NXQL.ECM_ISTRASHED + " requires literal 0 or 1 as right argument");
        }

        return equalsDeleted ?
                SearchOperator.of(new Document("equals", new Document("path", name).append("value", equalsDeleted)))
                : null;
    }

    public static SearchOperator makeVersionFilter(String op, String name, Object value) {
        return (Boolean) value ?
                SearchOperator.of(new Document("equals", new Document("path", name).append("value", value)))
                : null;
    }

    public static Object checkBoolValue(String nxqlName, Object value) {
        if (!"0".equals(value) && !"1".equals(value)) {
            return value;
        }
        switch (nxqlName) {
            case NXQL.ECM_ISPROXY:
            case NXQL.ECM_ISCHECKEDIN:
            case NXQL.ECM_ISTRASHED:
            case NXQL.ECM_ISVERSION:
            case NXQL.ECM_ISVERSION_OLD:
            case NXQL.ECM_ISRECORD:
            case NXQL.ECM_ISFLEXIBLERECORD:
            case NXQL.ECM_HASLEGALHOLD:
            case NXQL.ECM_ISLATESTMAJORVERSION:
            case NXQL.ECM_ISLATESTVERSION:
                break;
            default:
                SchemaManager schemaManager = Framework.getService(SchemaManager.class);
                Field field = schemaManager.getField(nxqlName);
                if (field == null || !BooleanType.ID.equals(field.getType().getName())) {
                    return value;
                }
        }
        return !"0".equals(value);
    }


    /**
     * Class to hold both a query and a filter
     */
    public static class QueryAndFilter {

        public final SearchOperator query;

        public final SearchOperator filter;

        public QueryAndFilter(SearchOperator query, SearchOperator filter) {
            this.query = query;
            this.filter = filter;
        }
    }

    public static class ExpressionBuilder {

        public final String operator;

        public CompoundSearchOperator query;

        public ExpressionBuilder(final String op) {
            this.operator = op;
            this.query = null;
        }

        public void add(final QueryAndFilter qf) {
            if (qf != null) {
                add(qf.query, qf.filter);
            }
        }

        public void add(SearchOperator q) {
            add(q, null);
        }

        public void add(final SearchOperator q, final SearchOperator f) {
            if (q == null && f == null) {
                return;
            }
            SearchOperator inputQuery = q;
            if (inputQuery == null) {
                inputQuery = f;
            }
            if (operator == null) {
                // first level expression
                query = (CompoundSearchOperator) inputQuery;
            } else {
                // boolean query
                if ("AND".equals(operator)) {
                    query = query != null ? query.must(List.of(inputQuery)) : SearchOperator.compound().must(List.of(inputQuery));
                } else if ("OR".equals(operator)) {
                    query = query != null ? query.should(List.of(inputQuery)) : SearchOperator.compound().should(List.of(inputQuery));
                } else if ("NOT".equals(operator)) {
                    query = query != null ? query.mustNot(List.of(inputQuery)) : SearchOperator.compound().mustNot(List.of(inputQuery));
                }
            }
        }

        public void merge(ExpressionBuilder expr) {
            if ((expr.operator != null) && expr.operator.equals(operator) && (query == null)) {
                query = expr.query;
            } else {
                add(new QueryAndFilter(expr.query, null));
            }
        }

        public SearchOperator get() {
            return query;
        }

        @Override
        public String toString() {
            return query.toString();
        }

    }


}
