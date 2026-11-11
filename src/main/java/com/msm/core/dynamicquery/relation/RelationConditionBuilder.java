package com.msm.core.dynamicquery.relation;

import com.msm.core.commons.Constants;
import com.msm.core.dynamicquery.query.FilterBuilder;
import com.msm.core.filter.domain.FilterRelation;
import com.msm.core.filter.domain.RelationMatch;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.ObjectMetadata;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Select;
import org.jooq.impl.DSL;

/**
 * Builds a {@link FilterRelation} as {@code EXISTS} (or {@code NOT EXISTS}) of a subquery on the related object:
 *
 * <pre>
 * exists (select 1 from v2_blanket_order as rel_1
 *          where rel_1.id = v2_blanket_order_product.blanket_order_id
 *            and (rel_1.is_deleted = false or rel_1.is_deleted is null)
 *            and &lt;data scope of v2blanketorder on rel_1&gt;
 *            and &lt;the relation's filters on rel_1&gt;)
 * </pre>
 *
 * The queried rows are never multiplied, so paging and the total count stay right.
 */
public final class RelationConditionBuilder {

    private RelationConditionBuilder() {
    }

    /**
     * @param filter          the relation condition
     * @param source          object the relation starts from (qualified when it is itself a related object)
     * @param sourceQualifier table name or alias the source's columns are qualified with
     * @param context         relations of the query
     */
    public static Condition build(FilterRelation filter, ObjectMetadata source, String sourceQualifier,
                                  RelationQueryContext context) {
        ResolvedRelation relation = RelationResolver.resolve(source, filter.getRelation());
        context.use(relation);
        return context.nested(() -> {
            String alias = context.nextAlias();
            ObjectMetadata target = QualifiedMetadata.of(relation.target(), alias);
            Condition where = relatedRows(relation, sourceQualifier, alias, target, context)
                    .and(FilterBuilder.build(filter.getFilters(), target, alias, context));
            Select<?> rows = DSL.selectOne().from(relation.target().getTable().as(alias)).where(where);
            return filter.getMatch() == RelationMatch.NONE ? DSL.notExists(rows) : DSL.exists(rows);
        });
    }

    /** The related rows of the source row that the caller may see and that are not deleted. */
    static Condition relatedRows(ResolvedRelation relation, String sourceQualifier, String alias,
                                 ObjectMetadata qualifiedTarget, RelationQueryContext context) {
        return relation.link(sourceQualifier, alias)
                .and(notDeleted(qualifiedTarget))
                .and(context.scope().rowCondition(qualifiedTarget));
    }

    /** {@code is_deleted = false or is_deleted is null}, as the default filter of the queried object. */
    @SuppressWarnings("unchecked")
    static Condition notDeleted(ObjectMetadata meta) {
        Attribute isDeleted = meta.getAttributeByName(Constants.IS_DELETED_FIELD);
        if (isDeleted == null) {
            return DSL.noCondition();
        }
        Field<Object> field = (Field<Object>) isDeleted.getField();
        return field.eq(Boolean.FALSE).or(field.isNull());
    }
}
