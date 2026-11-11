package com.msm.core.metadata;

import com.msm.core.metadata.annotation.AttributeDefinition;
import com.msm.core.metadata.annotation.AttributeDefinitionRef;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What the metadata builder reads for relations: {@code reverseName} of a reference and {@code filterable}. */
class RelationMetadataTest {

    @SuppressWarnings("unused")
    static class Line {
        @AttributeDefinition(attributeRef = @AttributeDefinitionRef(
                fieldName = "blanketOrderIdReference", objectRef = "v2blanketorder", reverseName = "products"))
        private UUID blanketOrderId;

        @AttributeDefinition(attributeRef = @AttributeDefinitionRef(fieldName = "productIdReference", objectRef = "product"))
        private UUID productId;

        @AttributeDefinition(filterable = false)
        private String otp;

        @AttributeDefinition
        private String code;
    }

    private static Attribute read(String field) throws Exception {
        Attribute attribute = new Attribute();
        ObjectMetadataBuilder.applyAttributeDefinition(Line.class.getDeclaredField(field), attribute);
        return attribute;
    }

    @Test
    void reverseNameOfTheReferenceIsKept() throws Exception {
        assertThat(read("blanketOrderId").getAttributeRef().getReverseName()).isEqualTo("products");
        assertThat(read("productId").getAttributeRef().getReverseName()).isNull();
    }

    @Test
    void filterableFalseIsKeptAndTheDefaultLeavesTheFlagUnset() throws Exception {
        Attribute otp = read("otp");
        Attribute code = read("code");

        assertThat(otp.getIsFilterable()).isFalse();
        assertThat(otp.isFilterableThroughRelation()).isFalse();
        assertThat(code.getIsFilterable()).isNull();
        assertThat(code.isFilterableThroughRelation()).isTrue();
    }
}
