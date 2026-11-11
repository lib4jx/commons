package com.msm.core.dynamicquery.relation;

/**
 * Limits of relations in one query. Defaults: 3 relations, 1 level (no relation inside a relation), 30 s timeout of a
 * query that uses a relation. A service may change them at start-up.
 */
public final class RelationSettings {
    private static volatile int maxRelations = 3;
    private static volatile int maxDepth = 1;
    private static volatile int queryTimeoutSeconds = 30;

    private RelationSettings() {
    }

    /** Distinct relations one query may use (filters, search, returned and sorted fields together). */
    public static int maxRelations() {
        return maxRelations;
    }

    public static void setMaxRelations(int value) {
        maxRelations = requirePositive(value, "maxRelations");
    }

    /** Relations one inside another; 1 means a relation's filters cannot use another relation. */
    public static int maxDepth() {
        return maxDepth;
    }

    public static void setMaxDepth(int value) {
        maxDepth = requirePositive(value, "maxDepth");
    }

    /** Timeout of a query (and its count) that uses a relation; 0 for none. */
    public static int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public static void setQueryTimeoutSeconds(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be >= 0");
        }
        queryTimeoutSeconds = value;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1");
        }
        return value;
    }
}
