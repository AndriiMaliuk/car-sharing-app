package org.carsharingapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stripe.Stripe;
import com.stripe.net.Webhook;
import java.util.Optional;
import org.carsharingapp.model.Payment;
import org.carsharingapp.repository.PaymentRepository;
import org.carsharingapp.service.stripe.StripeWebhookService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class StripeWebhookServiceTest {
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final StripeWebhookService service = new StripeWebhookService(repository);

    @Test
    void marksPaidAndIgnoresDuplicates() throws Exception {
        Payment payment = payment();
        deliver("paid");
        assertEquals(Payment.PaymentStatus.PAID, payment.getPaymentStatus());
        assertEquals("pi_test", payment.getPaymentIntentId());
        deliver("paid");
        verify(repository, times(1)).save(payment);
    }

    @Test
    void leavesUnpaidSessionUnchanged() throws Exception {
        Payment payment = payment();
        deliver("unpaid");
        assertEquals(Payment.PaymentStatus.UNPAID, payment.getPaymentStatus());
        verify(repository, never()).save(payment);
    }

    @Test
    void rejectsAmountMismatch() {
        Payment payment = payment();
        payment.setAmountTotal(999L);
        assertThrows(IllegalStateException.class, () -> deliver("paid"));
        verify(repository, never()).save(payment);
    }

    @Test
    void rejectsMissingPayment() {
        assertThrows(IllegalStateException.class, () -> deliver("paid"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid"})
    void rejectsInvalidSignature(String signature) {
        ReflectionTestUtils.setField(service, "webhookSecret", "whsec_test");
        assertThrows(IllegalArgumentException.class, () -> service.handleEvent("{}", signature));
        verifyNoInteractions(repository);
    }

    private void deliver(String status) throws Exception {
        ReflectionTestUtils.setField(service, "webhookSecret", "whsec_test");
        String payload = "{\"id\":\"evt_test\",\"object\":\"event\",\"api_version\":\""
                + Stripe.API_VERSION + "\",\"type\":\"checkout.session.completed\","
                + "\"data\":{\"object\":{\"object\":\"checkout.session\",\"id\":\"cs_test\","
                + "\"amount_total\":1000,\"currency\":\"usd\",\"payment_intent\":\"pi_test\","
                + "\"payment_status\":\"" + status + "\"}}}";
        service.handleEvent(payload, Webhook.Signature.generateSignatureHeader(payload, "whsec_test"));
    }

    private Payment payment() {
        Payment payment = new Payment();
        payment.setAmountTotal(1000L);
        payment.setCurrency("usd");
        payment.setPaymentStatus(Payment.PaymentStatus.UNPAID);
        when(repository.findBySessionId("cs_test")).thenReturn(Optional.of(payment));
        return payment;
    }
}
