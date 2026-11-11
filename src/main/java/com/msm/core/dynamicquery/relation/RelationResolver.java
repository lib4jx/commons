package com.msm.core.dynamicquery.relation;

import com.msm.core.commons.Constants;
import com.msm.core.commons.Utils;
import com.msm.core.dynamicquery.ObjectMetadataFactory;
import com.msm.core.exceptions.CommonErrors;
import com.msm.core.metadata.Attribute;
import com.msm.core.metadata.ObjectMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds a relation by name on an object. Relations come only from the attribute references
 * ({@code @AttributeDefinition(attributeRef = …)}) to objects registered in this service (same database); a name
 * the object does not have is refused, so a request can never name a table.
 *
 * <ul>
 *   <li><b>many-to-one</b>: an attribute of the object with a reference to a local object, named after the
 *   reference ({@code customerIdReference} gives {@code customer});</li>
 *   <li><b>one-to-many</b>: a reference of another local object (the child) to this one, named
 *   <ul>
 *     <li>its {@code reverseName} when set ({@code reverseName = "products"} on the blanket order key of a product
 *     line gives the blanket order {@code products});</li>
 *     <li>else the child's object name, when the child references this object once
 *     ({@code v2blanketorderproduct});</li>
 *     <li>else {@code <child>_<key field>}, when it references it several times without a reverseName
 *     ({@code contract_customerId}, {@code contract_invoiceAccountId}).</li>
 *   </ul></li>
 * </ul>
 */
public final class RelationResolver {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final String ID_REFERENCE_SUFFIX = "Id" + Constants.REFERENCE_SUFFIX;
    private static final String ID_SUFFIX = "Id";

    private RelationResolver() {
    }

    public static ResolvedRelation resolve(ObjectMetadata source, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw CommonErrors.invalid(String.valueOf(name), "Invalid relation name: " + name);
        }
        List<ResolvedRelation> found = new ArrayList<>();
        for (Attribute attribute : source.getAttributeRefs()) {
            if (name.equals(referenceRelationName(attribute))) {
                localObject(attribute.getAttributeRef().getObjectRef()).ifPresent(target -> found.add(new ResolvedRelation(
                        name, RelationKind.MANY_TO_ONE, source, target, attribute.getFieldName(), Constants.OBJECT_PK)));
            }
        }
        for (ObjectMetadata child : ObjectMetadataFactory.getAll()) {
            List<Attribute> keys = keysTo(child, source);
            long unnamed = keys.stream().filter(key -> Utils.STR.isBlank(key.getAttributeRef().getReverseName())).count();
            for (Attribute key : keys) {
                if (name.equals(reverseRelationName(child, key, unnamed))) {
                    found.add(new ResolvedRelation(name, RelationKind.ONE_TO_MANY, source, child,
                            Constants.OBJECT_PK, key.getFieldName()));
                }
            }
        }
        if (found.isEmpty()) {
            throw CommonErrors.invalid(name, "Unknown relation '" + name + "' on object " + source.getName());
        }
        if (found.size() > 1) {
            throw CommonErrors.invalid(name, "Ambiguous relation '" + name + "' on object " + source.getName());
        }
        return found.getFirst();
    }

    /** The child's single-key references to {@code parent}. */
    private static List<Attribute> keysTo(ObjectMetadata child, ObjectMetadata parent) {
        return child.getAttributeRefs().stream()
                .filter(attribute -> parent.getName().equalsIgnoreCase(attribute.getAttributeRef().getObjectRef()))
                .filter(attribute -> !attribute.isCollectionField())
                .toList();
    }

    /**
     * Name of the one-to-many relation a child's key gives its parent: the reverseName, else the child's name, else
     * (several keys without reverseName to the same parent) {@code <child>_<key field>}.
     */
    static String reverseRelationName(ObjectMetadata child, Attribute key, long keysWithoutReverseName) {
        String reverseName = key.getAttributeRef().getReverseName();
        if (Utils.STR.isNotBlank(reverseName)) {
            return reverseName;
        }
        return keysWithoutReverseName == 1 ? child.getName() : child.getName() + "_" + key.getFieldName();
    }

    /**
     * Name of the many-to-one relation an attribute reference gives: {@code customerIdReference} → {@code customer},
     * else the attribute name without {@code Id}; null for a list attribute (it holds several keys).
     */
    static String referenceRelationName(Attribute attribute) {
        if (!attribute.hasRef() || attribute.isCollectionField()) {
            return null;
        }
        String reference = attribute.getAttributeRef().getFieldName();
        if (reference.endsWith(ID_REFERENCE_SUFFIX) && reference.length() > ID_REFERENCE_SUFFIX.length()) {
            return reference.substring(0, reference.length() - ID_REFERENCE_SUFFIX.length());
        }
        String fieldName = attribute.getFieldName();
        if (fieldName.endsWith(ID_SUFFIX) && fieldName.length() > ID_SUFFIX.length()) {
            return fieldName.substring(0, fieldName.length() - ID_SUFFIX.length());
        }
        return null;
    }

    private static Optional<ObjectMetadata> localObject(String objectName) {
        if (Utils.STR.isBlank(objectName)) {
            return Optional.empty();
        }
        return ObjectMetadataFactory.getObjectMetadata(objectName.toLowerCase());
    }
}
