package com.msm.core.exceptions;

import java.util.Map;

/** A filter reached, through a relation, an object the caller may not view. */
public class RelationAccessDeniedException extends GenericBaseException {
    public static final String OBJECT_PARAM = "object";

    public RelationAccessDeniedException(String objectName, String message) {
        super(ErrorCodeEnum.RELATION_ACCESS_DENIED, message, Map.of(OBJECT_PARAM, objectName));
    }
}
