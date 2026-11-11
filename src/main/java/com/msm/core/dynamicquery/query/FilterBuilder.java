package com.msm.core.dynamicquery.query;


import com.msm.core.commons.Utils;
import com.msm.core.dynamicquery.operator.OperatorFactory;
import com.msm.core.dynamicquery.relation.RelationConditionBuilder;
import com.msm.core.dynamicquery.relation.RelationFieldResolver;
import com.msm.core.dynamicquery.relation.RelationQueryContext;
import com.msm.core.exceptions.CommonErrors;
import com.msm.core.filter.domain.FilterCondition;
import com.msm.core.filter.domain.FilterGroup;
import com.msm.core.filter.domain.FilterObject;
import com.msm.core.filter.domain.FilterRelation;
import com.msm.core.filter.domain.LogicalOperator;
import com.msm.core.metadata.ObjectMetadata;
import org.jooq.Condition;
import org.jooq.impl.DSL;

import java.util.List;

public class FilterBuilder {

    /** Conditions of one object, without relations ({@link FilterRelation} is refused). */
    public static Condition build(FilterObject filter, ObjectMetadata meta) {
        return build(filter, meta, null, null);
    }

    /** Conditions of the queried object, relations included; its columns are qualified with its table name. */
    public static Condition build(FilterObject filter, ObjectMetadata meta, RelationQueryContext relations) {
        return build(filter, meta, meta.getTableName(), relations);
    }

    /**
     * @param qualifier table name or alias the object's columns are qualified with, for the relations it starts
     * @param relations relations of the query; null when relations are not supported by the caller
     */
    public static Condition build(FilterObject filter, ObjectMetadata meta, String qualifier, RelationQueryContext relations) {
        if (filter == null) {
            return DSL.noCondition();
        }

        if (filter instanceof FilterCondition fc) {
            return buildCondition(fc, meta, qualifier, relations);
        }

        if (filter instanceof FilterGroup fg) {
            return buildGroup(fg, meta, qualifier, relations);
        }

        if (filter instanceof FilterRelation fr) {
            if (relations == null) {
                throw CommonErrors.unsupported(String.valueOf(fr.getRelation()),
                        "A relation filter is supported only by the filter / lookup queries");
            }
            return RelationConditionBuilder.build(fr, meta, qualifier, relations);
        }

        throw CommonErrors.invalid("Unknown", "Unknown filter type");
    }

    private static Condition buildGroup(FilterGroup group, ObjectMetadata meta, String qualifier, RelationQueryContext relations) {
        List<Condition> conditions = Utils.CL.emptyIfNull(group.getConditions())
                .stream()
                .map(f -> build(f, meta, qualifier, relations))
                .toList();

        if (conditions.isEmpty()) {
            return DSL.noCondition();
        }

        LogicalOperator operator = group.getOperator() == null ? LogicalOperator.AND : group.getOperator();
        return switch (operator) {
            case AND -> DSL.and(conditions);
            case OR -> DSL.or(conditions);
        };
    }

    private static Condition buildCondition(FilterCondition condition, ObjectMetadata meta, String qualifier, RelationQueryContext relations) {
        if (RelationFieldResolver.isRelationPath(condition.getField())) {
            return buildRelationFieldCondition(condition, meta, qualifier, relations);
        }
        if (relations != null && relations.insideRelation()) {
            RelationFieldResolver.requireFilterable(meta, condition.getField());
        }
        return OperatorFactory.get(condition.getOperator()).handle(meta, condition);
    }

    /**
     * A condition on a related field, {@code {"field": "@blanketOrder.code", …}}: the same as a {@link FilterRelation}
     * (ANY) holding the condition on {@code code}. Each such condition is its own EXISTS: through a one-to-many
     * relation, two of them may match two different related rows; put them in one {@link FilterRelation} to require
     * the same row.
     */
    private static Condition buildRelationFieldCondition(FilterCondition condition, ObjectMetadata meta, String qualifier, RelationQueryContext relations) {
        if (relations == null) {
            throw CommonErrors.unsupported(condition.getField(),
                    "A relation field is supported only by the filter / lookup queries");
        }
        RelationFieldResolver.RelationPath path = RelationFieldResolver.parse(condition.getField());
        FilterCondition related = FilterCondition.builder()
                .field(path.field())
                .operator(condition.getOperator())
                .value(condition.getValue())
                .joinType(condition.getJoinType())
                .build();
        FilterGroup filters = FilterGroup.builder().operator(LogicalOperator.AND).conditions(List.of(related)).build();
        return RelationConditionBuilder.build(FilterRelation.any(path.relation(), filters), meta, qualifier, relations);
    }
}
