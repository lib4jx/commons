package com.msm.core.dynamicquery.relation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msm.core.dynamicquery.SelectBuilder;
import com.msm.core.dynamicquery.query.FilterBuilder;
import com.msm.core.dynamicquery.query.SearchOrDefaultFilter;
import com.msm.core.dynamicquery.query.SortingApplier;
import com.msm.core.exceptions.FieldValueInvalidException;
import com.msm.core.exceptions.RelationAccessDeniedException;
import com.msm.core.exceptions.UnsupportedException;
import com.msm.core.filter.domain.FilterCondition;
import com.msm.core.filter.domain.FilterGroup;
import com.msm.core.filter.domain.FilterOperator;
import com.msm.core.filter.domain.FilterRelation;
import com.msm.core.filter.domain.ObjectFilterRequest;
import com.msm.core.filter.domain.RelationMatch;
import com.msm.core.filter.domain.SearchRequest;
import com.msm.core.filter.domain.pageable.Sort;
import com.msm.core.filter.domain.pageable.SortDirection;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.security.DataScopeConditionResolver;
import com.msm.core.security.SecurityConditionProvider;
import com.msm.core.security.enums.AccessScope;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.QueryPart;
import org.jooq.SQLDialect;
import org.jooq.SelectField;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.msm.core.dynamicquery.relation.RelationFixtures.LINE;
import static com.msm.core.dynamicquery.relation.RelationFixtures.NOTE;
import static com.msm.core.dynamicquery.relation.RelationFixtures.ORDER;
import static com.msm.core.dynamicquery.relation.RelationFixtures.ORG_A;
import static com.msm.core.dynamicquery.relation.RelationFixtures.PROFILE;
import static com.msm.core.dynamicquery.relation.RelationFixtures.and;
import static com.msm.core.dynamicquery.relation.RelationFixtures.condition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL of relations: EXISTS filters, scalar subquery fields, search, limits and the caller's scope. */
class RelationQueryTest {

    private static final DSLContext DSL_CONTEXT = DSL.using(SQLDialect.POSTGRES);

    @BeforeAll
    static void register() {
        RelationFixtures.register();
    }

    @AfterEach
    void reset() {
        RelationSettings.setMaxRelations(3);
        RelationSettings.setMaxDepth(1);
        RelationFixtures.resetCaller();
    }

    private static String sql(QueryPart part) {
        return DSL_CONTEXT.renderInlined(part);
    }

    private static Condition filter(ObjectMetadata meta, FilterGroup filters) {
        return FilterBuilder.build(filters, meta, RelationQueryContext.unsecured());
    }

    // ------------------------------------------------------------------ resolving

    @Test
    void referenceIsManyToOneAndItsReverseNameIsOneToMany() {
        ResolvedRelation order = RelationResolver.resolve(LINE, "order");
        ResolvedRelation lines = RelationResolver.resolve(ORDER, "lines");

        assertThat(order.kind()).isEqualTo(RelationKind.MANY_TO_ONE);
        assertThat(order.target().getName()).isEqualTo("rtorder");
        assertThat(lines.kind()).isEqualTo(RelationKind.ONE_TO_MANY);
        assertThat(lines.target().getName()).isEqualTo("rtline");
        assertThat(sql(lines.link("rt_order", "rel_1"))).isEqualTo("\"rel_1\".\"order_id\" = \"rt_order\".\"id\"");
    }

    @Test
    void childReferencingTheParentOnceGivesAOneToManyNamedAfterTheChild() {
        ResolvedRelation payments = RelationResolver.resolve(ORDER, "rtpayment");

        assertThat(payments.kind()).isEqualTo(RelationKind.ONE_TO_MANY);
        assertThat(sql(payments.link("rt_order", "rel_1"))).isEqualTo("\"rel_1\".\"order_id\" = \"rt_order\".\"id\"");
    }

    @Test
    void childReferencingTheParentSeveralTimesGivesOneRelationPerKey() {
        assertThat(sql(RelationResolver.resolve(ORDER, "rtnote_orderId").link("rt_order", "rel_1")))
                .isEqualTo("\"rel_1\".\"order_id\" = \"rt_order\".\"id\"");
        assertThat(sql(RelationResolver.resolve(ORDER, "rtnote_sourceOrderId").link("rt_order", "rel_1")))
                .isEqualTo("\"rel_1\".\"source_order_id\" = \"rt_order\".\"id\"");
        assertThatThrownBy(() -> RelationResolver.resolve(ORDER, "rtnote"))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("Unknown relation 'rtnote'");
    }

