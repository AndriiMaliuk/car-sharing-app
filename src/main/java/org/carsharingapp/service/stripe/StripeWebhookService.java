package org.carsharingapp.service.stripe;

import com.google.gson.JsonSyntaxException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.carsharingapp.model.Payment;
import org.carsharingapp.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StripeWebhookService {
    @Value("${stripe.webhook-secret}")
    private String webhookSecret;
    private final PaymentRepository paymentRepository;

    @Transactional
    public void handleEvent(String payload, String signature) {
        try {
            Event event = Webhook.constructEvent(
                    payload,
                    signature,
                    webhookSecret
            );
            switch (event.getType()) {
                case "checkout.session.completed":
                case "checkout.session.async_payment_succeeded":
                    Session session = extractSession(event);
                    processSession(session);
                    break;
                default:
                    break;
            }
        } catch (SignatureVerificationException e) {
            throw new IllegalArgumentException("Invalid Stripe signature; header present: "
                    + (signature != null && !signature.isBlank()), e);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid Stripe webhook JSON; payload length: "
                    + (payload == null ? 0 : payload.length()), e);
        }
    }

    private void processSession(Session session) {
        Payment payment = paymentRepository.findBySessionId(session.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found for session: " + session.getId()
                ));

        if (!Objects.equals(payment.getAmountTotal(), session.getAmountTotal())
                || !Objects.equals(payment.getCurrency(), session.getCurrency())) {
            throw new IllegalStateException(
                    "Payment amount or currency mismatch for session: " + session.getId()
                            + "; expected: " + payment.getAmountTotal()
                            + " " + payment.getCurrency()
                            + ", received: " + session.getAmountTotal()
                            + " " + session.getCurrency()
            );
        }

        if (payment.getPaymentStatus() == Payment.PaymentStatus.PAID) {
            return;
        }

        if ("paid".equals(session.getPaymentStatus())) {
            payment.setPaymentStatus(Payment.PaymentStatus.PAID);
            payment.setPaymentIntentId(session.getPaymentIntent());
            paymentRepository.save(payment);
        }
    }

    private Session extractSession(Event event) {
        StripeObject object = event.getDataObjectDeserializer()
                .getObject()
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot deserialize Stripe event: " + event.getId()
                                + ", type: " + event.getType()
                                + ", API version: " + event.getApiVersion()
                ));

        if (!(object instanceof Session session)) {
            throw new IllegalStateException(
                    "Expected Checkout Session for event: " + event.getId()
                            + ", received type: " + object.getClass().getSimpleName()
            );
        }

        return session;
    }
}
