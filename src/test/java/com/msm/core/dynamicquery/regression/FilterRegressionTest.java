package com.msm.core.dynamicquery.regression;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.msm.core.dynamicquery.ObjectMetadataFactory;
import com.msm.core.dynamicquery.internal.InternalFilterQuery;
import com.msm.core.dynamicquery.query.DefaultFilterQuery;
import com.msm.core.dynamicquery.query.FilterQuery;
import com.msm.core.filter.domain.FilterCondition;
import com.msm.core.filter.domain.FilterGroup;
import com.msm.core.filter.domain.FilterObject;
import com.msm.core.filter.domain.FilterOperator;
import com.msm.core.filter.domain.LogicalOperator;
import com.msm.core.filter.domain.ObjectFilterRequest;
import com.msm.core.filter.domain.SearchRequest;
import com.msm.core.filter.domain.pageable.PageRequest;
import com.msm.core.filter.domain.pageable.Sort;
import com.msm.core.filter.domain.pageable.SortDirection;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.AttributeRef;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.metadata.ObjectRelation;
import com.msm.core.metadata.RelationalTypeEnum;
import com.msm.core.security.DataScopeConditionResolver;
import com.msm.core.security.IdentifiableCode;
import com.msm.core.security.RequestContextHolder;
import com.msm.core.security.SecurityConditionProvider;
import com.msm.core.security.context.AuthorizationContext;
import com.msm.core.security.context.DataScopeContext;
import com.msm.core.security.context.RequestContext;
import com.msm.core.security.context.impl.DefaultDataScopeContext;
import com.msm.core.security.enums.AccessScope;
import com.msm.core.security.enums.SecurityDataScopeType;
import com.msm.core.security.model.Team;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression matrix of the existing filter (no relation): every operator, groups, search, soft delete, returned
 * fields, sorts, paging, data scopes, lookup, streams and the find methods, on the secured and the internal query.
 *
 * <p>It records, per case, the SQL and binds the query runs (mock connection) and, when {@code MSM_TEST_DB_URL} is
 * set, the result on a seeded PostgreSQL. Uses only the API of commons 2.4.2, so the same file runs on the previous
 * version: comparing the two outputs shows every change of behaviour. The SQL output is compared with
 * {@code filter-regression/golden-sql.txt} and the rows with {@code filter-regression/golden-rows.txt} (when the
 * database is set), golden files reviewed against the output of 2.4.2.
 *
 * <p>Outputs: {@code build/filter-regression/<label>-sql.txt} and {@code <label>-rows.txt}, label from the system
 * property or environment {@code FILTER_REGRESSION_LABEL} (default {@code current}).
 */
class FilterRegressionTest {

    private static final String DB_URL = System.getenv("MSM_TEST_DB_URL");
    private static final String LABEL = System.getProperty("FILTER_REGRESSION_LABEL",
            System.getenv().getOrDefault("FILTER_REGRESSION_LABEL", "current"));

    private static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final UUID OWNER_1 = UUID.fromString("00000000-0000-0000-0000-000000000c01");
    private static final UUID OWNER_2 = UUID.fromString("00000000-0000-0000-0000-000000000c02");
    private static final UUID TEAM_1 = UUID.fromString("00000000-0000-0000-0000-000000000d01");
    private static final UUID TEAM_2 = UUID.fromString("00000000-0000-0000-0000-000000000d02");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000e01");

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private static final ObjectMetadata ORDER = ObjectMetadata.builder()
            .name("frorder").tableName("fr_order")
            .attributes(new ArrayList<>(List.of(
                    attribute("id", "id", "UUID"),
                    attribute("code", "code", "String"),
                    attribute("name", "name", "String"),
                    attribute("amount", "amount", "BigDecimal"),
                    attribute("qty", "qty", "Long"),
                    attribute("active", "active", "Boolean"),
                    attribute("createdAt", "created_at", "Instant"),
                    attribute("tags", "tags", "List<String>"),
                    reference("customerId", "customer_id", "customerIdReference", "remoteaccount"),
                    attribute("ownerId", "owner_id", "UUID"),
                    attribute("teamId", "team_id", "UUID"),
                    attribute("orgId", "org_id", "UUID"),
                    attribute("isDeleted", "is_deleted", "Boolean"),
                    attribute("version", "version", "Long"),
                    attribute("customValues", "custom_values", "Map<String, Object>"))))
            .build();

