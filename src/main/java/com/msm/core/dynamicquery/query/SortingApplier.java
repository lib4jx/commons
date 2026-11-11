package com.msm.core.dynamicquery.query;

import com.msm.core.commons.Utils;
import com.msm.core.dynamicquery.FieldResolver;
import com.msm.core.dynamicquery.relation.RelationFieldResolver;
import com.msm.core.dynamicquery.relation.RelationQueryContext;
import com.msm.core.filter.domain.ObjectFilterRequest;
import com.msm.core.filter.domain.pageable.Sort;
import com.msm.core.filter.domain.pageable.SortDirection;
import com.msm.core.metadata.ObjectMetadata;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.SortField;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SortingApplier {

    public static void apply(SelectConditionStep<Record> query,
                       ObjectFilterRequest request,
                       ObjectMetadata meta) {
        apply(query, request, meta, null);
    }

    /** Sorts of the request, {@code @<relation>.<field>} included when {@code relations} is given. */
    public static void apply(SelectConditionStep<Record> query,
                             ObjectFilterRequest request,
                             ObjectMetadata meta,
                             RelationQueryContext relations) {

        if (Objects.isNull(request.getPageRequest())) return;
        List<SortField<?>> sortFieldList = getSortField(meta, request.getPageRequest().getSorts(), relations);
        if(Utils.CL.isNotEmpty(sortFieldList)) {
            query.orderBy(sortFieldList);
        }
    }


    public static List<SortField<?>> getSortField(ObjectMetadata meta, List<Sort> sorts) {
        return getSortField(meta, sorts, null);
    }

    public static List<SortField<?>> getSortField(ObjectMetadata meta, List<Sort> sorts, RelationQueryContext relations) {

        if (Objects.isNull(sorts)) return List.of();

        return sorts
                .stream()
                .map(s -> {
                    Field<?> field = relations != null && RelationFieldResolver.isRelationPath(s.getAttribute())
                            ? RelationFieldResolver.resolve(meta, meta.getTableName(), s.getAttribute(), relations)
                            : FieldResolver.resolve(meta, s.getAttribute());
                    return SortDirection.ASC.name().equalsIgnoreCase(s.getDirection().name())
                            ? field.asc()
                            : field.desc();
                })
                .collect(Collectors.toList());
    }
}
