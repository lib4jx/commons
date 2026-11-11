package com.msm.core.dynamicquery.relation;

import com.msm.core.metadata.ObjectMetadata;
import org.jooq.Condition;
import org.jooq.impl.DSL;

/** What a query may see of a related object: who may reach it and which of its rows. */
public interface RelationScope {

    /** Throws when the caller may not reach the related object at all. */
    void checkAccess(ObjectMetadata target);

    /** Condition on the related rows (the data scope), on the metadata qualified with the subquery's alias. */
    Condition rowCondition(ObjectMetadata qualifiedTarget);

    /** For internal queries, which apply no permission or data scope to the queried object either. */
    RelationScope UNSECURED = new RelationScope() {
        @Override
        public void checkAccess(ObjectMetadata target) {
            // internal queries are not checked
        }

        @Override
        public Condition rowCondition(ObjectMetadata qualifiedTarget) {
            return DSL.noCondition();
        }
    };
}
