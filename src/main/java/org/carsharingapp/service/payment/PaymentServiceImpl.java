package org.carsharingapp.service.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.carsharingapp.dto.payment.CreatePaymentRequestDto;
import org.carsharingapp.dto.payment.PaymentDto;
import org.carsharingapp.exeption.EntityNotFoundException;
import org.carsharingapp.exeption.PaymentExeption;
import org.carsharingapp.exeption.StripeIntegrationException;
import org.carsharingapp.mapper.PaymentMapper;
import org.carsharingapp.model.Car;
import org.carsharingapp.model.Payment;
import org.carsharingapp.model.Rental;
import org.carsharingapp.model.User;
import org.carsharingapp.repository.CarRepository;
import org.carsharingapp.repository.PaymentRepository;
import org.carsharingapp.repository.RentalRepository;
import org.carsharingapp.service.stripe.StripeService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final RentalRepository rentalRepository;
    private final CarRepository carRepository;
    private final StripeService stripeService;

    @Override
    public PaymentDto createPayment(CreatePaymentRequestDto requestDto) {
        User authenticatedUser = getAuthenticatedUser();

        Rental rental = rentalRepository.findById(requestDto.rentalId()).orElseThrow(() ->
                new EntityNotFoundException("Can't find rental with id: " + requestDto.rentalId()));
        if (!isManager(authenticatedUser)
                && !rental.getUserId().equals(authenticatedUser.getId())) {
            throw new AccessDeniedException("User " + authenticatedUser.getId()
                    + " cannot create payment for rental with id: " + rental.getId());
        }
        if (paymentRepository.existsByRental_Id(requestDto.rentalId())) {
            throw new PaymentExeption("Payment already exists for rental with id: "
                    + requestDto.rentalId());
        }

        Car car = carRepository.findById(rental.getCarId()).orElseThrow(() ->
                new EntityNotFoundException("Can't find car by id: " + rental.getCarId()));

        long amountTotal = calculateTotal(rental, car).movePointRight(2).longValueExact();
        Payment payment = setPaymentFields(rental, amountTotal);

        try {
            Session session = stripeService.createCheckoutSession(
                    rental.getId(), amountTotal, payment.getCurrency()
            );
            payment.setSessionId(session.getId());
            payment.setSessionUrl(session.getUrl());
            payment.setPaymentIntentId(session.getPaymentIntent());
            payment.setPaymentStatus(fromStripe(session.getPaymentStatus()));
            return paymentMapper.toDto(paymentRepository.save(payment));
        } catch (StripeException e) {
            throw new StripeIntegrationException("Cannot create Stripe session for rental with id: "
                    + rental.getId() + ", amount: " + amountTotal
                    + ", currency: " + payment.getCurrency(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentDto getPaymentById(Long id) {
        Payment payment = paymentRepository.findById(id).orElseThrow(() ->
                new EntityNotFoundException("Can't find payment with id: " + id));

        User authenticatedUser = getAuthenticatedUser();
        if (!isManager(authenticatedUser)
                && !payment.getRental().getUserId().equals(authenticatedUser.getId())) {
            throw new AccessDeniedException("User " + authenticatedUser.getId()
                    + " cannot access payment with id: " + id);
        }
        return paymentMapper.toDto(payment);
    }

    private User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return user;
        }
        throw new IllegalStateException("Can't find authenticated user; authentication present: "
                + (authentication != null));
    }

    private boolean isManager(User user) {
        return user.getRole() == User.Role.MANAGER;
    }

    private BigDecimal calculateTotal(Rental rental, Car car) {
        long days = ChronoUnit.DAYS.between(
                rental.getRentalDate(),
                rental.getReturnDate()
        );
        if (days < 0) {
            throw new PaymentExeption("Return date " + rental.getReturnDate()
                    + " cannot precede rental date " + rental.getRentalDate()
                    + " for rental with id: " + rental.getId());
        }
        long billableDays = Math.max(1, days);
        return car.getDailyFee().multiply(BigDecimal.valueOf(billableDays));
    }

    private Payment setPaymentFields(Rental rental, Long amountTotal) {
        Payment payment = new Payment();
        payment.setRental(rental);
        payment.setAmountTotal(amountTotal);
        payment.setCurrency("usd");
        return payment;
    }

    private Payment.PaymentStatus fromStripe(String status) {
        return switch (status) {
            case "unpaid" -> Payment.PaymentStatus.UNPAID;
            case "paid" -> Payment.PaymentStatus.PAID;
            case "no_payment_required" -> Payment.PaymentStatus.NO_PAYMENT_REQUIRED;
            default -> throw new IllegalArgumentException(
                    "Unknown Stripe payment status: " + status
            );
        };
    }
}
