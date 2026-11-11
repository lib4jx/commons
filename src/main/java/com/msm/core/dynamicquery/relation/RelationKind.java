package com.msm.core.dynamicquery.relation;

/** Direction of a relation, seen from the object that uses it. */
public enum RelationKind {
    /** The object holds the key (e.g. a product line to its blanket order): at most one related row. */
    MANY_TO_ONE,
    /** The related object holds the key (e.g. a blanket order to its product lines): any number of related rows. */
    ONE_TO_MANY
}
