package com.msm.core.dynamicquery.relation;

import com.msm.core.dynamicquery.ObjectMetadataFactory;
import com.msm.core.dynamicquery.internal.InternalFilterQuery;
import com.msm.core.dynamicquery.query.DefaultFilterQuery;
import com.msm.core.exceptions.FieldValueInvalidException;
import com.msm.core.exceptions.RelationAccessDeniedException;
import com.msm.core.filter.domain.FilterGroup;
import com.msm.core.filter.domain.FilterOperator;
import com.msm.core.filter.domain.FilterRelation;
import com.msm.core.filter.domain.ObjectFilterRequest;
import com.msm.core.filter.domain.PageResponse;
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
import com.msm.core.security.SecurityConditionProvider;
import com.msm.core.security.enums.AccessScope;
import com.msm.core.security.enums.SecurityDataScopeType;
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
import java.util.stream.Collectors;

import static com.msm.core.dynamicquery.relation.RelationFixtures.ORG_A;
import static com.msm.core.dynamicquery.relation.RelationFixtures.ORG_B;
import static com.msm.core.dynamicquery.relation.RelationFixtures.and;
import static com.msm.core.dynamicquery.relation.RelationFixtures.condition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Edge cases of relations on a real PostgreSQL: a self reference, a target that is a child object (data scope of
 * its parent), a target without security nor soft delete, a list reference, null keys, NOT EXISTS, search on a
 * one-to-many, paging and sorting on a related field, streams, lookup and the internal query.
 *
 * <pre>
 * parents (re_parent, secured by business unit):
 *   A1 (org A), A2 (org A, manager A1), A3 (org A, manager A1, deleted), B1 (org B), A4 (org A, manager B1)
 * children (re_child, child of re_parent: data scope of the parent):
 *   c-a1 → A1, c-a2 → A2, c-del → A2 (deleted), c-b1 → B1
 * notes (re_note, no security, no soft delete): n-a1 → A1, n-null → no parent
 * caller: business unit A
 * </pre>
 */
class RelationEdgeCasesPostgresIT {

    private static final String DB_URL = System.getenv("MSM_TEST_DB_URL");

    private static final ObjectMetadata PARENT = ObjectMetadata.builder()
            .name("reparent").tableName("re_parent")
            .attributes(new ArrayList<>(List.of(
                    attribute("id", "id", "UUID"),
                    attribute("code", "code", "String"),
                    reference("managerId", "manager_id", "managerIdReference", "reparent"),
                    listReference("peerId", "peer_ids", "peerIdReference", "reparent"),
                    attribute("orgId", "org_id", "UUID"),
                    attribute("isDeleted", "is_deleted", "Boolean"))))
            .build();

    private static final ObjectMetadata CHILD = ObjectMetadata.builder()
            .name("rechild").tableName("re_child")
            .attributes(new ArrayList<>(List.of(
                    attribute("id", "id", "UUID"),
                    reference("parentId", "parent_id", "parentIdReference", "reparent"),
                    attribute("code", "code", "String"),
                    attribute("isDeleted", "is_deleted", "Boolean"))))
            .objectRelation(ObjectRelation.builder().relationType(RelationalTypeEnum.CHILD_OF)
                    .targetObject("reparent").foreignKeyAttribute("parentId").build())
            .build();

    private static final ObjectMetadata NOTE = ObjectMetadata.builder()
            .name("renote").tableName("re_note")
            .securityEnabled(false)
            .attributes(new ArrayList<>(List.of(
                    attribute("id", "id", "UUID"),
                    reference("parentId", "parent_id", "parentIdReference", "reparent"),
                    attribute("code", "code", "String"))))
            .build();

    private static final UUID A1 = id(1);
    private static final UUID A2 = id(2);
    private static final UUID A3 = id(3);
    private static final UUID B1 = id(4);
    private static final UUID A4 = id(5);

