package com.msm.core.dynamicquery;

import com.msm.core.exceptions.CommonErrors;
import com.msm.core.metadata.ObjectMetadata;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ObjectMetadataFactory {

    private static final Map<String, ObjectMetadata> CACHE_TABLE = new ConcurrentHashMap<>();

    public static void registerObjectMetadata(ObjectMetadata objectMetadata) {
        CACHE_TABLE.put(objectMetadata.getName().toLowerCase(), objectMetadata);
    }

    public static Optional<ObjectMetadata> getObjectMetadata(String objectName) {
        return Optional.ofNullable(CACHE_TABLE.get(objectName));
    }

    public static ObjectMetadata getObjectMetadataByName(String objectName) {
        ObjectMetadata objectMetadata = CACHE_TABLE.get(objectName);
        if(objectMetadata == null) {
            throw CommonErrors.objectNotFound(objectName);
        }
        return objectMetadata;
    }

    /** Every registered object (a snapshot). */
    public static Collection<ObjectMetadata> getAll() {
        return List.copyOf(CACHE_TABLE.values());
    }
}
