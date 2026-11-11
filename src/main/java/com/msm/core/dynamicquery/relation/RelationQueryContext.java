package com.msm.core.dynamicquery.relation;

import com.msm.core.exceptions.CommonErrors;
import org.jooq.ResultQuery;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * State of the relations of one query: the scope applied to related objects, the aliases given to their subqueries,
 * the relations used (limited by {@link RelationSettings#maxRelations()}) and how deep the current one is (limited
 * by {@link RelationSettings#maxDepth()}). Not thread safe: one per query.
 */
public final class RelationQueryContext {
    private static final String ALIAS_PREFIX = "rel_";

    private final RelationScope scope;
    private final Set<String> relations = new HashSet<>();
    private int aliasSequence;
    private int depth;

    private RelationQueryContext(RelationScope scope) {
        this.scope = scope;
    }

    public static RelationQueryContext of(RelationScope scope) {
        return new RelationQueryContext(scope);
    }

    public static RelationQueryContext unsecured() {
        return new RelationQueryContext(RelationScope.UNSECURED);
    }

    public RelationScope scope() {
        return scope;
    }

    /** True once the query uses a relation. */
    public boolean hasRelations() {
        return !relations.isEmpty();
    }

    /** True while building inside a relation's subquery. */
    public boolean insideRelation() {
        return depth > 0;
    }

    /** Records the use of a relation, checking the caller may reach it and the number of relations. */
    public void use(ResolvedRelation relation) {
        if (relations.add(relation.source().getName() + "." + relation.name())) {
            if (relations.size() > RelationSettings.maxRelations()) {
                throw CommonErrors.invalid(relation.name(),
                        "A query may use at most " + RelationSettings.maxRelations() + " relations");
            }
            scope.checkAccess(relation.target());
        }
    }

    /** Builds the subquery of a relation one level deeper. */
    public <T> T nested(Supplier<T> body) {
        if (depth >= RelationSettings.maxDepth()) {
            throw CommonErrors.invalid("relation",
                    "Relations can be nested at most " + RelationSettings.maxDepth() + " level(s)");
        }
        depth++;
        try {
            return body.get();
        } finally {
            depth--;
        }
    }

    String nextAlias() {
        aliasSequence++;
        return ALIAS_PREFIX + aliasSequence;
    }

    /** Applies the relation query timeout when the query uses a relation. */
    public <Q extends ResultQuery<?>> Q applyTimeout(Q query) {
        if (hasRelations() && RelationSettings.queryTimeoutSeconds() > 0) {
            query.queryTimeout(RelationSettings.queryTimeoutSeconds());
        }
        return query;
    }
}