    private static final ObjectMetadata ITEM = ObjectMetadata.builder()
            .name("frorderitem").tableName("fr_order_item")
            .attributes(new ArrayList<>(List.of(
                    attribute("id", "id", "UUID"),
                    attribute("orderId", "order_id", "UUID"),
                    attribute("code", "code", "String"),
                    attribute("isDeleted", "is_deleted", "Boolean"))))
            .objectRelation(ObjectRelation.builder().relationType(RelationalTypeEnum.CHILD_OF)
                    .targetObject("frorder").foreignKeyAttribute("orderId").build())
            .build();

    private static final ObjectMetadata PLAIN = ObjectMetadata.builder()
            .name("frplain").tableName("fr_plain")
            .securityEnabled(false)
            .attributes(new ArrayList<>(List.of(
                    attribute("id", "id", "UUID"),
                    attribute("code", "code", "String"))))
            .build();

    private static Connection connection;

    @BeforeAll
    static void setUp() throws Exception {
        ORDER.setSecuredAttributes(Map.of(
                SecurityDataScopeType.OWNER, ORDER.getAttributeByName("ownerId"),
                SecurityDataScopeType.TEAM, ORDER.getAttributeByName("teamId"),
                SecurityDataScopeType.BUSINESS_UNIT, ORDER.getAttributeByName("orgId"),
                SecurityDataScopeType.PARENT_CHILD, ORDER.getAttributeByName("orgId")));
        ITEM.setSecuredAttributes(Map.of());
        PLAIN.setSecuredAttributes(Map.of());
        ObjectMetadataFactory.registerObjectMetadata(ORDER);
        ObjectMetadataFactory.registerObjectMetadata(ITEM);
        ObjectMetadataFactory.registerObjectMetadata(PLAIN);
        if (DB_URL != null) {
            connection = DriverManager.getConnection(DB_URL);
            seed(DSL.using(connection, SQLDialect.POSTGRES));
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        RequestContextHolder.setRequestContextProvider(() -> context(Caller.NONE));
        if (connection != null) {
            connection.close();
        }
    }

    // ================================================================== cases

    private record Case(String name, Caller caller, Function<DSLContext, Object> run) {
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        Map<String, Supplier<ObjectFilterRequest>> requests = requests();
        // every request: secured filter as a business unit caller, internal filter
        requests.forEach((name, request) -> {
            cases.add(new Case("filter/default/" + name, Caller.BU, dsl -> secured(dsl).filter(ORDER, request.get())));
            cases.add(new Case("filter/internal/" + name, Caller.BU, dsl -> new InternalFilterQuery(dsl).filter(ORDER, request.get())));
        });
        // some requests with every caller, and as a lookup
        for (String name : List.of("empty", "eq.string", "search.code_name", "deleted.eq_true", "sort.amount_desc_code_asc")) {
            for (Caller caller : Caller.values()) {
                cases.add(new Case("filter/default/" + name + "@" + caller, caller,
                        dsl -> secured(dsl).filter(ORDER, requests.get(name).get())));
            }
            cases.add(new Case("lookup/default/" + name, Caller.BU, dsl -> secured(dsl).lookup(ORDER, requests.get(name).get())));
            cases.add(new Case("lookup/internal/" + name, Caller.BU, dsl -> new InternalFilterQuery(dsl).lookup(ORDER, requests.get(name).get())));
        }
        // a child object (data scope of its parent) and an object without security nor soft delete
        cases.add(new Case("filter/default/item.code", Caller.BU, dsl -> secured(dsl).filter(ITEM,
                request(and(condition("code", FilterOperator.EQUALS, "I1")), null, page(1, 10)))));
        cases.add(new Case("filter/default/item.code@ORG", Caller.ORG, dsl -> secured(dsl).filter(ITEM,
                request(and(condition("code", FilterOperator.EQUALS, "I1")), null, page(1, 10)))));
        cases.add(new Case("filter/default/plain.code", Caller.NONE, dsl -> secured(dsl).filter(PLAIN,
                request(and(condition("code", FilterOperator.LIKE, "p")), null, page(1, 10)))));
        cases.add(new Case("filter/internal/plain.code", Caller.NONE, dsl -> new InternalFilterQuery(dsl).filter(PLAIN,
                request(and(condition("code", FilterOperator.LIKE, "p")), null, page(1, 10)))));
        // streams
        for (String kind : List.of("default", "internal")) {
            cases.add(new Case("stream/" + kind + "/stream", Caller.BU, dsl ->
                    query(kind, dsl).filterStream(ORDER, streamRequest(), 10).toList()));
            cases.add(new Case("stream/" + kind + "/consumer", Caller.BU, dsl -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                query(kind, dsl).filterStream(ORDER, streamRequest(), 10, rows::add);
                return rows;
            }));
            cases.add(new Case("stream/" + kind + "/typeReference", Caller.BU, dsl -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                query(kind, dsl).filterStream(ORDER, streamRequest(), 10, new TypeReference<Map<String, Object>>() {
                }, rows::add);
                return rows;
            }));
            cases.add(new Case("stream/" + kind + "/class", Caller.BU, dsl -> {
                List<OrderRow> rows = new ArrayList<>();
                query(kind, dsl).filterStream(ORDER, streamRequest(), 10, OrderRow.class, rows::add);
                return rows;
            }));
        }
        // find methods
        UUID o1 = id(1);
        Condition codeLike = DSL.field(DSL.name("code")).like("O%");
        for (String kind : List.of("default", "internal")) {
            cases.add(new Case("find/" + kind + "/byId", Caller.BU, dsl -> query(kind, dsl).findById(ORDER, o1)));
            cases.add(new Case("find/" + kind + "/byIdFields", Caller.BU, dsl -> query(kind, dsl).findById(ORDER, o1, List.of("id", "code"))));
            cases.add(new Case("find/" + kind + "/byIdDeleted", Caller.BU, dsl -> query(kind, dsl).findById(ORDER, id(3))));
            cases.add(new Case("find/" + kind + "/byIds", Caller.BU, dsl -> query(kind, dsl).findByIds(ORDER, List.of(o1, id(2), id(3)))));
            cases.add(new Case("find/" + kind + "/byCondition", Caller.BU, dsl -> query(kind, dsl).findByCondition(ORDER, codeLike)));
            cases.add(new Case("find/" + kind + "/byConditionFields", Caller.BU, dsl -> query(kind, dsl).findByCondition(ORDER, codeLike, List.of("code"))));
            cases.add(new Case("find/" + kind + "/byConditionLimit", Caller.BU, dsl -> query(kind, dsl).findByCondition(ORDER, codeLike, 2,
                    List.of(Sort.of("code", SortDirection.DESC)), List.of("code"))));
            cases.add(new Case("find/" + kind + "/oneByCondition", Caller.BU, dsl -> query(kind, dsl).findOneByCondition(ORDER, codeLike)));
        }
        return cases;
    }

    /** Requests on {@code frorder}, each built fresh (the queries change the request). */
    private static Map<String, Supplier<ObjectFilterRequest>> requests() {
        Map<String, Supplier<ObjectFilterRequest>> r = new LinkedHashMap<>();
        PageRequest page = page(1, 20);
        r.put("empty", () -> new ObjectFilterRequest());
        r.put("empty.page", () -> request(null, null, page));
        r.put("emptyGroup", () -> request(and(), null, page));
        r.put("nullConditions", () -> request(new FilterGroup(LogicalOperator.AND, null), null, page));
        r.put("nullOperator", () -> request(new FilterGroup(null, list(condition("code", FilterOperator.EQUALS, "O1"))), null, page));
        // returned fields
        r.put("fields.explicit", () -> request(null, List.of("id", "code", "amount", "createdAt", "tags"), page));
        r.put("fields.json", () -> request(null, List.of("id", "customValues.customerIdReference.name", "customerIdReference.code", "customValues.score"), page));
        r.put("fields.unknown", () -> request(null, List.of("id", "nope"), page));
        r.put("fields.nestedOnColumn", () -> request(null, List.of("code.x"), page));
        // EQUALS / NOT_EQUALS
        r.put("eq.string", () -> request(and(condition("code", FilterOperator.EQUALS, "O1")), null, page));
        r.put("eq.uuid", () -> request(and(condition("customerId", FilterOperator.EQUALS, CUSTOMER.toString())), null, page));
        r.put("eq.decimal", () -> request(and(condition("amount", FilterOperator.EQUALS, "10.5")), null, page));
        r.put("eq.long", () -> request(and(condition("qty", FilterOperator.EQUALS, 3)), null, page));
        r.put("eq.boolean", () -> request(and(condition("active", FilterOperator.EQUALS, true)), null, page));
        r.put("eq.null", () -> request(and(condition("name", FilterOperator.EQUALS, null)), null, page));
        r.put("eq.instant", () -> request(and(condition("createdAt", FilterOperator.EQUALS, "2026-01-02T00:00:00Z")), null, page));
        r.put("eq.jsonPath", () -> request(and(condition("customValues.customerIdReference.code", FilterOperator.EQUALS, "C1")), null, page));
        r.put("eq.referencePath", () -> request(and(condition("customerIdReference.code", FilterOperator.EQUALS, "C1")), null, page));
        r.put("eq.jsonNumber", () -> request(and(condition("customValues.score", FilterOperator.EQUALS, 7)), null, page));
        r.put("eq.jsonNull", () -> request(and(condition("customValues.score", FilterOperator.EQUALS, null)), null, page));
        r.put("eq.nestedOnColumn", () -> request(and(condition("code.anything", FilterOperator.EQUALS, "O1")), null, page));
        r.put("eq.badCast", () -> request(and(condition("qty", FilterOperator.EQUALS, "abc")), null, page));
        r.put("ne.string", () -> request(and(condition("code", FilterOperator.NOT_EQUALS, "O1")), null, page));
        r.put("ne.null", () -> request(and(condition("name", FilterOperator.NOT_EQUALS, null)), null, page));
        r.put("ne.jsonPath", () -> request(and(condition("customerIdReference.code", FilterOperator.NOT_EQUALS, "C1")), null, page));
        // LIKE
        r.put("like.string", () -> request(and(condition("name", FilterOperator.LIKE, "Đơn")), null, page));
        r.put("like.jsonPath", () -> request(and(condition("customerIdReference.name", FilterOperator.LIKE, "khach")), null, page));
        r.put("like.number", () -> request(and(condition("amount", FilterOperator.LIKE, "1")), null, page));
        r.put("like.null", () -> request(and(condition("name", FilterOperator.LIKE, null)), null, page));
        r.put("like.percent", () -> request(and(condition("code", FilterOperator.LIKE, "O%")), null, page));
        // comparisons
        r.put("gt.decimal", () -> request(and(condition("amount", FilterOperator.GREATER_THAN, 10)), null, page));
        r.put("gte.long", () -> request(and(condition("qty", FilterOperator.GREATER_THAN_OR_EQUAL, 2)), null, page));
        r.put("lt.instant", () -> request(and(condition("createdAt", FilterOperator.LESS_THAN, "2026-01-03T00:00:00Z")), null, page));
        r.put("lte.jsonNumber", () -> request(and(condition("customValues.score", FilterOperator.LESS_THAN_OR_EQUAL, 7)), null, page));
        r.put("gt.jsonString", () -> request(and(condition("customerIdReference.code", FilterOperator.GREATER_THAN, "C1")), null, page));
        r.put("gt.string", () -> request(and(condition("code", FilterOperator.GREATER_THAN, "O2")), null, page));
        // IN / NOT_IN
        r.put("in.string", () -> request(and(condition("code", FilterOperator.IN, List.of("O1", "O2"))), null, page));
        r.put("in.uuid", () -> request(and(condition("id", FilterOperator.IN, List.of(id(1).toString(), id(2).toString()))), null, page));
        r.put("in.long", () -> request(and(condition("qty", FilterOperator.IN, List.of(1, 3))), null, page));
        r.put("in.jsonPath", () -> request(and(condition("customerIdReference.code", FilterOperator.IN, List.of("C1", "C2"))), null, page));
        r.put("in.empty", () -> request(and(condition("code", FilterOperator.IN, List.of())), null, page));
        r.put("in.notAList", () -> request(and(condition("code", FilterOperator.IN, "O1")), null, page));
        r.put("notIn.string", () -> request(and(condition("code", FilterOperator.NOT_IN, List.of("O1"))), null, page));
        r.put("notIn.jsonPath", () -> request(and(condition("customerIdReference.code", FilterOperator.NOT_IN, List.of("C1"))), null, page));
        // BETWEEN
        r.put("between.decimal", () -> request(and(condition("amount", FilterOperator.BETWEEN, List.of(5, 20))), null, page));
        r.put("between.instant", () -> request(and(condition("createdAt", FilterOperator.BETWEEN,
                List.of("2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z"))), null, page));
        r.put("between.string", () -> request(and(condition("code", FilterOperator.BETWEEN, List.of("O1", "O3"))), null, page));
        r.put("between.jsonNumber", () -> request(and(condition("customValues.score", FilterOperator.BETWEEN, List.of(1, 8))), null, page));
        r.put("between.oneValue", () -> request(and(condition("amount", FilterOperator.BETWEEN, List.of(5))), null, page));
        // CONTAINS
        r.put("contains.string", () -> request(and(condition("name", FilterOperator.CONTAINS, "khung")), null, page));
        r.put("contains.array", () -> request(and(condition("tags", FilterOperator.CONTAINS, List.of("a"))), null, page));
        r.put("contains.jsonPathList", () -> request(and(condition("customerIdReference.code", FilterOperator.CONTAINS, List.of("C1"))), null, page));
        r.put("containsOneOf.array", () -> request(and(condition("tags", FilterOperator.CONTAINS_ONE_OF, List.of("a", "z"))), null, page));
        r.put("notContains", () -> request(and(condition("tags", FilterOperator.NOT_CONTAINS, List.of("a"))), null, page));
        r.put("notContainsOneOf", () -> request(and(condition("tags", FilterOperator.NOT_CONTAINS_ONE_OF, List.of("a"))), null, page));
        // groups
        r.put("group.orOfAnd", () -> request(or(
                and(condition("code", FilterOperator.EQUALS, "O1"), condition("active", FilterOperator.EQUALS, true)),
                and(condition("qty", FilterOperator.GREATER_THAN, 2))), null, page));
        r.put("group.deep", () -> request(and(condition("active", FilterOperator.EQUALS, true),
                or(condition("code", FilterOperator.EQUALS, "O1"), and(condition("qty", FilterOperator.IN, List.of(2, 3)),
                        or(condition("name", FilterOperator.LIKE, "hai"))))), null, page));
        r.put("group.emptyNested", () -> request(and(condition("code", FilterOperator.LIKE, "O"), or()), null, page));
        // field paths
        r.put("path.unknown", () -> request(and(condition("nope", FilterOperator.EQUALS, "x")), null, page));
        r.put("path.injection", () -> request(and(condition("code\"; drop table fr_order; --", FilterOperator.EQUALS, "x")), null, page));
        r.put("path.tooDeep", () -> request(and(condition("customValues.a.b.c.d.e", FilterOperator.EQUALS, "x")), null, page));
        r.put("path.undeclaredReference", () -> request(and(condition("ownerIdReference.name", FilterOperator.EQUALS, "x")), null, page));
        // search
        r.put("search.code_name", () -> search("o1", List.of("code", "name"), null, page));
        r.put("search.accent", () -> search("don khung", List.of("name"), null, page));
        r.put("search.jsonPath", () -> search("khach", List.of("customerIdReference.name"), null, page));
        r.put("search.blank", () -> search(" ", List.of("code"), null, page));
        r.put("search.noFields", () -> search("o1", List.of(), null, page));
        r.put("search.withFilters", () -> search("o", List.of("code"), and(condition("active", FilterOperator.EQUALS, true)), page));
        // soft delete
        r.put("deleted.eq_false", () -> request(and(condition("isDeleted", FilterOperator.EQUALS, false)), null, page));
        r.put("deleted.eq_true", () -> request(and(condition("isDeleted", FilterOperator.EQUALS, true)), null, page));
        r.put("deleted.ne_true", () -> request(and(condition("isDeleted", FilterOperator.NOT_EQUALS, true)), null, page));
        r.put("deleted.nested", () -> request(and(condition("code", FilterOperator.LIKE, "O"),
                or(condition("isDeleted", FilterOperator.EQUALS, true), condition("active", FilterOperator.EQUALS, false))), null, page));
        r.put("deleted.withSearch", () -> search("o", List.of("code"), and(condition("isDeleted", FilterOperator.EQUALS, true)), page));
        // sorts and paging
        r.put("sort.code_asc", () -> request(null, List.of("id", "code"), page(1, 20, Sort.of("code", SortDirection.ASC))));
        r.put("sort.amount_desc_code_asc", () -> request(null, List.of("code", "amount"),
                page(1, 20, Sort.of("amount", SortDirection.DESC), Sort.of("code", SortDirection.ASC))));
        r.put("sort.jsonPath", () -> request(null, List.of("code"), page(1, 20, Sort.of("customValues.score", SortDirection.DESC))));
        r.put("sort.referencePath", () -> request(null, List.of("code"), page(1, 20, Sort.of("customerIdReference.code", SortDirection.ASC))));
        r.put("sort.unknown", () -> request(null, List.of("code"), page(1, 20, Sort.of("nope", SortDirection.ASC))));
        r.put("page.second", () -> request(null, List.of("code"), page(2, 2, Sort.of("code", SortDirection.ASC))));
        r.put("page.beyond", () -> request(null, List.of("code"), page(9, 2, Sort.of("code", SortDirection.ASC))));
        r.put("noPage.sortIgnored", () -> {
            ObjectFilterRequest request = request(null, List.of("code"), null);
            return request;
        });
        return r;
    }

    // ================================================================== run and compare

    @Test
    void recordsAndComparesTheFilterBehaviour() throws Exception {
        List<String> sqlOutput = new ArrayList<>();
        List<String> rowsOutput = new ArrayList<>();
        for (Case c : cases()) {
            RequestContextHolder.setRequestContextProvider(() -> context(c.caller()));
            sqlOutput.add("### " + c.name());
            sqlOutput.addAll(recordSql(c));
            if (connection != null) {
                rowsOutput.add("### " + c.name());
                rowsOutput.add(runOnDatabase(c));
            }
        }
        Path dir = Path.of("build", "filter-regression");
        Files.createDirectories(dir);
        Files.write(dir.resolve(LABEL + "-sql.txt"), sqlOutput, StandardCharsets.UTF_8);
        if (connection != null) {
            Files.write(dir.resolve(LABEL + "-rows.txt"), rowsOutput, StandardCharsets.UTF_8);
        }
        List<String> goldenSql = golden("filter-regression/golden-sql.txt");
        if (goldenSql != null) {
            assertThat(sqlOutput).containsExactlyElementsOf(goldenSql);
        }
        List<String> goldenRows = golden("filter-regression/golden-rows.txt");
        if (goldenRows != null && connection != null) {
            assertThat(rowsOutput).containsExactlyElementsOf(goldenRows);
        }
    }

    /** SQL and binds of every statement the case runs, or the error it throws. */
    private static List<String> recordSql(Case c) {
        List<String> statements = new ArrayList<>();
        DSLContext renderer = DSL.using(SQLDialect.POSTGRES);
        DSLContext mock = DSL.using(new MockConnection(context -> {
            statements.add(context.sql() + "  <<  " + Arrays.deepToString(context.bindings()));
            return new MockResult[]{new MockResult(0, renderer.newResult())};
        }), SQLDialect.POSTGRES);
        try {
            c.run().apply(mock);
        } catch (RuntimeException e) {
            statements.add("ERROR " + e.getClass().getName() + ": " + e.getMessage());
        }
        return statements;
    }

    private static String runOnDatabase(Case c) {
        try {
            return JSON.writeValueAsString(c.run().apply(DSL.using(connection, SQLDialect.POSTGRES)));
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            int newline = message.indexOf('\n');
            return "ERROR " + e.getClass().getName() + ": " + (newline < 0 ? message : message.substring(0, newline));
        }
    }

    private static List<String> golden(String resource) throws Exception {
        try (InputStream in = FilterRegressionTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    // ================================================================== database

    private static void seed(DSLContext dsl) {
        dsl.execute("create extension if not exists unaccent");
        dsl.execute("drop table if exists fr_order_item, fr_order, fr_plain");
        dsl.execute("""
                create table fr_order (id uuid primary key, code text, name text, amount numeric, qty bigint,
                  active boolean, created_at timestamptz, tags text[], customer_id uuid, owner_id uuid, team_id uuid,
                  org_id uuid, is_deleted boolean, version bigint, custom_values jsonb)""");
        dsl.execute("create table fr_order_item (id uuid primary key, order_id uuid, code text, is_deleted boolean)");
        dsl.execute("create table fr_plain (id uuid primary key, code text)");
        order(dsl, 1, "O1", "Đơn khung Một", "10.5", 1, true, "2026-01-01T00:00:00Z", "{a,b}", CUSTOMER, OWNER_1, TEAM_1, ORG_A, false,
                "{\"customerIdReference\": {\"code\": \"C1\", \"name\": \"Khách Một\"}, \"score\": 7}");
        order(dsl, 2, "O2", "Đơn khung Hai", "20", 2, false, "2026-01-02T00:00:00Z", "{b}", null, OWNER_2, TEAM_1, ORG_A, null,
                "{\"customerIdReference\": {\"code\": \"C2\", \"name\": \"Khách Hai\"}, \"score\": 3}");
        order(dsl, 3, "O3", "Đơn khung Ba", "30", 3, true, "2026-01-03T00:00:00Z", "{a}", CUSTOMER, OWNER_1, TEAM_2, ORG_A, true,
                "{\"score\": 9}");
        order(dsl, 4, "O4", null, "5", 4, null, "2026-01-04T00:00:00Z", "{}", null, OWNER_1, TEAM_2, ORG_B, false, "{}");
        order(dsl, 5, "O5", "Đơn lẻ", null, null, true, null, null, CUSTOMER, null, null, ORG_B, false, null);
        order(dsl, 6, "P6", "Đơn khung Sáu", "12", 3, true, "2026-01-05T00:00:00Z", "{z}", null, OWNER_2, TEAM_2, ORG_A, false,
                "{\"customerIdReference\": {\"code\": \"C1\", \"name\": \"Khách Một\"}, \"score\": 1}");
        dsl.execute("insert into fr_order_item values (?, ?, 'I1', false), (?, ?, 'I1', false), (?, ?, 'I2', true)",
                id(101), id(1), id(102), id(4), id(103), id(1));
        dsl.execute("insert into fr_plain values (?, 'p1'), (?, 'q2')", id(201), id(202));
    }

    private static void order(DSLContext dsl, int n, String code, String name, String amount, Integer qty, Boolean active,
                              String createdAt, String tags, UUID customer, UUID owner, UUID team, UUID org, Boolean deleted,
                              String customValues) {
        dsl.execute("""
                        insert into fr_order values (?, ?, ?, cast(? as numeric), ?, ?, cast(? as timestamptz), cast(? as text[]),
                          ?, ?, ?, ?, ?, 1, cast(? as jsonb))""",
                id(n), code, name, amount, qty, active, createdAt, tags, customer, owner, team, org, deleted, customValues);
    }

    // ================================================================== helpers

    /** A caller of the secured queries. */
    private enum Caller {
        SUPER_ADMIN, ORG, BU, OWNER, TEAM, PARENT_CHILD, BU_OR_OWNER, NONE
    }

    private static RequestContext context(Caller caller) {
        Set<AccessScope> scopes = switch (caller) {
            case ORG -> Set.of(AccessScope.ORGANIZATION);
            case BU -> Set.of(AccessScope.BUSINESS_UNIT);
            case OWNER -> Set.of(AccessScope.OWNER);
            case TEAM -> Set.of(AccessScope.TEAM);
            case PARENT_CHILD -> Set.of(AccessScope.PARENT_CHILD);
            // ordered: the data scope ORs the scopes in this order (Set.of would change it from run to run)
            case BU_OR_OWNER -> new LinkedHashSet<>(List.of(AccessScope.BUSINESS_UNIT, AccessScope.OWNER));
            case SUPER_ADMIN, NONE -> Set.of();
        };
        AuthorizationContext authorization = new AuthorizationContext() {
            @Override
            public Set<AccessScope> getScope(String objectName, IdentifiableCode action) {
                return scopes;
            }

            @Override
            public boolean hasPermission(String objectName, IdentifiableCode action) {
                return !scopes.isEmpty();
            }
        };
        DataScopeContext dataScope = new DefaultDataScopeContext(Set.of(TEAM_1), Set.of(ORG_A), Set.of(ORG_B), Set.of(), OWNER_1);
        return new RequestContext() {
            @Override
            public String getTenantCode() {
                return "test";
            }

            @Override
            public UUID getUserId() {
                return OWNER_1;
            }

            @Override
            public String getUsername() {
                return "tester";
            }

            @Override
            public Team getTeam() {
                return null;
            }

            @Override
            public boolean isSupperAdmin() {
                return caller == Caller.SUPER_ADMIN;
            }

            @Override
            public AuthorizationContext getAuthorization() {
                return authorization;
            }

            @Override
            public DataScopeContext getDataScopeContext() {
                return dataScope;
            }
        };
    }

    /** Fields of a row read by the class stream. */
    public static class OrderRow {
        public UUID id;
        public String code;
    }

    private static FilterQuery query(String kind, DSLContext dsl) {
        return "default".equals(kind) ? secured(dsl) : new InternalFilterQuery(dsl);
    }

    private static DefaultFilterQuery secured(DSLContext dsl) {
        return new DefaultFilterQuery(dsl, new SecurityConditionProvider(new DataScopeConditionResolver()));
    }

    private static ObjectFilterRequest streamRequest() {
        return request(and(condition("code", FilterOperator.LIKE, "o")), List.of("id", "code"),
                page(1, 2, Sort.of("code", SortDirection.ASC)));
    }

    private static ObjectFilterRequest request(FilterGroup filters, List<String> returnFields, PageRequest page) {
        return ObjectFilterRequest.builder()
                .filters(filters)
                .returnFields(returnFields == null ? null : new ArrayList<>(returnFields))
                .pageRequest(page)
                .build();
    }

    private static ObjectFilterRequest search(String keyword, List<String> fields, FilterGroup filters, PageRequest page) {
        ObjectFilterRequest request = request(filters, null, page);
        request.setSearch(new SearchRequest(keyword, new ArrayList<>(fields)));
        return request;
    }

    private static PageRequest page(int page, int size, Sort... sorts) {
        return PageRequest.of(page, size, sorts.length == 0 ? null : List.of(sorts));
    }

    private static FilterGroup and(FilterObject... conditions) {
        return new FilterGroup(LogicalOperator.AND, list(conditions));
    }

    private static FilterGroup or(FilterObject... conditions) {
        return new FilterGroup(LogicalOperator.OR, list(conditions));
    }

    private static List<FilterObject> list(FilterObject... conditions) {
        return new ArrayList<>(Arrays.asList(conditions));
    }

    private static FilterCondition condition(String field, FilterOperator operator, Object value) {
        return FilterCondition.create(field, operator, value);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    private static Attribute attribute(String fieldName, String columnName, String fieldType) {
        return Attribute.builder().fieldName(fieldName).columnName(columnName).fieldType(fieldType).build();
    }

    private static Attribute reference(String fieldName, String columnName, String referenceName, String objectRef) {
        return Attribute.builder().fieldName(fieldName).columnName(columnName).fieldType("UUID")
                .attributeRef(AttributeRef.of(referenceName, objectRef)).build();
    }
}
