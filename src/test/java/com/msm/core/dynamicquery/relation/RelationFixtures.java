package com.msm.core.dynamicquery.relation;

import com.msm.core.dynamicquery.ObjectMetadataFactory;
import com.msm.core.filter.domain.FilterCondition;
import com.msm.core.filter.domain.FilterGroup;
import com.msm.core.filter.domain.FilterObject;
import com.msm.core.filter.domain.FilterOperator;
import com.msm.core.filter.domain.LogicalOperator;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.AttributeRef;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.security.IdentifiableCode;
import com.msm.core.security.RequestContextHolder;
import com.msm.core.security.context.AuthorizationContext;
import com.msm.core.security.context.DataScopeContext;
import com.msm.core.security.context.RequestContext;
import com.msm.core.security.context.impl.DefaultDataScopeContext;
import com.msm.core.security.enums.AccessScope;
import com.msm.core.security.enums.SecurityDataScopeType;
import com.msm.core.security.model.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Three objects of one service: an account, an order (many-to-one {@code customer} from its reference to the
 * account) and an order line (many-to-one {@code order} from its reference to the order, whose
 * {@code reverseName} gives the order the one-to-many {@code lines}). A payment references the order once and a note
 * twice, both without reverseName: the order gets {@code rtpayment}, {@code rtnote_orderId} and
 * {@code rtnote_sourceOrderId}.
 * A profile references the account through {@code creditAnalystReference}, a name without {@code Id}.
 * The quotation the order references is not an object of this service. Each object is secured by business unit.
 */
final class RelationFixtures {

    static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    static final ObjectMetadata ACCOUNT = securedByOrg(metadata("rtaccount", "rt_account",
            attribute("id", "id", "UUID"),
            attribute("code", "code", "String"),
            attribute("name", "name", "String"),
            attribute("orgId", "org_id", "UUID"),
            attribute("isDeleted", "is_deleted", "Boolean")));

    static final ObjectMetadata ORDER = securedByOrg(metadata("rtorder", "rt_order",
            attribute("id", "id", "UUID"),
            attribute("code", "code", "String"),
            attribute("name", "name", "String"),
            reference("quoteId", "quote_id", "quoteIdReference", "rtquotation"),
            reference("customerId", "customer_id", "customerIdReference", "rtaccount"),
            secret("secret", "secret", "String"),
            attribute("orgId", "org_id", "UUID"),
            attribute("isDeleted", "is_deleted", "Boolean"),
            attribute("customValues", "custom_values", "Map<String, Object>")));

    static final ObjectMetadata LINE = securedByOrg(metadata("rtline", "rt_line",
            attribute("id", "id", "UUID"),
            reference("orderId", "order_id", "orderIdReference", "rtorder", "lines"),
            attribute("productCode", "product_code", "String"),
            attribute("customerCode", "customer_code", "String"),
            attribute("orgId", "org_id", "UUID"),
            attribute("isDeleted", "is_deleted", "Boolean")));

    static final ObjectMetadata PAYMENT = metadata("rtpayment", "rt_payment",
            attribute("id", "id", "UUID"),
            reference("orderId", "order_id", "orderIdReference", "rtorder"));

    static final ObjectMetadata NOTE = metadata("rtnote", "rt_note",
            attribute("id", "id", "UUID"),
            reference("orderId", "order_id", "orderIdReference", "rtorder"),
            reference("sourceOrderId", "source_order_id", "sourceOrderIdReference", "rtorder"));

    /** A reference whose names follow neither {@code <x>Id} nor {@code <x>IdReference}, like Profile.creditAnalyst. */
    static final ObjectMetadata PROFILE = metadata("rtprofile", "rt_profile",
            attribute("id", "id", "UUID"),
            reference("creditAnalyst", "credit_analyst", "creditAnalystReference", "rtaccount"));

    private RelationFixtures() {
    }

    static void register() {
        ObjectMetadataFactory.registerObjectMetadata(ACCOUNT);
        ObjectMetadataFactory.registerObjectMetadata(ORDER);
        ObjectMetadataFactory.registerObjectMetadata(LINE);
        ObjectMetadataFactory.registerObjectMetadata(PAYMENT);
        ObjectMetadataFactory.registerObjectMetadata(NOTE);
        ObjectMetadataFactory.registerObjectMetadata(PROFILE);
    }

    // ------------------------------------------------------------------ request context

    /** A caller with the given VIEW scopes per object (no entry: no permission) in business unit {@code orgs}. */
    static void callerWithScopes(Map<String, Set<AccessScope>> scopes, Set<UUID> orgs) {
        RequestContextHolder.setRequestContextProvider(() -> context(false, scopes, orgs));
    }

    static void superAdmin() {
        RequestContextHolder.setRequestContextProvider(() -> context(true, Map.of(), Set.of()));
    }

    /** Back to a caller without any permission, as the library default. */
    static void resetCaller() {
        RequestContextHolder.setRequestContextProvider(() -> context(false, Map.of(), Set.of()));
    }

    private static RequestContext context(boolean superAdmin, Map<String, Set<AccessScope>> scopes, Set<UUID> orgs) {
        AuthorizationContext authorization = new AuthorizationContext() {
            @Override
            public Set<AccessScope> getScope(String objectName, IdentifiableCode action) {
                return scopes.getOrDefault(objectName, Set.of());
            }

            @Override
            public boolean hasPermission(String objectName, IdentifiableCode action) {
                return scopes.containsKey(objectName);
            }
        };
        DataScopeContext dataScope = new DefaultDataScopeContext(Set.of(), orgs, Set.of(), Set.of(), null);
        return new RequestContext() {
            @Override
            public String getTenantCode() {
                return "test";
            }

            @Override
            public UUID getUserId() {
                return UUID.randomUUID();
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
                return superAdmin;
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

    // ------------------------------------------------------------------ filters

    static FilterGroup and(FilterObject... conditions) {
        return FilterGroup.builder().operator(LogicalOperator.AND).conditions(new ArrayList<>(List.of(conditions))).build();
    }

    static FilterCondition condition(String field, FilterOperator operator, Object value) {
        return FilterCondition.create(field, operator, value);
    }

    // ------------------------------------------------------------------ metadata

    private static ObjectMetadata metadata(String name, String table, Attribute... attributes) {
        return ObjectMetadata.builder().name(name).tableName(table).attributes(new ArrayList<>(List.of(attributes))).build();
    }

    private static ObjectMetadata securedByOrg(ObjectMetadata meta) {
        meta.setSecuredAttributes(Map.of(SecurityDataScopeType.BUSINESS_UNIT, meta.getAttributeByName("orgId")));
        return meta;
    }

    private static Attribute attribute(String fieldName, String columnName, String fieldType) {
        return Attribute.builder().fieldName(fieldName).columnName(columnName).fieldType(fieldType).build();
    }

    private static Attribute reference(String fieldName, String columnName, String referenceName, String objectRef) {
        return reference(fieldName, columnName, referenceName, objectRef, null);
    }

    private static Attribute reference(String fieldName, String columnName, String referenceName, String objectRef,
                                       String reverseName) {
        AttributeRef ref = AttributeRef.of(referenceName, objectRef);
        ref.setReverseName(reverseName);
        return Attribute.builder().fieldName(fieldName).columnName(columnName).fieldType("UUID").attributeRef(ref).build();
    }

    private static Attribute secret(String fieldName, String columnName, String fieldType) {
        return Attribute.builder().fieldName(fieldName).columnName(columnName).fieldType(fieldType).isFilterable(false).build();
    }
}