    @Test
    void reverseNameReplacesTheGeneratedName() {
        assertThatThrownBy(() -> RelationResolver.resolve(ORDER, "rtline"))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("Unknown relation 'rtline'");
    }

    @Test
    void referenceToALocalObjectGivesAManyToOneRelationNamedAfterIt() {
        ResolvedRelation customer = RelationResolver.resolve(ORDER, "customer");

        assertThat(customer.kind()).isEqualTo(RelationKind.MANY_TO_ONE);
        assertThat(customer.target().getName()).isEqualTo("rtaccount");
        assertThat(sql(customer.link("rt_order", "rel_1"))).isEqualTo("\"rel_1\".\"id\" = \"rt_order\".\"customer_id\"");
    }

    @Test
    void referenceToAnObjectOfAnotherServiceIsNoRelation() {
        assertThatThrownBy(() -> RelationResolver.resolve(ORDER, "quote"))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("Unknown relation 'quote'");
    }

    @Test
    void referenceFieldIsTheManyToOneNameAndTheShortNameAnAlias() {
        ResolvedRelation full = RelationResolver.resolve(ORDER, "customerIdReference");
        ResolvedRelation alias = RelationResolver.resolve(ORDER, "customer");

        assertThat(full.kind()).isEqualTo(RelationKind.MANY_TO_ONE);
        assertThat(full.target().getName()).isEqualTo("rtaccount");
        assertThat(full.name()).isEqualTo("customerIdReference");
        assertThat(full.key()).isEqualTo(alias.key());
        assertThat(sql(full.link("rt_order", "rel_1"))).isEqualTo(sql(alias.link("rt_order", "rel_1")));
    }

    @Test
    void twoReferencesToOneObjectAreTwoRelationsNamedAfterTheirReferenceFields() {
        assertThat(sql(RelationResolver.resolve(NOTE, "orderIdReference").link("rt_note", "rel_1")))
                .isEqualTo("\"rel_1\".\"id\" = \"rt_note\".\"order_id\"");
        assertThat(sql(RelationResolver.resolve(NOTE, "sourceOrderIdReference").link("rt_note", "rel_1")))
                .isEqualTo("\"rel_1\".\"id\" = \"rt_note\".\"source_order_id\"");
    }

    @Test
    void referenceNamedWithoutIdHasOnlyItsFullName() {
        ResolvedRelation analyst = RelationResolver.resolve(PROFILE, "creditAnalystReference");

        assertThat(analyst.kind()).isEqualTo(RelationKind.MANY_TO_ONE);
        assertThat(sql(analyst.link("rt_profile", "rel_1"))).isEqualTo("\"rel_1\".\"id\" = \"rt_profile\".\"credit_analyst\"");
        assertThatThrownBy(() -> RelationResolver.resolve(PROFILE, "creditAnalyst"))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("Unknown relation 'creditAnalyst'");
    }

    @Test
    void fullReferenceNameToAnObjectOfAnotherServiceIsNoRelation() {
        assertThatThrownBy(() -> RelationResolver.resolve(ORDER, "quoteIdReference"))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("Unknown relation 'quoteIdReference'");
    }

    @Test
    void relationNameIsCaseSensitive() {
        assertThatThrownBy(() -> RelationResolver.resolve(ORDER, "customeridreference"))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("Unknown relation 'customeridreference'");
    }

    @Test
    void relationNameMustBeAPlainName() {
        assertThatThrownBy(() -> RelationResolver.resolve(LINE, "order\"; drop table x"))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("Invalid relation name");
    }

    // ------------------------------------------------------------------ filters

    @Test
    void relationFilterIsAnExistsOnTheAliasedRelatedTable() {
        String sql = sql(filter(LINE, and(FilterRelation.any("order", and(condition("code", FilterOperator.EQUALS, "BO1"))))));

        assertThat(sql).startsWith("exists (select 1 as \"one\" from \"rt_order\" as \"rel_1\" where (")
                .contains("\"rel_1\".\"id\" = \"rt_line\".\"order_id\"")
                .contains("(\"rel_1\".\"is_deleted\" = false or \"rel_1\".\"is_deleted\" is null)")
                .contains("\"rel_1\".\"code\" = 'BO1'");
    }

