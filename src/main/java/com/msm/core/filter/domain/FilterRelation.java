package com.msm.core.filter.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A condition on the rows of another object of the same service, reached through a relation the object declares
 * (many-to-one, e.g. {@code blanketOrderIdReference}, or one-to-many, e.g. {@code lines}).
 *
 * <pre>
 * { "relation": "blanketOrderIdReference", "match": "ANY",
 *   "filters": { "operator": "AND", "conditions": [ { "field": "code", "operator": "LIKE", "value": "BO.26" } ] } }
 * </pre>
 *
 * It is built as {@code EXISTS} / {@code NOT EXISTS} of a subquery on the related object, so the rows of the queried
 * object are never multiplied; the subquery applies the related object's data scope and soft delete.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public final class FilterRelation implements FilterObject {
    /** Name of the relation, as the object declares it. */
    private String relation;
    /** {@link RelationMatch#ANY} when null. */
    private RelationMatch match;
    /** Conditions on the related object; null or empty means "has a related row" (or "has none" with NONE). */
    private FilterGroup filters;

    public static FilterRelation any(String relation, FilterGroup filters) {
        return new FilterRelation(relation, RelationMatch.ANY, filters);
    }

    public static FilterRelation none(String relation, FilterGroup filters) {
        return new FilterRelation(relation, RelationMatch.NONE, filters);
    }
}
