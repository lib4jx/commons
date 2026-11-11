package com.msm.core.dynamicquery.query;

import com.msm.core.commons.Constants;
import com.msm.core.commons.Utils;
import com.msm.core.dynamicquery.relation.RelationFieldResolver;
import com.msm.core.filter.domain.FilterCondition;
import com.msm.core.filter.domain.FilterGroup;
import com.msm.core.filter.domain.FilterObject;
import com.msm.core.filter.domain.FilterOperator;
import com.msm.core.filter.domain.FilterRelation;
import com.msm.core.filter.domain.LogicalOperator;
import com.msm.core.filter.domain.ObjectFilterRequest;
import com.msm.core.filter.domain.SearchRequest;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.ObjectMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SearchOrDefaultFilter {
    private  SearchOrDefaultFilter() {}

    public static void resolveSearchFilter(ObjectFilterRequest filter) {
        FilterGroup searchFilterGroup = createSearchFilterGroup(filter.getSearch());

        if(Objects.nonNull(searchFilterGroup)) {
            FilterGroup currentFilterGroup = filter.getFilters();
            FilterGroup newSearchFilterGroup = FilterGroup
                    .builder()
                    .operator(LogicalOperator.AND)
                    .conditions(Utils.CL.newArrayList(searchFilterGroup)).build();
            if(Objects.isNull(currentFilterGroup)) {
                filter.setFilters(searchFilterGroup);
            } else {
                List<FilterObject> filterConditionList = currentFilterGroup.getConditions();
                if(Utils.CL.isEmpty(filterConditionList)) {
                    filter.setFilters(searchFilterGroup);
                } else {
                    newSearchFilterGroup.getConditions().add(currentFilterGroup);
                    filter.setFilters(newSearchFilterGroup);
                }
            }
        }
    }

    /**
     * OR of a LIKE per search field. The fields {@code @<relation>.<field>} of one relation become one
     * {@link FilterRelation} (ANY) holding the OR of their LIKEs.
     */
    private static FilterGroup createSearchFilterGroup(SearchRequest searchRequest) {
        if(Objects.nonNull(searchRequest)) {
            List<String> fields = searchRequest.getFields();
            if(Utils.CL.isNotEmpty(fields) && Utils.STR.isNotBlank(searchRequest.getKeyword())) {
                String keyword = searchRequest.getKeyword();
                List<FilterObject> filterConditionList = new ArrayList<>();
                Map<String, List<FilterObject>> relationConditions = new LinkedHashMap<>();
                for (String field : fields) {
                    if (RelationFieldResolver.isRelationPath(field)) {
                        RelationFieldResolver.RelationPath path = RelationFieldResolver.parse(field);
                        relationConditions.computeIfAbsent(path.relation(), relation -> new ArrayList<>())
                                .add(likeCondition(path.field(), keyword));
                    } else {
                        filterConditionList.add(likeCondition(field, keyword));
                    }
                }
                relationConditions.forEach((relation, conditions) -> filterConditionList.add(FilterRelation.any(relation,
                        FilterGroup.builder().operator(LogicalOperator.OR).conditions(conditions).build())));

                return FilterGroup.builder().operator(LogicalOperator.OR).conditions(filterConditionList).build();
            }
        }
        return  null;
    }

    private static FilterObject likeCondition(String field, String keyword) {
        return FilterCondition.builder().field(field).operator(FilterOperator.LIKE).value(keyword).build();
    }

    public static void addIsDeletedFilter(ObjectMetadata objectMetadata, ObjectFilterRequest filter) {
        Attribute attribute = objectMetadata.getAttributeByName(Constants.IS_DELETED_FIELD);
        if (Objects.isNull(attribute)) {
            return;
        }

        FilterGroup currentFilter = filter.getFilters();
        FilterGroup newDeletedFilterGroup = FilterGroup
                .builder()
                .operator(LogicalOperator.AND)
                .conditions(Utils.CL.newArrayList(defaultFilterGroup())).build();

        if (Objects.isNull(currentFilter)) {
            filter.setFilters(newDeletedFilterGroup);
            return;
        }

        if (!isDeletedFilter(currentFilter)) {
            if(Utils.CL.isEmpty(currentFilter.getConditions())) {
                filter.setFilters(newDeletedFilterGroup);
            } else {
                newDeletedFilterGroup.getConditions().add(currentFilter);
                filter.setFilters(newDeletedFilterGroup);
            }
        }
    }

    /**
     * Always keeps the soft deleted rows out, even when the request has its own condition on {@code isDeleted}
     * (which {@link #addIsDeletedFilter} lets through, so a caller could read deleted rows): the request's filters
     * are AND-ed with {@code isDeleted = false or isDeleted is null}.
     */
    public static void enforceNotDeleted(ObjectMetadata objectMetadata, ObjectFilterRequest filter) {
        if (Objects.isNull(objectMetadata.getAttributeByName(Constants.IS_DELETED_FIELD))) {
            return;
        }
        List<FilterObject> conditions = Utils.CL.newArrayList(defaultFilterGroup());
        FilterGroup currentFilter = filter.getFilters();
        if (Objects.nonNull(currentFilter) && Utils.CL.isNotEmpty(currentFilter.getConditions())) {
            conditions.add(currentFilter);
        }
        filter.setFilters(FilterGroup.builder().operator(LogicalOperator.AND).conditions(conditions).build());
    }

    private static FilterGroup defaultFilterGroup() {
        FilterGroup filterGroup = new FilterGroup();
        filterGroup.setOperator(LogicalOperator.OR);
        FilterCondition filterCondition = FilterCondition.create(Constants.IS_DELETED_FIELD, FilterOperator.EQUALS, Boolean.FALSE);
        FilterCondition filterCondition0 = FilterCondition.create(Constants.IS_DELETED_FIELD, FilterOperator.EQUALS, null);
        filterGroup.setConditions(Utils.CL.newArrayList(filterCondition, filterCondition0));

        return filterGroup;
    }

    private static boolean isDeletedFilter(FilterGroup filterGroup) {
        if (Objects.isNull(filterGroup)) {
            return false;
        }
        for (FilterObject filter : Utils.CL.emptyIfNull(filterGroup.getConditions())) {
            if (filter instanceof FilterCondition condition) {
                if (Utils.STR.equalIgnoreCase(condition.getField(), Constants.IS_DELETED_FIELD)) {
                    return true;
                }
            } else if (filter instanceof FilterGroup group && isDeletedFilter(group)) {
                return true;
            }
        }

        return false;
    }
}
