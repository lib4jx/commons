package com.msm.core.dynamicquery.relation;

import com.msm.core.dynamicquery.internal.InternalFilterQuery;
import com.msm.core.dynamicquery.query.DefaultFilterQuery;
import com.msm.core.exceptions.RelationAccessDeniedException;
import com.msm.core.filter.domain.FilterOperator;
import com.msm.core.filter.domain.FilterRelation;
import com.msm.core.filter.domain.ObjectFilterRequest;
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
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.msm.core.dynamicquery.relation.RelationFixtures.LINE;
import static com.msm.core.dynamicquery.relation.RelationFixtures.ORG_A;
import static com.msm.core.dynamicquery.relation.RelationFixtures.and;
import static com.msm.core.dynamicquery.relation.RelationFixtures.condition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The filter queries end to end, on a mock connection that records the SQL they run. */
class RelationFilterQueryTest {

    private final List<String> executed = new ArrayList<>();
    private DSLContext dsl;

    @BeforeAll
    static void register() {
        RelationFixtures.register();
    }

    @BeforeEach
    void connect() {
        DSLContext renderer = DSL.using(SQLDialect.POSTGRES);
        dsl = DSL.using(new MockConnection(context -> {
            executed.add(context.sql());
            return new MockResult[]{new MockResult(0, renderer.newResult())};
        }), SQLDialect.POSTGRES);
    }

    @AfterEach
    void reset() {
        RelationFixtures.resetCaller();
    }

    private static ObjectFilterRequest request() {
        return ObjectFilterRequest.builder()
                .returnFields(new ArrayList<>(List.of("id", "productCode", "@order.code", "@order.quoteIdReference.code")))
                .search(new SearchRequest("bo", List.of("productCode", "@order.code")))
                .filters(and(
                        condition("isDeleted", FilterOperator.EQUALS, true),
                        FilterRelation.any("order", and(condition("name", FilterOperator.LIKE, "x")))))
                .pageRequest(PageRequest.builder().page(1).size(20)
                        .sorts(List.of(Sort.of("@order.code", SortDirection.ASC))).build())
                .build();
    }

    @Test
    void securedFilterAppliesTheRelationsToThePageAndToTheCount() {
        RelationFixtures.callerWithScopes(Map.of(
                "rtline", Set.of(AccessScope.BUSINESS_UNIT),
                "rtorder", Set.of(AccessScope.BUSINESS_UNIT)), Set.of(ORG_A));
        DefaultFilterQuery query = new DefaultFilterQuery(dsl,
                new SecurityConditionProvider(new DataScopeConditionResolver()));

        query.filter(LINE, request());

        assertThat(executed).hasSize(2);
        String page = executed.get(0);
        String count = executed.get(1);
        // returned and sorted related fields are scalar subqueries; the search and the filter are EXISTS
        assertThat(page).startsWith("select \"id\" as \"id\", \"product_code\" as \"productCode\", (select \"rel_")
                .contains("as \"@order.code\"")
                .contains("as \"@order.quoteIdReference.code\"")
                .contains("from \"rt_line\" where")
                .contains("exists (select 1 as \"one\" from \"rt_order\" as \"rel_")
                .contains("order by (select \"rel_")
                .contains("offset ? rows fetch next ? rows only");
        // the soft delete of the line is enforced although the request asked for deleted rows
        assertThat(page).contains("(\"is_deleted\" = ? or \"is_deleted\" is null)");
        // each of the 5 relation subqueries of the page (2 returned fields, search, filter, sort) carries the order's
        // data scope, plus the line's own scope
        assertThat(occurrences(page, "\"org_id\" in (cast(? as uuid))")).isEqualTo(6);
        // the count has the same filters (search and filter subqueries, plus the line's scope), without the returned
        // fields and the sort
        assertThat(count).startsWith("select count(*) from \"rt_line\" where")
                .contains("exists (select 1 as \"one\" from \"rt_order\" as \"rel_")
                .doesNotContain("order by");
        assertThat(occurrences(count, "\"org_id\" in (cast(? as uuid))")).isEqualTo(3);
    }

    @Test
    void securedFilterRefusesARelatedObjectTheCallerMayNotView() {
        RelationFixtures.callerWithScopes(Map.of("rtline", Set.of(AccessScope.ORGANIZATION)), Set.of(ORG_A));
        DefaultFilterQuery query = new DefaultFilterQuery(dsl,
                new SecurityConditionProvider(new DataScopeConditionResolver()));

        assertThatThrownBy(() -> query.filter(LINE, request())).isInstanceOf(RelationAccessDeniedException.class);
        assertThat(executed).isEmpty();
    }

    @Test
    void internalFilterUsesRelationsWithoutScopeAndKeepsTheRequestedSoftDelete() {
        new InternalFilterQuery(dsl).filter(LINE, request());

        String page = executed.getFirst();
        assertThat(page).contains("exists (select 1 as \"one\" from \"rt_order\" as \"rel_")
                .doesNotContain("\"org_id\" in");
        // internal queries keep letting the request choose the deleted rows
        assertThat(page).contains("and \"is_deleted\" = ? and exists (")
                .doesNotContain("(\"is_deleted\" = ? or \"is_deleted\" is null)");
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }
}
