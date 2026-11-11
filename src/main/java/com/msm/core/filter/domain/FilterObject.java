package com.msm.core.filter.domain;


import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
        {@JsonSubTypes.Type(FilterGroup.class),
        @JsonSubTypes.Type(FilterCondition.class),
        @JsonSubTypes.Type(FilterRelation.class)}
)
public sealed interface FilterObject permits FilterGroup, FilterCondition, FilterRelation {
}
