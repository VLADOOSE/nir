package com.vladoose.nir.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** B4: позиция запроса КП может прийти без количества (у лота его нет), но не с нулём. */
class PriceRequestQuantityValidationTest {

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static <T> Set<String> invalid(T bean) {
        return VALIDATOR.validate(bean).stream().map(ConstraintViolation::getPropertyPath)
                .map(Object::toString).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void sendItem_quantityMayBeEmpty_butNotZero() {
        PriceRequestSendRequest.Item i = new PriceRequestSendRequest.Item();
        i.setTenderLotId(1L);
        assertThat(invalid(i)).isEmpty();

        i.setRequestedQuantity(0);
        assertThat(invalid(i)).containsExactly("requestedQuantity");
    }

    @Test
    void itemRequest_quantityMayBeEmpty_butNotZero() {
        PriceRequestItemRequest i = new PriceRequestItemRequest();
        i.setTenderLotId(1L);
        assertThat(invalid(i)).isEmpty();

        i.setRequestedQuantity(0);
        assertThat(invalid(i)).containsExactly("requestedQuantity");
    }
}