    @Test
    void noneMatchIsANotExists() {
        String sql = sql(filter(ORDER, and(FilterRelation.none("lines", and(condition("productCode", FilterOperator.EQUALS, "P1"))))));

        assertThat(sql).startsWith("not exists (select 1 as \"one\" from \"rt_line\" as \"rel_1\"")
                .contains("\"rel_1\".\"order_id\" = \"rt_order\".\"id\"")
                .contains("\"rel_1\".\"product_code\" = 'P1'");
    }

    @Test
    void relationWithoutFiltersAsksForARelatedRow() {
        String sql = sql(filter(ORDER, and(new FilterRelation("lines", null, null))));

        assertThat(sql).startsWith("exists (select 1 as \"one\" from \"rt_line\" as \"rel_1\"");
    }

    @Test
    void jsonReferencePathOfTheRelatedObjectIsQualified() {
        String sql = sql(filter(LINE, and(FilterRelation.any("order",
                and(condition("quoteIdReference.code", FilterOperator.LIKE, "BG"))))));

        assertThat(sql).contains("((\"rel_1\".\"custom_values\" -> 'quoteIdReference') ->> 'code')");
    }

    @Test
    void conditionsOfTheQueriedObjectStayUnqualified() {
        String sql = sql(filter(LINE, and(condition("productCode", FilterOperator.EQUALS, "P1"),
                FilterRelation.any("order", and(condition("code", FilterOperator.EQUALS, "BO1"))))));

        assertThat(sql).startsWith("(\"product_code\" = 'P1' and exists (");
    }

    @Test
    void relationFilterNeedsAQueryContext() {
        FilterGroup filters = and(FilterRelation.any("order", null));

        assertThatThrownBy(() -> FilterBuilder.build(filters, LINE)).isInstanceOf(UnsupportedException.class);
    }

    @Test
    void fieldNotFilterableIsRefusedThroughARelation() {
        FilterGroup filters = and(FilterRelation.any("order", and(condition("secret", FilterOperator.EQUALS, "x"))));

        assertThatThrownBy(() -> filter(LINE, filters))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("cannot be used through a relation");
        // the object's own query is not affected
        assertThat(sql(filter(ORDER, and(condition("secret", FilterOperator.EQUALS, "x"))))).isEqualTo("\"secret\" = 'x'");
    }

    @Test
    void relationInsideARelationIsRefusedAtTheDefaultDepth() {
        FilterGroup filters = and(FilterRelation.any("order", and(FilterRelation.any("customer", null))));

        assertThatThrownBy(() -> filter(LINE, filters))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("nested at most 1");
    }

    @Test
    void relationInsideARelationIsCorrelatedToTheOuterAlias() {
        RelationSettings.setMaxDepth(2);

        String sql = sql(filter(LINE, and(FilterRelation.any("order",
                and(FilterRelation.any("customer", and(condition("code", FilterOperator.EQUALS, "C1"))))))));

        assertThat(sql).contains("from \"rt_account\" as \"rel_2\"")
                .contains("\"rel_2\".\"id\" = \"rel_1\".\"customer_id\"")
                .contains("\"rel_2\".\"code\" = 'C1'");
    }

    @Test
    void tooManyRelationsAreRefused() {
        RelationSettings.setMaxRelations(1);
        FilterGroup filters = and(FilterRelation.any("lines", null), FilterRelation.any("customer", null));

        assertThatThrownBy(() -> filter(ORDER, filters))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("at most 1 relations");
    }

    @Test
    void sameRelationTwiceCountsOnce() {
        RelationSettings.setMaxRelations(1);

        String sql = sql(filter(ORDER, and(FilterRelation.any("lines", null), FilterRelation.none("lines", null))));

        assertThat(sql).contains("\"rel_1\"").contains("\"rel_2\"");
    }

