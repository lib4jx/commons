package com.msm.core.dynamicquery.relation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msm.core.dynamicquery.internal.InternalFilterQuery;
import com.msm.core.dynamicquery.query.DefaultFilterQuery;
import com.msm.core.filter.domain.FilterOperator;
import com.msm.core.filter.domain.FilterRelation;
import com.msm.core.filter.domain.ObjectFilterRequest;
import com.msm.core.filter.domain.PageResponse;
import com.msm.core.filter.domain.SearchRequest;
import com.msm.core.filter.domain.pageable.PageRequest;
import com.msm.core.filter.domain.pageable.Sort;
import com.msm.core.filter.domain.pageable.SortDirection;
import com.msm.core.security.DataScopeConditionResolver;
import com.msm.core.security.SecurityConditionProvider;
import com.msm.core.security.enums.AccessScope;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.msm.core.dynamicquery.relation.RelationFixtures.LINE;
import static com.msm.core.dynamicquery.relation.RelationFixtures.ORDER;
import static com.msm.core.dynamicquery.relation.RelationFixtures.ORG_A;
import static com.msm.core.dynamicquery.relation.RelationFixtures.ORG_B;
import static com.msm.core.dynamicquery.relation.RelationFixtures.and;
import static com.msm.core.dynamicquery.relation.RelationFixtures.condition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Relations against a real PostgreSQL (the SQL must run, the aliases and the unqualified data scope must resolve to
 * the right table). Skipped unless {@code MSM_TEST_DB_URL} is set. Tables are recreated before each test.
 *
 * <pre>
 * orders: BO.01 (org A, quote BG.01), BO.02 (org B), BO.03 (org A, deleted), BO.04 (org A, no line)
 * lines (org A): L1 → BO.01 P1, L2 → BO.01 P2, L3 → BO.02 P3, L4 → BO.03 P4, L5 → no order P5
 * caller: business unit A on lines and orders
 * </pre>
 */
class RelationPostgresIT {

    private static final String DB_URL = System.getenv("MSM_TEST_DB_URL");

    private static Connection connection;
    private static DSLContext dsl;

