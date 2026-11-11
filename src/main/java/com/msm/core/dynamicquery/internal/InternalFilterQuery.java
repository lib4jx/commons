package com.msm.core.dynamicquery.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.msm.core.commons.Constants;
import com.msm.core.commons.Utils;
import com.msm.core.dynamicquery.ConditionUtils;
import com.msm.core.dynamicquery.SelectBuilder;
import com.msm.core.dynamicquery.query.FilterBuilder;
import com.msm.core.dynamicquery.query.FilterQuery;
import com.msm.core.dynamicquery.query.PagingApplier;
import com.msm.core.dynamicquery.query.SearchOrDefaultFilter;
import com.msm.core.dynamicquery.query.SortingApplier;
import com.msm.core.dynamicquery.relation.RelationQueryContext;
import com.msm.core.exceptions.CommonErrors;
import com.msm.core.filter.domain.FilterCondition;
import com.msm.core.filter.domain.FilterGroup;
import com.msm.core.filter.domain.FilterOperator;
import com.msm.core.filter.domain.LogicalOperator;
import com.msm.core.filter.domain.ObjectFilterRequest;
import com.msm.core.filter.domain.PageResponse;
import com.msm.core.filter.domain.pageable.Sort;
import com.msm.core.metadata.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectConditionStep;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class InternalFilterQuery implements FilterQuery {
    private final DSLContext dsl;

    @Override
    public PageResponse<Map<String, Object>> lookup(ObjectMetadata meta, ObjectFilterRequest request) {
        return page(meta, request);
    }


    @Override
    public PageResponse<Map<String, Object>> filter(ObjectMetadata meta, ObjectFilterRequest request) {
        return page(meta, request);
    }

    @Override
    public Stream<Map<String, Object>> filterStream(ObjectMetadata meta, ObjectFilterRequest request, int fetchSize) {
        return prepare(meta, request).select()
                .fetchSize(fetchSize)
                .fetchStream()
                .map(Record::intoMap);
    }

    @Override
    public <T> void filterStream(
            ObjectMetadata meta,
            ObjectFilterRequest request,
            int fetchSize,
            Class<T> targetClass,
            Consumer<T> consumer) {

        SelectConditionStep<Record> query = prepare(meta, request).select();

        try (Stream<T> stream = query.fetchSize(fetchSize).fetchStream().map(r -> r.into(targetClass))) {
            stream.forEach(consumer);
        }
    }

    @Override
    public <T> void filterStream(
            ObjectMetadata meta,
            ObjectFilterRequest request,
            int fetchSize,
            TypeReference<T> targetType,
            Consumer<T> consumer) {

        SelectConditionStep<Record> query = prepare(meta, request).select();

        try (Stream<Record> recordStream = query.fetchSize(fetchSize).fetchStream()) {
            recordStream
                    .map(Record::intoMap)
                    .map(map -> Utils.O.convertObject(map, targetType))
                    .forEach(consumer);
        } catch (Exception e) {
            throw new RuntimeException("Error while streaming or convert data to typeReference", e);
        }
    }

    @Override
    public void filterStream(
            ObjectMetadata meta,
            ObjectFilterRequest request,
            int fetchSize,
            Consumer<Map<String, Object>> consumer) {

        SelectConditionStep<Record> query = prepare(meta, request).select();

        try (Stream<Map<String, Object>> stream = query.fetchSize(fetchSize).fetchStream().map(Record::intoMap)) {
            stream.forEach(consumer);
        }
    }

    /** One page of the request (and the total when it asks for a page). */
    private PageResponse<Map<String, Object>> page(ObjectMetadata meta, ObjectFilterRequest request) {
        PreparedQuery prepared = prepare(meta, request);
        SelectConditionStep<Record> query = prepared.select();
        PagingApplier.apply(query, request);
        List<Map<String, Object>> result = query.fetchMaps();
        if(Objects.nonNull(request.getPageRequest())) {
            Long total = prepared.relations().applyTimeout(dsl
                    .selectCount()
                    .from(meta.getTable())
                    .where(prepared.condition()))
                    .fetchOne(0, Long.class);

            if (total == null) {
                total = 0L;
            }
            return PageResponse.of(result, total, request.getPageRequest().getPage(), request.getPageRequest().getSize());
        }

        return PageResponse.of(result);
    }

    /**
     * The select of the request: search, soft delete, filters, return fields and sorts, relations included. Internal:
     * no permission or data scope, on the queried object or on the related ones.
     */
    private PreparedQuery prepare(ObjectMetadata meta, ObjectFilterRequest request) {
        RelationQueryContext relations = RelationQueryContext.unsecured();
        SearchOrDefaultFilter.resolveSearchFilter(request);
        SearchOrDefaultFilter.addIsDeletedFilter(meta, request);
        Condition condition = FilterBuilder.build(request.getFilters(), meta, relations);
        SelectConditionStep<Record> query = dsl
                .select(SelectBuilder.buildFields(meta, request, relations))
                .from(meta.getTable())
                .where(condition);

        SortingApplier.apply(query, request, meta, relations);
        relations.applyTimeout(query);
        return new PreparedQuery(query, condition, relations);
    }

    private record PreparedQuery(SelectConditionStep<Record> select, Condition condition, RelationQueryContext relations) {
    }


    public Map<String, Object> findById(ObjectMetadata meta, Object id, List<String> returnFields) {
        if (id == null) {
            throw CommonErrors.required("id", "Id must not be null");
        }
        ObjectFilterRequest objectFilterRequest = ObjectFilterRequest
                .builder()
                .objectInfo(ObjectFilterRequest.ObjectInfo.of(meta.getName()))
                .returnFields(returnFields)
                .filters(FilterGroup
                        .builder()
                        .operator(LogicalOperator.AND)
                        .conditions(Utils.CL.newArrayList(FilterCondition.create(Constants.OBJECT_PK, FilterOperator.EQUALS, id)))
                        .build())
                .build();
        PageResponse<Map<String, Object>> result = filter(meta, objectFilterRequest);

        return Utils.CL.getFirst(result.getContents());
    }

    public Map<String, Object> findById(ObjectMetadata meta, Object id) {
        if (id == null) {
            throw CommonErrors.required("id", "Id must not be null");
        }
        ObjectFilterRequest objectFilterRequest = ObjectFilterRequest
                .builder()
                .objectInfo(ObjectFilterRequest.ObjectInfo.of(meta.getName()))
                .returnFields(meta.getFieldNames())
                .filters(FilterGroup
                        .builder()
                        .operator(LogicalOperator.AND)
                        .conditions(Utils.CL.newArrayList(FilterCondition.create(Constants.OBJECT_PK, FilterOperator.EQUALS, id)))
                        .build())
                .build();
        PageResponse<Map<String, Object>> result = filter(meta, objectFilterRequest);

        return Utils.CL.getFirst(result.getContents());
    }

    public List<Map<String, Object>> findByIds(ObjectMetadata meta, List<Object> ids, List<String> returnFields) {
        if (Utils.CL.isEmpty(ids)) {
            return Utils.CL.newArrayList();
        }
        ObjectFilterRequest objectFilterRequest = ObjectFilterRequest
                .builder()
                .objectInfo(ObjectFilterRequest.ObjectInfo.of(meta.getName()))
                .returnFields(returnFields)
                .filters(FilterGroup
                        .builder()
                        .operator(LogicalOperator.AND)
                        .conditions(Utils.CL.newArrayList(FilterCondition.create(Constants.OBJECT_PK, FilterOperator.IN, ids)))
                        .build())
                .build();
        PageResponse<Map<String, Object>> result = filter(meta, objectFilterRequest);

        return result.getContents();
    }

    public List<Map<String, Object>> findByIds(ObjectMetadata meta, List<Object> ids) {
        return findByIds(meta, ids, meta.getFieldNames());
    }

    public List<Map<String, Object>> findByCondition(ObjectMetadata meta, Condition condition) {
        return findByCondition(meta, condition, meta.getFieldNames());
    }

    public List<Map<String, Object>> findByCondition(ObjectMetadata meta, Condition condition, List<String> returnFields) {
        ConditionUtils.requireWhereCondition(condition);

        return dsl
                .select(SelectBuilder.buildFields(meta, returnFields))
                .from(meta.getTable())
                .where(condition)
                .and(isDeleteCondition(meta))
                .fetchMaps();
    }

    @Override
    public List<Map<String, Object>> findByCondition(
            ObjectMetadata meta,
            Condition condition,
            int limit,
            List<Sort> sortFields,
            List<String> returnFields
    ) {
        SelectConditionStep<Record> query = dsl
                .select(SelectBuilder.buildFields(meta, returnFields))
                .from(meta.getTable())
                .where(condition);


//        List<SortField<?>> sortFieldList = SortingApplier.getSortField(meta, sortFields);
//        if(Utils.CL.isNotEmpty(sortFieldList)) {
//            query.orderBy(sortFieldList);
//        }

        return internalFindByCondition(query, meta, limit, sortFields);
    }

    public Map<String, Object> findOneByCondition(ObjectMetadata meta, Condition condition) {
        return findOneByCondition(meta, condition, meta.getFieldNames());
    }

    public Map<String, Object> findOneByCondition(ObjectMetadata meta, Condition condition, List<String> returnFields) {
        ConditionUtils.requireWhereCondition(condition);
        return dsl
                .select(SelectBuilder.buildFields(meta, returnFields))
                .from(meta.getTable())
                .where(condition)
                .and(isDeleteCondition(meta))
                .limit(1)
                .fetchOneMap();
    }
}