    @Test
    void fullNameAndAliasOfOneRelationCountOnce() {
        RelationSettings.setMaxRelations(1);

        String sql = sql(filter(LINE, and(FilterRelation.any("orderIdReference", null), FilterRelation.none("order", null))));

        assertThat(sql).contains("\"rel_1\"").contains("\"rel_2\"");
    }

    // ------------------------------------------------------------------ conditions on a related field

    @Test
    void conditionOnARelatedFieldIsTheSameExistsAsARelationNode() {
        String byField = sql(filter(LINE, and(condition("@order.code", FilterOperator.IN, List.of("BO1", "BO2")))));
        String byNode = sql(filter(LINE, and(FilterRelation.any("order",
                and(condition("code", FilterOperator.IN, List.of("BO1", "BO2")))))));

        assertThat(byField).isEqualTo(byNode).startsWith("exists (select 1 as \"one\" from \"rt_order\" as \"rel_1\"")
                .contains("\"rel_1\".\"code\" in ('BO1', 'BO2')");
    }

    @Test
    void conditionOnARelatedJsonReferenceField() {
        assertThat(sql(filter(LINE, and(condition("@order.quoteIdReference.code", FilterOperator.LIKE, "bg")))))
                .contains("((\"rel_1\".\"custom_values\" -> 'quoteIdReference') ->> 'code')");
    }

    @Test
    void conditionsOnRelatedFieldsAreOneExistsEach() {
        String sql = sql(filter(ORDER, and(condition("@lines.productCode", FilterOperator.EQUALS, "P1"),
                condition("@lines.customerCode", FilterOperator.EQUALS, "C1"))));

        // through a one-to-many, two conditions may match two different lines (a relation node requires the same line)
        assertThat(sql).contains("\"rel_1\".\"product_code\" = 'P1'").contains("\"rel_2\".\"customer_code\" = 'C1'");
    }

    @Test
    void conditionOnARelatedFieldNeedsAQueryContext() {
        FilterGroup filters = and(condition("@order.code", FilterOperator.EQUALS, "BO1"));

        assertThatThrownBy(() -> FilterBuilder.build(filters, LINE))
                .isInstanceOf(UnsupportedException.class)
                .hasMessageContaining("relation field");
    }

    @Test
    void conditionOnARelatedFieldKeepsTheChecks() {
        assertThatThrownBy(() -> filter(LINE, and(condition("@order.secret", FilterOperator.EQUALS, "x"))))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("cannot be used through a relation");
        assertThatThrownBy(() -> filter(LINE, and(condition("@order", FilterOperator.EQUALS, "x"))))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("expected @<relation>.<field>");
        assertThatThrownBy(() -> filter(LINE, and(FilterRelation.any("order", and(condition("@customer.code", FilterOperator.EQUALS, "C1"))))))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("nested at most 1");
    }

    // ------------------------------------------------------------------ returned and sorted fields

    @Test
    void allFieldsAddTheRelatedFieldsToEveryAttribute() {
        List<SelectField<?>> fields = SelectBuilder.buildFields(LINE,
                List.of("*", "@order.code", "productCode"), RelationQueryContext.unsecured());

        // every attribute of the line once (productCode is not repeated), then the related field
        assertThat(fields).hasSize(LINE.getAttributes().size() + 1);
        assertThat(sql(DSL.select(fields))).startsWith("select \"id\" as \"id\", \"order_id\" as \"orderId\", ")
                .endsWith(" as \"@order.code\"");
    }

    @Test
    void allFieldsAloneIsTheSameAsNoReturnFields() {
        assertThat(sql(DSL.select(SelectBuilder.buildFields(LINE, List.of("*")))))
                .isEqualTo(sql(DSL.select(SelectBuilder.buildFields(LINE, List.of()))))
                .isEqualTo(sql(DSL.select(SelectBuilder.buildFields(LINE, (List<String>) null))));
    }

    @Test
    void returnFieldsWithoutAllFieldsAreKeptAsListed() {
        assertThat(sql(DSL.select(SelectBuilder.buildFields(LINE, List.of("productCode", "productCode")))))
                .isEqualTo("select \"product_code\" as \"productCode\", \"product_code\" as \"productCode\"");
    }

