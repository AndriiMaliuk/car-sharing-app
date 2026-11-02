package org.carsharingapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDate;
import java.util.Optional;
import org.carsharingapp.dto.rental.CreateRentalRequestDto;
import org.carsharingapp.mapper.RentalMapper;
import org.carsharingapp.model.*;
import org.carsharingapp.repository.*;
import org.carsharingapp.service.rental.RentalServiceImpl;
import org.carsharingapp.service.notification.NotificationService;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RentalServiceTest {
    private final RentalRepository rentals = mock(RentalRepository.class);
    private final CarRepository cars = mock(CarRepository.class);
    private final RentalMapper mapper = mock(RentalMapper.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final RentalServiceImpl service = new RentalServiceImpl(rentals, mapper, cars, notifications);
    private User user;
    private Rental rental;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setRole(User.Role.CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
        rental = new Rental();
        rental.setId(2L);
        rental.setUserId(1L);
        rental.setCarId(3L);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsRentalAndUpdatesInventory() {
        CreateRentalRequestDto request = new CreateRentalRequestDto();
        request.setDaysCount(3);
        when(mapper.toModel(request)).thenReturn(rental);
        Car car = new Car();
        car.setInventory(5);
        when(cars.findById(3L)).thenReturn(Optional.of(car));
        when(rentals.save(rental)).thenReturn(rental);
        service.save(request);
        assertEquals(4, car.getInventory());
        assertEquals(1L, rental.getUserId());
        assertEquals(rental.getRentalDate().plusDays(3), rental.getReturnDate());
        verify(notifications).sendMessage(contains("Rental id: <code>2"));
    }

    @Test
    void sendsNotificationOnlyAfterCommit() {
        CreateRentalRequestDto request = new CreateRentalRequestDto();
        request.setDaysCount(2);
        when(mapper.toModel(request)).thenReturn(rental);
        Car car = new Car();
        car.setInventory(2);
        when(cars.findById(3L)).thenReturn(Optional.of(car));
        when(rentals.save(rental)).thenReturn(rental);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.save(request);
            verifyNoInteractions(notifications);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(notifications).sendMessage(contains("Rental id: <code>2"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void returnsCar() {
        when(rentals.findById(2L)).thenReturn(Optional.of(rental));
        Car car = new Car();
        car.setInventory(2);
        when(cars.findById(3L)).thenReturn(Optional.of(car));
        when(rentals.save(rental)).thenReturn(rental);
        service.returnCar(2L);
        assertEquals(3, car.getInventory());
        assertFalse(rental.isActive());
        assertEquals(LocalDate.now(), rental.getActualReturnDate());
    }

    @Test
    void restrictsAccessAndAllowsManager() {
        rental.setUserId(9L);
        when(rentals.findById(2L)).thenReturn(Optional.of(rental));
        assertThrows(AccessDeniedException.class, () -> service.findById(2L));
        user.setRole(User.Role.MANAGER);
        service.findById(2L);
        verify(mapper).toDto(rental);
    }

    @Test
    void filtersOwnRentals() {
        Pageable pageable = Pageable.unpaged();
        when(rentals.findByUserIdAndActiveTrue(1L, pageable)).thenReturn(Page.empty());
        when(rentals.findByUserIdAndActiveFalse(1L, pageable)).thenReturn(Page.empty());
        assertTrue(service.findRentalIsActive(null, true, pageable).isEmpty());
        assertTrue(service.findRentalIsActive(1L, false, pageable).isEmpty());
        assertThrows(AccessDeniedException.class, () -> service.findRentalIsActive(9L, true, pageable));
    }
}
