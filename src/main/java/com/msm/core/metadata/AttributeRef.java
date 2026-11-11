package com.msm.core.metadata;

import lombok.Data;

import java.util.Set;

@Data
public class AttributeRef {
    private String fieldName;
    private String objectRef;
    private String usageType = "Reference";
    private Set<String> attributeRefs;
    /** One-to-many relation name on the referenced object back to this object; null for the generated name. */
    private String reverseName;

    public AttributeRef(String fieldName, String objectRef) {
        this.fieldName = fieldName;
        this.objectRef = objectRef;
    }

    public AttributeRef(String fieldName, String objectRef, String usageType) {
        this.fieldName = fieldName;
        this.objectRef = objectRef;
        this.usageType = usageType;
    }

    public AttributeRef(String fieldName, String objectRef, String usageType, Set<String> attributeRefs) {
        this.fieldName = fieldName;
        this.objectRef = objectRef;
        this.usageType = usageType;
        this.attributeRefs = attributeRefs;
    }

    public static AttributeRef of(String fieldName, String objectRef) {
        return new AttributeRef(fieldName, objectRef);
    }

    public static AttributeRef of(String fieldName, String objectRef, String usageType) {
        return new AttributeRef(fieldName, objectRef, usageType);
    }

    public static AttributeRef of(String fieldName, String objectRef, String usageType, Set<String> attributeRefs) {
        return new AttributeRef(fieldName, objectRef, usageType, attributeRefs);
    }
}