    private static Connection connection;
    private static DSLContext dsl;

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue(DB_URL != null, "MSM_TEST_DB_URL not set");
        PARENT.setSecuredAttributes(Map.of(SecurityDataScopeType.BUSINESS_UNIT, PARENT.getAttributeByName("orgId")));
        CHILD.setSecuredAttributes(Map.of());
        NOTE.setSecuredAttributes(Map.of());
        ObjectMetadataFactory.registerObjectMetadata(PARENT);
        ObjectMetadataFactory.registerObjectMetadata(CHILD);
        ObjectMetadataFactory.registerObjectMetadata(NOTE);
        connection = DriverManager.getConnection(DB_URL);
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
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
        dsl.execute("drop table if exists re_parent, re_child, re_note");
        dsl.execute("create table re_parent (id uuid primary key, code text, manager_id uuid, peer_ids uuid[], org_id uuid, is_deleted boolean)");
        dsl.execute("create table re_child (id uuid primary key, parent_id uuid, code text, is_deleted boolean)");
        dsl.execute("create table re_note (id uuid primary key, parent_id uuid, code text)");
        parent(A1, "A1", null, ORG_A, false);
        parent(A2, "A2", A1, ORG_A, null);
        parent(A3, "A3", A1, ORG_A, true);
        parent(B1, "B1", null, ORG_B, false);
        parent(A4, "A4", B1, ORG_A, false);
        child("c-a1", A1, false);
        child("c-a2", A2, false);
        child("c-del", A2, true);
        child("c-b1", B1, false);
        dsl.execute("insert into re_note values (?, ?, 'n-a1'), (?, null, 'n-null')", UUID.randomUUID(), A1, UUID.randomUUID());
        // business unit A on the parents and on the children (the children use the scope of their parent)
        RelationFixtures.callerWithScopes(Map.of(
                "reparent", Set.of(AccessScope.BUSINESS_UNIT),
                "rechild", Set.of(AccessScope.BUSINESS_UNIT)), Set.of(ORG_A));
    }

    @AfterEach
    void reset() {
        RelationSettings.setMaxRelations(3);
        RelationFixtures.resetCaller();
    }

    // ------------------------------------------------------------------ self reference

    @Test
    void selfReferenceReadsTheManagerOnlyWhenTheCallerSeesIt() {
        List<Map<String, Object>> rows = secured().filter(PARENT,
                request(List.of("code", "@manager.code"), Sort.of("code", SortDirection.ASC))).getContents();

        // A3 is deleted; B1 is out of the business unit, so A4's manager reads as null
        assertThat(rows).extracting(row -> row.get("code")).containsExactly("A1", "A2", "A4");
        assertThat(rows).extracting(row -> row.get("@manager.code")).containsExactly(null, "A1", null);
    }

    @Test
    void selfOneToManyFindsTheParentsWithVisibleSubordinates() {
        ObjectFilterRequest request = request(List.of("code"), null);
        // generated name: the object references itself once (managerId), the list reference makes no relation
        request.setFilters(and(FilterRelation.any("reparent", null)));

        // A1 manages A2 (A3 is deleted); B1 manages A4 but B1 itself is out of the business unit
        assertThat(codes(secured().filter(PARENT, request))).containsExactly("A1");
    }

    @Test
    void listReferenceGivesNoRelation() {
        // a list key would be named "peer"; it holds several keys, so no relation
        ObjectFilterRequest request = request(List.of("code", "@peer.code"), null);

        assertThatThrownBy(() -> secured().filter(PARENT, request))
                .isInstanceOf(FieldValueInvalidException.class)
                .hasMessageContaining("Unknown relation 'peer'");
    }

    // ------------------------------------------------------------------ child object target

    @Test
    void childTargetUsesTheDataScopeOfItsParentAndSkipsDeletedChildren() {
        ObjectFilterRequest withChild = request(List.of("code"), Sort.of("code", SortDirection.ASC));
        withChild.setFilters(and(FilterRelation.any("rechild", null)));
        ObjectFilterRequest withChildA2 = request(List.of("code"), null);
        withChildA2.setFilters(and(FilterRelation.any("rechild", and(condition("code", FilterOperator.LIKE, "del")))));

        assertThat(codes(secured().filter(PARENT, withChild))).containsExactly("A1", "A2");
        assertThat(codes(secured().filter(PARENT, withChildA2))).isEmpty();
    }

    @Test
    void childTargetNeedsThePermissionOfItsParentObject() {
        RelationFixtures.callerWithScopes(Map.of("rechild", Set.of(AccessScope.ORGANIZATION)), Set.of(ORG_A));
        ObjectFilterRequest request = request(List.of("code"), null);

        // the child uses the permission of reparent, which this caller does not have
        assertThatThrownBy(() -> secured().filter(CHILD, withRelation(request, FilterRelation.any("parent", null))))
                .isInstanceOf(RelationAccessDeniedException.class);
    }

    // ------------------------------------------------------------------ target without security nor soft delete

    @Test
    void targetWithoutSecurityNeedsNoPermissionAndHasNoSoftDelete() {
        RelationFixtures.callerWithScopes(Map.of("reparent", Set.of(AccessScope.BUSINESS_UNIT)), Set.of(ORG_A));
        ObjectFilterRequest request = request(List.of("code"), null);
        request.setFilters(and(FilterRelation.any("renote", and(condition("code", FilterOperator.EQUALS, "n-a1")))));

        assertThat(codes(secured().filter(PARENT, request))).containsExactly("A1");
    }

    // ------------------------------------------------------------------ null keys and NONE

    @Test
    void nullKeyHasNoRelatedRowAndNoneKeepsIt() {
        ObjectFilterRequest withParent = request(List.of("code"), null);
        withParent.setFilters(and(FilterRelation.any("parent", null)));
        ObjectFilterRequest withoutParent = request(List.of("code"), null);
        withoutParent.setFilters(and(FilterRelation.none("parent", null)));

        assertThat(codes(new InternalFilterQuery(dsl).filter(NOTE, withParent))).containsExactly("n-a1");
        assertThat(codes(new InternalFilterQuery(dsl).filter(NOTE, withoutParent))).containsExactly("n-null");
    }

    @Test
    void noneWithConditionsKeepsTheParentsWithoutAMatchingChild() {
        ObjectFilterRequest request = request(List.of("code"), Sort.of("code", SortDirection.ASC));
        request.setFilters(and(FilterRelation.none("rechild", and(condition("code", FilterOperator.EQUALS, "c-a1")))));

        // A2 and A4 have no child c-a1 (A4 has no child at all)
        assertThat(codes(secured().filter(PARENT, request))).containsExactly("A2", "A4");
    }

    // ------------------------------------------------------------------ search, paging, sort

    @Test
    void searchOnAOneToManyField() {
        ObjectFilterRequest request = request(List.of("code"), Sort.of("code", SortDirection.ASC));
        request.setSearch(new SearchRequest("C-A", List.of("code", "@rechild.code")));

        // A1 (c-a1) and A2 (c-a2) by their children; no parent code contains "c-a"
        assertThat(codes(secured().filter(PARENT, request))).containsExactly("A1", "A2");
    }

    @Test
    void pagingAndSortingOnARelatedFieldKeepTheTotal() {
        ObjectFilterRequest first = request(List.of("code", "@manager.code"), Sort.of("@manager.code", SortDirection.DESC));
        first.setPageRequest(PageRequest.of(1, 2, List.of(Sort.of("@manager.code", SortDirection.DESC), Sort.of("code", SortDirection.ASC))));
        ObjectFilterRequest second = request(List.of("code", "@manager.code"), null);
        second.setPageRequest(PageRequest.of(2, 2, List.of(Sort.of("@manager.code", SortDirection.DESC), Sort.of("code", SortDirection.ASC))));

        PageResponse<Map<String, Object>> page1 = secured().filter(PARENT, first);
        PageResponse<Map<String, Object>> page2 = secured().filter(PARENT, second);

        assertThat(page1.getTotalElements()).isEqualTo(3);
        assertThat(page2.getTotalElements()).isEqualTo(3);
        // nulls first in a descending sort (PostgreSQL): A1 and A4 (manager not visible), then A2 (manager A1)
        assertThat(codes(page1)).containsExactly("A1", "A4");
        assertThat(codes(page2)).containsExactly("A2");
    }

    @Test
    void sameRelationInFilterFieldsAndSortCountsOnce() {
        RelationSettings.setMaxRelations(1);
        ObjectFilterRequest request = request(List.of("code", "@manager.code"), Sort.of("@manager.code", SortDirection.ASC));
        request.setFilters(and(FilterRelation.any("manager", and(condition("id", FilterOperator.EQUALS, A1.toString())))));
        request.setSearch(new SearchRequest("a", List.of("@manager.code")));

        assertThat(codes(secured().filter(PARENT, request))).containsExactly("A2");
    }

    // ------------------------------------------------------------------ streams, lookup, internal

    @Test
    void streamsAndLookupUseRelationsToo() {
        ObjectFilterRequest streamed = request(List.of("code", "@manager.code"), Sort.of("code", SortDirection.ASC));
        streamed.setFilters(and(FilterRelation.any("manager", null)));
        List<Map<String, Object>> rows = new ArrayList<>();
        secured().filterStream(PARENT, streamed, 10, rows::add);

        ObjectFilterRequest lookup = request(List.of("code"), null);
        lookup.setFilters(and(FilterRelation.any("rechild", null)));

        assertThat(rows).extracting(row -> row.get("@manager.code")).containsExactly("A1");
        assertThat(codes(secured().lookup(PARENT, lookup))).containsExactlyInAnyOrder("A1", "A2");
    }

    @Test
    void internalQuerySeesEveryNotDeletedRelatedRow() {
        ObjectFilterRequest request = request(List.of("code", "@manager.code"), Sort.of("code", SortDirection.ASC));

        List<Map<String, Object>> rows = new InternalFilterQuery(dsl).filter(PARENT, request).getContents();

        assertThat(rows.stream().collect(Collectors.toMap(row -> (String) row.get("code"),
                row -> String.valueOf(row.get("@manager.code")))))
                .containsEntry("A2", "A1").containsEntry("A4", "B1").containsEntry("A1", "null").doesNotContainKey("A3");
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

    private static ObjectFilterRequest withRelation(ObjectFilterRequest request, FilterRelation relation) {
        request.setFilters(FilterGroup.builder().conditions(new ArrayList<>(List.of(relation))).build());
        return request;
    }

    private static List<Object> codes(PageResponse<Map<String, Object>> page) {
        return page.getContents().stream().map(row -> row.get("code")).toList();
    }

    private static void parent(UUID id, String code, UUID manager, UUID org, Boolean deleted) {
        dsl.execute("insert into re_parent values (?, ?, ?, null, ?, ?)", id, code, manager, org, deleted);
    }

    private static void child(String code, UUID parent, boolean deleted) {
        dsl.execute("insert into re_child values (?, ?, ?, ?)", UUID.randomUUID(), parent, code, deleted);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", 900 + n));
    }

    private static Attribute attribute(String fieldName, String columnName, String fieldType) {
        return Attribute.builder().fieldName(fieldName).columnName(columnName).fieldType(fieldType).build();
    }

    private static Attribute reference(String fieldName, String columnName, String referenceName, String objectRef) {
        return Attribute.builder().fieldName(fieldName).columnName(columnName).fieldType("UUID")
                .attributeRef(AttributeRef.of(referenceName, objectRef)).build();
    }

    private static Attribute listReference(String fieldName, String columnName, String referenceName, String objectRef) {
        return Attribute.builder().fieldName(fieldName).columnName(columnName).fieldType("List<UUID>")
                .attributeRef(AttributeRef.of(referenceName, objectRef)).build();
    }
}
