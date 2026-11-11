package com.msm.core.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to define metadata, configuration, and validation rules for a dynamic attribute.
 * <p>
 * This annotation can be applied to methods, fields, or other annotation types to enforce constraints
 * and define structural mapping (e.g., UI fields, database columns).
 * </p>
 *
 * @author YourName
 * @since 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE, ElementType.FIELD})
public @interface AttributeDefinition {

    /**
     * A marker enum representing the default empty state when no specific enum type is specified.
     */
    enum NoEnum {}

    /**
     * The data type of the field (e.g., "TEXT", "NUMBER", "DATE", "BOOLEAN").
     * Defaults to an empty string.
     *
     * @return the data type as a String
     */
    String fieldType() default "";

    /**
     * The display name or logical identifier of the field used in UI rendering or business logic.
     *
     * @return the field name
     */
    String fieldName() default "";

    /**
     * The name of the corresponding database column mapped to this attribute.
     *
     * @return the database column name
     */
    String columnName() default "";

    /**
     * A regular expression (Regex) pattern used to validate the string format of the input data.
     *
     * @return the regular expression pattern
     */
    String regex() default "";

    /**
     * Specifies whether this attribute is mandatory.
     * Defaults to {@code false}.
     *
     * @return {@code true} if the field is required, {@code false} otherwise
     */
    boolean required() default false;

    /**
     * Indicates whether the field allows free-text entry rather than being restricted to a predefined list.
     * Defaults to {@code false}.
     *
     * @return {@code true} if free-text is permitted, {@code false} otherwise
     */
    boolean freeText() default false;

    /**
     * The default value assigned to this attribute if no user input is provided.
     *
     * @return the default value as a String
     */
    String defaultValue() default "";

    /**
     * The minimum allowed character length. Only applicable to string-based attributes.
     * Defaults to {@code -1} (no minimum length limit).
     *
     * @return the minimum string length
     */
    long minLength() default -1;

    /**
     * The maximum allowed character length. Only applicable to string-based attributes.
     * Defaults to {@code -1} (no maximum length limit).
     *
     * @return the maximum string length
     */
    long maxLength() default -1;


    /**
     * The minimum numeric value required. Only applicable to numeric attributes.
     * Defaults to {@code -1} (no lower bound restriction).
     *
     * @return the minimum numeric value
     */
    long minValue() default -1;

    /**
     * The maximum numeric value allowed. Only applicable to numeric attributes.
     * Defaults to {@code -1} (no upper bound restriction).
     *
     * @return the maximum numeric value
     */
    long maxValue() default -1;

    /**
     * The minimum physical file size in bytes (for file attachments) or the minimum
     * number of elements (for collections/arrays).
     * Defaults to {@code -1} (no minimum size restriction).
     *
     * @return the minimum size or capacity
     */
    long minSize() default -1;

    /**
     * The maximum physical file size in bytes (for file attachments) or the maximum
     * number of elements (for collections/arrays).
     * Defaults to {@code -1} (no size restriction).
     *
     * @return the maximum size or capacity
     */
    long maxSize() default -1;

    /**
     * The Enum class defining the set of acceptable values for this attribute.
     * Defaults to {@link NoEnum} indicating that no enum validation is applied.
     * <p>
     * Values must be exactly the constant's {@code name()} (case-sensitive). For a collection or
     * array field every element must be a constant of the enum.
     *
     * @return the associated Enum class type
     */
    Class<? extends Enum<?>> enumType() default NoEnum.class;

    /**
     * Defines a reference or mapping relationship to another attribute definition.
     *
     * @return the associated attribute reference metadata
     */
    AttributeDefinitionRef attributeRef() default @AttributeDefinitionRef();

    /**
     * False for a sensitive attribute (password hash, OTP, token…) that a filter of another object must not reach
     * through a relation: it cannot be filtered on, selected or sorted from the related side.
     * Defaults to {@code true}.
     *
     * @return whether the attribute can be used through a relation
     */
    boolean filterable() default true;
}