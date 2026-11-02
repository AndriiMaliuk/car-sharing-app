package org.carsharingapp.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EntityValidationTest {
    @Test
    void requiresUserFieldsAndAcceptsEncodedPassword() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            User user = new User();
            assertFalse(factory.getValidator().validate(user).isEmpty());
            user.setEmail("user@example.com");
            user.setPassword("a".repeat(60));
            user.setFirstName("John");
            user.setLastName("Smith");
            user.setRole(User.Role.CUSTOMER);
            assertTrue(factory.getValidator().validate(user).isEmpty());
            user.setEmail("invalid");
            assertFalse(factory.getValidator().validate(user).isEmpty());
        }
    }

    @Test
    void rejectsNegativeInventoryAndInvalidPrice() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Car car = new Car();
            car.setBrand("Toyota");
            car.setModel("Corolla");
            car.setType(Car.Type.SEDAN);
            car.setDailyFee(new BigDecimal("20.00"));
            assertTrue(factory.getValidator().validate(car).isEmpty());
            car.setInventory(-1);
            assertFalse(factory.getValidator().validate(car).isEmpty());
            car.setInventory(0);
            car.setDailyFee(new BigDecimal("1.001"));
            assertFalse(factory.getValidator().validate(car).isEmpty());
            car.setDailyFee(BigDecimal.ZERO);
            assertFalse(factory.getValidator().validate(car).isEmpty());
        }
    }
}