    @Test
    void relatedFieldIsAScalarSubqueryNamedAsRequested() {
        List<SelectField<?>> fields = SelectBuilder.buildFields(LINE,
                List.of("productCode", "@order.code"), RelationQueryContext.unsecured());

        assertThat(sql(DSL.select(fields)))
                .startsWith("select \"product_code\" as \"productCode\", (select \"rel_1\".\"code\" from \"rt_order\" as \"rel_1\" where (")
                .contains("\"rel_1\".\"id\" = \"rt_line\".\"order_id\"")
                .endsWith(") as \"@order.code\"");
    }

    @Test
    void relatedJsonReferenceFieldReadsTheRelatedCustomValues() {
        List<SelectField<?>> fields = SelectBuilder.buildFields(LINE,
                List.of("@order.quoteIdReference.code"), RelationQueryContext.unsecured());

        assertThat(sql(DSL.select(fields))).contains("((\"rel_1\".\"custom_values\" -> 'quoteIdReference') ->> 'code')");
    }

    @Test
    void relatedFieldThroughTheFullReferenceNameReadsTheRelatedJson() {
        List<SelectField<?>> fields = SelectBuilder.buildFields(LINE,
                List.of("@orderIdReference.quoteIdReference.code"), RelationQueryContext.unsecured());

        assertThat(sql(DSL.select(fields)))
                .contains("((\"rel_1\".\"custom_values\" -> 'quoteIdReference') ->> 'code')")
                .contains("\"rel_1\".\"id\" = \"rt_line\".\"order_id\"")
                .endsWith(") as \"@orderIdReference.quoteIdReference.code\"");
    }

    @Test
    void oneToManyFieldCannotBeReturned() {
        assertThatThrownBy(() -> SelectBuilder.buildFields(ORDER, List.of("@lines.productCode"), RelationQueryContext.unsecured()))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("many-to-one");
    }

