package com.msm.core.dynamicquery.relation;

import com.msm.core.commons.Utils;
import com.msm.core.exceptions.CommonErrors;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.ObjectMetadata;
import com.msm.core.security.enums.SecurityDataScopeType;
import org.jooq.Field;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies of an object's metadata whose columns are qualified with a table alias ({@code "rel_1"."code"}), for the
 * subqueries of relations. The operators, the field resolver and the data scope read columns through
 * {@link Attribute#getField()}, so they work unchanged on the copy.
 */
public final class QualifiedMetadata {

    private QualifiedMetadata() {
    }

    /** The metadata with every attribute (and secured attribute) qualified with {@code qualifier}. */
    public static ObjectMetadata of(ObjectMetadata meta, String qualifier) {
        Map<Attribute, Attribute> copies = new IdentityHashMap<>();
        List<Attribute> attributes = Utils.CL.emptyIfNull(meta.getAttributes()).stream()
                .map(attribute -> copies.computeIfAbsent(attribute, original -> qualify(original, qualifier)))
                .toList();
        Map<SecurityDataScopeType, Attribute> securedAttributes = new EnumMap<>(SecurityDataScopeType.class);
        Utils.CL.emptyIfNull(meta.getSecuredAttributes()).forEach((type, attribute) ->
                securedAttributes.put(type, copies.computeIfAbsent(attribute, original -> qualify(original, qualifier))));
        return meta.toBuilder()
                .attributes(attributes)
                .attributeMap(new HashMap<>())
                .securedAttributes(securedAttributes)
                .build();
    }

    /** One column of the object, qualified with {@code qualifier}. */
    public static Field<?> field(ObjectMetadata meta, String attributeName, String qualifier) {
        Attribute attribute = meta.getAttributeByName(attributeName);
        if (attribute == null) {
            throw CommonErrors.invalid(attributeName, "Relation key " + attributeName + " not found on " + meta.getName());
        }
        return qualify(attribute, qualifier).getField();
    }

    private static Attribute qualify(Attribute attribute, String qualifier) {
        return attribute.toBuilder().qualifier(qualifier).build();
    }
}