    private static final UUID O1 = UUID.randomUUID();
    private static final UUID O2 = UUID.randomUUID();
    private static final UUID O3 = UUID.randomUUID();
    private static final UUID O4 = UUID.randomUUID();

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue(DB_URL != null, "MSM_TEST_DB_URL not set");
        connection = DriverManager.getConnection(DB_URL);
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        RelationFixtures.register();
    }

    @AfterAll
    static void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void data() {
        dsl.execute("create extension if not exists unaccent");
        dsl.execute("drop table if exists rt_line, rt_order, rt_account");
        dsl.execute("create table rt_account (id uuid primary key, code text, name text, org_id uuid, is_deleted boolean)");
        dsl.execute("""
                create table rt_order (id uuid primary key, code text, name text, quote_id uuid, customer_id uuid,
                                       secret text, org_id uuid, is_deleted boolean, custom_values jsonb)""");
        dsl.execute("""
                create table rt_line (id uuid primary key, order_id uuid, product_code text, customer_code text,
                                      org_id uuid, is_deleted boolean)""");
        order(O1, "BO.01", "Đơn khung Một", ORG_A, false, "{\"quoteIdReference\": {\"code\": \"BG.01\"}}");
        order(O2, "BO.02", "Đơn khung Hai", ORG_B, false, "{}");
        order(O3, "BO.03", "Đơn khung Ba", ORG_A, true, "{}");
        order(O4, "BO.04", "Đơn khung Bốn", ORG_A, null, "{}");
        line("P1", O1);
        line("P2", O1);
        line("P3", O2);
        line("P4", O3);
        line("P5", null);
        RelationFixtures.callerWithScopes(Map.of(
                "rtline", Set.of(AccessScope.BUSINESS_UNIT),
                "rtorder", Set.of(AccessScope.BUSINESS_UNIT)), Set.of(ORG_A));
    }

    @AfterEach
    void reset() {
        RelationFixtures.resetCaller();
    }

    @Test
    void relatedFieldsAreReadForEachLineAndSortedWithoutMultiplyingTheLines() {
        PageResponse<Map<String, Object>> page = secured().filter(LINE, request(
                List.of("productCode", "@order.code", "@order.quoteIdReference.code"),
                Sort.of("@order.code", SortDirection.ASC)));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContents()).extracting(row -> row.get("productCode")).startsWith("P1", "P2");
        assertThat(page.getContents().getFirst()).containsEntry("@order.code", "BO.01")
                .containsEntry("@order.quoteIdReference.code", "BG.01");
        // BO.02 is out of the caller's business unit, BO.03 is deleted, P5 has no order: all read as null
        assertThat(page.getContents()).filteredOn(row -> List.of("P3", "P4", "P5").contains(row.get("productCode")))
                .allSatisfy(row -> assertThat(row.get("@order.code")).isNull());
    }

    @Test
    void fullReferenceNameReadsTheSameRowsAsTheShortAlias() {
        ObjectFilterRequest request = request(
                List.of("productCode", "@orderIdReference.code", "@order.code", "@orderIdReference.quoteIdReference.code"),
                Sort.of("@orderIdReference.code", SortDirection.ASC));
        request.setFilters(and(condition("@orderIdReference.code", FilterOperator.LIKE, "bo")));

        PageResponse<Map<String, Object>> page = secured().filter(LINE, request);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContents()).extracting(row -> row.get("productCode")).containsExactlyInAnyOrder("P1", "P2");
        assertThat(page.getContents()).allSatisfy(row -> assertThat(row)
                .containsEntry("@orderIdReference.code", "BO.01")
                .containsEntry("@order.code", "BO.01")
                .containsEntry("@orderIdReference.quoteIdReference.code", "BG.01"));
    }

    @Test
    void relationFilterKeepsTheLinesOfVisibleOrdersOnly() {
        ObjectFilterRequest request = request(List.of("productCode"), null);
        request.setFilters(and(FilterRelation.any("order", and(condition("code", FilterOperator.LIKE, "bo")))));

        PageResponse<Map<String, Object>> page = secured().filter(LINE, request);

        assertThat(page.getContents()).extracting(row -> row.get("productCode")).containsExactlyInAnyOrder("P1", "P2");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void searchReachesTheRelatedFieldsWithoutAccents() {
        ObjectFilterRequest request = request(List.of("productCode"), null);
        request.setSearch(new SearchRequest("don khung mot", List.of("productCode", "@order.name")));

        PageResponse<Map<String, Object>> page = secured().filter(LINE, request);

        assertThat(page.getContents()).extracting(row -> row.get("productCode")).containsExactlyInAnyOrder("P1", "P2");
    }

    @Test
    void oneToManyFiltersTheOrdersByTheirLines() {
        ObjectFilterRequest withP2 = request(List.of("code"), null);
        withP2.setFilters(and(FilterRelation.any("lines", and(condition("productCode", FilterOperator.EQUALS, "P2")))));
        ObjectFilterRequest withoutLine = request(List.of("code"), null);
        withoutLine.setFilters(and(FilterRelation.none("lines", null)));

        assertThat(secured().filter(ORDER, withP2).getContents()).extracting(row -> row.get("code")).containsExactly("BO.01");
        assertThat(secured().filter(ORDER, withoutLine).getContents()).extracting(row -> row.get("code")).containsExactly("BO.04");
    }

    @Test
    void superAdminAndInternalQueriesSeeEveryNotDeletedOrder() {
        ObjectFilterRequest request = request(List.of("productCode", "@order.code"), Sort.of("productCode", SortDirection.ASC));
        RelationFixtures.superAdmin();

        List<Map<String, Object>> asAdmin = secured().filter(LINE, request).getContents();
        List<Map<String, Object>> internal = new InternalFilterQuery(dsl).filter(LINE,
                request(List.of("productCode", "@order.code"), Sort.of("productCode", SortDirection.ASC))).getContents();

        assertThat(asAdmin).extracting(row -> row.get("@order.code")).containsExactly("BO.01", "BO.01", "BO.02", null, null);
        assertThat(internal).extracting(row -> row.get("@order.code")).containsExactly("BO.01", "BO.01", "BO.02", null, null);
    }

    @Test
    void securedQueryNeverReturnsDeletedRowsEvenWhenAsked() {
        dsl.execute("update rt_line set is_deleted = true where product_code = 'P5'");
        ObjectFilterRequest request = request(List.of("productCode"), null);
        request.setFilters(and(condition("isDeleted", FilterOperator.EQUALS, true)));

        assertThat(secured().filter(LINE, request).getTotalElements()).isZero();
    }

    @Test
    void frontEndPayloadWithAllFieldsRelatedColumnsSearchAndFilters() throws Exception {
        String payload = """
                {
                  "returnFields": ["*", "@order.code", "@order.name", "@order.quoteIdReference.code"],
                  "search": {"keyword": "khung", "fields": ["@order.code", "@order.name", "productCode", "customerCode"]},
                  "filters": {"operator": "AND", "conditions": [
                    {"field": "@order.code", "operator": "IN", "value": ["BO.01", "BO.02", "BO.03"]},
                    {"relation": "order", "filters": {"operator": "AND", "conditions": [
                      {"field": "quoteIdReference.code", "operator": "LIKE", "value": "bg"}]}}
                  ]},
                  "pageRequest": {"page": 1, "size": 20, "sorts": [
                    {"attribute": "@order.code", "direction": "DESC"}, {"attribute": "productCode", "direction": "ASC"}]}
                }""";
        ObjectFilterRequest request = new ObjectMapper().readValue(payload, ObjectFilterRequest.class);

        PageResponse<Map<String, Object>> page = secured().filter(LINE, request);

        // BO.02 is out of the business unit and BO.03 is deleted: only the lines of BO.01 (quote BG.01)
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContents()).extracting(row -> row.get("productCode")).containsExactly("P1", "P2");
        assertThat(page.getContents().getFirst())
                .containsKeys("id", "orderId", "productCode", "customerCode", "orgId", "isDeleted")
                .containsEntry("@order.code", "BO.01")
                .containsEntry("@order.name", "Đơn khung Một")
                .containsEntry("@order.quoteIdReference.code", "BG.01");
    }

    // ------------------------------------------------------------------ helpers

    private static DefaultFilterQuery secured() {
        return new DefaultFilterQuery(dsl, new SecurityConditionProvider(new DataScopeConditionResolver()));
    }

    private static ObjectFilterRequest request(List<String> returnFields, Sort sort) {
        return ObjectFilterRequest.builder()
                .returnFields(new ArrayList<>(returnFields))
                .pageRequest(PageRequest.of(1, 20, sort == null ? null : List.of(sort)))
                .build();
    }

    private static void order(UUID id, String code, String name, UUID org, Boolean deleted, String customValues) {
        dsl.execute("insert into rt_order (id, code, name, org_id, is_deleted, custom_values) values (?, ?, ?, ?, ?, ?::jsonb)",
                id, code, name, org, deleted, customValues);
    }

    private static void line(String productCode, UUID orderId) {
        dsl.execute("insert into rt_line (id, order_id, product_code, org_id, is_deleted) values (?, ?, ?, ?, false)",
                UUID.randomUUID(), orderId, productCode, ORG_A);
    }
}
