package com.msm.core.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.msm.core.commons.GenericTypeResolverFactory;
import com.msm.core.commons.Utils;
import com.msm.core.dynamicquery.mapping.JavaTypeMappingFactory;
import com.msm.core.exceptions.CommonErrors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.Objects;

/**
 * {
 *       "fieldType": "UUID",
 *       "fieldName": "paymentTermId",
 *       "columnName": "payment_term_id",
 *       "attributeRef": {
 *         "fieldName": "paymentTermIdReference",
 *         "objectRef": "paymentterm",
 *         "usageType": "Reference"
 *       },
 *       "isRequired": true
 *     }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Attribute {
    private String fieldType;
    private String fieldName;
    private String columnName;
    private String columnType;
    private AttributeRef attributeRef;
    private Boolean isRequired;
    private Long minLength;
    private Long maxLength;
    private Long maxValue;
    private Long minValue;
    private Long minSize;
    private Long maxSize;
    private Boolean isJson = false;
    private Boolean isFreeText;
    private Object defaultValue;
    private Boolean multiSelect;
    private String regex;
    private String formatValue;
    private String formula;
    private Boolean isSystem;
    private String objectType;
    private Class<? extends Enum<?>> enumType;
    /** False when the attribute cannot be filtered on, selected or sorted through a relation (null means true). */
    private Boolean isFilterable;
    /**
     * Table or alias the column is qualified with in SQL, or null for the bare column. Set only on the copies a
     * relation subquery uses ({@code com.msm.core.dynamicquery.relation.QualifiedMetadata}).
     */
    @JsonIgnore
    private String qualifier;

    /**
     * Constructs a field representation for a database column with its associated metadata.
     * <p>
     * This method resolves the appropriate data type by mapping the internal {@code fieldType}
     * through the {@link JavaTypeMappingFactory}. It establishes the link between the
     * application-level Java type and the corresponding database-level storage format.
     * </p>
     * <p>
     * <b>Implementation Note:</b> By default, common Java types map to their standard SQL
     * equivalents (e.g., {@code String} maps to {@code VARCHAR}). A future enhancement
     * will allow overriding this default behavior using a specific {@code columnType}
     * (e.g., explicitly defining {@code TEXT} for a string field).
     * </p>
     *
     * @return A field object initialized with the column name and the resolved data type.
     * @see JavaTypeMappingFactory#getDataType(String)
     */
    @JsonIgnore
    public Field<?> getField() {
        DataType<?> dataType = JavaTypeMappingFactory.getDataType(fieldType);
        //dataType.getSQLDataType().getTypeName() != columnType
        //Exp: String -> VARCHAR: default
        //if String -> TEXT then columnType = TEXT
        //Get data type by columnType=TEXT but implement after
        if (qualifier != null) {
            return DSL.field(DSL.name(qualifier, columnName), dataType);
        }
        return DSL.field(DSL.name(columnName), dataType);
    }

    @JsonIgnore
    public JavaType getJavaType() {
        return GenericTypeResolverFactory.resolve(fieldType);
    }

    /**
     * Converts or casts a given value to the target {@code fieldType} defined for this field.
     * <p>
     * This method utilizes {@link com.msm.core.commons.ObjectUtils#convertToType(Object, Class)} to perform the conversion at runtime.
     * It is designed to bridge the gap between raw data (such as database results or request payloads)
     * and the specific Java types required by the application domain.
     * </p>
     *
     * @param <T>   The expected generic return type.
     * @param value The object to be converted.
     * @return      The value successfully cast or converted to type {@code T}.
     * @throws RuntimeException (via {@link CommonErrors#castException}) if the conversion fails,
     *                          providing details about the field name, target type, and source type.
     */
    public <T> T cast(Object value) {
        try {
            return Utils.O.convertToType(value, fieldType);
        } catch (Exception e) {
            throw CommonErrors.castException(fieldName, "Cannot cast field '" + fieldName + "' with type " + fieldType + " to type " + value.getClass(), e);
        }
    }

    public <T> T castAndAcceptCollectionAsArray(Object value) {
        try {
            return Utils.O.convertToType(value, fieldType, true);
        } catch (Exception e) {
            throw CommonErrors.castException(fieldName, "Cannot cast field '" + fieldName + "' with type " + fieldType + " to type " + value.getClass(), e);
        }
    }

    @JsonIgnore
    public boolean isJsonField() {
        if (isJson == null) {
            return fieldType.toLowerCase().contains("json")
                    || fieldType.toLowerCase().contains("jsonb")
                    || fieldType.toLowerCase().contains("map")
                    || JavaTypeMappingFactory.isJson(fieldType);
        }

        return isJson;
    }

    @JsonIgnore
    public boolean isCollectionField() {
        return getJavaType().isCollectionLikeType();
    }

    @JsonIgnore
    public Field<?> getFieldRef() {
        if (attributeRef != null) {
            return DSL.field(DSL.name(attributeRef.getFieldName()));
        }
        return null;
    }

    public boolean hasRef() {
        return Objects.nonNull(getAttributeRef()) && Utils.STR.isNotBlank(getAttributeRef().getFieldName());
    }

    /** False only when the attribute is declared {@code filterable = false}. */
    @JsonIgnore
    public boolean isFilterableThroughRelation() {
        return !Boolean.FALSE.equals(isFilterable);
    }

    public boolean isEnumField() {
        return Objects.nonNull(enumType);
    }

}