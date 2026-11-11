package com.msm.core.dynamicquery.relation;

import com.msm.core.metadata.ObjectMetadata;
import org.jooq.Condition;
import org.jooq.Field;

/**
 * A relation found on an object: the rows of {@code target} whose {@code targetKey} equals the source row's
 * {@code sourceKey}.
 *
 * @param name      relation name, as used in a request
 * @param kind      many-to-one (the source holds the key) or one-to-many (the target holds it)
 * @param source    object the relation starts from
 * @param target    related object (same service)
 * @param sourceKey attribute of the source compared
 * @param targetKey attribute of the target compared
 */
public record ResolvedRelation(
        String name,
        RelationKind kind,
        ObjectMetadata source,
        ObjectMetadata target,
        String sourceKey,
        String targetKey) {

    /**
     * Links the related rows to the source row: {@code <targetAlias>.<targetKey> = <sourceQualifier>.<sourceKey>}.
     * Both sides are qualified, so the condition reads right inside a subquery of the target.
     */
    @SuppressWarnings("unchecked")
    public Condition link(String sourceQualifier, String targetAlias) {
        Field<Object> sourceField = (Field<Object>) QualifiedMetadata.field(source, sourceKey, sourceQualifier);
        Field<Object> targetField = (Field<Object>) QualifiedMetadata.field(target, targetKey, targetAlias);
        return targetField.eq(sourceField);
    }
}
