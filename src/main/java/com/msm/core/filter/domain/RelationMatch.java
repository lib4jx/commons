package com.msm.core.filter.domain;

/** How a {@link FilterRelation} matches the related rows. */
public enum RelationMatch {
    /** At least one related row meets the conditions ({@code EXISTS}). */
    ANY,
    /** No related row meets the conditions ({@code NOT EXISTS}). */
    NONE
}
