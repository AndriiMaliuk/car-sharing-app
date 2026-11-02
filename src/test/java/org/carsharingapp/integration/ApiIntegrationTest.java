package org.carsharingapp.integration;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import java.math.BigDecimal;
import java.util.Map;
import org.carsharingapp.model.Car;
import org.carsharingapp.model.Payment;
import org.carsharingapp.model.User;
import org.carsharingapp.repository.CarRepository;
import org.carsharingapp.repository.PaymentRepository;
import org.carsharingapp.repository.RentalRepository;
import org.carsharingapp.repository.UserRepository;
import org.carsharingapp.service.notification.NotificationService;
import org.carsharingapp.service.stripe.StripeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.0.36:///api-integration"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    private static final String PASSWORD = "password123";
    private static final String EMAIL = "customer@example.com";
    private static final String WEBHOOK_SECRET = "whsec_test_placeholder";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private CarRepository cars;
    @Autowired private RentalRepository rentals;
    @Autowired private PaymentRepository payments;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private StripeService stripe;
    @MockBean private NotificationService notifications;

    private String token;
    private Car car;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM rentals");
        jdbc.update("DELETE FROM cars");
        jdbc.update("DELETE FROM users");
        register(EMAIL);
        token = login(EMAIL);
        car = new Car();
        car.setBrand("Toyota");
        car.setModel("Corolla");
        car.setType(Car.Type.SEDAN);
        car.setInventory(2);
        car.setDailyFee(new BigDecimal("25.00"));
        car = cars.saveAndFlush(car);
    }

    @Test
    void registersLogsInAndReadsProfileWithJwt() throws Exception {
        User user = users.findByEmail(EMAIL).orElseThrow();
        assertTrue(encoder.matches(PASSWORD, user.getPassword()));
        assertEquals(User.Role.CUSTOMER, user.getRole());
        mvc.perform(get("/users/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDuplicateAndInvalidRegistrationWithoutSavingUser() throws Exception {
        mvc.perform(post("/auth/registration").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(EMAIL)))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString(EMAIL)));
        mvc.perform(post("/auth/registration").contentType(MediaType.APPLICATION_JSON)
                        .content(registration("invalid-email")))
                .andExpect(status().isBadRequest());
        assertEquals(1, users.count());
    }

    @Test
    void onlyManagerCanCreateCars() throws Exception {
        String body = mapper.writeValueAsString(Map.of("brand", "Honda", "model", "Civic",
                "type", "SEDAN", "inventory", 3, "dailyFee", 30));
        mvc.perform(post("/cars").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(1, cars.count());
        User manager = users.findByEmail(EMAIL).orElseThrow();
        manager.setRole(User.Role.MANAGER);
        users.saveAndFlush(manager);
        mvc.perform(post("/cars").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brand").value("Honda"));
        assertEquals(2, cars.count());
    }

    @Test
    void rentsAndReturnsCarAndSendsNotificationAfterCommit() throws Exception {
        long rentalId = createRental();
        assertEquals(1, cars.findById(car.getId()).orElseThrow().getInventory());
        verify(notifications).sendMessage(contains("Rental id: <code>" + rentalId));
        mvc.perform(post("/rentals/{id}/return", rentalId).header("Authorization", token))
                .andExpect(status().isOk());
        assertEquals(2, cars.findById(car.getId()).orElseThrow().getInventory());
        assertFalse(rentals.findById(rentalId).orElseThrow().isActive());
        assertNotNull(rentals.findById(rentalId).orElseThrow().getActualReturnDate());
    }

    @Test
    void invalidRentalDoesNotChangeInventoryOrSendNotification() throws Exception {
        mvc.perform(post("/rentals").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("carId", car.getId(),
                                "daysCount", 0))))
                .andExpect(status().isBadRequest());
        assertEquals(0, rentals.count());
        assertEquals(2, cars.findById(car.getId()).orElseThrow().getInventory());
        verifyNoInteractions(notifications);
    }

    @Test
    void customerCannotReadOrReturnAnotherCustomersRental() throws Exception {
        long rentalId = createRental();
        register("other@example.com");
        String otherToken = login("other@example.com");
        mvc.perform(get("/rentals/{id}", rentalId).header("Authorization", otherToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/rentals/{id}/return", rentalId)
                        .header("Authorization", otherToken))
                .andExpect(status().isForbidden());
        assertTrue(rentals.findById(rentalId).orElseThrow().isActive());
        assertEquals(1, cars.findById(car.getId()).orElseThrow().getInventory());
    }

    @Test
    void createsPaymentAndRejectsDuplicateAndForeignAccess() throws Exception {
        long rentalId = createRental();
        long paymentId = createPayment(rentalId);
        Payment payment = payments.findById(paymentId).orElseThrow();
        assertEquals(5000L, payment.getAmountTotal());
        assertEquals(Payment.PaymentStatus.UNPAID, payment.getPaymentStatus());
        mvc.perform(get("/payments/{id}", paymentId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rentalId").value(rentalId));
        mvc.perform(post("/payments").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("rentalId", rentalId))))
                .andExpect(status().isConflict());
        register("other@example.com");
        String otherToken = login("other@example.com");
        mvc.perform(get("/payments/{id}", paymentId).header("Authorization", otherToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/payments").header("Authorization", otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("rentalId", rentalId))))
                .andExpect(status().isForbidden());
        assertEquals(1, payments.count());
        verify(stripe, times(1)).createCheckoutSession(rentalId, 5000L, "usd");
    }

    @Test
    void stripeFailureDoesNotPersistPayment() throws Exception {
        long rentalId = createRental();
        when(stripe.createCheckoutSession(anyLong(), anyLong(), anyString()))
                .thenThrow(new ApiConnectionException("Connection failed"));
        mvc.perform(post("/payments").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("rentalId", rentalId))))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(containsString("rental with id: " + rentalId)));
        assertEquals(0, payments.count());
    }

    @Test
    void signedWebhookPersistsPaymentAndDuplicateIsHarmless() throws Exception {
        long paymentId = createPayment(createRental());
        String payload = webhookPayload(5000);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                            .header("Stripe-Signature", sign(payload)).content(payload))
                    .andExpect(status().isOk());
        }
        Payment payment = payments.findById(paymentId).orElseThrow();
        assertEquals(Payment.PaymentStatus.PAID, payment.getPaymentStatus());
        assertEquals("pi_integration", payment.getPaymentIntentId());
        assertEquals(1, payments.count());
    }

    @Test
    void invalidWebhookSignatureAndAmountCannotMarkPaymentPaid() throws Exception {
        long paymentId = createPayment(createRental());
        String payload = webhookPayload(5000);
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "invalid").content(payload))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
        String mismatchedPayload = webhookPayload(4999);
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(mismatchedPayload))
                        .content(mismatchedPayload))
                .andExpect(status().isInternalServerError());
        Payment payment = payments.findById(paymentId).orElseThrow();
        assertEquals(Payment.PaymentStatus.UNPAID, payment.getPaymentStatus());
        assertNull(payment.getPaymentIntentId());
    }

    private String registration(String email) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD,
                "repeatPassword", PASSWORD, "firstName", "John", "lastName", "Smith"));
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/auth/registration").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(email)))
                .andExpect(status().isOk());
    }

    private String login(String email) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).get("token").asText();
    }

    private long createRental() throws Exception {
        String response = mvc.perform(post("/rentals").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("carId", car.getId(),
                                "daysCount", 2))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asLong();
    }

    private long createPayment(long rentalId) throws Exception {
        Session session = new Session();
        session.setId("cs_integration");
        session.setUrl("https://checkout.stripe.com/test");
        session.setPaymentStatus("unpaid");
        when(stripe.createCheckoutSession(rentalId, 5000L, "usd")).thenReturn(session);
        String response = mvc.perform(post("/payments").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("rentalId", rentalId))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode result = mapper.readTree(response);
        assertEquals(5000, result.get("amountTotal").asLong());
        return result.get("id").asLong();
    }

    private String webhookPayload(long amount) throws Exception {
        return mapper.writeValueAsString(Map.of("id", "evt_integration", "object", "event",
                "api_version", Stripe.API_VERSION, "type", "checkout.session.completed",
                "data", Map.of("object", Map.of("object", "checkout.session",
                        "id", "cs_integration", "amount_total", amount, "currency", "usd",
                        "payment_intent", "pi_integration", "payment_status", "paid"))));
    }

    private String sign(String payload) throws Exception {
        return Webhook.Signature.generateSignatureHeader(payload, WEBHOOK_SECRET);
    }
}
