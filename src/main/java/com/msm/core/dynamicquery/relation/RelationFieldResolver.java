package com.msm.core.dynamicquery.relation;

import com.msm.core.dynamicquery.FieldResolver;
import com.msm.core.exceptions.CommonErrors;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.ObjectMetadata;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Fields of a related object in returnFields, sorts and search fields, written {@code @<relation>.<field path>}
 * ({@code @blanketOrder.code}, {@code @blanketOrder.quoteIdReference.code}).
 *
 * <p>Returned and sorted fields are read with a scalar subquery, and only through a many-to-one relation (one
 * related row at most), so the rows are not multiplied. The subquery applies the related object's data scope and
 * soft delete: a related row the caller may not see reads as null.
 */
public final class RelationFieldResolver {
    public static final String PREFIX = "@";

    private RelationFieldResolver() {
    }

    /** A field path of a related object: the relation and the field path on it. */
    public record RelationPath(String relation, String field) {
    }

    public static boolean isRelationPath(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    public static RelationPath parse(String path) {
        int dot = isRelationPath(path) ? path.indexOf('.') : -1;
        if (dot <= PREFIX.length() || dot == path.length() - 1) {
            throw CommonErrors.invalid(String.valueOf(path),
                    "Invalid relation field: " + path + " (expected @<relation>.<field>)");
        }
        return new RelationPath(path.substring(PREFIX.length(), dot), path.substring(dot + 1));
    }

    /**
     * The value of a related field for each source row:
     * {@code (select rel_1.code from v2_blanket_order as rel_1 where rel_1.id = <source key> and … limit 1)}.
     */
    public static Field<?> resolve(ObjectMetadata source, String sourceQualifier, String path,
                                   RelationQueryContext context) {
        RelationPath relationPath = parse(path);
        ResolvedRelation relation = RelationResolver.resolve(source, relationPath.relation());
        if (relation.kind() != RelationKind.MANY_TO_ONE) {
            throw CommonErrors.invalid(path,
                    "Only a field of a many-to-one relation can be returned or sorted: " + path);
        }
        context.use(relation);
        return context.nested(() -> {
            String alias = context.nextAlias();
            ObjectMetadata target = QualifiedMetadata.of(relation.target(), alias);
            requireFilterable(target, relationPath.field());
            return scalar(FieldResolver.resolve(target, relationPath.field()),
                    relation.target().getTable().as(alias),
                    RelationConditionBuilder.relatedRows(relation, sourceQualifier, alias, target, context));
        });
    }

    private static <T> Field<T> scalar(Field<T> value, Table<?> from, Condition where) {
        return DSL.field(DSL.select(value).from(from).where(where).limit(1));
    }

    /** Refuses a field of a related object declared {@code filterable = false}. */
    public static void requireFilterable(ObjectMetadata relatedObject, String fieldPath) {
        String first = fieldPath == null ? "" : fieldPath.split("\\.")[0];
        Attribute attribute = relatedObject.getAttributeByName(first);
        if (attribute == null) {
            attribute = FieldResolver.resolveTarget(relatedObject, fieldPath).attribute();
        }
        if (!attribute.isFilterableThroughRelation()) {
            throw CommonErrors.invalid(fieldPath,
                    "Field " + fieldPath + " of " + relatedObject.getName() + " cannot be used through a relation");
        }
    }
}