    @Test
    void relatedFieldIsRefusedWithoutAQueryContext() {
        assertThatThrownBy(() -> SelectBuilder.buildFields(LINE, List.of("@order.code")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void relatedFieldMustNameAField() {
        assertThatThrownBy(() -> SelectBuilder.buildFields(LINE, List.of("@order"), RelationQueryContext.unsecured()))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("expected @<relation>.<field>");
    }

    @Test
    void sortByARelatedField() {
        String sql = sql(SortingApplier.getSortField(LINE,
                List.of(Sort.of("@order.code", SortDirection.DESC)), RelationQueryContext.unsecured()).getFirst());

        assertThat(sql).startsWith("(select \"rel_1\".\"code\" from \"rt_order\" as \"rel_1\"").endsWith(" desc");
    }

    // ------------------------------------------------------------------ search

    @Test
    void searchFieldsOfOneRelationShareOneExists() {
        ObjectFilterRequest request = ObjectFilterRequest.builder()
                .search(new SearchRequest("bo", List.of("productCode", "@order.code", "@order.name")))
                .build();
        SearchOrDefaultFilter.resolveSearchFilter(request);

        FilterGroup search = request.getFilters();
        assertThat(search.getConditions()).hasSize(2);
        assertThat(search.getConditions().get(0)).isInstanceOf(FilterCondition.class);
        FilterRelation relation = (FilterRelation) search.getConditions().get(1);
        assertThat(relation.getRelation()).isEqualTo("order");
        assertThat(relation.getFilters().getConditions()).hasSize(2);

        String sql = sql(filter(LINE, search));
        assertThat(sql).contains(" or exists (select 1 as \"one\" from \"rt_order\" as \"rel_1\"")
                .contains("unaccent(lower(\"rel_1\".\"code\"))")
                .contains("unaccent(lower(\"rel_1\".\"name\"))");
    }

    // ------------------------------------------------------------------ soft delete

    @Test
    void notDeletedIsEnforcedEvenWhenTheRequestAsksForDeletedRows() {
        ObjectFilterRequest request = ObjectFilterRequest.builder()
                .filters(and(condition("isDeleted", FilterOperator.EQUALS, true)))
                .build();
        SearchOrDefaultFilter.enforceNotDeleted(LINE, request);

        assertThat(sql(FilterBuilder.build(request.getFilters(), LINE)))
                .isEqualTo("((\"is_deleted\" = false or \"is_deleted\" is null) and \"is_deleted\" = true)");
    }

    @Test
    void addIsDeletedFilterStillLetsTheRequestChoose() {
        ObjectFilterRequest request = ObjectFilterRequest.builder()
                .filters(and(condition("isDeleted", FilterOperator.EQUALS, true)))
                .build();
        SearchOrDefaultFilter.addIsDeletedFilter(LINE, request);

        assertThat(sql(FilterBuilder.build(request.getFilters(), LINE))).isEqualTo("\"is_deleted\" = true");
    }

    // ------------------------------------------------------------------ caller's scope

    @Test
    void securedScopeAppliesTheDataScopeOfTheRelatedObjectInsideTheSubquery() {
        RelationFixtures.callerWithScopes(Map.of(
                "rtline", Set.of(AccessScope.BUSINESS_UNIT),
                "rtorder", Set.of(AccessScope.BUSINESS_UNIT)), Set.of(ORG_A));
        RelationQueryContext relations = RelationQueryContext.of(
                new SecuredRelationScope(new SecurityConditionProvider(new DataScopeConditionResolver())));

        String sql = sql(FilterBuilder.build(and(FilterRelation.any("order", null)), LINE, relations));

        assertThat(sql).startsWith("exists (select 1 as \"one\" from \"rt_order\" as \"rel_1\"")
                // the data scope reads the related object's columns unqualified: inside the subquery they resolve to rel_1
                .contains("and \"org_id\" in (cast('" + ORG_A + "' as uuid))");
    }

    @Test
    void securedScopeRefusesARelatedObjectTheCallerMayNotView() {
        RelationFixtures.callerWithScopes(Map.of("rtline", Set.of(AccessScope.ORGANIZATION)), Set.of(ORG_A));
        RelationQueryContext relations = RelationQueryContext.of(
                new SecuredRelationScope(new SecurityConditionProvider(new DataScopeConditionResolver())));

        assertThatThrownBy(() -> FilterBuilder.build(and(FilterRelation.any("order", null)), LINE, relations))
                .isInstanceOf(RelationAccessDeniedException.class)
                .hasMessageContaining("rtorder");
        assertThatThrownBy(() -> SelectBuilder.buildFields(LINE, List.of("@order.code"), RelationQueryContext.of(
                new SecuredRelationScope(new SecurityConditionProvider(new DataScopeConditionResolver())))))
                .isInstanceOf(RelationAccessDeniedException.class);
    }

    @Test
    void superAdminReachesEveryRelatedObject() {
        RelationFixtures.superAdmin();
        RelationQueryContext relations = RelationQueryContext.of(
                new SecuredRelationScope(new SecurityConditionProvider(new DataScopeConditionResolver())));

        assertThat(sql(FilterBuilder.build(and(FilterRelation.any("order", null)), LINE, relations)))
                .startsWith("exists (").contains("true");
    }

    // ------------------------------------------------------------------ JSON

    @Test
    void relationNodeIsDeducedFromItsJson() throws Exception {
        String json = """
                {"operator": "AND", "conditions": [
                  {"field": "productCode", "operator": "EQUALS", "value": "P1"},
                  {"relation": "order", "match": "NONE",
                   "filters": {"operator": "AND", "conditions": [{"field": "code", "operator": "LIKE", "value": "BO"}]}}
                ]}""";

        FilterGroup group = new ObjectMapper().readValue(json, FilterGroup.class);

        assertThat(group.getConditions().get(0)).isInstanceOf(FilterCondition.class);
        FilterRelation relation = (FilterRelation) group.getConditions().get(1);
        assertThat(relation.getMatch()).isEqualTo(RelationMatch.NONE);
        assertThat(relation.getFilters().getConditions().getFirst()).isInstanceOf(FilterCondition.class);
    }

    // ------------------------------------------------------------------ qualified metadata

    @Test
    void qualifiedCopyLeavesTheRegisteredMetadataUntouched() {
        ObjectMetadata qualified = QualifiedMetadata.of(ORDER, "rel_9");

        assertThat(sql(qualified.getAttributeByName("code").getField())).isEqualTo("\"rel_9\".\"code\"");
        assertThat(sql(qualified.getSecuredAttributes().values().iterator().next().getField())).isEqualTo("\"rel_9\".\"org_id\"");
        assertThat(sql(ORDER.getAttributeByName("code").getField())).isEqualTo("\"code\"");
    }
}
