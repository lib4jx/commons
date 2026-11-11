package com.msm.core.dynamicquery;

import com.msm.core.commons.Utils;
import com.msm.core.dynamicquery.relation.RelationFieldResolver;
import com.msm.core.dynamicquery.relation.RelationQueryContext;
import com.msm.core.filter.domain.ObjectFilterRequest;
import com.msm.core.metadata.ObjectMetadata;
import org.jooq.Field;
import org.jooq.SelectField;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class SelectBuilder {
    /** In returnFields: every attribute of the queried object, e.g. {@code ["*", "@blanketOrder.code"]}. */
    public static final String ALL_FIELDS = "*";

    private SelectBuilder() {}

    public static List<SelectField<?>> buildFields(ObjectMetadata objectMetadata, ObjectFilterRequest request) {
        return buildFields(objectMetadata, request.getReturnFields());
    }

    public static List<SelectField<?>> buildFields(ObjectMetadata objectMetadata, List<String> returnFields) {
        return buildFields(objectMetadata, returnFields, null);
    }

    /** Return fields of the queried object, {@code @<relation>.<field>} included. */
    public static List<SelectField<?>> buildFields(ObjectMetadata objectMetadata, ObjectFilterRequest request, RelationQueryContext relations) {
        return buildFields(objectMetadata, request.getReturnFields(), relations);
    }

    /**
     * Null or empty returnFields: every attribute of the object (as before). {@value #ALL_FIELDS} stands for every
     * attribute at its place, so related fields can be added to them ({@code ["*", "@blanketOrder.code"]}); with
     * it, a field listed twice is selected once.
     *
     * @param relations relations of the query, or null when the caller does not support relation fields
     */
    public static List<SelectField<?>> buildFields(ObjectMetadata objectMetadata, List<String> returnFields, RelationQueryContext relations) {
        if (Utils.CL.isEmpty(returnFields)) {
            return objectMetadata.getAttributes().stream().map(attribute -> getSelectField(attribute.getFieldName(), objectMetadata)).collect(Collectors.toList());
        }

        return expandAllFields(objectMetadata, returnFields)
                .stream()
                .map(f -> relations != null && RelationFieldResolver.isRelationPath(f)
                        ? RelationFieldResolver.resolve(objectMetadata, objectMetadata.getTableName(), f, relations).as(f)
                        : getSelectField(f, objectMetadata))
                .collect(Collectors.toList());
    }

    /** The fields with {@value #ALL_FIELDS} replaced by every attribute name, each field once; unchanged without it. */
    private static List<String> expandAllFields(ObjectMetadata objectMetadata, List<String> returnFields) {
        if (!returnFields.contains(ALL_FIELDS)) {
            return returnFields;
        }
        Set<String> fields = new LinkedHashSet<>();
        for (String field : returnFields) {
            if (ALL_FIELDS.equals(field)) {
                objectMetadata.getAttributes().forEach(attribute -> fields.add(attribute.getFieldName()));
            } else {
                fields.add(field);
            }
        }
        return new ArrayList<>(fields);
    }

    static SelectField<?> getSelectField(String fieldPath, ObjectMetadata objectMetadata) {
        String[] parts = fieldPath.split("\\.");
        String alias = parts[0];
        if (parts.length > 1) {
            alias = String.join(".", parts);
        }

        Field<?> field = FieldResolver.resolve(objectMetadata, fieldPath);
        return field.as(alias);
    }
}
